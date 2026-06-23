package com.zwbd.agentnexus.sdui.debug.workflow;

import java.util.Map;

public record NodeWorkflowNode(
        String nodeId,
        String slotId,
        String nodeType,
        Map<String, Object> params
) {}
