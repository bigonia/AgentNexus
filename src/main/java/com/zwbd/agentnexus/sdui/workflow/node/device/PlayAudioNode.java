package com.zwbd.agentnexus.sdui.workflow.node.device;

import com.zwbd.agentnexus.sdui.service.AudioService;
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
public class PlayAudioNode implements CapabilityNode {

    private final AudioService audioService;

    @Override
    public String type() { return "device.audio.play"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "播放音频", "在设备上播放预设音频",
                "device", "volume-2",
                List.of(
                        new NodeSchema.ParamDef("preset", "string", false, null,
                                "预设音: notification/success/error/warning/click/beep"),
                        new NodeSchema.ParamDef("text", "string", false, null, "TTS 文本（用于朗读）")
                ),
                List.of(),
                false, 10000);
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String preset = (String) ctx.resolvedInputs().get("preset");
        String text = (String) ctx.resolvedInputs().get("text");
        if (text != null) {
            String resolved = resolve(text, ctx);
            audioService.playTts(ctx.deviceId(), resolved);
        } else if (preset != null) {
            audioService.playPreset(ctx.deviceId(), preset);
        }
        return NodeResult.completed(Map.of());
    }

    private String resolve(Object expr, NodeContext ctx) {
        if (expr == null) return null;
        Object resolved = VariableResolver.resolveExpression(expr.toString(),
                ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env());
        return resolved != null ? resolved.toString() : expr.toString();
    }
}
