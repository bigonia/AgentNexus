package com.zwbd.agentnexus.sdui.workflow.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkflowDag {

    private WorkflowDag() {}

    public record Node(String id, String type, String kind, Map<String, Object> config) {}
    public record Edge(String from, String to, String condition) {}

    @SuppressWarnings("unchecked")
    public static List<Node> nodes(Map<String, Object> dag) {
        Object raw = dag != null ? dag.get("nodes") : null;
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Node> nodes = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> node = normalize(map);
            String id = string(node.get("id"));
            String type = string(node.get("type"));
            String kind = string(node.getOrDefault("kind", node.get("nodeType")));
            Map<String, Object> config = node.get("config") instanceof Map<?, ?> cfg
                    ? normalize(cfg) : new LinkedHashMap<>();
            if (!id.isBlank()) {
                nodes.add(new Node(id, type, kind, config));
            }
        }
        return nodes;
    }

    public static List<Edge> edges(Map<String, Object> dag) {
        Object raw = dag != null ? dag.get("edges") : null;
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Edge> edges = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> edge = normalize(map);
            String from = string(edge.getOrDefault("from", edge.get("source")));
            String to = string(edge.getOrDefault("to", edge.get("target")));
            String condition = string(edge.get("condition"));
            if (!from.isBlank() && !to.isBlank()) {
                edges.add(new Edge(from, to, condition));
            }
        }
        return edges;
    }

    static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static Map<String, Object> normalize(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }
}
