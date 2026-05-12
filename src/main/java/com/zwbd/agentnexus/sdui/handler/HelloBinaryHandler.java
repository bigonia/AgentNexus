package com.zwbd.agentnexus.sdui.handler;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec.DecodedFrame;
import com.zwbd.agentnexus.sdui.service.DeviceLifecycleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
public class HelloBinaryHandler implements BinaryFrameHandler {

    private final DeviceSessionManager sessionManager;
    private final DeviceLifecycleService lifecycleService;

    public HelloBinaryHandler(DeviceSessionManager sessionManager, DeviceLifecycleService lifecycleService) {
        this.sessionManager = sessionManager;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public int getSupportedMsgType() { return 1; }

    @Override
    public void handle(WebSocketSession session, DecodedFrame frame) {
        String deviceId = frame.tlvs().stream()
                .filter(t -> t.type() == 100)
                .findFirst().map(t -> t.asString()).orElse(null);
        if (deviceId == null) {
            log.warn("HELLO frame missing TLV_TERMINAL_ID");
            return;
        }
        sessionManager.registerSession(deviceId, session);
        lifecycleService.onHello(deviceId, frame);
        log.info("Terminal HELLO registered: deviceId={}", deviceId);
    }
}
