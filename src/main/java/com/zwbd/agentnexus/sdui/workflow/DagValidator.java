package com.zwbd.agentnexus.sdui.workflow;

import java.util.*;

/**
 * Validates DAG structure and produces topological ordering for node execution.
 * Nodes/edges from the workflow definition's triggers + actions + edges.
 */
public class DagValidator {

    public record DagResult(
            List<String> topologicalOrder,
            Map<String, List<String>> adjacency,
            Map<String, Integer> inDegree,
            boolean isValid,
            String error
    ) {}

    /**
     * Build a DAG from trigger edge entries and node-to-node edges.
     * Triggers are entry points (in-degree 0).
     */
    public static DagResult buildAndValidate(Map<String, List<ActionDef>> actions,
                                              List<EdgeDef> edges,
                                              List<TriggerDef> triggers) {
        Set<String> allNodes = new LinkedHashSet<>();
        Map<String, List<String>> adj = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();

        // Collect all action node IDs
        if (actions != null) {
            allNodes.addAll(actions.keySet());
        }

        // Collect trigger IDs as entry points
        if (triggers != null) {
            for (TriggerDef t : triggers) {
                allNodes.add(t.id());
            }
        }

        // Initialize adjacency and in-degree
        for (String node : allNodes) {
            adj.putIfAbsent(node, new ArrayList<>());
            inDegree.putIfAbsent(node, 0);
        }

        // Add edges
        if (edges != null) {
            for (EdgeDef edge : edges) {
                if (!allNodes.contains(edge.from()) || !allNodes.contains(edge.to())) {
                    return new DagResult(List.of(), adj, inDegree, false,
                            "Edge references unknown node: " + edge.from() + " -> " + edge.to());
                }
                adj.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
                inDegree.merge(edge.to(), 1, Integer::sum);
                inDegree.putIfAbsent(edge.from(), inDegree.getOrDefault(edge.from(), 0));
            }
        }

        // Auto-connect: if a trigger has actions but no explicit edges,
        // the trigger is the entry point (no edges needed, topo sort handles it)
        // For backward compatibility, if there are no edges at all, treat as sequential
        // and all nodes are reachable from triggers via actions map.

        // Kahn's algorithm for topological sort
        Queue<String> queue = new LinkedList<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            order.add(node);
            for (String neighbor : adj.getOrDefault(node, List.of())) {
                int newDegree = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, newDegree);
                if (newDegree == 0) {
                    queue.add(neighbor);
                }
            }
        }

        if (order.size() != allNodes.size()) {
            Set<String> ordered = new LinkedHashSet<>(order);
            List<String> cycleNodes = new ArrayList<>(allNodes);
            cycleNodes.removeAll(ordered);
            return new DagResult(order, adj, inDegree, false,
                    "Cycle detected involving nodes: " + cycleNodes);
        }

        return new DagResult(order, adj, inDegree, true, null);
    }

    /**
     * Returns the execution levels for parallel execution.
     * Nodes at the same level have no dependencies on each other and can run in parallel.
     */
    public static List<List<String>> computeExecutionLevels(List<String> topologicalOrder,
                                                             Map<String, List<String>> adjacency) {
        // BFS-based level assignment
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (String node : topologicalOrder) {
            int maxPredLevel = -1;
            // Find max level among predecessors
            for (var entry : adjacency.entrySet()) {
                if (entry.getValue().contains(node)) {
                    Integer predLevel = levels.get(entry.getKey());
                    if (predLevel != null && predLevel > maxPredLevel) {
                        maxPredLevel = predLevel;
                    }
                }
            }
            levels.put(node, maxPredLevel + 1);
        }

        // Group by level
        Map<Integer, List<String>> byLevel = new LinkedHashMap<>();
        for (var entry : levels.entrySet()) {
            byLevel.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        List<List<String>> result = new ArrayList<>();
        for (int i = 0; byLevel.containsKey(i); i++) {
            result.add(byLevel.get(i));
        }
        return result;
    }
}
