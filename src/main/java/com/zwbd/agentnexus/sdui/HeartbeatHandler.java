package com.zwbd.agentnexus.sdui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.section.SectionOrchestrationService;
import com.zwbd.agentnexus.sdui.section.SectionPresets;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import com.zwbd.agentnexus.sdui.service.SduiDeviceService;
import com.zwbd.agentnexus.sdui.service.SduiProtocolService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
public class HeartbeatHandler implements TopicHandler {

    private final DeviceSessionManager sessionManager;
    private final SduiDeviceService deviceService;
    private final SduiProtocolService protocolService;
    private final SduiCapabilityService capabilityService;
    private final SectionOrchestrationService orchestrationService;

    public HeartbeatHandler(DeviceSessionManager sessionManager,
                            SduiDeviceService deviceService,
                            SduiProtocolService protocolService,
                            SduiCapabilityService capabilityService,
                            SectionOrchestrationService orchestrationService) {
        this.sessionManager = sessionManager;
        this.deviceService = deviceService;
        this.protocolService = protocolService;
        this.capabilityService = capabilityService;
        this.orchestrationService = orchestrationService;
    }

    @Override
    public String getSupportedTopic() {
        return "telemetry/heartbeat";
    }

    @Override
    public void handle(WebSocketSession session, SduiMessage message) {
        String deviceId = message.getDeviceId();
        boolean justConnected = false;

        if (!sessionManager.isDeviceOnline(deviceId) || !sessionManager.isSameSession(deviceId, session)) {
            sessionManager.registerSession(deviceId, session);
            justConnected = true;
        }

        JsonNode payload = message.getPayload();
        if (payload == null || payload.isNull()) {
            payload = createEmptyPayload();
            log.info("Heartbeat payload missing, fallback to empty payload. deviceId={}", deviceId);
        }

        int rssi = payload.path("wifi_rssi").asInt(0);
        int freeHeap = payload.path("free_heap_internal").asInt(0);
        log.debug("Heartbeat {} -> RSSI: {} dBm, FreeHeap: {} bytes", deviceId, rssi, freeHeap);

        SduiDevice device = deviceService.onHeartbeat(deviceId, payload);
        if (justConnected) {
            log.info("Device connected. deviceId={}, registrationStatus={}, spaceId={}",
                    deviceId, device.getRegistrationStatus(), device.getOwnerSpaceId());
        }
        if (!justConnected) {
            return;
        }

        if ("UNCLAIMED".equalsIgnoreCase(device.getRegistrationStatus())) {
            log.info("Device unclaimed, pushing claim code scene. deviceId={}, claimCode={}",
                    deviceId, device.getClaimCode());
            pushClaimCodeScene(device);
            return;
        }

        log.info("Device claimed and reconnected. deviceId={}, capabilitiesStored={}",
                deviceId, device.getCapabilitiesSnapshot() != null);
    }

    private void pushClaimCodeScene(SduiDevice device) {
        String code = device.getClaimCode();
        if (code == null || code.isBlank()) {
            log.warn("Device {} is unclaimed but has no claim code, skip scene push", device.getDeviceId());
            return;
        }
        try {
            orchestrationService.sendScene(device.getDeviceId(),
                    SectionPresets.claimCodeScene(code));
        } catch (Exception e) {
            log.warn("Failed to push claim code scene to device {}: {}", device.getDeviceId(), e.getMessage());
        }
    }

    private JsonNode createEmptyPayload() {
        return new ObjectNode(JsonNodeFactory.instance);
    }
}
