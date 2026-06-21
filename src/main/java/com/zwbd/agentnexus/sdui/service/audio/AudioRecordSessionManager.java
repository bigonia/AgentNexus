package com.zwbd.agentnexus.sdui.service.audio;

import com.zwbd.agentnexus.sdui.debug.DebugSessionHandle;
import com.zwbd.agentnexus.sdui.debug.SessionProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-device PCM audio recording sessions.
 *
 * <p>The terminal sends raw PCM chunks via binary frames (msgType=18) between
 * {@code audio/record state=start} and {@code audio/record state=stop} markers.
 * This component buffers those chunks keyed by deviceId.</p>
 *
 * <p>Thread-safe: WebSocket messages for a single device are processed
 * sequentially by the Spring WebSocket thread model; the
 * {@link ConcurrentHashMap} guards cross-device access.</p>
 */
@Slf4j
@Component
public class AudioRecordSessionManager implements SessionProvider {

    private final ConcurrentHashMap<String, AudioRecordSession> sessions = new ConcurrentHashMap<>();

    /**
     * Create a new recording buffer for the device.
     * If a session already exists (previous recording not properly stopped),
     * it is cancelled first.
     */
    public void startSession(String deviceId) {
        AudioRecordSession existing = sessions.put(deviceId, new AudioRecordSession());
        if (existing != null) {
            log.warn("Overwriting existing audio record session for device={}, " +
                    "previous buffer had {} bytes", deviceId, existing.buffer.size());
        }
        log.info("Audio record session started: device={}", deviceId);
    }

    /**
     * Append a raw PCM chunk to the device's recording buffer.
     * No-op if no active session exists.
     */
    public void appendChunk(String deviceId, byte[] pcmChunk) {
        if (pcmChunk == null || pcmChunk.length == 0) return;
        AudioRecordSession session = sessions.get(deviceId);
        if (session == null) {
            log.debug("Ignoring PCM chunk for device={}: no active recording session", deviceId);
            return;
        }
        synchronized (session) {
            session.buffer.write(pcmChunk, 0, pcmChunk.length);
            session.chunkCount++;
        }
    }

    /**
     * Stop recording and return the complete PCM byte array.
     * Returns null if no active session exists.
     */
    public byte[] stopSession(String deviceId) {
        AudioRecordSession session = sessions.remove(deviceId);
        if (session == null) {
            log.debug("No active recording session to stop for device={}", deviceId);
            return null;
        }
        synchronized (session) {
            byte[] pcm = session.buffer.toByteArray();
            long durationMs = (pcm.length / 2) * 1000L / 22050;
            log.info("Audio record session stopped: device={}, pcmBytes={}, durationMs≈{}",
                    deviceId, pcm.length, durationMs);
            return pcm;
        }
    }

    /** Check whether a device has an active recording session. */
    public boolean isRecording(String deviceId) {
        return sessions.containsKey(deviceId);
    }

    /** Cancel a recording session without returning data. */
    public void cancelSession(String deviceId) {
        AudioRecordSession removed = sessions.remove(deviceId);
        if (removed != null) {
            log.info("Audio record session cancelled: device={}, discardedBytes={}",
                    deviceId, removed.buffer.size());
        }
    }

    /** Get the current buffer size for a device, or 0 if no session. */
    public int getBufferSize(String deviceId) {
        AudioRecordSession session = sessions.get(deviceId);
        return session != null ? session.buffer.size() : 0;
    }

    /** Get the session start time (epoch millis), or 0 if no session. */
    public long getSessionStartTime(String deviceId) {
        AudioRecordSession session = sessions.get(deviceId);
        return session != null ? session.startedAt : 0;
    }

    /** Get the number of PCM chunks received, or 0 if no session. */
    public int getChunkCount(String deviceId) {
        AudioRecordSession session = sessions.get(deviceId);
        return session != null ? session.chunkCount : 0;
    }

    // ── SessionProvider implementation ──

    @Override
    public String sessionId() {
        return "audio-record";
    }

    @Override
    public Optional<DebugSessionHandle> getSession(String deviceId) {
        if (!isRecording(deviceId)) {
            return Optional.empty();
        }
        return Optional.of(new DebugSessionHandle(
                "audio-record",
                deviceId,
                "audio.record",
                "active",
                getSessionStartTime(deviceId),
                Map.of("pcmBytes", getBufferSize(deviceId),
                        "chunkCount", getChunkCount(deviceId))
        ));
    }

    @Override
    public List<DebugSessionHandle> listActiveSessions() {
        List<DebugSessionHandle> result = new ArrayList<>();
        for (String deviceId : sessions.keySet()) {
            getSession(deviceId).ifPresent(result::add);
        }
        return result;
    }

    private static class AudioRecordSession {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final long startedAt = System.currentTimeMillis();
        int chunkCount = 0;
    }
}
