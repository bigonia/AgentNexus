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

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Speech-to-Text workflow node. Transcribes audio data and saves the result
 * to {@code $data.transcription}.
 */
@Component
@RequiredArgsConstructor
public class SttNode implements CapabilityNode {

    private final AudioService audioService;

    @Override
    public String type() { return "platform.stt"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "STT 语音识别", "将音频数据转录为文本",
                "platform", "mic",
                List.of(
                        new NodeSchema.ParamDef("audioData", "string", true, null,
                                "音频数据 (base64 编码)，支持 $data.xxx / $trigger.xxx"),
                        new NodeSchema.ParamDef("format", "string", false, "wav",
                                "音频格式: wav, pcm, opus, mp3")
                ),
                List.of(
                        new NodeSchema.ParamDef("transcription", "string", false, null, "转录文本结果")
                ),
                false, 20000, null, "platform", SduiProtocolConstants.NodeProtocols.SERVER_AUDIO, SduiRuntimeHandlers.PLATFORM_AUDIO_STT, Map.of());
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        Object audioInput = ctx.resolvedInputs().get("audioData");
        String format = (String) ctx.resolvedInputs().getOrDefault("format", "wav");

        if (audioInput == null) {
            return NodeResult.error("Missing 'audioData' input");
        }

        // Resolve variable references
        Object resolved = resolve(audioInput, ctx);

        byte[] audioBytes;
        if (resolved instanceof byte[] b) {
            audioBytes = b;
        } else if (resolved instanceof String s) {
            // Assume base64-encoded
            try {
                audioBytes = Base64.getDecoder().decode(s);
            } catch (IllegalArgumentException e) {
                return NodeResult.error("Invalid base64 audioData: " + e.getMessage());
            }
        } else {
            return NodeResult.error("audioData must be a base64 string or byte array, got: "
                    + (resolved != null ? resolved.getClass().getSimpleName() : "null"));
        }

        if (!audioService.isSttAvailable()) {
            return NodeResult.error("STT is not available — no SttProvider configured");
        }

        String transcription = audioService.transcribeAudio(audioBytes, format);
        if (transcription == null || transcription.isEmpty()) {
            return NodeResult.error("STT transcription returned empty result");
        }

        return NodeResult.completed(Map.of("transcription", transcription),
                Set.of("transcription"));
    }

    private Object resolve(Object expr, NodeContext ctx) {
        if (expr == null) return null;
        Object resolved = VariableResolver.resolveExpression(expr.toString(),
                ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env());
        return resolved != null ? resolved : expr;
    }
}
