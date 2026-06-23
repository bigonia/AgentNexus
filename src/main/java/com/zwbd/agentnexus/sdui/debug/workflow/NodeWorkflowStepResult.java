package com.zwbd.agentnexus.sdui.debug.workflow;

import java.util.Map;

public record NodeWorkflowStepResult(
        String nodeId,
        String slotId,
        String deviceId,
        String nodeType,
        String status,
        Map<String, Object> result,
        String error
) {}
