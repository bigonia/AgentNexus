package com.zwbd.agentnexus.sdui.debug;

import java.util.Map;

/**
 * Immutable snapshot of an active debug session.
 *
 * <p>Each session represents an ongoing capability activity on a device
 * (audio recording, future: video capture, screen recording, etc.).
 * This record is the unified response shape for the generic sessions API.</p>
 */
public record DebugSessionHandle(
        /** Stable identifier for this session type (e.g. "audio-record") */
        String sessionId,

        /** Device that owns this session */
        String deviceId,

        /** Capability type (e.g. "audio.record") */
        String type,

        /** Current status: "active", "completed", "cancelled" */
        String status,

        /** Epoch millis when the session started */
        long startedAt,

        /** Type-specific metrics (e.g. bytesReceived, chunkCount for audio) */
        Map<String, Object> metrics
) {}
