package com.zwbd.agentnexus.sdui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.service.DeviceLifecycleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class MotionEventHandler implements TopicHandler {

    private final DeviceSessionManager sessionManager;
    private final DeviceLifecycleService lifecycleService;
    private final ObjectMapper objectMapper;

    public MotionEventHandler(DeviceSessionManager sessionManager,
                              DeviceLifecycleService lifecycleService,
                              ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.lifecycleService = lifecycleService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getSupportedTopic() {
        return "motion";
    }

    @Override
    public void handle(WebSocketSession session, SduiMessage message) {
        String deviceId = message.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            log.warn("motion message missing deviceId");
            return;
        }

        sessionManager.registerSession(deviceId, session);
        lifecycleService.touchDevice(deviceId);

        JsonNode payloadNode = message.getPayload();
        if (payloadNode == null || payloadNode.isNull()) {
            log.debug("motion from {} has no payload", deviceId);
            return;
        }

        String eventType = payloadNode.path("type").asText("");
        String legacyEventName = toLegacyEventName(eventType);
        if (legacyEventName == null) {
            log.warn("motion from {} has unsupported type: {}", deviceId, eventType);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = objectMapper.convertValue(payloadNode, Map.class);
        payloadMap = new LinkedHashMap<>(payloadMap);
        payloadMap.put("eventName", legacyEventName);
        payloadMap.putIfAbsent("ts", System.currentTimeMillis());

        EventPayload payload = EventPayload.fromJsonTopic(
                "input:motion." + legacyEventName,
                deviceId,
                payloadMap
        );

        log.info("Motion event received from {}: eventId={}, payload={}",
                deviceId, payload.eventId(), payload.rawFields());
    }

    private String toLegacyEventName(String eventType) {
        return switch (eventType) {
            case "shake" -> "imu.shake";
            case "wrist_raise" -> "imu.wrist_raise";
            case "flip" -> "imu.flip";
            default -> null;
        };
    }
}
