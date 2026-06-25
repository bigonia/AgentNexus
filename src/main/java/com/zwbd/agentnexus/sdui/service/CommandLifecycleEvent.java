package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.model.SduiDeviceCommand;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

public record CommandLifecycleEvent(
        String eventId,
        String phase,
        String deviceId,
        String cmdId,
        String command,
        String action,
        String status,
        String reason,
        Map<String, Object> payload
) {
    public static CommandLifecycleEvent from(SduiDeviceCommand command, String phase) {
        String eventId = resolveEventId(phase, command.getStatus());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
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
        return new CommandLifecycleEvent(
                eventId,
                phase,
                command.getDeviceId(),
                command.getCmdId(),
                command.getCommand(),
                command.getAction(),
                command.getStatus(),
                command.getReason(),
                payload
        );
    }

    private static String resolveEventId(String phase, String status) {
        if ("dispatch".equals(phase)) {
            return "command.dispatch";
        }
        if ("timeout".equals(phase)) {
            return "command.timeout";
        }
        if ("ack".equals(phase)) {
            return switch (status != null ? status : "") {
                case "ACKED" -> "command.ack";
                case "REJECTED" -> "command.rejected";
                default -> "command.failed";
            };
        }
        if ("FAILED".equals(status) || "ERROR".equals(status)) {
            return "command.failed";
        }
        return "command.result";
    }
}
