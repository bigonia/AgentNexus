package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.model.SduiDeviceCommand;
import com.zwbd.agentnexus.sdui.service.CommandLifecycleEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class CommandResultStreamService {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final List<CommandResultListener> listeners = new CopyOnWriteArrayList<>();

    public interface CommandResultListener {
        void onCommandEvent(CommandLifecycleEvent event);
    }

    public void addListener(CommandResultListener listener) {
        listeners.add(listener);
    }

    public void removeListener(CommandResultListener listener) {
        listeners.remove(listener);
    }

    public SseEmitter subscribe(String deviceId) {
        SseEmitter existing = emitters.get(deviceId);
        if (existing != null) {
            existing.complete();
        }

        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(deviceId, emitter);

        emitter.onCompletion(() -> emitters.remove(deviceId, emitter));
        emitter.onTimeout(() -> emitters.remove(deviceId, emitter));
        emitter.onError(error -> emitters.remove(deviceId, emitter));

        send(deviceId, "connected", Map.of(
                "deviceId", deviceId,
                "message", "Command result stream connected"
        ));
        return emitter;
    }

    public void publishCommand(SduiDeviceCommand command, String phase) {
        if (command == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", phase);
        payload.put("deviceId", command.getDeviceId());
        payload.put("cmdId", command.getCmdId());
        payload.put("command", command.getCommand());
        payload.put("action", command.getAction());
        payload.put("status", command.getStatus());
        payload.put("reason", command.getReason());
        payload.put("createdAt", command.getCreatedAt() != null ? command.getCreatedAt().toString() : null);
        payload.put("ackAt", command.getAckTs() != null
                ? java.time.Instant.ofEpochMilli(command.getAckTs()).atOffset(ZoneOffset.UTC).toString()
                : null);
        send(command.getDeviceId(), "command_result", payload);
        publishToListeners(CommandLifecycleEvent.from(command, phase));
    }

    private void publishToListeners(CommandLifecycleEvent event) {
        for (CommandResultListener listener : listeners) {
            try {
                listener.onCommandEvent(event);
            } catch (Exception e) {
                log.warn("CommandResultListener error for device {} command {}: {}",
                        event.deviceId(), event.command(), e.getMessage());
            }
        }
    }

    private void send(String deviceId, String eventName, Map<String, Object> payload) {
        SseEmitter emitter = emitters.get(deviceId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (IOException e) {
            emitters.remove(deviceId, emitter);
            log.info("Command SSE send failed for device {}: {}", deviceId, e.getMessage());
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
        }
    }
}
