package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDefinitionEntity;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDeploymentEntity;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowRunEntity;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowDefinition;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowNode;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowDefinitionRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowDeploymentRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowRunRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class NodeWorkflowManagementService {

    private final NodeWorkflowService workflowService;
    private final NodeWorkflowDeploymentService deploymentService;
    private final NodeWorkflowDefinitionRepository workflowRepository;
    private final NodeWorkflowDeploymentRepository deploymentRepository;
    private final NodeWorkflowRunRepository runRepository;
    private final DeviceSessionManager sessionManager;

    public NodeWorkflowManagementService(NodeWorkflowService workflowService,
                                         NodeWorkflowDeploymentService deploymentService,
                                         NodeWorkflowDefinitionRepository workflowRepository,
                                         NodeWorkflowDeploymentRepository deploymentRepository,
                                         NodeWorkflowRunRepository runRepository,
                                         DeviceSessionManager sessionManager) {
        this.workflowService = workflowService;
        this.deploymentService = deploymentService;
        this.workflowRepository = workflowRepository;
        this.deploymentRepository = deploymentRepository;
        this.runRepository = runRepository;
        this.sessionManager = sessionManager;
    }

    public Map<String, Object> overview() {
        List<NodeWorkflowDefinitionEntity> workflows = workflowRepository.findAllByOrderByUpdatedAtDesc();
        List<NodeWorkflowDeploymentEntity> deployments = deploymentRepository.findAll();
        List<NodeWorkflowRunEntity> runs = runRepository.findAllByOrderByStartedAtDesc();
        List<Map<String, Object>> conflicts = conflicts();
        NodeWorkflowRunEntity latestRun = runs.isEmpty() ? null : runs.get(0);
        NodeWorkflowRunEntity latestFailedRun = runs.stream()
                .filter(run -> "failed".equals(run.getStatus()))
                .findFirst()
                .orElse(null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workflowCount", workflows.size());
        data.put("deploymentCount", deployments.size());
        data.put("activeDeploymentCount", deployments.stream().filter(dep -> "active".equals(dep.getStatus())).count());
        data.put("runCount", runs.size());
        data.put("failedRunCount", runs.stream().filter(run -> "failed".equals(run.getStatus())).count());
        data.put("conflictCount", conflicts.size());
        data.put("latestRun", latestRun != null ? runSummary(latestRun) : null);
        data.put("latestFailedRun", latestFailedRun != null ? runSummary(latestFailedRun) : null);
        data.put("health", conflicts.isEmpty() && latestFailedRun == null ? "ok" : conflicts.isEmpty() ? "warning" : "error");
        return data;
    }

    public List<Map<String, Object>> deployments(String status) {
        String normalized = status == null || status.isBlank() ? "active" : status;
        List<NodeWorkflowDeploymentEntity> deployments = "all".equals(normalized)
                ? deploymentRepository.findAllByOrderByDeployedAtDesc()
                : deploymentRepository.findByStatusOrderByDeployedAtDesc(normalized);
        return deployments.stream().map(this::deploymentSummary).toList();
    }

    public List<Map<String, Object>> deviceDeployments(String deviceId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (NodeWorkflowDeploymentEntity deployment : deploymentRepository.findAllByOrderByDeployedAtDesc()) {
            Map<String, String> bindings = NodeWorkflowSupport.stringMap(deployment.getSlotBindings());
            List<String> slotIds = bindings.entrySet().stream()
                    .filter(entry -> deviceId.equals(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
            if (slotIds.isEmpty()) continue;
            Map<String, Object> summary = deploymentSummary(deployment);
            summary.put("deviceId", deviceId);
            summary.put("slotIds", slotIds);
            summary.put("nodes", nodesForSlots(deployment, slotIds));
            result.add(summary);
        }
        return result;
    }

    public List<Map<String, Object>> conflicts() {
        List<TriggerWithWorkflow> triggers = new ArrayList<>();
        for (NodeWorkflowDeploymentEntity deployment : deploymentRepository.findByStatus("active")) {
            try {
                NodeWorkflowDefinition workflow = workflowService.workflow(deployment.getWorkflowId());
                NodeWorkflowDefinitionEntity entity = workflowService.requireEntity(deployment.getWorkflowId());
                for (NodeWorkflowDeploymentService.TriggerBinding trigger : deploymentService.triggerBindings(workflow, deployment)) {
                    triggers.add(new TriggerWithWorkflow(trigger, entity.getName(), deployment.getWorkflowVersion()));
                }
            } catch (Exception ignored) {
                // Broken deployments are surfaced by deployment details; conflict scanning skips them.
            }
        }

        List<Map<String, Object>> conflicts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < triggers.size(); i++) {
            for (int j = i + 1; j < triggers.size(); j++) {
                TriggerWithWorkflow left = triggers.get(i);
                TriggerWithWorkflow right = triggers.get(j);
                if (!left.trigger.conflictsWith(right.trigger)) continue;
                String key = conflictKey(left.trigger, right.trigger);
                if (!seen.add(key)) continue;
                conflicts.add(Map.of(
                        "type", "blocking_conflict",
                        "left", triggerMap(left),
                        "right", triggerMap(right),
                        "message", "multiple active deployments listen to the same trigger"
                ));
            }
        }
        return conflicts;
    }

    public Map<String, Object> inspectDeployment(String workflowId, Map<String, Object> body) {
        return deploymentService.inspectDeployment(workflowId, body == null ? Map.of() : body);
    }

    public List<Map<String, Object>> recentRuns(String status, int limit) {
        String normalized = status == null || status.isBlank() ? "all" : status;
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        List<NodeWorkflowRunEntity> runs = "all".equals(normalized)
                ? runRepository.findAllByOrderByStartedAtDesc()
                : runRepository.findByStatusOrderByStartedAtDesc(normalized);
        return runs.stream().limit(safeLimit).map(this::runSummary).toList();
    }

    private Map<String, Object> deploymentSummary(NodeWorkflowDeploymentEntity deployment) {
        NodeWorkflowDefinitionEntity workflow = null;
        try {
            workflow = workflowService.requireEntity(deployment.getWorkflowId());
        } catch (Exception ignored) {
        }
        List<NodeWorkflowRunEntity> runs = runRepository.findByDeploymentIdOrderByStartedAtDesc(deployment.getId());
        NodeWorkflowRunEntity lastRun = runs.isEmpty() ? null : runs.get(0);
        long failedRunCount = runs.stream().filter(run -> "failed".equals(run.getStatus())).count();

        Map<String, Object> data = deploymentService.toMap(deployment);
        data.put("workflowName", workflow != null ? workflow.getName() : null);
        data.put("workflowStatus", workflow != null ? workflow.getStatus() : null);
        data.put("deviceStatuses", deviceStatuses(deployment));
        data.put("lastRun", lastRun != null ? runSummary(lastRun) : null);
        data.put("lastStatus", lastRun != null ? lastRun.getStatus() : null);
        data.put("lastError", lastRun != null ? lastRun.getError() : null);
        data.put("runCount", runs.size());
        data.put("failedRunCount", failedRunCount);
        data.put("health", deploymentHealth(deployment, failedRunCount, lastRun));
        return data;
    }

    private List<Map<String, Object>> nodesForSlots(NodeWorkflowDeploymentEntity deployment, List<String> slotIds) {
        try {
            NodeWorkflowDefinition workflow = workflowService.workflow(deployment.getWorkflowId());
            return workflow.nodes().stream()
                    .filter(node -> slotIds.contains(node.slotId()))
                    .map(node -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("nodeId", node.nodeId());
                        item.put("nodeType", node.nodeType());
                        item.put("slotId", node.slotId());
                        item.put("role", NodeWorkflowSupport.TRIGGER_NODE_TYPES.contains(node.nodeType()) ? "trigger" : "action");
                        item.put("params", node.params());
                        return item;
                    })
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> deviceStatuses(NodeWorkflowDeploymentEntity deployment) {
        Map<String, Object> result = new LinkedHashMap<>();
        NodeWorkflowSupport.stringMap(deployment.getSlotBindings()).forEach((slotId, deviceId) -> result.put(slotId, Map.of(
                "slotId", slotId,
                "deviceId", deviceId,
                "online", sessionManager.isDeviceOnline(deviceId)
        )));
        return result;
    }

    private String deploymentHealth(NodeWorkflowDeploymentEntity deployment, long failedRunCount, NodeWorkflowRunEntity lastRun) {
        if (!"active".equals(deployment.getStatus())) return "inactive";
        boolean anyOffline = NodeWorkflowSupport.stringMap(deployment.getSlotBindings()).values().stream()
                .anyMatch(deviceId -> !sessionManager.isDeviceOnline(deviceId));
        if (anyOffline || (lastRun != null && "failed".equals(lastRun.getStatus()))) return "error";
        if (failedRunCount > 0) return "warning";
        return "ok";
    }

    private Map<String, Object> runSummary(NodeWorkflowRunEntity run) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", run.getId());
        data.put("workflowId", run.getWorkflowId());
        data.put("deploymentId", run.getDeploymentId());
        data.put("triggerNodeId", run.getTriggerNodeId());
        data.put("status", run.getStatus());
        data.put("error", run.getError());
        data.put("startedAt", run.getStartedAt() != null ? run.getStartedAt().toString() : null);
        data.put("completedAt", run.getCompletedAt() != null ? run.getCompletedAt().toString() : null);
        return data;
    }

    private Map<String, Object> triggerMap(TriggerWithWorkflow trigger) {
        Map<String, Object> data = new LinkedHashMap<>(trigger.trigger.toMap());
        data.put("workflowName", trigger.workflowName);
        data.put("workflowVersion", trigger.workflowVersion);
        return data;
    }

    private String conflictKey(NodeWorkflowDeploymentService.TriggerBinding left,
                               NodeWorkflowDeploymentService.TriggerBinding right) {
        return List.of(left.deploymentId(), left.triggerNodeId(), right.deploymentId(), right.triggerNodeId()).stream()
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.joining(":"));
    }

    private record TriggerWithWorkflow(NodeWorkflowDeploymentService.TriggerBinding trigger,
                                       String workflowName,
                                       Integer workflowVersion) {}
}
