package com.zwbd.agentnexus.sdui.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.v2.session.DeviceHandshake;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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

    /**
     * 构造一个处于打开状态的模拟 WebSocket 会话。
     *
     * <p>关键：{@code close()} 必须真的把会话翻成关闭态。真实会话关闭后 {@code isOpen()} 立即变 false，
     * 平台侧的在线判定（{@code DeviceConnectionRegistry.isOnline}）正是读它；若桩把 {@code isOpen()}
     * 固定为 true，则"平台主动断开后设备变离线"这类用例会失败，而这种失败看起来像生产代码的 bug。</p>
     */
    public static WebSocketSession openSession(String sessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        AtomicBoolean open = new AtomicBoolean(true);
        when(session.getId()).thenReturn(sessionId);
        when(session.isOpen()).thenAnswer(invocation -> open.get());
        try {
            doAnswer(invocation -> {
                open.set(false);
                return null;
            }).when(session).close(any(CloseStatus.class));
        } catch (IOException e) {
            throw new IllegalStateException("构造模拟 WebSocket 会话失败", e);
        }
        return session;
    }

    public static DeviceHandshake handshake(String deviceId, String hash) {
        return DeviceHandshake.of(deviceId, "2", hash);
    }
}
