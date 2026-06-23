package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.handler.EventInputHandler;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDeploymentEntity;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowRunEntity;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowRunStepEntity;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowDefinition;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowEdge;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowNode;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowDeploymentRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowRunRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowRunStepRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class NodeWorkflowRuntimeService implements EventInputHandler.PayloadEventListener {

    private final NodeWorkflowService workflowService;
    private final NodeWorkflowDeploymentService deploymentService;
    private final NodeWorkflowDeploymentRepository deploymentRepository;
    private final NodeWorkflowRunRepository runRepository;
    private final NodeWorkflowRunStepRepository stepRepository;
    private final CapabilityNodeExecutorService executorService;
    private final NodeWorkflowRunContextService contextService;
    private final NodeWorkflowParameterResolver parameterResolver;

    public NodeWorkflowRuntimeService(EventInputHandler eventInputHandler,
                                      NodeWorkflowService workflowService,
                                      NodeWorkflowDeploymentService deploymentService,
                                      NodeWorkflowDeploymentRepository deploymentRepository,
                                      NodeWorkflowRunRepository runRepository,
                                      NodeWorkflowRunStepRepository stepRepository,
                                      CapabilityNodeExecutorService executorService,
                                      NodeWorkflowRunContextService contextService,
                                      NodeWorkflowParameterResolver parameterResolver) {
        this.workflowService = workflowService;
        this.deploymentService = deploymentService;
        this.deploymentRepository = deploymentRepository;
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.executorService = executorService;
        this.contextService = contextService;
        this.parameterResolver = parameterResolver;
        eventInputHandler.addPayloadListener(this);
    }

    @Override
    public void onEvent(EventPayload payload) {
        if (payload == null || payload.deviceId() == null) {
            return;
        }
        for (NodeWorkflowDeploymentEntity deployment : deploymentRepository.findByStatus("active")) {
            NodeWorkflowDefinition workflow;
            try {
                workflow = workflowService.workflow(deployment.getWorkflowId());
            } catch (Exception e) {
                log.warn("Node workflow deployment {} skipped: {}", deployment.getId(), e.getMessage());
                continue;
            }
            Optional<String> slotId = slotForDevice(deployment, payload.deviceId());
            if (slotId.isEmpty()) {
                continue;
            }
            for (NodeWorkflowNode trigger : workflow.nodes()) {
                if (!NodeWorkflowSupport.TRIGGER_NODE_TYPES.contains(trigger.nodeType())) continue;
                if (!slotId.get().equals(trigger.slotId())) continue;
                if (!triggerMatches(trigger, payload)) continue;
                execute(workflow, deployment, trigger, eventToMap(payload));
            }
        }
    }

    public Map<String, Object> testTrigger(String workflowId, String deploymentId, Map<String, Object> body) {
        NodeWorkflowDefinition workflow = workflowService.workflow(workflowId);
        NodeWorkflowDeploymentEntity deployment = deploymentService.requireDeployment(workflowId, deploymentId);
        if (!"active".equals(deployment.getStatus())) {
            throw new IllegalArgumentException("deployment is not active: " + deploymentId);
        }
        String triggerNodeId = NodeWorkflowSupport.string(body.get("triggerNodeId"));
        NodeWorkflowNode trigger = triggerNodeId.isBlank()
                ? firstTriggerNode(workflow)
                : NodeWorkflowSupport.nodeById(workflow, triggerNodeId);
        if (!NodeWorkflowSupport.TRIGGER_NODE_TYPES.contains(trigger.nodeType())) {
            throw new IllegalArgumentException("test trigger node must be button.trigger: " + trigger.nodeId());
        }
        String deviceId = NodeWorkflowSupport.stringMap(deployment.getSlotBindings()).get(trigger.slotId());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", NodeWorkflowSupport.string(trigger.params().getOrDefault("eventId", body.getOrDefault("eventId", "manual.test"))));
        event.put("deviceId", deviceId);
        event.put("nodeId", NodeWorkflowSupport.string(trigger.params().getOrDefault("nodeId", body.getOrDefault("nodeId", ""))));
        event.put("manual", true);
        event.put("ts", System.currentTimeMillis());
        return runToMap(execute(workflow, deployment, trigger, event), true);
    }

    public List<Map<String, Object>> listRuns(String workflowId, String deploymentId) {
        deploymentService.requireDeployment(workflowId, deploymentId);
        return runRepository.findByDeploymentIdOrderByStartedAtDesc(deploymentId)
                .stream()
                .map(run -> runToMap(run, false))
                .toList();
    }

    public Map<String, Object> getRun(String workflowId, String runId) {
        NodeWorkflowRunEntity run = runRepository.findByWorkflowIdAndId(workflowId, runId)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
        return runToMap(run, true);
    }

    private NodeWorkflowRunEntity execute(NodeWorkflowDefinition workflow,
                                          NodeWorkflowDeploymentEntity deployment,
                                          NodeWorkflowNode trigger,
                                          Map<String, Object> event) {
        NodeWorkflowRunEntity run = new NodeWorkflowRunEntity();
        run.setWorkflowId(workflow.id());
        run.setDeploymentId(deployment.getId());
        run.setTriggerNodeId(trigger.nodeId());
        run.setTriggerEvent(new LinkedHashMap<>(event));
        run = runRepository.save(run);
        Map<String, Object> context = contextService.create(run, deployment, workflow, trigger, event);
        run.setContext(context);
        run = runRepository.save(run);

        String status = "passed";
        String error = null;
        List<NodeWorkflowNode> plan = NodeWorkflowSupport.executionPlan(workflow, trigger);
        for (NodeWorkflowNode target : plan) {
            StepExecution stepExecution = saveStep(run, deployment, target, context);
            run.setContext(context);
            run = runRepository.save(run);
            if (!stepExecution.passed()) {
                status = "failed";
                error = stepExecution.error();
                break;
            }
        }
        if (plan.isEmpty()) {
            status = "ignored";
            error = "trigger has no outgoing edges: " + trigger.nodeId();
        }
        run.setStatus(status);
        run.setError(error);
        run.setCompletedAt(LocalDateTime.now());
        run.setContext(context);
        return runRepository.save(run);
    }

    private StepExecution saveStep(NodeWorkflowRunEntity run,
                                   NodeWorkflowDeploymentEntity deployment,
                                   NodeWorkflowNode target,
                                   Map<String, Object> context) {
        String deviceId = NodeWorkflowSupport.stringMap(deployment.getSlotBindings()).get(target.slotId());
        NodeWorkflowRunStepEntity step = new NodeWorkflowRunStepEntity();
        step.setRunId(run.getId());
        step.setWorkflowId(run.getWorkflowId());
        step.setDeploymentId(run.getDeploymentId());
        step.setNodeId(target.nodeId());
        step.setSlotId(target.slotId());
        step.setDeviceId(deviceId);
        step.setNodeType(target.nodeType());
        try {
            Map<String, Object> resolvedParams = parameterResolver.resolveParams(target.params(), context);
            step.setInputParams(resolvedParams);
            Map<String, Object> result = executorService.execute(deviceId, target.nodeType(), resolvedParams,
                    Map.of(
                            "workflowId", run.getWorkflowId(),
                            "deploymentId", run.getDeploymentId(),
                            "runId", run.getId(),
                            "slotId", target.slotId(),
                            "nodeId", target.nodeId()
                    ));
            String stepStatus = "sent".equals(result.get("status")) || Boolean.TRUE.equals(result.get("sent"))
                    ? "passed" : NodeWorkflowSupport.string(result.getOrDefault("status", "failed"));
            step.setStatus(stepStatus);
            step.setResult(result);
            contextService.putNodeResult(context, run, target, deviceId, result);
            stepRepository.save(step);
            if (!"passed".equals(stepStatus)) return new StepExecution(false, "node " + target.nodeId() + " returned " + stepStatus);
            return new StepExecution(true, null);
        } catch (Exception e) {
            step.setStatus("failed");
            step.setResult(Map.of());
            step.setError(e.getMessage());
            stepRepository.save(step);
            return new StepExecution(false, e.getMessage());
        }
    }

    private boolean triggerMatches(NodeWorkflowNode trigger, EventPayload payload) {
        String expectedEventId = NodeWorkflowSupport.string(trigger.params().get("eventId"));
        String expectedNodeId = NodeWorkflowSupport.string(trigger.params().get("nodeId"));
        if (!NodeWorkflowSupport.eventMatches(expectedEventId, payload.eventId())) {
            return false;
        }
        return expectedNodeId.isBlank() || expectedNodeId.equals(payload.nodeId());
    }

    private Optional<String> slotForDevice(NodeWorkflowDeploymentEntity deployment, String deviceId) {
        return NodeWorkflowSupport.stringMap(deployment.getSlotBindings()).entrySet().stream()
                .filter(entry -> deviceId.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private NodeWorkflowNode firstTriggerNode(NodeWorkflowDefinition workflow) {
        return workflow.nodes().stream()
                .filter(node -> NodeWorkflowSupport.TRIGGER_NODE_TYPES.contains(node.nodeType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("workflow has no trigger node"));
    }

    private Map<String, Object> eventToMap(EventPayload payload) {
        Map<String, Object> event = new LinkedHashMap<>(payload.toLegacyMap());
        event.put("eventId", payload.eventId());
        event.put("deviceId", payload.deviceId());
        event.put("nodeId", payload.nodeId());
        event.put("ts", payload.ts());
        return event;
    }

    private Map<String, Object> runToMap(NodeWorkflowRunEntity run, boolean includeSteps) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", run.getId());
        data.put("workflowId", run.getWorkflowId());
        data.put("deploymentId", run.getDeploymentId());
        data.put("triggerNodeId", run.getTriggerNodeId());
        data.put("status", run.getStatus());
        data.put("event", run.getTriggerEvent());
        data.put("context", run.getContext());
        data.put("error", run.getError());
        data.put("startedAt", run.getStartedAt() != null ? run.getStartedAt().toString() : null);
        data.put("completedAt", run.getCompletedAt() != null ? run.getCompletedAt().toString() : null);
        if (includeSteps) {
            data.put("steps", stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                    .map(this::stepToMap)
                    .toList());
        }
        return data;
    }

    private Map<String, Object> stepToMap(NodeWorkflowRunStepEntity step) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepId", step.getId());
        data.put("runId", step.getRunId());
        data.put("nodeId", step.getNodeId());
        data.put("slotId", step.getSlotId());
        data.put("deviceId", step.getDeviceId());
        data.put("nodeType", step.getNodeType());
        data.put("status", step.getStatus());
        data.put("inputParams", step.getInputParams());
        data.put("resolvedParams", step.getInputParams());
        data.put("result", step.getResult());
        data.put("error", step.getError());
        data.put("createdAt", step.getCreatedAt() != null ? step.getCreatedAt().toString() : null);
        return data;
    }

    private record StepExecution(boolean passed, String error) {}
}
