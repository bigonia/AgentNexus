package com.zwbd.agentnexus.sdui;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * @Author: wnli
 * @Date: 2026/3/12 16:04
 * @Desc: WebSocket 端点注册配置
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private SduiWebSocketHandler sduiWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 注册终端连接的端点，例如: ws://192.168.x.x:8080/ws/sdui
        // 允许跨域，方便本地测试
        registry.addHandler(sduiWebSocketHandler, "/ws/sdui")
                .setAllowedOrigins("*");
    }
}
