package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.handler.EventInputHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class EventStreamService implements EventInputHandler.EventListener {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public EventStreamService(EventInputHandler eventInputHandler) {
        eventInputHandler.addListener(this);
    }

    public SseEmitter subscribe(String deviceId) {
        SseEmitter existing = emitters.get(deviceId);
        if (existing != null) {
            existing.complete();
        }

        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(deviceId, emitter);

        emitter.onCompletion(() -> {
            emitters.remove(deviceId, emitter);
            log.info("SSE completed for device {}", deviceId);
        });
        emitter.onTimeout(() -> {
            emitters.remove(deviceId, emitter);
            log.info("SSE timed out for device {}", deviceId);
        });
        emitter.onError(e -> {
            emitters.remove(deviceId, emitter);
            log.info("SSE error for device {}: {}", deviceId, e.getMessage());
        });

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("deviceId", deviceId, "message", "Event stream connected")));
        } catch (IOException e) {
            emitters.remove(deviceId, emitter);
            log.error("Failed to send SSE connected event for device {}", deviceId, e);
        }

        return emitter;
    }

    @Override
    public void onEvent(String deviceId, String kind, String nodeId, String sectionId, long ts) {
        SseEmitter emitter = emitters.get(deviceId);
        if (emitter == null) return;

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("ts", ts);
        if (nodeId != null) data.put("nodeId", nodeId);
        if (sectionId != null) data.put("sectionId", sectionId);

        try {
            emitter.send(SseEmitter.event()
                    .name(kind != null ? kind : "unknown")
                    .data(data));
        } catch (IOException e) {
            emitters.remove(deviceId, emitter);
            log.info("SSE send failed for device {}, removing emitter: {}", deviceId, e.getMessage());
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
        }
    }
}
