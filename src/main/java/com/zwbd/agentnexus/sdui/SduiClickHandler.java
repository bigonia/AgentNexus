package com.zwbd.agentnexus.sdui;

import com.zwbd.agentnexus.sdui.service.SduiAppRuntimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
@RequiredArgsConstructor
public class SduiClickHandler implements TopicHandler {

    private final SduiAppRuntimeService runtimeService;

    @Override
    public String getSupportedTopic() {
        return "ui/click";
    }

    @Override
    public void handle(WebSocketSession session, SduiMessage message) {
        runtimeService.handleEvent(message.getDeviceId(), message);
    }
}

