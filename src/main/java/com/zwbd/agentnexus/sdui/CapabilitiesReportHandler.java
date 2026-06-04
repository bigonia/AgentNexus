package com.zwbd.agentnexus.sdui;

import com.zwbd.agentnexus.sdui.service.SduiDeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
public class CapabilitiesReportHandler implements TopicHandler {

    private final SduiDeviceService deviceService;
    private final DeviceSessionManager sessionManager;

    public CapabilitiesReportHandler(SduiDeviceService deviceService,
                                     DeviceSessionManager sessionManager) {
        this.deviceService = deviceService;
        this.sessionManager = sessionManager;
    }

    @Override
    public String getSupportedTopic() {
        return "device/capabilities";
    }

    @Override
    public void handle(WebSocketSession session, SduiMessage message) {
        String deviceId = message.getDeviceId();
        log.info("Capabilities report received from device {}", deviceId);
        sessionManager.registerSession(deviceId, session);
        if (message.getPayload() != null) {
            deviceService.handleCapabilitiesReport(deviceId, message.getPayload());
        }
    }
}
