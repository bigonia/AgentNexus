package com.zwbd.agentnexus.sdui;

import com.zwbd.agentnexus.sdui.service.SduiDeviceService;
import com.zwbd.agentnexus.sdui.ui.DevicePrimaryUiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
public class CapabilitiesReportHandler implements TopicHandler {

    private final SduiDeviceService deviceService;
    private final DeviceSessionManager sessionManager;
    private final DevicePrimaryUiService primaryUiService;

    public CapabilitiesReportHandler(SduiDeviceService deviceService,
                                     DeviceSessionManager sessionManager,
                                     DevicePrimaryUiService primaryUiService) {
        this.deviceService = deviceService;
        this.sessionManager = sessionManager;
        this.primaryUiService = primaryUiService;
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
        try {
            primaryUiService.restorePrimary(deviceId);
        } catch (Exception e) {
            log.warn("Failed to restore primary UI after capabilities report: device={}, error={}",
                    deviceId, e.getMessage());
        }
        if (message.getPayload() != null) {
            deviceService.handleCapabilitiesReport(deviceId, message.getPayload());
        }
    }
}
