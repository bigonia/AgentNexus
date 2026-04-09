package com.zwbd.agentnexus.sdui.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SduiProtocolService {

    private final DeviceSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    public boolean sendLayout(String deviceId, JsonNode payload) {
        return sendTopic(deviceId, "ui/layout", payload);
    }

    public boolean sendUpdate(String deviceId, JsonNode payload) {
        return sendTopic(deviceId, "ui/update", payload);
    }

    public boolean sendControl(String deviceId, JsonNode payload) {
        return sendTopic(deviceId, "cmd/control", payload);
    }

    public boolean sendTopic(String deviceId, String topic, JsonNode payload) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("topic", topic);
        envelope.put("device_id", deviceId);
        envelope.set("payload", payload == null ? objectMapper.createObjectNode() : payload);
        boolean ok = sessionManager.sendMessage(deviceId, envelope.toString());
        if (!ok) {
            log.info("Discarded outgoing message because device offline. deviceId={}, topic={}", deviceId, topic);
        }
        return ok;
    }
}

