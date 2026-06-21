package com.zwbd.agentnexus.sdui.service.audio;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.handler.BinaryFrameHandler;
import com.zwbd.agentnexus.sdui.handler.EventInputHandler;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * Handles inbound {@code AUDIO_RECORD_CHUNK} binary frames (msgType=18)
 * from devices during microphone recording.
 *
 * <p>The frame payload is raw PCM audio data (no TLVs, no base64).
 * Chunks are appended to the per-device buffer managed by
 * {@link AudioRecordSessionManager}.</p>
 *
 * <p>Throttled progress events ({@code audio.record.chunk}) are published
 * via {@link EventInputHandler} at most once per second to avoid flooding
 * SSE clients.</p>
 *
 * <p>Registered automatically via {@link com.zwbd.agentnexus.sdui.MessageRouter}'s
 * {@code List<BinaryFrameHandler>} constructor injection.</p>
 */
@Slf4j
@Component
public class AudioRecordChunkHandler implements BinaryFrameHandler {

    private final DeviceSessionManager sessionManager;
    private final AudioRecordSessionManager recordSessionManager;
    private final EventInputHandler eventInputHandler;

    public AudioRecordChunkHandler(DeviceSessionManager sessionManager,
                                   AudioRecordSessionManager recordSessionManager,
                                   EventInputHandler eventInputHandler) {
        this.sessionManager = sessionManager;
        this.recordSessionManager = recordSessionManager;
        this.eventInputHandler = eventInputHandler;
    }

    @Override
    public int getSupportedMsgType() {
        return BinaryProtocolCodec.MSG_TYPE_AUDIO_RECORD_CHUNK;
    }

    @Override
    public void handle(WebSocketSession session, BinaryProtocolCodec.DecodedFrame frame) {
        String deviceId = sessionManager.getDeviceIdBySessionId(session.getId());
        if (deviceId == null) {
            log.debug("AUDIO_RECORD_CHUNK from unknown session, ignored");
            return;
        }

        byte[] pcmChunk = frame.payload();
        if (pcmChunk == null || pcmChunk.length == 0) {
            log.debug("Empty AUDIO_RECORD_CHUNK from device={}", deviceId);
            return;
        }

        log.debug("Audio record chunk: device={}, bytes={}, seq={}", deviceId, pcmChunk.length, frame.seq());
        recordSessionManager.appendChunk(deviceId, pcmChunk);

        // Throttled progress event: at most once per second
        publishProgressIfDue(deviceId);
    }

    /**
     * Publish an {@code audio.record.chunk} progress event if at least 1 second
     * has elapsed since the last one for this device.
     */
    private void publishProgressIfDue(String deviceId) {
        long now = System.currentTimeMillis();
        Long lastSent = lastProgressTimeMap.get(deviceId);
        if (lastSent != null && now - lastSent < 1000) {
            return;
        }
        lastProgressTimeMap.put(deviceId, now);

        int bytesReceived = recordSessionManager.getBufferSize(deviceId);
        int chunkCount = recordSessionManager.getChunkCount(deviceId);

        eventInputHandler.publishEvent(EventPayload.fromLegacyMap(deviceId, "audio.record.chunk",
                Map.of("bytesReceived", bytesReceived,
                       "chunkCount", chunkCount)));
    }

    /** Tracks the last time a progress event was sent per device. */
    private final java.util.Map<String, Long> lastProgressTimeMap = new java.util.concurrent.ConcurrentHashMap<>();
}
