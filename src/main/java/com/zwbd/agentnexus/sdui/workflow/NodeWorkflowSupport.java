package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.workflow.model.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NodeWorkflowSupport {

    static final Set<String> TRIGGER_NODE_TYPES = Set.of("button.trigger", "section.trigger");
    static final Set<String> OUTPUT_NODE_TYPES = Set.of("rgb.effect", "audio.play", "audio.record", "ui.update", "display.section");
    private static final Pattern ANY_REF = Pattern.compile("\\$[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_\\-]+)*");
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
            if (!TRIGGER_NODE_TYPES.contains(node.nodeType()) && !OUTPUT_NODE_TYPES.contains(node.nodeType())) {
                errors.add("unsupported nodeType: " + node.nodeType());
            }
            errors.addAll(validateReferenceSyntax(node.nodeId(), node.params()));
            hasTrigger = hasTrigger || TRIGGER_NODE_TYPES.contains(node.nodeType());
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
            if (!TRIGGER_NODE_TYPES.contains(from.nodeType()) && !OUTPUT_NODE_TYPES.contains(from.nodeType())) {
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
            if (TRIGGER_NODE_TYPES.contains(node.nodeType())) {
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

    private static List<String> validateReferenceSyntax(String nodeId, Object value) {
        List<String> errors = new ArrayList<>();
        collectReferenceSyntaxErrors(nodeId, value, errors);
        return errors;
    }

    private static void collectReferenceSyntaxErrors(String nodeId, Object value, List<String> errors) {
        if (value instanceof Map<?, ?> map) {
            for (Object child : map.values()) {
                collectReferenceSyntaxErrors(nodeId, child, errors);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object child : list) {
                collectReferenceSyntaxErrors(nodeId, child, errors);
            }
            return;
        }
        if (!(value instanceof String text) || !text.contains("$")) {
            return;
        }
        int index = text.indexOf('$');
        while (index >= 0) {
            Matcher matcher = ANY_REF.matcher(text.substring(index));
            if (!matcher.lookingAt()) {
                errors.add("node " + nodeId + " has invalid reference syntax near: " + text.substring(index));
                return;
            }
            index = text.indexOf('$', index + Math.max(1, matcher.end()));
        }
    }
}
