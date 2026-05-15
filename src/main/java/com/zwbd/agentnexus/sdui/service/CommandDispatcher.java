package com.zwbd.agentnexus.sdui.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommandDispatcher {

    private final SduiCapabilityService capabilityService;
    private final SduiProtocolService protocolService;

    public DispatchResult dispatch(String deviceId, String semanticCommand, Object value) {
        SduiCapabilityService.CommandRoute route = capabilityService.resolveRoute(deviceId, semanticCommand);
        String cmdId = UUID.randomUUID().toString();
        String paramsJson = buildParams(route, cmdId, value);
        boolean sent = protocolService.sendControlCommand(deviceId, route.topic(), paramsJson);
        log.info("Command dispatched: device={} cmd={} -> topic={} action={} bare={} cmdId={}",
                deviceId, semanticCommand, route.topic(), route.action(), route.bareTopic(), cmdId);
        return new DispatchResult(cmdId, route.topic(), route.action(), paramsJson, sent);
    }

    private String buildParams(SduiCapabilityService.CommandRoute route, String cmdId, Object value) {
        if (route.isActionTopic()) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("{\"cmd_id\":\"").append(cmdId)
                    .append("\",\"action\":\"").append(route.action()).append('"');
            if (value != null) {
                sb.append(",\"value\":").append(formatValue(value));
            }
            sb.append('}');
            return sb.toString();
        }
        if (value instanceof String s) return s;
        return "{}";
    }

    private String formatValue(Object value) {
        if (value instanceof Number) return value.toString();
        return '"' + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    public record DispatchResult(String cmdId, String topic, String action, String payload, boolean sent) {}
}
