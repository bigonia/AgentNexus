package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalog;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalogService;
import com.zwbd.agentnexus.sdui.routing.DeviceProtocolRouter;
import com.zwbd.agentnexus.sdui.ui.WorkflowUiContextService;
import com.zwbd.agentnexus.sdui.v2.business.BusinessConfigService;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilityRegistryV2;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilitySchemaV2;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDefinitionEntity;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDeploymentEntity;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowDefinition;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowNode;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowSlot;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowDeploymentRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class NodeWorkflowDeploymentService {

    private final NodeWorkflowService workflowService;
    private final NodeWorkflowDeploymentRepository deploymentRepository;
    private final DeviceSessionManager sessionManager;
    private final CapabilityNodeCatalogService nodeCatalogService;
    private final WorkflowUiContextService uiContextService;
    private final WorkflowBusinessConfigAssembler configAssembler;
    private final CapabilityRegistryV2 capabilityRegistry;
    private final DeviceProtocolRouter protocolRouter;
    private final BusinessConfigService businessConfigService;

    public NodeWorkflowDeploymentService(NodeWorkflowService workflowService,
                                         NodeWorkflowDeploymentRepository deploymentRepository,
                                         DeviceSessionManager sessionManager,
                                         CapabilityNodeCatalogService nodeCatalogService,
                                         WorkflowUiContextService uiContextService,
                                         WorkflowBusinessConfigAssembler configAssembler,
                                         CapabilityRegistryV2 capabilityRegistry,
                                         DeviceProtocolRouter protocolRouter,
                                         BusinessConfigService businessConfigService) {
        this.workflowService = workflowService;
        this.deploymentRepository = deploymentRepository;
        this.sessionManager = sessionManager;
        this.nodeCatalogService = nodeCatalogService;
        this.uiContextService = uiContextService;
        this.configAssembler = configAssembler;
        this.capabilityRegistry = capabilityRegistry;
        this.protocolRouter = protocolRouter;
        this.businessConfigService = businessConfigService;
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

        // 先组装再落库：组装是纯计算，把"下发不了的配置"挡在写库与发请求之前。
        AssembleResult assembleResult = assemble(workflow, bindings);
        if (assembleResult.hasErrors()) {
            throw new IllegalArgumentException(String.join("; ", assembleResult.errorMessages()));
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
        data.put("deviceConfigs", assembleResult.toMap());
        data.put("deviceDispatch", dispatch(assembleResult));
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stopped", true);
        result.put("workflowId", workflowId);
        result.put("deploymentId", deploymentId);
        result.put("deviceDispatch", clearBusiness(deployment));
        return result;
    }

    // ── 业务配置：组装与下发（P5a） ──────────────────────────────────────────

    /**
     * 按分流配置为参与本次部署的每台设备组装业务配置。
     *
     * <p>只对走 v2 协议的设备组装。旧协议设备仍由旧工作流运行时逐条下发命令，
     * 暂不生成 {@code BusinessConfig}（见 {@code DeviceProtocolRouter}）。</p>
     */
    private AssembleResult assemble(NodeWorkflowDefinition workflow, Map<String, String> bindings) {
        List<WorkflowBusinessConfigAssembler.Assembled> configs = new ArrayList<>();
        List<String> legacyDevices = new ArrayList<>();
        for (String deviceId : WorkflowBusinessConfigAssembler.devicesOf(bindings)) {
            if (!protocolRouter.isV2(deviceId)) {
                legacyDevices.add(deviceId);
                continue;
            }
            CapabilitySchemaV2 schema = capabilityRegistry.schemaFor(deviceId).orElse(null);
            configs.add(configAssembler.assemble(workflow, bindings, deviceId, schema));
        }
        return new AssembleResult(configs, legacyDevices);
    }

    /**
     * 对 v2 设备下发全量业务配置。
     *
     * <p>下发是异步的，这里只发起请求并记录结果，不阻塞部署接口。配置版本号由
     * {@code BusinessConfigService} 在准备阶段自增，因此响应里不回填具体版本。</p>
     */
    private Map<String, Object> dispatch(AssembleResult assembleResult) {
        Map<String, Object> byDevice = new LinkedHashMap<>();
        for (WorkflowBusinessConfigAssembler.Assembled assembled : assembleResult.configs()) {
            businessConfigService.apply(assembled.deviceId(), assembled.config())
                    .whenComplete((outcome, error) -> {
                        if (error != null) {
                            log.warn("业务配置下发异常: device={}, error={}",
                                    assembled.deviceId(), error.getMessage());
                            return;
                        }
                        log.info("业务配置下发结果: device={}, bindings={}, ok={}, error={}",
                                assembled.deviceId(), assembled.config().triggers().size(),
                                outcome.ok(), outcome.error());
                    });
            Map<String, Object> entry = new LinkedHashMap<>(assembled.toMap());
            entry.put("protocol", "v2");
            entry.put("dispatch", "requested");
            byDevice.put(assembled.deviceId(), entry);
        }
        for (String deviceId : assembleResult.legacyDevices()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("deviceId", deviceId);
            entry.put("protocol", "legacy");
            entry.put("dispatch", "skipped");
            entry.put("reason", "设备按 sdui.routing 仍使用旧协议，未下发 v2 业务配置");
            byDevice.put(deviceId, entry);
        }
        return byDevice;
    }

    /**
     * 停止部署时清理设备的业务运行状态。
     *
     * <p>用 {@code business.reset} 而不是 {@code business.update(空配置)}：01§6.1 要求 reset 让终端
     * 进入"无活动业务状态"，而空配置仍然是一份合法配置，两者语义不同。</p>
     */
    private Map<String, Object> clearBusiness(NodeWorkflowDeploymentEntity deployment) {
        Map<String, Object> byDevice = new LinkedHashMap<>();
        for (String deviceId : WorkflowBusinessConfigAssembler.devicesOf(
                NodeWorkflowSupport.stringMap(deployment.getSlotBindings()))) {
            if (!protocolRouter.isV2(deviceId)) {
                Map<String, Object> skipped = new LinkedHashMap<>();
                skipped.put("deviceId", deviceId);
                skipped.put("protocol", "legacy");
                skipped.put("dispatch", "skipped");
                byDevice.put(deviceId, skipped);
                continue;
            }
            businessConfigService.reset(deviceId).whenComplete((outcome, error) -> {
                if (error != null) {
                    log.warn("业务清理异常: device={}, error={}", deviceId, error.getMessage());
                    return;
                }
                log.info("业务清理结果: device={}, ok={}, error={}", deviceId, outcome.ok(), outcome.error());
            });
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("deviceId", deviceId);
            entry.put("protocol", "v2");
            entry.put("dispatch", "reset-requested");
            byDevice.put(deviceId, entry);
        }
        return byDevice;
    }

    /**
     * 一次部署涉及的组装结果。
     *
     * @param configs       v2 设备的组装结果
     * @param legacyDevices 按分流仍走旧协议、未组装配置的设备
     */
    private record AssembleResult(List<WorkflowBusinessConfigAssembler.Assembled> configs,
                                  List<String> legacyDevices) {

        boolean hasErrors() {
            return configs.stream().anyMatch(WorkflowBusinessConfigAssembler.Assembled::hasErrors);
        }

        List<String> errorMessages() {
            List<String> messages = new ArrayList<>();
            for (WorkflowBusinessConfigAssembler.Assembled assembled : configs) {
                for (String message : assembled.errorMessages()) {
                    messages.add("device " + assembled.deviceId() + ": " + message);
                }
            }
            return messages;
        }

        Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("v2Devices", configs.stream()
                    .map(WorkflowBusinessConfigAssembler.Assembled::toMap)
                    .toList());
            data.put("legacyDevices", legacyDevices);
            return data;
        }
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
        // 仅在绑定本身合法时预览业务配置，避免在无效输入上叠加二次报错。
        AssembleResult assembleResult = errors.isEmpty()
                ? assemble(workflow, bindings)
                : new AssembleResult(List.of(), List.of());
        errors.addAll(assembleResult.errorMessages());
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
        data.put("deviceConfigs", assembleResult.toMap());
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
            if (!NodeWorkflowSupport.isTriggerNode(node.nodeType())) continue;
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
