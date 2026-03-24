package com.zwbd.agentnexus.sdui;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2026/3/12 15:57
 * @Desc:
 * 核心消息路由器
 * 负责将 WebSocket 收到的 JSON 文本解析并分发给对应的 TopicHandler
 */
@Slf4j
@Component
public class MessageRouter {

    private final ObjectMapper objectMapper;
    private final List<TopicHandler> handlers;

    // Topic 到 Handler 的快速映射表
    private final Map<String, TopicHandler> handlerMap = new HashMap<>();

    @Autowired
    public MessageRouter(ObjectMapper objectMapper, List<TopicHandler> handlers) {
        this.objectMapper = objectMapper;
        this.handlers = handlers;
    }

    /**
     * Spring 容器启动后，自动将所有实现了 TopicHandler 接口的 Bean 注册到映射表中
     */
    @PostConstruct
    public void init() {
        for (TopicHandler handler : handlers) {
            handlerMap.put(handler.getSupportedTopic(), handler);
            log.info("已注册 SDUI 路由: {} -> {}", handler.getSupportedTopic(), handler.getClass().getSimpleName());
        }
    }

    /**
     * 路由入口方法
     */
    public void routeMessage(WebSocketSession session, String jsonPayload) {
        try {
            // 1. JSON 信封解析
            SduiMessage message = objectMapper.readValue(jsonPayload, SduiMessage.class);

            if (message.getTopic() == null || message.getDeviceId() == null) {
                log.warn("收到格式无效的消息 (缺少 topic 或 device_id): {}", jsonPayload);
                return;
            }

            // 2. 查找匹配的 Handler
            TopicHandler handler = handlerMap.get(message.getTopic());

            // 可选：支持通配符或前缀匹配 (例如匹配所有 ui/*)，此处暂用精确匹配

            if (handler != null) {
                // 3. 分发执行
                handler.handle(session, message);
            } else {
                log.debug("未找到对应 Topic [{}] 的处理器，消息被忽略", message.getTopic());
            }

        } catch (Exception e) {
            log.error("消息路由解析失败. Payload: {}", jsonPayload, e);
        }
    }
}
