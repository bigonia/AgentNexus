package com.zwbd.agentnexus.sdui.workflow.node.device;

import com.zwbd.agentnexus.sdui.service.CommandDispatcher;
import com.zwbd.agentnexus.sdui.workflow.VariableResolver;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ControlNode implements CapabilityNode {

    private final CommandDispatcher dispatcher;

    @Override
    public String type() { return "device.control"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "设备命令", "向设备发送执行器命令",
                "device", "terminal",
                List.of(
                        new NodeSchema.ParamDef("command", "string", true, null, "命令名, e.g. rgb.effect.set"),
                        new NodeSchema.ParamDef("value", "string", false, null, "命令值")
                ),
                List.of(),
                false, 5000);
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String command = (String) ctx.resolvedInputs().get("command");
        Object rawValue = ctx.resolvedInputs().get("value");
        String value = resolve(rawValue, ctx);
        dispatcher.dispatch(ctx.deviceId(), command, value);
        return NodeResult.completed(Map.of());
    }

    private String resolve(Object expr, NodeContext ctx) {
        if (expr == null) return null;
        Object resolved = VariableResolver.resolveExpression(expr.toString(),
                ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env());
        return resolved != null ? resolved.toString() : expr.toString();
    }
}
