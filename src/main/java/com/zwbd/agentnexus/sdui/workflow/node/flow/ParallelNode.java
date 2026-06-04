package com.zwbd.agentnexus.sdui.workflow.node.flow;

import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ParallelNode implements CapabilityNode {

    @Override
    public String type() { return "flow.parallel"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "并行执行", "并行执行多个分支，等待所有分支完成后继续",
                "flow_control", "git-branch",
                List.of(new NodeSchema.ParamDef("branches", "actions[][]", true, null, "并行执行的动作分支列表")),
                List.of(new NodeSchema.ParamDef("results", "object[]", false, null, "各分支的输出结果数组")),
                false, 0);
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        // Parallel execution is handled by the DAG executor / ActionExecutor.
        // This node serves as a schema declaration for the editor.
        return NodeResult.completed(Map.of());
    }
}
