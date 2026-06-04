package com.zwbd.agentnexus.sdui.workflow.node.device;

import com.zwbd.agentnexus.sdui.service.CommandDispatcher;
import com.zwbd.agentnexus.sdui.workflow.VariableResolver;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LightNode implements CapabilityNode {

    private final CommandDispatcher dispatcher;

    @Override
    public String type() { return "device.light"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "灯光控制", "控制设备 RGB 灯光效果",
                "device", "sun",
                List.of(
                        new NodeSchema.ParamDef("mode", "string", true, null, "模式: effect / off"),
                        new NodeSchema.ParamDef("color", "string", false, null, "颜色 (hex RGB, 如 #FF0000)，effect 模式时使用"),
                        new NodeSchema.ParamDef("brightness", "number", false, null, "亮度 0-100，effect 模式时使用"),
                        new NodeSchema.ParamDef("duration", "number", false, null, "持续时间 (毫秒)")
                ),
                List.of(),
                false, 5000);
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String mode = (String) ctx.resolvedInputs().get("mode");
        String color = resolve(ctx.resolvedInputs().get("color"), ctx);
        Object brightnessObj = ctx.resolvedInputs().get("brightness");
        Object durationObj = ctx.resolvedInputs().get("duration");

        if ("off".equals(mode)) {
            dispatcher.dispatch(ctx.deviceId(), "rgb.off", null);
            log.info("Light off: device={}", ctx.deviceId());
            return NodeResult.completed(Map.of("mode", "off"));
        }

        // effect mode: build structured params
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mode", "effect");
        if (color != null) params.put("color", color);
        if (brightnessObj instanceof Number n) params.put("brightness", n.intValue());
        if (durationObj instanceof Number n) params.put("duration", n.intValue());

        String value = params.isEmpty() ? null : params.toString();
        dispatcher.dispatch(ctx.deviceId(), "rgb.effect.set", value);
        log.info("Light effect: device={} params={}", ctx.deviceId(), params);
        return NodeResult.completed(Map.of("mode", "effect", "params", params));
    }

    private String resolve(Object expr, NodeContext ctx) {
        if (expr == null) return null;
        Object resolved = VariableResolver.resolveExpression(expr.toString(),
                ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env());
        return resolved != null ? resolved.toString() : expr.toString();
    }
}
