package com.zwbd.agentnexus.sdui;

import com.zwbd.agentnexus.sdui.v2.transport.SduiV2WebSocketHandler;
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

    @Autowired
    private SduiV2WebSocketHandler sduiV2WebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 旧协议端点，保留用于过渡期回归；P5 阶段随旧协议一并删除
        registry.addHandler(sduiWebSocketHandler, "/ws/sdui", "/")
                .setAllowedOrigins("*");

        // LCD_085 重构后的 v2 协议端点
        registry.addHandler(sduiV2WebSocketHandler, "/ws/sdui/v2")
                .setAllowedOrigins("*");
    }
}
