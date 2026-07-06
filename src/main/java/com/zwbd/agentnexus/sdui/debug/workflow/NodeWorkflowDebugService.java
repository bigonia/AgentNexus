package com.zwbd.agentnexus.sdui.debug.workflow;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalog;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalogService;
import com.zwbd.agentnexus.sdui.debug.node.CapabilityNodeTestService;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.handler.EventInputHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Service
public class NodeWorkflowDebugService implements EventInputHandler.PayloadEventListener {

    private static final int MAX_RUNS_PER_DEPLOYMENT = 50;
    private static boolean isTriggerNode(String nodeType) { return nodeType != null && nodeType.endsWith(".trigger"); }
    private static final Set<String> OUTPUT_NODE_TYPES = Set.of("rgb.effect", "audio.play", "audio.record", "ui.update", "display.section");

    private final DeviceSessionManager sessionManager;
    private final CapabilityNodeCatalogService nodeCatalogService;
    private final CapabilityNodeTestService nodeTestService;
    private final Map<String, NodeWorkflowDefinition> workflows = new ConcurrentHashMap<>();
    private final Map<String, NodeWorkflowDeployment> deployments = new ConcurrentHashMap<>();
    private final Map<String, Deque<NodeWorkflowRun>> runsByDeployment = new ConcurrentHashMap<>();

    public NodeWorkflowDebugService(EventInputHandler eventInputHandler,
                                    DeviceSessionManager sessionManager,
                                    CapabilityNodeCatalogService nodeCatalogService,
                                    CapabilityNodeTestService nodeTestService) {
        this.sessionManager = sessionManager;
        this.nodeCatalogService = nodeCatalogService;
        this.nodeTestService = nodeTestService;
        eventInputHandler.addPayloadListener(this);
    }

    public Map<String, Object> createWorkflow(NodeWorkflowDefinition definition) {
        NodeWorkflowDefinition normalized = normalizeDefinition(definition);
        validateDefinition(normalized);
        workflows.put(normalized.id(), normalized);
        return workflowToMap(normalized);
    }

    public Map<String, Object> deploy(String workflowId, Map<String, Object> body) {
        NodeWorkflowDefinition workflow = requireWorkflow(workflowId);
        Map<String, String> bindings = normalizeBindings(body.get("slotBindings"));
        validateBindings(workflow, bindings);

        String deploymentId = UUID.randomUUID().toString();
        NodeWorkflowDeployment deployment = new NodeWorkflowDeployment(
                deploymentId,
                workflow.id(),
                bindings,
                "active",
                System.currentTimeMillis()
        );
        List<String> replacedDeployments = removeConflictingDeployments(workflow, deployment);
        deployments.put(deploymentId, deployment);
        runsByDeployment.put(deploymentId, new ConcurrentLinkedDeque<>());
        Map<String, Object> data = deploymentToMap(workflow, deployment);
        data.put("replacedDeployments", replacedDeployments);
        return data;
    }

    public Optional<Map<String, Object>> getDeployment(String workflowId, String deploymentId) {
        NodeWorkflowDeployment deployment = deployments.get(deploymentId);
        if (deployment == null || !deployment.workflowId().equals(workflowId)) {
            return Optional.empty();
        }
        NodeWorkflowDefinition workflow = requireWorkflow(workflowId);
        return Optional.of(deploymentToMap(workflow, deployment));
    }

    public Map<String, Object> deleteDeployment(String workflowId, String deploymentId) {
        NodeWorkflowDeployment deployment = deployments.get(deploymentId);
        if (deployment == null || !deployment.workflowId().equals(workflowId)) {
            throw new IllegalArgumentException("deployment not found: " + deploymentId);
        }
        deployments.remove(deploymentId);
        return Map.of(
                "deleted", true,
                "workflowId", workflowId,
                "deploymentId", deploymentId
        );
    }

    public List<Map<String, Object>> listRuns(String workflowId, String deploymentId) {
        NodeWorkflowDeployment deployment = deployments.get(deploymentId);
        if (deployment == null || !deployment.workflowId().equals(workflowId)) {
            throw new IllegalArgumentException("deployment not found: " + deploymentId);
        }
        return runsByDeployment.getOrDefault(deploymentId, new ConcurrentLinkedDeque<>())
                .stream()
                .map(this::runToMap)
                .toList();
    }

    public Map<String, Object> testTrigger(String workflowId, String deploymentId, Map<String, Object> body) {
        NodeWorkflowDefinition workflow = requireWorkflow(workflowId);
        NodeWorkflowDeployment deployment = requireActiveDeployment(workflowId, deploymentId);
        String triggerNodeId = string(body.get("triggerNodeId"));
        NodeWorkflowNode trigger = triggerNodeId.isBlank()
                ? firstTriggerNode(workflow)
                : nodeById(workflow, triggerNodeId);
        if (!isTriggerNode(trigger.nodeType())) {
            throw new IllegalArgumentException("test trigger node must be a trigger type: " + trigger.nodeId());
        }

        String deviceId = deployment.slotBindings().get(trigger.slotId());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", string(trigger.params().getOrDefault("eventId", body.getOrDefault("eventId", "manual.test"))));
        event.put("deviceId", deviceId);
        event.put("nodeId", string(trigger.params().getOrDefault("nodeId", body.getOrDefault("nodeId", ""))));
        event.put("manual", true);
        event.put("ts", System.currentTimeMillis());
        NodeWorkflowRun run = execute(workflow, deployment, trigger, event);
        return runToMap(run);
    }

    @Override
    public void onEvent(EventPayload payload) {
        if (payload == null || payload.deviceId() == null) {
            return;
        }
        for (NodeWorkflowDeployment deployment : deployments.values()) {
            if (!"active".equals(deployment.status())) {
                continue;
            }
            NodeWorkflowDefinition workflow = workflows.get(deployment.workflowId());
            if (workflow == null) {
                continue;
            }
            Optional<String> slotId = slotForDevice(deployment, payload.deviceId());
            if (slotId.isEmpty()) {
                continue;
            }
            for (NodeWorkflowNode trigger : workflow.nodes()) {
                if (!isTriggerNode(trigger.nodeType())) {
                    continue;
                }
                if (!slotId.get().equals(trigger.slotId())) {
                    continue;
                }
                if (!triggerMatches(trigger, payload)) {
                    continue;
                }
                execute(workflow, deployment, trigger, eventToMap(payload));
            }
        }
    }

    private NodeWorkflowRun execute(NodeWorkflowDefinition workflow,
                                    NodeWorkflowDeployment deployment,
                                    NodeWorkflowNode trigger,
                                    Map<String, Object> event) {
        String runId = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();
        List<NodeWorkflowStepResult> steps = new ArrayList<>();
        String status = "passed";
        String error = null;

        for (NodeWorkflowEdge edge : workflow.edges()) {
            if (!trigger.nodeId().equals(edge.from())) {
                continue;
            }
            NodeWorkflowNode target = nodeById(workflow, edge.to());
            String deviceId = deployment.slotBindings().get(target.slotId());
            try {
                Map<String, Object> result = nodeTestService.executeOutputTest(deviceId, Map.of(
                        "nodeType", target.nodeType(),
                        "params", target.params()
                ));
                String stepStatus = "sent".equals(result.get("status")) || Boolean.TRUE.equals(result.get("sent"))
                        ? "passed" : string(result.getOrDefault("status", "failed"));
                if (!"passed".equals(stepStatus)) {
                    status = "failed";
                    error = "node " + target.nodeId() + " returned " + stepStatus;
                }
                steps.add(new NodeWorkflowStepResult(
                        target.nodeId(),
                        target.slotId(),
                        deviceId,
                        target.nodeType(),
                        stepStatus,
                        result,
                        null
                ));
            } catch (Exception e) {
                status = "failed";
                error = e.getMessage();
                steps.add(new NodeWorkflowStepResult(
                        target.nodeId(),
                        target.slotId(),
                        deviceId,
                        target.nodeType(),
                        "failed",
                        Map.of(),
                        e.getMessage()
                ));
            }
        }
        if (steps.isEmpty()) {
            status = "ignored";
            error = "trigger has no outgoing edges: " + trigger.nodeId();
        }

        NodeWorkflowRun run = new NodeWorkflowRun(
                runId,
                workflow.id(),
                deployment.deploymentId(),
                trigger.nodeId(),
                status,
                event,
                List.copyOf(steps),
                createdAt,
                System.currentTimeMillis(),
                error
        );
        appendRun(deployment.deploymentId(), run);
        return run;
    }

    private void validateDefinition(NodeWorkflowDefinition workflow) {
        if (workflow.slots().isEmpty()) {
            throw new IllegalArgumentException("slots are required");
        }
        if (workflow.nodes().isEmpty()) {
            throw new IllegalArgumentException("nodes are required");
        }
        if (workflow.edges().isEmpty()) {
            throw new IllegalArgumentException("edges are required");
        }

        Set<String> slotIds = new LinkedHashSet<>();
        for (NodeWorkflowSlot slot : workflow.slots()) {
            if (string(slot.slotId()).isBlank()) {
                throw new IllegalArgumentException("slotId is required");
            }
            if (!slotIds.add(slot.slotId())) {
                throw new IllegalArgumentException("duplicate slotId: " + slot.slotId());
            }
        }

        Set<String> nodeIds = new LinkedHashSet<>();
        boolean hasTrigger = false;
        for (NodeWorkflowNode node : workflow.nodes()) {
            if (string(node.nodeId()).isBlank()) {
                throw new IllegalArgumentException("nodeId is required");
            }
            if (!nodeIds.add(node.nodeId())) {
                throw new IllegalArgumentException("duplicate nodeId: " + node.nodeId());
            }
            if (!slotIds.contains(node.slotId())) {
                throw new IllegalArgumentException("node references unknown slot: " + node.nodeId());
            }
            if (!isTriggerNode(node.nodeType()) && !OUTPUT_NODE_TYPES.contains(node.nodeType())) {
                throw new IllegalArgumentException("unsupported nodeType: " + node.nodeType());
            }
            hasTrigger = hasTrigger || isTriggerNode(node.nodeType());
        }
        if (!hasTrigger) {
            throw new IllegalArgumentException("at least one trigger node is required");
        }

        for (NodeWorkflowEdge edge : workflow.edges()) {
            if (!nodeIds.contains(edge.from())) {
                throw new IllegalArgumentException("edge references unknown from node: " + edge.from());
            }
            if (!nodeIds.contains(edge.to())) {
                throw new IllegalArgumentException("edge references unknown to node: " + edge.to());
            }
            NodeWorkflowNode from = nodeById(workflow, edge.from());
            NodeWorkflowNode to = nodeById(workflow, edge.to());
            if (!isTriggerNode(from.nodeType())) {
                throw new IllegalArgumentException("edge from node must be trigger: " + edge.from());
            }
            if (!OUTPUT_NODE_TYPES.contains(to.nodeType())) {
                throw new IllegalArgumentException("edge to node must be output: " + edge.to());
            }
        }
    }

    private void validateBindings(NodeWorkflowDefinition workflow, Map<String, String> bindings) {
        Set<String> expectedSlots = workflow.slots().stream()
                .map(NodeWorkflowSlot::slotId)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        for (String boundSlot : bindings.keySet()) {
            if (!expectedSlots.contains(boundSlot)) {
                throw new IllegalArgumentException("slot binding references unknown slot: " + boundSlot);
            }
        }
        for (String slotId : expectedSlots) {
            String deviceId = bindings.get(slotId);
            if (deviceId == null || deviceId.isBlank()) {
                throw new IllegalArgumentException("slot binding is required: " + slotId);
            }
            if (!sessionManager.isDeviceOnline(deviceId)) {
                throw new IllegalArgumentException("device is offline for slot " + slotId + ": " + deviceId);
            }
        }

        for (NodeWorkflowNode node : workflow.nodes()) {
            String deviceId = bindings.get(node.slotId());
            CapabilityNodeCatalog catalog = nodeCatalogService.buildForDevice(deviceId);
            boolean hasNode = catalog.nodes().stream().anyMatch(def -> node.nodeType().equals(def.nodeType()));
            if (!hasNode) {
                throw new IllegalArgumentException("device " + deviceId + " for slot " + node.slotId()
                        + " does not support nodeType " + node.nodeType());
            }
        }
    }

    private List<String> removeConflictingDeployments(NodeWorkflowDefinition newWorkflow,
                                                      NodeWorkflowDeployment newDeployment) {
        List<TriggerBinding> newTriggers = triggerBindings(newWorkflow, newDeployment);
        if (newTriggers.isEmpty()) {
            return List.of();
        }
        List<String> removed = new ArrayList<>();
        for (NodeWorkflowDeployment existingDeployment : deployments.values()) {
            if (!"active".equals(existingDeployment.status())) {
                continue;
            }
            NodeWorkflowDefinition existingWorkflow = workflows.get(existingDeployment.workflowId());
            if (existingWorkflow == null) {
                continue;
            }
            if (hasConflictingTrigger(newTriggers, triggerBindings(existingWorkflow, existingDeployment))) {
                deployments.remove(existingDeployment.deploymentId());
                removed.add(existingDeployment.deploymentId());
            }
        }
        if (!removed.isEmpty()) {
            log.info("Replaced {} conflicting debug node workflow deployments for workflow {}: {}",
                    removed.size(), newWorkflow.id(), removed);
        }
        return removed;
    }

    private boolean hasConflictingTrigger(List<TriggerBinding> left, List<TriggerBinding> right) {
        for (TriggerBinding a : left) {
            for (TriggerBinding b : right) {
                if (a.conflictsWith(b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<TriggerBinding> triggerBindings(NodeWorkflowDefinition workflow,
                                                 NodeWorkflowDeployment deployment) {
        List<TriggerBinding> bindings = new ArrayList<>();
        for (NodeWorkflowNode node : workflow.nodes()) {
            if (!isTriggerNode(node.nodeType())) {
                continue;
            }
            String deviceId = deployment.slotBindings().get(node.slotId());
            if (deviceId == null || deviceId.isBlank()) {
                continue;
            }
            bindings.add(new TriggerBinding(
                    deviceId,
                    string(node.params().get("eventId")),
                    string(node.params().get("nodeId")),
                    string(node.params().get("sectionId"))
            ));
        }
        return bindings;
    }

    private boolean triggerMatches(NodeWorkflowNode trigger, EventPayload payload) {
        String expectedEventId = string(trigger.params().get("eventId"));
        String expectedNodeId = string(trigger.params().get("nodeId"));
        String expectedSectionId = string(trigger.params().get("sectionId"));
        if (!eventMatches(expectedEventId, payload.eventId())) {
            return false;
        }
        if (!nodeIdMatches(expectedNodeId, payload.nodeId())) {
            return false;
        }
        return expectedSectionId.isBlank() || expectedSectionId.equals(payload.sectionId());
    }

    private boolean eventMatches(String expected, String actual) {
        if (Objects.equals(expected, actual)) {
            return true;
        }
        if (expected == null || actual == null || expected.isBlank() || actual.isBlank()) {
            return false;
        }
        return expected.endsWith("." + actual) || actual.endsWith("." + expected);
    }

    /**
     * Match expected nodeId against actual nodeId, stripping any {@code :digit}
     * state suffix that device firmware may append (e.g. "option_b:0" → "option_b").
     */
    private boolean nodeIdMatches(String expected, String actual) {
        if (expected == null || expected.isBlank()) return true;
        if (actual == null || actual.isBlank()) return false;
        if (expected.equals(actual)) return true;
        String stripped = actual.replaceFirst(":\\d+$", "");
        return expected.equals(stripped);
    }

    private Optional<String> slotForDevice(NodeWorkflowDeployment deployment, String deviceId) {
        return deployment.slotBindings().entrySet().stream()
                .filter(entry -> deviceId.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private NodeWorkflowDefinition normalizeDefinition(NodeWorkflowDefinition raw) {
        if (raw == null) {
            throw new IllegalArgumentException("workflow definition is required");
        }
        String id = string(raw.id()).isBlank() ? UUID.randomUUID().toString() : raw.id();
        List<NodeWorkflowSlot> slots = safeList(raw.slots()).stream()
                .map(slot -> new NodeWorkflowSlot(
                        string(slot.slotId()),
                        string(slot.board()),
                        string(slot.displayName()),
                        List.copyOf(safeList(slot.requiredCapabilities()))
                ))
                .toList();
        List<NodeWorkflowNode> nodes = safeList(raw.nodes()).stream()
                .map(node -> new NodeWorkflowNode(
                        string(node.nodeId()),
                        string(node.slotId()),
                        string(node.nodeType()),
                        normalizeMap(node.params())
                ))
                .toList();
        List<NodeWorkflowEdge> edges = safeList(raw.edges()).stream()
                .map(edge -> new NodeWorkflowEdge(string(edge.from()), string(edge.to())))
                .toList();
        return new NodeWorkflowDefinition(id, string(raw.name()), slots, nodes, edges);
    }

    private Map<String, String> normalizeBindings(Object raw) {
        Map<String, String> bindings = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                bindings.put(String.valueOf(entry.getKey()), string(entry.getValue()));
            }
        }
        return bindings;
    }

    private Map<String, Object> normalizeMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    private NodeWorkflowDefinition requireWorkflow(String workflowId) {
        NodeWorkflowDefinition workflow = workflows.get(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("workflow not found: " + workflowId);
        }
        return workflow;
    }

    private NodeWorkflowDeployment requireActiveDeployment(String workflowId, String deploymentId) {
        NodeWorkflowDeployment deployment = deployments.get(deploymentId);
        if (deployment == null || !deployment.workflowId().equals(workflowId)) {
            throw new IllegalArgumentException("deployment not found: " + deploymentId);
        }
        if (!"active".equals(deployment.status())) {
            throw new IllegalArgumentException("deployment is not active: " + deploymentId);
        }
        return deployment;
    }

    private NodeWorkflowNode firstTriggerNode(NodeWorkflowDefinition workflow) {
        return workflow.nodes().stream()
                .filter(node -> isTriggerNode(node.nodeType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("workflow has no trigger node"));
    }

    private NodeWorkflowNode nodeById(NodeWorkflowDefinition workflow, String nodeId) {
        return workflow.nodes().stream()
                .filter(node -> node.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("node not found: " + nodeId));
    }

    private void appendRun(String deploymentId, NodeWorkflowRun run) {
        Deque<NodeWorkflowRun> runs = runsByDeployment.computeIfAbsent(deploymentId, ignored -> new ConcurrentLinkedDeque<>());
        runs.addFirst(run);
        while (runs.size() > MAX_RUNS_PER_DEPLOYMENT) {
            runs.removeLast();
        }
    }

    private Map<String, Object> workflowToMap(NodeWorkflowDefinition workflow) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workflowId", workflow.id());
        data.put("id", workflow.id());
        data.put("name", workflow.name());
        data.put("slots", workflow.slots());
        data.put("nodes", workflow.nodes());
        data.put("edges", workflow.edges());
        return data;
    }

    private Map<String, Object> deploymentToMap(NodeWorkflowDefinition workflow, NodeWorkflowDeployment deployment) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deploymentId", deployment.deploymentId());
        data.put("workflowId", deployment.workflowId());
        data.put("workflowName", workflow.name());
        data.put("slotBindings", deployment.slotBindings());
        data.put("status", deployment.status());
        data.put("createdAt", deployment.createdAt());
        data.put("createdAtIso", Instant.ofEpochMilli(deployment.createdAt()).toString());
        NodeWorkflowRun latestRun = runsByDeployment
                .getOrDefault(deployment.deploymentId(), new ConcurrentLinkedDeque<>())
                .peekFirst();
        if (latestRun != null) {
            data.put("latestRun", runToMap(latestRun));
        }
        return data;
    }

    private Map<String, Object> runToMap(NodeWorkflowRun run) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", run.runId());
        data.put("workflowId", run.workflowId());
        data.put("deploymentId", run.deploymentId());
        data.put("triggerNodeId", run.triggerNodeId());
        data.put("status", run.status());
        data.put("event", run.event());
        data.put("steps", run.steps());
        data.put("createdAt", run.createdAt());
        data.put("createdAtIso", Instant.ofEpochMilli(run.createdAt()).toString());
        data.put("completedAt", run.completedAt());
        data.put("completedAtIso", Instant.ofEpochMilli(run.completedAt()).toString());
        if (run.error() != null) {
            data.put("error", run.error());
        }
        return data;
    }

    private Map<String, Object> eventToMap(EventPayload payload) {
        Map<String, Object> event = new LinkedHashMap<>(payload.toLegacyMap());
        event.put("eventId", payload.eventId());
        event.put("deviceId", payload.deviceId());
        event.put("nodeId", payload.nodeId());
        event.put("ts", payload.ts());
        if (!payload.sectionId().isEmpty()) {
            event.put("sectionId", payload.sectionId());
        }
        if (!payload.pageId().isEmpty()) {
            event.put("pageId", payload.pageId());
        }
        return event;
    }

    private <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record TriggerBinding(String deviceId, String eventId, String nodeId, String sectionId) {
        boolean conflictsWith(TriggerBinding other) {
            return deviceId.equals(other.deviceId)
                    && eventMatches(eventId, other.eventId)
                    && (nodeId.isBlank() || other.nodeId.isBlank() || nodeIdMatches(nodeId, other.nodeId))
                    && (sectionId.isBlank() || other.sectionId.isBlank() || sectionId.equals(other.sectionId));
        }

        private boolean eventMatches(String left, String right) {
            if (Objects.equals(left, right)) {
                return true;
            }
            if (left == null || right == null || left.isBlank() || right.isBlank()) {
                return false;
            }
            return left.endsWith("." + right) || right.endsWith("." + left);
        }

        private boolean nodeIdMatches(String left, String right) {
            if (left == null || left.isBlank() || right == null || right.isBlank()) return true;
            if (left.equals(right)) return true;
            String strippedLeft = left.replaceFirst(":\\d+$", "");
            String strippedRight = right.replaceFirst(":\\d+$", "");
            return strippedLeft.equals(strippedRight);
        }
    }
}
