package com.zwbd.agentnexus.sdui;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * @Author: wnli
 * @Date: 2026/3/12 16:03
 * @Desc:
 * Spring WebSocket 底层处理器
 * 负责接收底层连接事件和原始文本数据
 */
@Slf4j
@Component
public class SduiWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private MessageRouter messageRouter;

    @Autowired
    private DeviceSessionManager sessionManager;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("新的 WebSocket 连接已建立: {}", session.getId());
        // 注意：此时还不知道 device_id，需要在收到首条 telemetry/heartbeat 后才正式绑定
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        // 将原始 JSON 报文抛给路由器处理
        messageRouter.routeMessage(session, payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("WebSocket 连接已关闭: {}, 状态码: {}", session.getId(), status.getCode());
        // 清理会话
        sessionManager.removeSession(session);
    }
}