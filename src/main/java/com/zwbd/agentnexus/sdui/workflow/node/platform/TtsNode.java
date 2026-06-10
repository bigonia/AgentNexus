package com.zwbd.agentnexus.sdui.workflow.node.platform;

import com.zwbd.agentnexus.sdui.protocol.SduiProtocolConstants;
import com.zwbd.agentnexus.sdui.protocol.SduiRuntimeHandlers;
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
public class TtsNode implements CapabilityNode {

    private final AudioService audioService;

    @Override
    public String type() { return "platform.tts"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "TTS 语音合成", "将文本转为语音并在设备上播放",
                "platform", "mic",
                List.of(new NodeSchema.ParamDef("text", "string", true, null, "朗读文本，支持 $data.xxx / $trigger.xxx")),
                List.of(new NodeSchema.ParamDef("audioUrl", "string", false, null, "生成的音频 URL")),
                false, 15000, null, "platform", SduiProtocolConstants.NodeProtocols.SERVER_AUDIO, SduiRuntimeHandlers.PLATFORM_AUDIO_TTS, Map.of());
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String text = (String) ctx.resolvedInputs().get("text");
        if (text == null) return NodeResult.error("Missing 'text' input");
        String resolved = resolve(text, ctx);
        audioService.playTts(ctx.deviceId(), resolved);
        return NodeResult.completed(Map.of());
    }

    private String resolve(Object expr, NodeContext ctx) {
        if (expr == null) return null;
        Object resolved = VariableResolver.resolveExpression(expr.toString(),
                ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env());
        return resolved != null ? resolved.toString() : expr.toString();
    }
}
