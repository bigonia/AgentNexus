package com.zwbd.agentnexus.sdui.service.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.SduiMessage;
import com.zwbd.agentnexus.sdui.TopicHandler;
import com.zwbd.agentnexus.sdui.artifact.SduiArtifactService;
import com.zwbd.agentnexus.sdui.debug.DebugArtifactStore;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.handler.EventInputHandler;
import com.zwbd.agentnexus.sdui.service.DeviceLifecycleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.Map;

/**
 * Handles incoming {@code audio/record} topic messages from devices.
 *
 * <h3>New protocol (primary)</h3>
 * Terminal sends state markers to bound the recording lifecycle:
 * <ul>
 *   <li>{@code state: "start"} — terminal began capturing; platform creates a PCM buffer.</li>
 *   <li>{@code state: "stop"} — terminal finished uploading all PCM chunks;
 *       platform assembles the buffer, converts PCM→WAV, runs STT, and publishes
 *       {@code audio.record.data} + {@code audio.stt.result} events.</li>
 * </ul>
 * Raw PCM chunks arrive separately via binary frames (msgType=18), handled by
 * {@link AudioRecordChunkHandler}.
 *
 * <h3>Legacy (backward compat)</h3>
 * If the payload contains base64-encoded audio data rather than a state marker,
 * it is processed inline via the {@link AudioRecordProcessor} callback.
 *
 * <p>Registered automatically via {@link com.zwbd.agentnexus.sdui.MessageRouter}'s
 * {@code List<TopicHandler>} constructor injection.</p>
 */
@Slf4j
@Component
public class AudioRecordHandler implements TopicHandler {

    /** PCM audio format used by the terminal firmware. */
    public static final int PCM_SAMPLE_RATE = 22050;
    public static final int PCM_CHANNELS = 1;
    public static final int PCM_BITS_PER_SAMPLE = 16;

    private final DeviceSessionManager sessionManager;
    private final DeviceLifecycleService lifecycleService;
    private final AudioRecordSessionManager recordSessionManager;
    private final DebugArtifactStore artifactStore;
    private final SduiArtifactService artifactService;
    private final EventInputHandler eventInputHandler;

    /**
     * Callback for processing audio data (STT). Wired after construction
     * by {@link com.zwbd.agentnexus.sdui.service.AudioService} to avoid
     * circular dependency.
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
                              DeviceLifecycleService lifecycleService,
                              AudioRecordSessionManager recordSessionManager,
                              DebugArtifactStore artifactStore,
                              SduiArtifactService artifactService,
                              EventInputHandler eventInputHandler) {
        this.sessionManager = sessionManager;
        this.lifecycleService = lifecycleService;
        this.recordSessionManager = recordSessionManager;
        this.artifactStore = artifactStore;
        this.artifactService = artifactService;
        this.eventInputHandler = eventInputHandler;
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

        // ── New protocol: state markers ──
        if (payload.has("state")) {
            handleStateMarker(deviceId, payload);
            return;
        }

        // ── Legacy: base64-encoded audio data (backward compat) ──
        handleLegacyAudioData(deviceId, payload);
    }

    // ── New protocol: state-based recording ──

    private void handleStateMarker(String deviceId, JsonNode payload) {
        String state = payload.get("state").asText();

        switch (state) {
            case "start" -> {
                recordSessionManager.startSession(deviceId);
                log.info("Audio recording started (state=start): device={}", deviceId);
                eventInputHandler.publishEvent(EventPayload.fromLegacyMap(deviceId, "audio.record.started",
                        Map.of("startedAt", System.currentTimeMillis())));
            }
            case "stop" -> {
                String reason = payload.has("reason") ? payload.get("reason").asText() : null;
                byte[] pcm = recordSessionManager.stopSession(deviceId);
                if (pcm != null && pcm.length > 0) {
                    log.info("Audio recording stopped (state=stop): device={}, pcmBytes={}, reason={}",
                            deviceId, pcm.length, reason);
                    processCompletedRecording(deviceId, pcm);
                } else {
                    log.info("Audio recording stopped with no data: device={}, reason={}", deviceId, reason);
                }
            }
            default -> log.debug("Unknown audio/record state '{}' from device={}", state, deviceId);
        }
    }

    /**
     * Process a completed recording: convert PCM→WAV, run STT, publish events,
     * and persist the result for debug inspection / playback.
     */
    private void processCompletedRecording(String deviceId, byte[] pcm) {
        // 1. Publish audio.record.data event (metadata about the raw recording)
        eventInputHandler.publishEvent(EventPayload.fromLegacyMap(deviceId, "audio.record.data",
                Map.of("pcmSize", pcm.length,
                        "sampleRate", PCM_SAMPLE_RATE,
                        "channels", PCM_CHANNELS,
                        "bitsPerSample", PCM_BITS_PER_SAMPLE,
                        "format", "pcm_s16le")));

        // 2. Convert PCM to WAV for STT processing and playback
        byte[] wav = pcmToWav(pcm, PCM_SAMPLE_RATE, PCM_CHANNELS, PCM_BITS_PER_SAMPLE);

        // 3. Run STT
        String transcription = null;
        if (processor != null) {
            // 3a. Publish audio.stt.processing event
            eventInputHandler.publishEvent(EventPayload.fromLegacyMap(deviceId, "audio.stt.processing",
                    Map.of("wavSize", wav.length)));

            try {
                transcription = processor.process(deviceId, wav, "wav");
                if (transcription != null && !transcription.isEmpty()) {
                    log.info("STT transcription for device {}: {} chars", deviceId, transcription.length());
                    // 3b. Publish STT result event
                    eventInputHandler.publishEvent(EventPayload.fromLegacyMap(deviceId, "audio.stt.result",
                            Map.of("text", transcription)));
                } else {
                    log.info("STT produced no transcription for device {}", deviceId);
                }
            } catch (Exception e) {
                log.error("STT processing failed for device {}: {}", deviceId, e.getMessage());
            }
        } else {
            log.debug("No STT processor wired, skipping transcription for device {}", deviceId);
        }

        // 4. Store as generic artifact for debug endpoints
        int durationMs = (int) ((long) pcm.length * 1000
                / (PCM_SAMPLE_RATE * PCM_CHANNELS * PCM_BITS_PER_SAMPLE / 8));
        Map<String, Object> metadata = Map.of("text", transcription != null ? transcription : "",
                "sttText", transcription != null ? transcription : "",
                "pcmSize", pcm.length,
                "sampleRate", PCM_SAMPLE_RATE,
                "channels", PCM_CHANNELS,
                "bitsPerSample", PCM_BITS_PER_SAMPLE,
                "durationMs", durationMs);
        artifactService.save(deviceId, SduiArtifactService.AUDIO_RECORDING, "audio/wav", metadata, wav);
        artifactStore.put(deviceId, new DebugArtifactStore.Artifact(
                "audio-record-latest",
                "audio/recording",
                "audio/wav",
                metadata,
                wav,
                System.currentTimeMillis()
        ));
    }

    // ── Legacy: base64 audio data ──

    private void handleLegacyAudioData(String deviceId, JsonNode payload) {
        byte[] audioBytes = extractAudioData(payload);
        if (audioBytes == null || audioBytes.length == 0) {
            log.warn("audio/record from {} has no audio data (and no state marker)", deviceId);
            return;
        }

        String format = payload.has("format") ? payload.get("format").asText() : null;
        log.info("Legacy audio record received from {}: {} bytes, format={}", deviceId, audioBytes.length, format);

        if (processor != null) {
            try {
                String transcription = processor.process(deviceId, audioBytes, format);
                if (transcription != null && !transcription.isEmpty()) {
                    log.info("Legacy STT transcription for device {}: {} chars", deviceId, transcription.length());
                    eventInputHandler.publishEvent(EventPayload.fromLegacyMap(deviceId, "audio.stt.result",
                            Map.of("text", transcription)));
                }
            } catch (Exception e) {
                log.error("Legacy STT processing failed for device {}: {}", deviceId, e.getMessage());
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

        // Direct audio field
        if (payload.has("audio") && payload.get("audio").isTextual()) {
            try {
                return Base64.getDecoder().decode(payload.get("audio").asText());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid base64 in audio field: {}", e.getMessage());
            }
        }

        return null;
    }

    // ── WAV encoding ──

    /**
     * Wrap raw PCM samples in a standard WAV (RIFF) header.
     *
     * @param pcm           raw PCM data (little-endian signed 16-bit)
     * @param sampleRate    sample rate in Hz (e.g. 22050)
     * @param channels      number of channels (1 = mono)
     * @param bitsPerSample bits per sample (e.g. 16)
     * @return valid WAV file bytes
     */
    public static byte[] pcmToWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcm.length;
        int fileSize = 36 + dataSize;

        ByteArrayOutputStream wav = new ByteArrayOutputStream(44 + dataSize);
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);

        header.put("RIFF".getBytes());
        header.putInt(fileSize);
        header.put("WAVE".getBytes());
        header.put("fmt ".getBytes());
        header.putInt(16);              // PCM chunk size
        header.putShort((short) 1);     // audio format = PCM
        header.putShort((short) channels);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short) blockAlign);
        header.putShort((short) bitsPerSample);
        header.put("data".getBytes());
        header.putInt(dataSize);

        try {
            wav.write(header.array());
            wav.write(pcm);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode WAV", e);
        }
        return wav.toByteArray();
    }
}
