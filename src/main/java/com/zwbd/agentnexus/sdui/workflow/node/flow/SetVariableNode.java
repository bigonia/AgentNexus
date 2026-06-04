package com.zwbd.agentnexus.sdui.workflow.node.flow;

import com.zwbd.agentnexus.sdui.workflow.VariableResolver;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class SetVariableNode implements CapabilityNode {

    @Override
    public String type() { return "flow.set_variable"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "设置变量", "向 $data.* 写入值",
                "flow_control", "edit-3",
                List.of(
                        new NodeSchema.ParamDef("variable", "string", true, null, "变量名（不含 $data. 前缀）"),
                        new NodeSchema.ParamDef("value", "string", true, null, "变量值，支持表达式和变量引用")
                ),
                List.of(),
                false, 5000);
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String varName = (String) ctx.resolvedInputs().get("variable");
        Object rawValue = ctx.resolvedInputs().get("value");
        Object resolved = VariableResolver.resolveExpression(
                rawValue != null ? rawValue.toString() : "",
                ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env());
        ctx.instance().putVariable(varName, resolved);
        log.info("SetVariable: $data.{} = {}", varName, resolved);
        return NodeResult.completed(Map.of(), Set.of(varName));
    }
}
