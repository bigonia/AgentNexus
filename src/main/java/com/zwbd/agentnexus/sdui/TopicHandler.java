package com.zwbd.agentnexus.sdui;

import org.springframework.web.socket.WebSocketSession;

/**
 * @Author: wnli
 * @Date: 2026/3/12 15:55
 * @Desc:  业务处理策略接口
 * 所有具体的业务处理器都应实现此接口
 */
public interface TopicHandler {

    /**
     * 声明该 Handler 处理的 Topic 前缀或全称
     * 例如："telemetry/heartbeat" 或 "ui/click"
     * @return 匹配的 Topic 字符串
     */
    String getSupportedTopic();

    /**
     * 处理具体的上行消息
     * @param session 当前的 WebSocket 会话
     * @param message 解析后的 SDUI 消息对象
     */
    void handle(WebSocketSession session, SduiMessage message);
}
