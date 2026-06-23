package com.zwbd.agentnexus.sdui.debug.workflow;

import java.util.Map;

public record NodeWorkflowDeployment(
        String deploymentId,
        String workflowId,
        Map<String, String> slotBindings,
        String status,
        long createdAt
) {}
