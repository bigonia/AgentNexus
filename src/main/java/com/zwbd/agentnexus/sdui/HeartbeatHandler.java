package com.zwbd.agentnexus.sdui;

import com.fasterxml.jackson.databind.JsonNode;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceAppBindingRepository;
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
    private final SduiDeviceAppBindingRepository bindingRepository;

    public HeartbeatHandler(DeviceSessionManager sessionManager,
                            SduiDeviceService deviceService,
                            SduiProtocolService protocolService,
                            SduiDeviceAppBindingRepository bindingRepository) {
        this.sessionManager = sessionManager;
        this.deviceService = deviceService;
        this.protocolService = protocolService;
        this.bindingRepository = bindingRepository;
    }

    @Override
    public String getSupportedTopic() {
        return "telemetry/heartbeat";
    }

    @Override
    public void handle(WebSocketSession session, SduiMessage message) {
        String deviceId = message.getDeviceId();
        boolean justConnected = false;

        if (!sessionManager.isDeviceOnline(deviceId)) {
            sessionManager.registerSession(deviceId, session);
            justConnected = true;
        }

        JsonNode payload = message.getPayload();
        if (payload == null) {
            return;
        }

        int rssi = payload.path("wifi_rssi").asInt(0);
        int freeHeap = payload.path("free_heap_internal").asInt(0);
        log.debug("Heartbeat {} -> RSSI: {} dBm, FreeHeap: {} bytes", deviceId, rssi, freeHeap);

        SduiDevice device = deviceService.onHeartbeat(deviceId, payload);
        if (justConnected) {
            log.info("Device registration state on first heartbeat. deviceId={}, registrationStatus={}, ownerSpaceId={}, claimedAt={}, claimCodeExpireAt={}",
                    deviceId,
                    device.getRegistrationStatus(),
                    device.getOwnerSpaceId(),
                    device.getClaimedAt(),
                    device.getClaimCodeExpireAt());
        }
        if (!justConnected) {
            return;
        }

        if ("UNCLAIMED".equalsIgnoreCase(device.getRegistrationStatus())) {
            boolean sent = protocolService.sendLayout(deviceId, deviceService.registrationPagePayload(device));
            log.info("Registration layout dispatch (stage=UNCLAIMED). deviceId={}, sent={}, claimCode={}", deviceId, sent, device.getClaimCode());
            return;
        }

        boolean hasActiveBinding = bindingRepository.findByDeviceIdAndActiveTrue(deviceId).isPresent();
        if (!hasActiveBinding) {
            boolean sent = protocolService.sendLayout(deviceId, deviceService.waitingForPublishPagePayload(device));
            log.info("Waiting layout dispatch (claimed but no app binding). deviceId={}, sent={}", deviceId, sent);
        }
    }
}
