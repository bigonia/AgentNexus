package com.zwbd.agentnexus.sdui.service.audio;

import com.zwbd.agentnexus.sdui.debug.DebugSessionHandle;
import com.zwbd.agentnexus.sdui.debug.SessionProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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
    private final ConcurrentHashMap<String, Long> noSessionChunkLogAt = new ConcurrentHashMap<>();
    private final long resumeGraceMs;

    public AudioRecordSessionManager(@Value("${sdui.audio.record.resume-grace-ms:120000}") long resumeGraceMs) {
        this.resumeGraceMs = resumeGraceMs;
    }

    /**
     * Create a new recording buffer for the device.
     * If a session already exists (previous recording not properly stopped),
     * it is cancelled first.
     */
    public void startSession(String deviceId) {
        AudioRecordSession existing = sessions.get(deviceId);
        if (existing != null) {
            synchronized (existing) {
                if (existing.state == SessionState.SUSPENDED && !existing.isResumeExpired(resumeGraceMs)) {
                    existing.resume();
                    log.info("Audio record session resumed by duplicate start: device={}, bufferedBytes={}, chunkCount={}",
                            deviceId, existing.buffer.size(), existing.chunkCount);
                    return;
                }
                if (existing.state == SessionState.ACTIVE) {
                    log.info("Audio record session start ignored: device={}, existingBytes={}, chunkCount={}",
                            deviceId, existing.buffer.size(), existing.chunkCount);
                    return;
                }
            }
            log.warn("Overwriting expired audio record session for device={}, previous buffer had {} bytes",
                    deviceId, existing.buffer.size());
        }
        sessions.put(deviceId, new AudioRecordSession());
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
            logNoSessionChunkIfDue(deviceId);
            return;
        }
        synchronized (session) {
            if (session.state == SessionState.SUSPENDED) {
                if (session.isResumeExpired(resumeGraceMs)) {
                    sessions.remove(deviceId, session);
                    log.warn("Ignoring PCM chunk for device={}: suspended recording expired, discardedBytes={}",
                            deviceId, session.buffer.size());
                    return;
                }
                session.resume();
                log.info("Audio record session resumed by PCM chunk: device={}, bufferedBytes={}, chunkCount={}",
                        deviceId, session.buffer.size(), session.chunkCount);
            }
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

    /** Suspend a recording session so the same device can resume after a short reconnect. */
    public boolean suspendSession(String deviceId) {
        AudioRecordSession session = sessions.get(deviceId);
        if (session == null) {
            return false;
        }
        synchronized (session) {
            if (session.state == SessionState.SUSPENDED) {
                return true;
            }
            session.suspend();
            log.info("Audio record session suspended: device={}, bufferedBytes={}, chunkCount={}, graceMs={}",
                    deviceId, session.buffer.size(), session.chunkCount, resumeGraceMs);
            return true;
        }
    }

    /** Resume a suspended recording session after the same device reconnects. */
    public boolean resumeSession(String deviceId) {
        AudioRecordSession session = sessions.get(deviceId);
        if (session == null) {
            return false;
        }
        synchronized (session) {
            if (session.state == SessionState.ACTIVE) {
                return true;
            }
            if (session.isResumeExpired(resumeGraceMs)) {
                sessions.remove(deviceId, session);
                log.warn("Audio record session resume expired: device={}, discardedBytes={}, chunkCount={}",
                        deviceId, session.buffer.size(), session.chunkCount);
                return false;
            }
            session.resume();
            log.info("Audio record session resumed: device={}, bufferedBytes={}, chunkCount={}",
                    deviceId, session.buffer.size(), session.chunkCount);
            return true;
        }
    }

    /**
     * Remember that the operator asked to stop while the device was briefly
     * disconnected. The next reconnect will consume this marker and dispatch
     * the stop command once.
     */
    public boolean requestStopOnReconnect(String deviceId, String reason) {
        AudioRecordSession session = sessions.get(deviceId);
        if (session == null) {
            return false;
        }
        synchronized (session) {
            session.stopRequestedOnReconnect = true;
            session.stopRequestReason = reason;
            log.info("Audio record stop deferred until reconnect: device={}, state={}, bufferedBytes={}, reason={}",
                    deviceId, session.state.name().toLowerCase(), session.buffer.size(), reason);
            return true;
        }
    }

    /** Consume a pending reconnect-stop marker exactly once. */
    public boolean consumeStopOnReconnect(String deviceId) {
        AudioRecordSession session = sessions.get(deviceId);
        if (session == null) {
            return false;
        }
        synchronized (session) {
            if (!session.stopRequestedOnReconnect) {
                return false;
            }
            session.stopRequestedOnReconnect = false;
            log.info("Audio record deferred stop consumed after reconnect: device={}, bufferedBytes={}, chunkCount={}",
                    deviceId, session.buffer.size(), session.chunkCount);
            return true;
        }
    }

    public boolean hasPendingStop(String deviceId) {
        AudioRecordSession session = sessions.get(deviceId);
        if (session == null) {
            return false;
        }
        synchronized (session) {
            return session.stopRequestedOnReconnect;
        }
    }

    private void logNoSessionChunkIfDue(String deviceId) {
        long now = System.currentTimeMillis();
        Long last = noSessionChunkLogAt.get(deviceId);
        if (last == null || now - last >= 5_000) {
            noSessionChunkLogAt.put(deviceId, now);
            log.debug("Ignoring PCM chunks for device={}: no active recording session", deviceId);
        }
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

    @Scheduled(fixedDelayString = "${sdui.audio.record.resume-scan-ms:10000}")
    public void purgeExpiredSuspendedSessions() {
        for (Map.Entry<String, AudioRecordSession> entry : sessions.entrySet()) {
            String deviceId = entry.getKey();
            AudioRecordSession session = entry.getValue();
            synchronized (session) {
                if (session.state == SessionState.SUSPENDED && session.isResumeExpired(resumeGraceMs)) {
                    if (sessions.remove(deviceId, session)) {
                        log.warn("Audio record session resume grace expired: device={}, discardedBytes={}, chunkCount={}",
                                deviceId, session.buffer.size(), session.chunkCount);
                    }
                }
            }
        }
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
                sessionState(deviceId),
                getSessionStartTime(deviceId),
                Map.of("pcmBytes", getBufferSize(deviceId),
                        "chunkCount", getChunkCount(deviceId),
                        "pendingStop", hasPendingStop(deviceId))
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

    private String sessionState(String deviceId) {
        AudioRecordSession session = sessions.get(deviceId);
        if (session == null) {
            return "unknown";
        }
        return session.state == SessionState.SUSPENDED ? "suspended" : "active";
    }

    private enum SessionState {
        ACTIVE,
        SUSPENDED
    }

    private static class AudioRecordSession {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final long startedAt = System.currentTimeMillis();
        int chunkCount = 0;
        SessionState state = SessionState.ACTIVE;
        long suspendedAt = 0;
        boolean stopRequestedOnReconnect = false;
        String stopRequestReason = "";

        void suspend() {
            state = SessionState.SUSPENDED;
            suspendedAt = System.currentTimeMillis();
        }

        void resume() {
            state = SessionState.ACTIVE;
            suspendedAt = 0;
        }

        boolean isResumeExpired(long resumeGraceMs) {
            return state == SessionState.SUSPENDED
                    && suspendedAt > 0
                    && System.currentTimeMillis() - suspendedAt > resumeGraceMs;
        }
    }
}
