package com.zwbd.agentnexus.sdui.capability;

import com.zwbd.agentnexus.sdui.protocol.SduiProtocolConstants;
import com.zwbd.agentnexus.sdui.protocol.SduiRuntimeHandlers;
import com.zwbd.agentnexus.sdui.service.AudioService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class PlatformCapabilityRegistry {

    private final AudioService audioService;

    public PlatformCapabilityRegistry(AudioService audioService) {
        this.audioService = audioService;
    }

    public record PlatformCapabilityDef(
            String id,
            String category,
            String displayName,
            String description,
            Map<String, Object> schema,
            Map<String, Object> protocol,
            Map<String, Object> constraints,
            String runtimeHandler,
            String debugRouteId,
            boolean available
    ) {}

    public List<PlatformCapabilityDef> listCapabilities() {
        List<Map<String, Object>> presets = audioService.getPresets() != null
                ? audioService.getPresets() : List.of();
        return List.of(
                new PlatformCapabilityDef(
                        "platform.audio.prompt.play",
                        "audio_output",
                        "平台提示音播放",
                        "由平台生成预设提示音并下发到终端播放",
                        Map.of("params", List.of(
                                field("preset", "string", false, "notification", "提示音预设")
                        )),
                        Map.of("transport", SduiProtocolConstants.NodeProtocols.SERVER_AUDIO),
                        Map.of("presets", presets),
                        SduiRuntimeHandlers.PLATFORM_AUDIO_PLAY,
                        "audio.prompt.play",
                        true
                ),
                new PlatformCapabilityDef(
                        "platform.audio.tts.speak",
                        "audio_output",
                        "平台 TTS 播报",
                        "由平台执行语音合成，再将音频下发到终端播放",
                        Map.of("params", List.of(
                                field("text", "string", true, null, "待播报文本")
                        )),
                        Map.of("transport", SduiProtocolConstants.NodeProtocols.SERVER_AUDIO),
                        Map.of(),
                        SduiRuntimeHandlers.PLATFORM_AUDIO_TTS,
                        "audio.tts.speak",
                        audioService.isTtsAvailable()
                ),
                new PlatformCapabilityDef(
                        "platform.audio.stt.transcribe",
                        "audio_input",
                        "平台语音识别",
                        "由平台执行 STT 转录，返回文本结果",
                        Map.of("params", List.of(
                                field("audioData", "string", true, null, "base64 编码音频"),
                                field("format", "string", false, "wav", "音频格式")
                        )),
                        Map.of("transport", SduiProtocolConstants.NodeProtocols.SERVER_AUDIO),
                        Map.of(),
                        SduiRuntimeHandlers.PLATFORM_AUDIO_STT,
                        "audio.stt.transcribe",
                        audioService.isSttAvailable()
                )
        );
    }

    public Optional<PlatformCapabilityDef> findByDebugRoute(String routeId) {
        return listCapabilities().stream()
                .filter(def -> def.debugRouteId().equals(routeId))
                .findFirst();
    }

    private Map<String, Object> field(String name, String type, boolean required, Object defaultValue, String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("type", type);
        field.put("required", required);
        if (defaultValue != null) {
            field.put("default", defaultValue);
        }
        field.put("description", description);
        return field;
    }
}
