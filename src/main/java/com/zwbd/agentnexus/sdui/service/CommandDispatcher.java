package com.zwbd.agentnexus.sdui.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommandDispatcher {

    private final SduiCapabilityService capabilityService;
    private final SduiProtocolService protocolService;

    public DispatchResult dispatch(String deviceId, String semanticCommand, Object value) {
        return dispatchWithAction(deviceId, semanticCommand, null, value);
    }

    public DispatchResult dispatchWithAction(String deviceId, String semanticCommand,
                                              String explicitAction, Object value) {
        SduiCapabilityService.CommandRoute route = capabilityService.resolveRoute(deviceId, semanticCommand);
        String cmdId = UUID.randomUUID().toString();
        String action = explicitAction != null ? explicitAction : route.action();
        String paramsJson = buildParams(route.topic(), action, cmdId, value);
        boolean sent = protocolService.sendControlCommand(deviceId, route.topic(), paramsJson);
        log.info("Command dispatched: device={} cmd={} -> topic={} action={} bare={} cmdId={} payload={}",
                deviceId, semanticCommand, route.topic(), action, route.bareTopic(), cmdId, paramsJson);
        return new DispatchResult(cmdId, route.topic(), action, paramsJson, sent);
    }

    private String buildParams(String topic, String action, String cmdId, Object value) {
        if (action != null) {
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"cmd_id\":\"").append(cmdId)
                    .append("\",\"action\":\"").append(action).append('"');
            appendValueFields(sb, value);
            sb.append('}');
            return sb.toString();
        }
        if (value instanceof String s) return s;
        if (value instanceof Map<?,?> map) {
            StringBuilder sb = new StringBuilder(256);
            sb.append('{');
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) sb.append(',');
                sb.append('"').append(entry.getKey()).append("\":");
                sb.append(formatValue(entry.getValue()));
                first = false;
            }
            sb.append('}');
            return sb.toString();
        }
        return "{}";
    }

    private void appendValueFields(StringBuilder sb, Object value) {
        if (value == null) return;
        if (value instanceof Map<?,?> map) {
            for (var entry : map.entrySet()) {
                sb.append(",\"").append(entry.getKey()).append("\":");
                sb.append(formatValue(entry.getValue()));
            }
        } else {
            sb.append(",\"value\":").append(formatValue(value));
        }
    }

    private String formatValue(Object value) {
        if (value instanceof Number) return value.toString();
        if (value instanceof Boolean) return value.toString();
        return '"' + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    public record DispatchResult(String cmdId, String topic, String action, String payload, boolean sent) {}
}
