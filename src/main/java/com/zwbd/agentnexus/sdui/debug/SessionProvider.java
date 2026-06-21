package com.zwbd.agentnexus.sdui.debug;

import java.util.List;
import java.util.Optional;

/**
 * Provider interface for a debug session type.
 *
 * <p>Each capability that has long-lived sessions (audio recording, etc.)
 * implements this interface. {@link DebugSessionService} auto-discovers all
 * implementations via Spring injection and exposes them through a unified API.</p>
 */
public interface SessionProvider {

    /** Stable session type id (e.g. "audio-record", "video-capture") */
    String sessionId();

    /** Get the session for a specific device, if active */
    Optional<DebugSessionHandle> getSession(String deviceId);

    /** List all currently active sessions across all devices */
    List<DebugSessionHandle> listActiveSessions();
}
