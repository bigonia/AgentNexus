package com.zwbd.agentnexus.sdui.handler;

import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec.DecodedFrame;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec.TlvEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.function.Function;

@Slf4j
@Component
public class EventInputHandler implements BinaryFrameHandler {

    @Override
    public int getSupportedMsgType() { return 9; }

    @Override
    public void handle(WebSocketSession session, DecodedFrame frame) {
        String kind = findTlv(frame, 120, TlvEntry::asString);
        String nodeId = findTlv(frame, 121, TlvEntry::asString);
        Long ts = findTlv(frame, 125, TlvEntry::asU32);
        log.info("Event input: kind={}, nodeId={}, ts={}", kind, nodeId, ts);
    }

    private <T> T findTlv(DecodedFrame frame, int type, Function<TlvEntry, T> extractor) {
        return frame.tlvs().stream()
                .filter(t -> t.type() == type)
                .findFirst().map(extractor).orElse(null);
    }
}
