package com.zwbd.agentnexus.sdui.handler;

import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec.DecodedFrame;
import org.springframework.web.socket.WebSocketSession;

public interface BinaryFrameHandler {
    int getSupportedMsgType();
    void handle(WebSocketSession session, DecodedFrame frame);
}
