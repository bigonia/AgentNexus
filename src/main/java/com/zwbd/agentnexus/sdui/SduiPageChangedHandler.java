package com.zwbd.agentnexus.sdui;

import com.zwbd.agentnexus.sdui.service.SduiAppRuntimeService;
import com.zwbd.agentnexus.sdui.service.SduiDeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
@RequiredArgsConstructor
public class SduiPageChangedHandler implements TopicHandler {

    private final SduiAppRuntimeService runtimeService;
    private final SduiDeviceService deviceService;

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
        runtimeService.handleEvent(message.getDeviceId(), message);
    }
}

