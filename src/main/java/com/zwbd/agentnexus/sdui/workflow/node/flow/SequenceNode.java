package com.zwbd.agentnexus.sdui.workflow.node.flow;

import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SequenceNode implements CapabilityNode {

    @Override
    public String type() { return "flow.sequence"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "顺序执行", "按顺序执行子步骤",
                "flow_control", "list-ordered",
                List.of(new NodeSchema.ParamDef("steps", "actions[]", true, null, "按顺序执行的动作列表")),
                List.of(),
                false, 0);
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        // Sequence is handled by ActionExecutor itself — this node primarily
        // serves as a schema declaration for the editor.
        return NodeResult.completed(Map.of());
    }
}
