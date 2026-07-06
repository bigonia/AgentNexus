package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.workflow.model.*;

import java.time.LocalDateTime;
import java.util.*;

final class NodeWorkflowSupport {

    /** All node types ending with {@code .trigger} are trigger nodes (button, motion, section, etc.). */
    static boolean isTriggerNode(String nodeType) {
        return nodeType != null && nodeType.endsWith(".trigger");
    }
    static final Set<String> OUTPUT_NODE_TYPES = Set.of("rgb.effect", "audio.play", "audio.record", "ui.update", "display.section");
    static final TypeReference<List<NodeWorkflowSlot>> SLOT_LIST = new TypeReference<>() {};
    static final TypeReference<List<NodeWorkflowNode>> NODE_LIST = new TypeReference<>() {};
    static final TypeReference<List<NodeWorkflowEdge>> EDGE_LIST = new TypeReference<>() {};
    static final TypeReference<List<Map<String, Object>>> UI_TEMPLATE_LIST = new TypeReference<>() {};

    private NodeWorkflowSupport() {}

    static NodeWorkflowDefinition normalize(NodeWorkflowDefinition raw) {
        if (raw == null) {
            throw new IllegalArgumentException("workflow definition is required");
        }
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
        List<Map<String, Object>> uiTemplates = safeList(raw.uiTemplates()).stream()
                .map(NodeWorkflowSupport::normalizeMutableMap)
                .toList();
        return new NodeWorkflowDefinition(string(raw.id()), string(raw.name()), slots, nodes, edges, uiTemplates);
    }

    static List<String> validateDefinition(NodeWorkflowDefinition raw) {
        List<String> errors = new ArrayList<>();
        NodeWorkflowDefinition workflow;
        try {
            workflow = normalize(raw);
        } catch (Exception e) {
            return List.of(e.getMessage());
        }
        if (workflow.slots().isEmpty()) errors.add("slots are required");
        if (workflow.nodes().isEmpty()) errors.add("nodes are required");
        if (workflow.edges().isEmpty()) errors.add("edges are required");

        Set<String> slotIds = new LinkedHashSet<>();
        for (NodeWorkflowSlot slot : workflow.slots()) {
            if (slot.slotId().isBlank()) errors.add("slotId is required");
            if (!slot.slotId().isBlank() && !slotIds.add(slot.slotId())) errors.add("duplicate slotId: " + slot.slotId());
        }

        Set<String> nodeIds = new LinkedHashSet<>();
        boolean hasTrigger = false;
        for (NodeWorkflowNode node : workflow.nodes()) {
            if (node.nodeId().isBlank()) errors.add("nodeId is required");
            if (!node.nodeId().isBlank() && !nodeIds.add(node.nodeId())) errors.add("duplicate nodeId: " + node.nodeId());
            if (!slotIds.contains(node.slotId())) errors.add("node references unknown slot: " + node.nodeId());
            if (!isTriggerNode(node.nodeType()) && !OUTPUT_NODE_TYPES.contains(node.nodeType())) {
                errors.add("unsupported nodeType: " + node.nodeType());
            }
            hasTrigger = hasTrigger || isTriggerNode(node.nodeType());
        }
        if (!hasTrigger) errors.add("at least one trigger node is required");

        for (NodeWorkflowEdge edge : workflow.edges()) {
            if (!nodeIds.contains(edge.from())) {
                errors.add("edge references unknown from node: " + edge.from());
                continue;
            }
            if (!nodeIds.contains(edge.to())) {
                errors.add("edge references unknown to node: " + edge.to());
                continue;
            }
            NodeWorkflowNode from = nodeById(workflow, edge.from());
            NodeWorkflowNode to = nodeById(workflow, edge.to());
            if (!isTriggerNode(from.nodeType()) && !OUTPUT_NODE_TYPES.contains(from.nodeType())) {
                errors.add("edge from node must be trigger or output: " + edge.from());
            }
            if (!OUTPUT_NODE_TYPES.contains(to.nodeType())) errors.add("edge to node must be output: " + edge.to());
        }
        if (errors.isEmpty()) {
            if (hasCycle(workflow)) {
                errors.add("workflow graph must be acyclic");
            }
            errors.addAll(validateOutputReachability(workflow));
        }
        return errors;
    }

    static List<NodeWorkflowNode> executionPlan(NodeWorkflowDefinition workflow, NodeWorkflowNode trigger) {
        Set<String> reachable = reachableNodeIds(workflow, trigger.nodeId());
        reachable.remove(trigger.nodeId());
        if (reachable.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> nodeOrder = nodeOrder(workflow);
        Map<String, List<String>> adjacency = adjacency(workflow);
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (String nodeId : reachable) {
            indegree.put(nodeId, 0);
        }
        for (NodeWorkflowEdge edge : workflow.edges()) {
            if (reachable.contains(edge.from()) && reachable.contains(edge.to())) {
                indegree.put(edge.to(), indegree.get(edge.to()) + 1);
            }
        }

        PriorityQueue<String> ready = new PriorityQueue<>(Comparator.comparingInt(nodeOrder::get));
        for (var entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<NodeWorkflowNode> plan = new ArrayList<>();
        while (!ready.isEmpty()) {
            String nodeId = ready.poll();
            plan.add(nodeById(workflow, nodeId));
            for (String next : adjacency.getOrDefault(nodeId, List.of())) {
                if (!reachable.contains(next)) continue;
                int nextIndegree = indegree.get(next) - 1;
                indegree.put(next, nextIndegree);
                if (nextIndegree == 0) {
                    ready.add(next);
                }
            }
        }
        if (plan.size() != reachable.size()) {
            throw new IllegalArgumentException("workflow graph has a cycle reachable from trigger: " + trigger.nodeId());
        }
        return plan;
    }

    static Map<String, Object> toDefinitionMap(ObjectMapper mapper, NodeWorkflowDefinition workflow) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("slots", mapper.convertValue(safeList(workflow.slots()), Object.class));
        data.put("nodes", mapper.convertValue(safeList(workflow.nodes()), Object.class));
        data.put("edges", mapper.convertValue(safeList(workflow.edges()), Object.class));
        data.put("uiTemplates", mapper.convertValue(safeList(workflow.uiTemplates()), Object.class));
        return data;
    }

    static NodeWorkflowDefinition fromDefinitionMap(ObjectMapper mapper, String id, String name, Map<String, Object> definition) {
        Map<String, Object> safe = definition != null ? definition : Map.of();
        return new NodeWorkflowDefinition(
                id,
                name,
                mapper.convertValue(safe.getOrDefault("slots", List.of()), SLOT_LIST),
                mapper.convertValue(safe.getOrDefault("nodes", List.of()), NODE_LIST),
                mapper.convertValue(safe.getOrDefault("edges", List.of()), EDGE_LIST),
                mapper.convertValue(safe.getOrDefault("uiTemplates", List.of()), UI_TEMPLATE_LIST)
        );
    }

    static NodeWorkflowNode nodeById(NodeWorkflowDefinition workflow, String nodeId) {
        return workflow.nodes().stream()
                .filter(node -> node.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("node not found: " + nodeId));
    }

    static Map<String, Object> temporal(LocalDateTime value) {
        if (value == null) return Map.of();
        return Map.of("value", value.toString());
    }

    static Map<String, Object> normalizeMap(Map<String, Object> raw) {
        return raw == null || raw.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    static Map<String, Object> normalizeMutableMap(Map<String, Object> raw) {
        return raw == null || raw.isEmpty() ? Map.of() : new LinkedHashMap<>(raw);
    }

    static Map<String, String> stringMap(Object raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), string(entry.getValue()));
            }
        }
        return result;
    }

    static boolean eventMatches(String expected, String actual) {
        if (Objects.equals(expected, actual)) return true;
        if (expected == null || actual == null || expected.isBlank() || actual.isBlank()) return false;
        return expected.endsWith("." + actual) || actual.endsWith("." + expected);
    }

    /**
     * Match a trigger node's expected nodeId against an event's actual nodeId.
     * <p>
     * The {@code :digit} state suffix is already stripped during event normalization
     * ({@link com.zwbd.agentnexus.sdui.event.EventRegistry#normalizePayload}), so
     * this method performs a direct comparison.</p>
     *
     * @return true if expected is blank (no filter), or expected matches actual nodeId.
     */
    static boolean nodeIdMatches(String expected, String actual) {
        if (expected == null || expected.isBlank()) return true;
        if (actual == null || actual.isBlank()) return false;
        return expected.equals(actual);
    }

    static <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean hasCycle(NodeWorkflowDefinition workflow) {
        Map<String, Integer> nodeOrder = nodeOrder(workflow);
        Map<String, List<String>> adjacency = adjacency(workflow);
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (NodeWorkflowNode node : workflow.nodes()) {
            indegree.put(node.nodeId(), 0);
        }
        for (NodeWorkflowEdge edge : workflow.edges()) {
            indegree.computeIfPresent(edge.to(), (ignored, value) -> value + 1);
        }

        PriorityQueue<String> ready = new PriorityQueue<>(Comparator.comparingInt(nodeOrder::get));
        for (var entry : indegree.entrySet()) {
            if (entry.getValue() == 0) ready.add(entry.getKey());
        }

        int processed = 0;
        while (!ready.isEmpty()) {
            String nodeId = ready.poll();
            processed++;
            for (String next : adjacency.getOrDefault(nodeId, List.of())) {
                int nextIndegree = indegree.get(next) - 1;
                indegree.put(next, nextIndegree);
                if (nextIndegree == 0) ready.add(next);
            }
        }
        return processed != workflow.nodes().size();
    }

    private static List<String> validateOutputReachability(NodeWorkflowDefinition workflow) {
        Set<String> reachable = new LinkedHashSet<>();
        for (NodeWorkflowNode node : workflow.nodes()) {
            if (isTriggerNode(node.nodeType())) {
                reachable.addAll(reachableNodeIds(workflow, node.nodeId()));
            }
        }
        List<String> errors = new ArrayList<>();
        for (NodeWorkflowNode node : workflow.nodes()) {
            if (OUTPUT_NODE_TYPES.contains(node.nodeType()) && !reachable.contains(node.nodeId())) {
                errors.add("output node is not reachable from any trigger: " + node.nodeId());
            }
        }
        return errors;
    }

    private static Set<String> reachableNodeIds(NodeWorkflowDefinition workflow, String startNodeId) {
        Map<String, List<String>> adjacency = adjacency(workflow);
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        visited.add(startNodeId);
        queue.add(startNodeId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return visited;
    }

    /**
     * Compute the set of upstream node IDs for every output node in the workflow.
     * An upstream node is reachable by following edges in reverse from the target.
     * Trigger nodes themselves are not included — only output nodes have upstreams.
     */
    static Map<String, Set<String>> upstreamNodeIds(NodeWorkflowDefinition workflow) {
        Map<String, List<String>> reverseAdj = new LinkedHashMap<>();
        for (var node : workflow.nodes()) {
            reverseAdj.put(node.nodeId(), new ArrayList<>());
        }
        for (var edge : workflow.edges()) {
            reverseAdj.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge.from());
        }
        Map<String, Set<String>> upstreamByNode = new LinkedHashMap<>();
        for (var node : workflow.nodes()) {
            if (OUTPUT_NODE_TYPES.contains(node.nodeType())) {
                Set<String> upstream = new LinkedHashSet<>();
                ArrayDeque<String> queue = new ArrayDeque<>();
                queue.add(node.nodeId());
                while (!queue.isEmpty()) {
                    String current = queue.removeFirst();
                    for (String prev : reverseAdj.getOrDefault(current, List.of())) {
                        if (upstream.add(prev)) {
                            queue.addLast(prev);
                        }
                    }
                }
                upstream.remove(node.nodeId());
                upstreamByNode.put(node.nodeId(), upstream);
            }
        }
        return upstreamByNode;
    }

    private static Map<String, List<String>> adjacency(NodeWorkflowDefinition workflow) {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (NodeWorkflowNode node : workflow.nodes()) {
            adjacency.put(node.nodeId(), new ArrayList<>());
        }
        for (NodeWorkflowEdge edge : workflow.edges()) {
            List<String> outgoing = adjacency.get(edge.from());
            if (outgoing != null) {
                outgoing.add(edge.to());
            }
        }
        return adjacency;
    }

    private static Map<String, Integer> nodeOrder(NodeWorkflowDefinition workflow) {
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < workflow.nodes().size(); i++) {
            order.put(workflow.nodes().get(i).nodeId(), i);
        }
        return order;
    }
}
