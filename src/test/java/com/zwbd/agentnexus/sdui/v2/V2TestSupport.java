package com.zwbd.agentnexus.sdui.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.v2.session.DeviceHandshake;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v2 单元测试的公共构造辅助。
 */
public final class V2TestSupport {

    private V2TestSupport() {}

    public static ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    public static V2ProtocolProperties properties() {
        return new V2ProtocolProperties();
    }

    /** 构造一个处于打开状态的模拟 WebSocket 会话。 */
    public static WebSocketSession openSession(String sessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    public static DeviceHandshake handshake(String deviceId, String hash) {
        return DeviceHandshake.of(deviceId, "2", hash);
    }
}
