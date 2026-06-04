package com.zwbd.agentnexus.sdui.workflow.node;

import java.util.Map;
import java.util.Set;

/**
 * Result returned by a CapabilityNode after execution.
 */
public record NodeResult(
        Status status,
        Map<String, Object> outputs,
        Set<String> changedVariables,
        String suspendEvent,
        String error
) {
    public enum Status { COMPLETED, SUSPENDED, ERROR }

    public static NodeResult completed(Map<String, Object> outputs, Set<String> changedVariables) {
        return new NodeResult(Status.COMPLETED, outputs, changedVariables, null, null);
    }

    public static NodeResult completed(Map<String, Object> outputs) {
        return new NodeResult(Status.COMPLETED, outputs, Set.of(), null, null);
    }

    public static NodeResult suspended(String resumeEvent) {
        return new NodeResult(Status.SUSPENDED, Map.of(), Set.of(), resumeEvent, null);
    }

    public static NodeResult error(String message) {
        return new NodeResult(Status.ERROR, Map.of(), Set.of(), null, message);
    }
}
