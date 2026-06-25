package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalog;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalogService;
import com.zwbd.agentnexus.sdui.ui.WorkflowUiContextService;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDefinitionEntity;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDeploymentEntity;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowDefinition;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowNode;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowSlot;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowDeploymentRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class NodeWorkflowDeploymentService {

    private final NodeWorkflowService workflowService;
    private final NodeWorkflowDeploymentRepository deploymentRepository;
    private final DeviceSessionManager sessionManager;
    private final CapabilityNodeCatalogService nodeCatalogService;
    private final WorkflowUiContextService uiContextService;

    public NodeWorkflowDeploymentService(NodeWorkflowService workflowService,
                                         NodeWorkflowDeploymentRepository deploymentRepository,
                                         DeviceSessionManager sessionManager,
                                         CapabilityNodeCatalogService nodeCatalogService,
                                         WorkflowUiContextService uiContextService) {
        this.workflowService = workflowService;
        this.deploymentRepository = deploymentRepository;
        this.sessionManager = sessionManager;
        this.nodeCatalogService = nodeCatalogService;
        this.uiContextService = uiContextService;
    }

    @Transactional
    public Map<String, Object> deploy(String workflowId, Map<String, Object> body) {
        NodeWorkflowDefinitionEntity workflowEntity = workflowService.requireEntity(workflowId);
        NodeWorkflowDefinition workflow = workflowService.workflow(workflowId);
        Map<String, String> bindings = NodeWorkflowSupport.stringMap(body.get("slotBindings"));
        List<String> errors = validateBindings(workflow, bindings);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        Map<String, Object> inspection = inspect(workflowEntity, workflow, bindings, null);

        NodeWorkflowDeploymentEntity deployment = new NodeWorkflowDeploymentEntity();
        deployment.setWorkflowId(workflowId);
        deployment.setWorkflowVersion(workflowEntity.getVersion());
        deployment.setSlotBindings(new LinkedHashMap<>(bindings));
        List<String> replaced = stopConflictingDeployments(workflow, deployment);
        NodeWorkflowDeploymentEntity saved = deploymentRepository.save(deployment);
        Map<String, Object> data = toMap(saved);
        data.put("replacedDeployments", replaced);
        data.put("inspection", inspection);
        data.put("uiContexts", uiContextService.initialize(workflow, saved));
        return data;
    }

    public Map<String, Object> validateDeployment(String workflowId, Map<String, Object> body) {
        NodeWorkflowDefinition workflow = workflowService.workflow(workflowId);
        Map<String, String> bindings = NodeWorkflowSupport.stringMap(body.get("slotBindings"));
        List<String> errors = validateBindings(workflow, bindings);
        return Map.of("valid", errors.isEmpty(), "errors", errors);
    }

    public Map<String, Object> inspectDeployment(String workflowId, Map<String, Object> body) {
        NodeWorkflowDefinitionEntity entity = workflowService.requireEntity(workflowId);
        NodeWorkflowDefinition workflow = workflowService.workflow(workflowId);
        Map<String, String> bindings = NodeWorkflowSupport.stringMap(body.get("slotBindings"));
        return inspect(entity, workflow, bindings, null);
    }

    public List<Map<String, Object>> list(String workflowId) {
        workflowService.requireEntity(workflowId);
        return deploymentRepository.findByWorkflowIdOrderByDeployedAtDesc(workflowId).stream()
                .map(this::toMap)
                .toList();
    }

    public Map<String, Object> get(String workflowId, String deploymentId) {
        return toMap(requireDeployment(workflowId, deploymentId));
    }

    @Transactional
    public Map<String, Object> stop(String workflowId, String deploymentId) {
        NodeWorkflowDeploymentEntity deployment = requireDeployment(workflowId, deploymentId);
        deployment.setStatus("stopped");
        deployment.setStoppedAt(LocalDateTime.now());
        deploymentRepository.save(deployment);
        return Map.of("stopped", true, "workflowId", workflowId, "deploymentId", deploymentId);
    }

    NodeWorkflowDeploymentEntity requireDeployment(String workflowId, String deploymentId) {
        NodeWorkflowDeploymentEntity deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new IllegalArgumentException("deployment not found: " + deploymentId));
        if (!workflowId.equals(deployment.getWorkflowId())) {
            throw new IllegalArgumentException("deployment does not belong to workflow: " + deploymentId);
        }
        return deployment;
    }

    Map<String, Object> toMap(NodeWorkflowDeploymentEntity deployment) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deploymentId", deployment.getId());
        data.put("workflowId", deployment.getWorkflowId());
        data.put("workflowVersion", deployment.getWorkflowVersion());
        data.put("status", deployment.getStatus());
        data.put("slotBindings", deployment.getSlotBindings());
        data.put("deployedAt", deployment.getDeployedAt() != null ? deployment.getDeployedAt().toString() : null);
        data.put("stoppedAt", deployment.getStoppedAt() != null ? deployment.getStoppedAt().toString() : null);
        data.put("uiContexts", uiContextService.list(deployment.getWorkflowId(), deployment.getId()));
        return data;
    }

    private List<String> validateBindings(NodeWorkflowDefinition workflow, Map<String, String> bindings) {
        List<String> errors = new ArrayList<>();
        Set<String> expectedSlots = new LinkedHashSet<>();
        for (NodeWorkflowSlot slot : workflow.slots()) {
            expectedSlots.add(slot.slotId());
        }
        for (String boundSlot : bindings.keySet()) {
            if (!expectedSlots.contains(boundSlot)) {
                errors.add("slot binding references unknown slot: " + boundSlot);
            }
        }
        for (String slotId : expectedSlots) {
            String deviceId = bindings.get(slotId);
            if (deviceId == null || deviceId.isBlank()) {
                errors.add("slot binding is required: " + slotId);
                continue;
            }
            if (!sessionManager.isDeviceOnline(deviceId)) {
                errors.add("device is offline for slot " + slotId + ": " + deviceId);
            }
        }
        if (!errors.isEmpty()) return errors;

        for (NodeWorkflowNode node : workflow.nodes()) {
            String deviceId = bindings.get(node.slotId());
            CapabilityNodeCatalog catalog = nodeCatalogService.buildForDevice(deviceId);
            boolean hasNode = catalog.nodes().stream().anyMatch(def -> node.nodeType().equals(def.nodeType()));
            if (!hasNode) {
                errors.add("device " + deviceId + " for slot " + node.slotId()
                        + " does not support nodeType " + node.nodeType());
            }
        }
        return errors;
    }

    Map<String, Object> inspect(NodeWorkflowDefinitionEntity workflowEntity,
                                NodeWorkflowDefinition workflow,
                                Map<String, String> bindings,
                                String excludeDeploymentId) {
        List<String> errors = new ArrayList<>(validateBindings(workflow, bindings));
        errors.addAll(uiContextService.validateTemplateBindings(workflow, bindings));
        List<Map<String, Object>> warnings = new ArrayList<>();
        List<Map<String, Object>> conflicts = conflictingActiveDeployments(workflow, bindings, excludeDeploymentId);

        Map<String, List<String>> slotsByDevice = new LinkedHashMap<>();
        for (var entry : bindings.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) continue;
            slotsByDevice.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(entry.getKey());
        }
        for (var entry : slotsByDevice.entrySet()) {
            if (entry.getValue().size() > 1) {
                warnings.add(Map.of(
                        "type", "same_device_multiple_slots",
                        "deviceId", entry.getKey(),
                        "slotIds", entry.getValue(),
                        "message", "same device is bound to multiple slots"
                ));
            }
        }

        boolean valid = errors.isEmpty() && conflicts.isEmpty();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valid", valid);
        data.put("workflowId", workflowEntity.getId());
        data.put("workflowVersion", workflowEntity.getVersion());
        data.put("slotBindings", new LinkedHashMap<>(bindings));
        data.put("errors", errors);
        data.put("warnings", warnings);
        data.put("conflicts", conflicts);
        data.put("health", !errors.isEmpty() || !conflicts.isEmpty() ? "error" : warnings.isEmpty() ? "ok" : "warning");
        return data;
    }

    List<Map<String, Object>> conflictingActiveDeployments(NodeWorkflowDefinition workflow,
                                                           Map<String, String> bindings,
                                                           String excludeDeploymentId) {
        NodeWorkflowDeploymentEntity candidate = new NodeWorkflowDeploymentEntity();
        candidate.setWorkflowId(workflow.id());
        candidate.setSlotBindings(new LinkedHashMap<>(bindings));
        List<TriggerBinding> candidateTriggers = triggerBindings(workflow, candidate);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (NodeWorkflowDeploymentEntity existing : deploymentRepository.findByStatus("active")) {
            if (excludeDeploymentId != null && excludeDeploymentId.equals(existing.getId())) {
                continue;
            }
            NodeWorkflowDefinition existingWorkflow;
            try {
                existingWorkflow = workflowService.workflow(existing.getWorkflowId());
            } catch (Exception ignored) {
                continue;
            }
            for (TriggerBinding a : candidateTriggers) {
                for (TriggerBinding b : triggerBindings(existingWorkflow, existing)) {
                    if (a.conflictsWith(b)) {
                        conflicts.add(Map.of(
                                "type", "blocking_conflict",
                                "candidate", a.toMap(),
                                "existing", b.toMap(),
                                "message", "trigger is already used by an active deployment"
                        ));
                    }
                }
            }
        }
        return conflicts;
    }

    private List<String> stopConflictingDeployments(NodeWorkflowDefinition newWorkflow,
                                                    NodeWorkflowDeploymentEntity newDeployment) {
        List<TriggerBinding> newTriggers = triggerBindings(newWorkflow, newDeployment);
        List<String> stopped = new ArrayList<>();
        for (NodeWorkflowDeploymentEntity existing : deploymentRepository.findByStatus("active")) {
            NodeWorkflowDefinition existingWorkflow;
            try {
                existingWorkflow = workflowService.workflow(existing.getWorkflowId());
            } catch (Exception ignored) {
                continue;
            }
            if (hasConflictingTrigger(newTriggers, triggerBindings(existingWorkflow, existing))) {
                existing.setStatus("replaced");
                existing.setStoppedAt(LocalDateTime.now());
                deploymentRepository.save(existing);
                stopped.add(existing.getId());
            }
        }
        return stopped;
    }

    private boolean hasConflictingTrigger(List<TriggerBinding> left, List<TriggerBinding> right) {
        for (TriggerBinding a : left) {
            for (TriggerBinding b : right) {
                if (a.conflictsWith(b)) return true;
            }
        }
        return false;
    }

    List<TriggerBinding> triggerBindings(NodeWorkflowDefinition workflow,
                                         NodeWorkflowDeploymentEntity deployment) {
        Map<String, String> bindings = NodeWorkflowSupport.stringMap(deployment.getSlotBindings());
        List<TriggerBinding> result = new ArrayList<>();
        for (NodeWorkflowNode node : workflow.nodes()) {
            if (!NodeWorkflowSupport.TRIGGER_NODE_TYPES.contains(node.nodeType())) continue;
            String deviceId = bindings.get(node.slotId());
            if (deviceId == null || deviceId.isBlank()) continue;
            result.add(new TriggerBinding(
                    deployment.getWorkflowId(),
                    deployment.getId(),
                    node.slotId(),
                    node.nodeId(),
                    deviceId,
                    NodeWorkflowSupport.string(node.params().get("eventId")),
                    NodeWorkflowSupport.string(node.params().get("nodeId")),
                    NodeWorkflowSupport.string(node.params().get("sectionId"))
            ));
        }
        return result;
    }

    record TriggerBinding(String workflowId,
                          String deploymentId,
                          String slotId,
                          String triggerNodeId,
                          String deviceId,
                          String eventId,
                          String nodeId,
                          String sectionId) {
        boolean conflictsWith(TriggerBinding other) {
            if (!deviceId.equals(other.deviceId)) return false;
            if (!NodeWorkflowSupport.eventMatches(eventId, other.eventId)) return false;
            if (!nodeId.isBlank() && !other.nodeId.isBlank() && !nodeId.equals(other.nodeId)) return false;
            return sectionId.isBlank() || other.sectionId.isBlank() || sectionId.equals(other.sectionId);
        }

        Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("workflowId", workflowId);
            data.put("deploymentId", deploymentId);
            data.put("slotId", slotId);
            data.put("triggerNodeId", triggerNodeId);
            data.put("deviceId", deviceId);
            data.put("eventId", eventId);
            data.put("nodeId", nodeId);
            data.put("sectionId", sectionId);
            return data;
        }
    }
}
