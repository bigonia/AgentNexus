package com.zwbd.agentnexus.sdui;

import com.zwbd.agentnexus.sdui.service.SduiDeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
public class SduiPageChangedHandler implements TopicHandler {

    private final SduiDeviceService deviceService;

    public SduiPageChangedHandler(SduiDeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @Override
    public String getSupportedTopic() {
        return "ui/page_changed";
    }

    @Override
    public void handle(WebSocketSession session, SduiMessage message) {
        String page = message.getPayload() == null ? null : message.getPayload().path("page").asText(null);
        if (page != null) {
            deviceService.updateCurrentPage(message.getDeviceId(), page);
        }
        log.debug("ui/page_changed from device {}: page={}", message.getDeviceId(), page);
    }
}
