package com.zwbd.agentnexus.sdui.service.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.SduiMessage;
import com.zwbd.agentnexus.sdui.TopicHandler;
import com.zwbd.agentnexus.sdui.service.DeviceLifecycleService;
import com.zwbd.agentnexus.sdui.workflow.WorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Base64;
import java.util.Map;

/**
 * Handles incoming {@code audio/record} topic messages from devices.
 * Extracts audio data (base64 or raw reference) and dispatches to
 * STT processing via the {@link AudioRecordProcessor} callback.
 *
 * <p>Registered automatically via {@link com.zwbd.agentnexus.sdui.MessageRouter}'s
 * {@code List<TopicHandler>} constructor injection.</p>
 */
@Slf4j
@Component
public class AudioRecordHandler implements TopicHandler {

    private final DeviceSessionManager sessionManager;
    private final WorkflowService workflowService;
    private final DeviceLifecycleService lifecycleService;

    /**
     * Callback for processing extracted audio data. Wired after construction
     * to avoid circular dependency with AudioService.
     */
    @FunctionalInterface
    public interface AudioRecordProcessor {
        /**
         * @param deviceId    the source device
         * @param audioBytes  decoded audio data
         * @param format      audio format hint (e.g. "wav", "pcm", "opus")
         * @return transcription text or null
         */
        String process(String deviceId, byte[] audioBytes, String format);
    }

    private AudioRecordProcessor processor;

    public AudioRecordHandler(DeviceSessionManager sessionManager,
                              @Lazy WorkflowService workflowService,
                              DeviceLifecycleService lifecycleService) {
        this.sessionManager = sessionManager;
        this.workflowService = workflowService;
        this.lifecycleService = lifecycleService;
    }

    public void setProcessor(AudioRecordProcessor processor) {
        this.processor = processor;
    }

    @Override
    public String getSupportedTopic() {
        return "audio/record";
    }

    @Override
    public void handle(WebSocketSession session, SduiMessage message) {
        String deviceId = message.getDeviceId();
        if (deviceId == null) {
            log.warn("audio/record message missing deviceId");
            return;
        }

        sessionManager.registerSession(deviceId, session);
        lifecycleService.touchDevice(deviceId);

        JsonNode payload = message.getPayload();
        if (payload == null) {
            log.debug("audio/record from {} has no payload", deviceId);
            return;
        }

        // Extract audio data: support base64-encoded and raw formats
        byte[] audioBytes = extractAudioData(payload);
        if (audioBytes == null || audioBytes.length == 0) {
            log.warn("audio/record from {} has no audio data", deviceId);
            return;
        }

        String format = payload.has("format") ? payload.get("format").asText() : null;
        log.info("Audio record received from {}: {} bytes, format={}", deviceId, audioBytes.length, format);

        // Fire workflow event for audio.record.data
        workflowService.fireEvent(deviceId, "audio.record.data",
                Map.of("deviceId", deviceId,
                       "size", audioBytes.length,
                       "format", format != null ? format : "unknown"));

        // Process STT if a processor is wired
        if (processor != null) {
            try {
                String transcription = processor.process(deviceId, audioBytes, format);
                if (transcription != null && !transcription.isEmpty()) {
                    log.info("STT transcription for device {}: {} chars", deviceId, transcription.length());
                    // Fire workflow event with transcription result
                    workflowService.fireEvent(deviceId, "audio.stt.result",
                            Map.of("deviceId", deviceId,
                                   "text", transcription,
                                   "format", format != null ? format : "unknown"));
                }
            } catch (Exception e) {
                log.error("STT processing failed for device {}: {}", deviceId, e.getMessage());
            }
        }
    }

    /**
     * Extract audio data from the payload, supporting multiple encoding formats.
     */
    private byte[] extractAudioData(JsonNode payload) {
        // Base64-encoded audio (most common for JSON transport)
        if (payload.has("data") && payload.get("data").isTextual()) {
            try {
                return Base64.getDecoder().decode(payload.get("data").asText());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid base64 audio data: {}", e.getMessage());
            }
        }

        // Binary data as array of integers
        if (payload.has("bytes") && payload.get("bytes").isArray()) {
            JsonNode arr = payload.get("bytes");
            byte[] bytes = new byte[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                bytes[i] = (byte) arr.get(i).asInt();
            }
            return bytes;
        }

        // Direct audio field (might be base64 or raw depending on implementation)
        if (payload.has("audio") && payload.get("audio").isTextual()) {
            try {
                return Base64.getDecoder().decode(payload.get("audio").asText());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid base64 in audio field: {}", e.getMessage());
            }
        }

        return null;
    }
}
