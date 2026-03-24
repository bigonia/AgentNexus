package com.zwbd.agentnexus.sdui;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * @Author: wnli
 * @Date: 2026/3/12 15:59
 * @Desc:
/**
 * 处理 telemetry/heartbeat 主题
 * 职责：注册设备上线、刷新心跳、记录遥测数据
 */
@Slf4j
@Component
public class HeartbeatHandler implements TopicHandler {

    @Autowired
    private DeviceSessionManager sessionManager;

    @Override
    public String getSupportedTopic() {
        return "telemetry/heartbeat";
    }

    @Override
    public void handle(WebSocketSession session, SduiMessage message) {
        String deviceId = message.getDeviceId();

        // 1. 鉴权与注册 (首次收到心跳即认为设备上线)
        if (!sessionManager.isDeviceOnline(deviceId)) {
            sessionManager.registerSession(deviceId, session);
            // TODO: 在这里可以触发“设备首次上线”事件，如下发首屏默认布局 ui/layout
        }

        // 2. 提取遥测数据并记录 (目前仅打印，后续可存入数据库)
        if (message.getPayload() != null) {
            int rssi = message.getPayload().path("wifi_rssi").asInt(0);
            int freeHeap = message.getPayload().path("free_heap_internal").asInt(0);
            log.debug("收到设备 {} 心跳 -> RSSI: {} dBm, FreeHeap: {} bytes", deviceId, rssi, freeHeap);
        }
    }
}
