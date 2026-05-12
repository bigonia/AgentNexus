package com.zwbd.agentnexus.sdui.handler;

import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec.DecodedFrame;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec.TlvEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
public class ErrorBinaryHandler implements BinaryFrameHandler {

    @Override
    public int getSupportedMsgType() { return 11; }

    @Override
    public void handle(WebSocketSession session, DecodedFrame frame) {
        int code = frame.tlvs().stream()
                .filter(t -> t.type() == 200)
                .findFirst().map(TlvEntry::asU16).orElse(-1);
        String detail = frame.tlvs().stream()
                .filter(t -> t.type() == 201)
                .findFirst().map(TlvEntry::asString).orElse("");
        log.error("Terminal error: code={}, detail={}", code, detail);
    }
}
