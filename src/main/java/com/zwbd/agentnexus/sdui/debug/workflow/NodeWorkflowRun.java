package com.zwbd.agentnexus.sdui.debug.workflow;

import java.util.List;
import java.util.Map;

public record NodeWorkflowRun(
        String runId,
        String workflowId,
        String deploymentId,
        String triggerNodeId,
        String status,
        Map<String, Object> event,
        List<NodeWorkflowStepResult> steps,
        long createdAt,
        long completedAt,
        String error
) {}
