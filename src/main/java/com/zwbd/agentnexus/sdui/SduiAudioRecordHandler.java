package com.zwbd.agentnexus.sdui;

import com.zwbd.agentnexus.sdui.service.SduiAppRuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
@RequiredArgsConstructor
public class SduiAudioRecordHandler implements TopicHandler {

    private final SduiAppRuntimeService runtimeService;

    @Override
    public String getSupportedTopic() {
        return "audio/record";
    }

    @Override
    public void handle(WebSocketSession session, SduiMessage message) {
        runtimeService.handleEvent(message.getDeviceId(), message);
    }
}

