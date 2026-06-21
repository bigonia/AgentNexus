package com.zwbd.agentnexus.sdui.debug;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Unified session registry for debug sessions.
 *
 * <p>Auto-discovers all {@link SessionProvider} implementations via Spring
 * constructor injection. Exposes generic session queries used by the
 * debug REST API ({@code GET /{deviceId}/sessions}).</p>
 */
@Slf4j
@Service
public class DebugSessionService {

    private final Map<String, SessionProvider> providers;

    public DebugSessionService(List<SessionProvider> providerList) {
        Map<String, SessionProvider> map = new LinkedHashMap<>();
        for (SessionProvider p : providerList) {
            map.put(p.sessionId(), p);
        }
        this.providers = Collections.unmodifiableMap(map);
        log.info("Registered {} debug session providers: {}", providers.size(), providers.keySet());
    }

    /** List all active sessions for a device across all providers. */
    public List<DebugSessionHandle> listSessions(String deviceId) {
        List<DebugSessionHandle> result = new ArrayList<>();
        for (SessionProvider provider : providers.values()) {
            provider.getSession(deviceId).ifPresent(result::add);
        }
        return result;
    }

    /** Get a specific session by id and device. */
    public Optional<DebugSessionHandle> getSession(String deviceId, String sessionId) {
        SessionProvider provider = providers.get(sessionId);
        if (provider == null) {
            return Optional.empty();
        }
        return provider.getSession(deviceId);
    }
}
