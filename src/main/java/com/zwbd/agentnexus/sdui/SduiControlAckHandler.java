package com.zwbd.agentnexus.sdui;

import com.fasterxml.jackson.databind.JsonNode;
import com.zwbd.agentnexus.sdui.service.SduiDeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
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
        JsonNode payload = message.getPayload();
        if (payload != null) {
            String cmdId = payload.path("cmd_id").asText("?");
            String status = payload.path("status").asText("?");
            String reason = payload.path("reason").asText("");
            log.info("Control ACK received: device={} cmdId={} status={} reason={}",
                    message.getDeviceId(), cmdId, status, reason);
            deviceService.handleControlAck(message.getDeviceId(), payload);
        }
    }
}
