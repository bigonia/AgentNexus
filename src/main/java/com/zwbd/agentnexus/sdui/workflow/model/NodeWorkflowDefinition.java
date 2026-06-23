package com.zwbd.agentnexus.sdui.workflow.model;

import java.util.List;
import java.util.Map;

public record NodeWorkflowDefinition(
        String id,
        String name,
        List<NodeWorkflowSlot> slots,
        List<NodeWorkflowNode> nodes,
        List<NodeWorkflowEdge> edges,
        List<Map<String, Object>> uiTemplates
) {
    public NodeWorkflowDefinition(String id,
                                  String name,
                                  List<NodeWorkflowSlot> slots,
                                  List<NodeWorkflowNode> nodes,
                                  List<NodeWorkflowEdge> edges) {
        this(id, name, slots, nodes, edges, List.of());
    }
}
