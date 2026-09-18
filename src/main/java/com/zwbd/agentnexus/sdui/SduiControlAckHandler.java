package com.zwbd.agentnexus.sdui;

import com.fasterxml.jackson.databind.JsonNode;
import com.zwbd.agentnexus.sdui.protocol.SduiProtocolConstants;
import com.zwbd.agentnexus.sdui.service.SduiDeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
@RequiredArgsConstructor
// LCD_085 refactor (2026-09-18): legacy protocol path, scheduled for removal.
// Replaced by: v2 统一 request/result 信封 (sdui.v2.transport.SduiV2MessageRouter)
// Kept only so un-migrated devices keep working; delete once the terminal rolls over to v2.
// See docs/sdui/lcd085-refactor/2026-09-18/10_PLATFORM_UPGRADE.md section 10.
@Deprecated(since = "0.10.0")
public class SduiControlAckHandler implements TopicHandler {

    private final SduiDeviceService deviceService;

    @Override
    public String getSupportedTopic() {
        return SduiProtocolConstants.Topics.COMMAND_CONTROL_ACK;
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
