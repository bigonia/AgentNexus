package com.zwbd.agentnexus.sdui.workflow.service;

import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WorkflowValidationService {

    private final DeviceCapabilityProjection capabilityProjection;

    public Map<String, Object> validate(String deviceId, Map<String, Object> dag) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<WorkflowDag.Node> nodes = WorkflowDag.nodes(dag);
        List<WorkflowDag.Edge> edges = WorkflowDag.edges(dag);

        if (nodes.isEmpty()) {
            errors.add("nodes is required");
        }

        long triggerCount = nodes.stream().filter(node -> "trigger".equals(node.type())).count();
        if (triggerCount == 0) {
            errors.add("at least one trigger node is required");
        }

        Set<String> nodeIds = new HashSet<>();
        for (WorkflowDag.Node node : nodes) {
            if (!nodeIds.add(node.id())) {
                errors.add("duplicate node id: " + node.id());
            }
            validateNode(deviceId, node, errors, warnings);
        }

        Map<String, List<String>> outgoing = new HashMap<>();
        for (WorkflowDag.Edge edge : edges) {
            if (!nodeIds.contains(edge.from())) {
                errors.add("edge references unknown source node: " + edge.from());
            }
            if (!nodeIds.contains(edge.to())) {
                errors.add("edge references unknown target node: " + edge.to());
            }
            outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
        }
        if (hasCycle(nodeIds, outgoing)) {
            errors.add("dag must not contain cycles");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("warnings", warnings);
        return result;
    }

    private void validateNode(String deviceId, WorkflowDag.Node node, List<String> errors, List<String> warnings) {
        if (node.type().isBlank()) {
            errors.add("node type is required: " + node.id());
            return;
        }
        if (!Set.of("trigger", "input", "output", "control").contains(node.type())) {
            errors.add("unsupported node type " + node.type() + " on " + node.id());
        }
        if ("trigger".equals(node.type()) && node.kind().isBlank()) {
            errors.add("trigger kind is required: " + node.id());
        }
        if ("trigger".equals(node.type())) {
            validateTriggerNode(deviceId, node, errors);
        }
        if ("output".equals(node.type())) {
            validateOutputNode(deviceId, node, errors, warnings);
        }
    }

    private void validateTriggerNode(String deviceId, WorkflowDag.Node node, List<String> errors) {
        if ("command.lifecycle".equals(node.kind())) {
            String eventId = WorkflowDag.string(node.config().get("eventId"));
            if (eventId.isBlank()) {
                errors.add("eventId is required on " + node.id());
            } else if (!Set.of("command.dispatch", "command.ack", "command.rejected",
                    "command.failed", "command.timeout", "command.result").contains(eventId)) {
                errors.add("unsupported command lifecycle event " + eventId + " on " + node.id());
            }
            String command = WorkflowDag.string(node.config().get("command"));
            if (!command.isBlank()) {
                boolean supported = capabilityProjection.commands(deviceId).stream().anyMatch(spec -> spec.id().equals(command));
                if (!supported) {
                    errors.add("device does not support command trigger filter " + command + " on " + node.id());
                }
            }
        }
    }

    private void validateOutputNode(String deviceId, WorkflowDag.Node node, List<String> errors, List<String> warnings) {
        String kind = node.kind();
        if ("device.command".equals(kind)) {
            String command = WorkflowDag.string(node.config().get("command"));
            if (command.isBlank()) {
                errors.add("command is required on " + node.id());
                return;
            }
            boolean supported = capabilityProjection.commands(deviceId).stream().anyMatch(spec -> spec.id().equals(command));
            if (!supported) {
                errors.add("device does not support command " + command + " on " + node.id());
            }
        } else if ("section.scene".equals(kind)) {
            if (!(node.config().get("sections") instanceof List<?>)) {
                errors.add("sections is required on " + node.id());
            }
        } else if ("section.patch".equals(kind)) {
            if (!(node.config().get("patches") instanceof List<?>)) {
                errors.add("patches is required on " + node.id());
            }
        } else if (!"platform.capability".equals(kind)) {
            warnings.add("output kind is not executable in v1: " + kind);
        }
    }

    private boolean hasCycle(Set<String> nodes, Map<String, List<String>> outgoing) {
        Map<String, Integer> indegree = new HashMap<>();
        for (String node : nodes) indegree.put(node, 0);
        for (List<String> targets : outgoing.values()) {
            for (String target : targets) {
                if (indegree.containsKey(target)) {
                    indegree.put(target, indegree.get(target) + 1);
                }
            }
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((node, degree) -> { if (degree == 0) queue.add(node); });
        int visited = 0;
        while (!queue.isEmpty()) {
            String node = queue.removeFirst();
            visited++;
            for (String target : outgoing.getOrDefault(node, List.of())) {
                if (!indegree.containsKey(target)) continue;
                int degree = indegree.merge(target, -1, Integer::sum);
                if (degree == 0) queue.add(target);
            }
        }
        return visited != nodes.size();
    }
}
