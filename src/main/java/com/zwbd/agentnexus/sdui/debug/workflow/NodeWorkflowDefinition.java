package com.zwbd.agentnexus.sdui.debug.workflow;

import java.util.List;

public record NodeWorkflowDefinition(
        String id,
        String name,
        List<NodeWorkflowSlot> slots,
        List<NodeWorkflowNode> nodes,
        List<NodeWorkflowEdge> edges
) {}
