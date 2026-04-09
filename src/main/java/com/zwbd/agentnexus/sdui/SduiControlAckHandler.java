package com.zwbd.agentnexus.sdui;

import com.zwbd.agentnexus.sdui.service.SduiDeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
@RequiredArgsConstructor
public class SduiControlAckHandler implements TopicHandler {

    private final SduiDeviceService deviceService;

    @Override
    public String getSupportedTopic() {
        return "cmd/control_ack";
    }

    @Override
    public void handle(WebSocketSession session, SduiMessage message) {
        if (message.getPayload() != null) {
            deviceService.handleControlAck(message.getDeviceId(), message.getPayload());
        }
    }
}

