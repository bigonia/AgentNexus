package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.capability.PlatformCapabilityRegistry;
import com.zwbd.agentnexus.sdui.protocol.SduiRuntimeHandlers;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PlatformCapabilityRuntimeService {

    private final PlatformCapabilityRegistry platformCapabilityRegistry;
    private final AudioService audioService;

    public PlatformCapabilityRuntimeService(PlatformCapabilityRegistry platformCapabilityRegistry,
                                            AudioService audioService) {
        this.platformCapabilityRegistry = platformCapabilityRegistry;
        this.audioService = audioService;
    }

    public boolean supports(String routeId) {
        return platformCapabilityRegistry.findByDebugRoute(routeId).isPresent();
    }

    public Map<String, Object> execute(String deviceId, String routeId, Map<String, Object> params) {
        return switch (routeId) {
            case "audio.prompt.play" -> playPreset(deviceId, params);
            case "audio.tts.speak" -> speak(deviceId, params);
            case "audio.stt.transcribe" -> transcribe(deviceId, params);
            default -> error("unsupported platform capability route: " + routeId);
        };
    }

    private Map<String, Object> playPreset(String deviceId, Map<String, Object> params) {
        String preset = params != null ? String.valueOf(params.getOrDefault("preset", "notification")) : "notification";
        if (!audioService.isValidPreset(preset)) {
            return error("invalid preset: " + preset);
        }
        AudioService.PlayResult result = audioService.playPreset(deviceId, preset);
        Map<String, Object> response = baseResponse(deviceId, "audio.prompt.play", SduiRuntimeHandlers.PLATFORM_AUDIO_PLAY);
        response.put("sent", result.sent());
        response.put("samples", result.samples());
        response.put("durationMs", result.durationMs());
        response.put("status", result.sent() ? "ACKED" : "SEND_FAILED");
        return response;
    }

    private Map<String, Object> speak(String deviceId, Map<String, Object> params) {
        String text = params != null ? String.valueOf(params.getOrDefault("text", "")) : "";
        if (text.isBlank()) {
            return error("text is required for audio.tts.speak");
        }
        AudioService.PlayResult result = audioService.playTts(deviceId, text);
        Map<String, Object> response = baseResponse(deviceId, "audio.tts.speak", SduiRuntimeHandlers.PLATFORM_AUDIO_TTS);
        response.put("sent", result.sent());
        response.put("status", result.sent() ? "ACKED" : "SEND_FAILED");
        return response;
    }

    private Map<String, Object> transcribe(String deviceId, Map<String, Object> params) {
        String audioData = params != null ? String.valueOf(params.getOrDefault("audioData", "")) : "";
        if (audioData.isBlank()) {
            return error("audioData is required for audio.stt.transcribe");
        }
        if (!audioService.isSttAvailable()) {
            return error("STT is not available — no SttProvider configured");
        }
        String format = params != null ? String.valueOf(params.getOrDefault("format", "wav")) : "wav";
        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(audioData);
        } catch (IllegalArgumentException e) {
            return error("Invalid base64 audioData: " + e.getMessage());
        }

        String transcription = audioService.transcribeAudio(audioBytes, format);
        if (transcription == null || transcription.isBlank()) {
            return error("STT transcription failed or returned empty result");
        }

        Map<String, Object> response = baseResponse(deviceId, "audio.stt.transcribe", SduiRuntimeHandlers.PLATFORM_AUDIO_STT);
        response.put("sent", true);
        response.put("status", "OK");
        response.put("transcription", transcription);
        return response;
    }

    private Map<String, Object> baseResponse(String deviceId, String routeId, String runtimeHandler) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deviceId", deviceId);
        response.put("command", routeId);
        response.put("source", "platform");
        response.put("runtimeHandler", runtimeHandler);
        return response;
    }

    private Map<String, Object> error(String message) {
        return Map.of("sent", false, "status", "ERROR", "error", message, "source", "platform");
    }
}
