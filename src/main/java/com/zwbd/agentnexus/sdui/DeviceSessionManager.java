package com.zwbd.agentnexus.sdui;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Author: wnli
 * @Date: 2026/3/12 15:54
 * @Desc:
 */
@Slf4j
@Component
public class DeviceSessionManager {

    // K: device_id (eFuse MAC), V: WebSocketSession
    private final Map<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();

    // 记录 SessionID 对应的 DeviceID，方便断开连接时反查
    private final Map<String, String> sessionIdToDeviceIdMap = new ConcurrentHashMap<>();

    /**
     * 注册/更新设备会话
     */
    public void registerSession(String deviceId, WebSocketSession session) {
        sessionMap.put(deviceId, session);
        sessionIdToDeviceIdMap.put(session.getId(), deviceId);
        log.info("设备已注册上线: {}, 当前在线总数: {}", deviceId, sessionMap.size());
    }

    /**
     * 移除设备会话
     */
    public void removeSession(WebSocketSession session) {
        String deviceId = sessionIdToDeviceIdMap.remove(session.getId());
        if (deviceId != null) {
            sessionMap.remove(deviceId);
            log.info("设备已离线: {}, 当前在线总数: {}", deviceId, sessionMap.size());
        }
    }

    /**
     * 向指定设备下发文本消息 (JSON)
     */
    public boolean sendMessage(String deviceId, String jsonMessage) {
        WebSocketSession session = sessionMap.get(deviceId);
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(jsonMessage));
                return true;
            } catch (IOException e) {
                log.error("向设备 {} 发送消息失败", deviceId, e);
                return false;
            }
        }
        log.warn("设备 {} 不在线或会话已关闭，消息下发失败", deviceId);
        return false;
    }

    /**
     * 判断设备是否在线
     */
    public boolean isDeviceOnline(String deviceId) {
        WebSocketSession session = sessionMap.get(deviceId);
        return session != null && session.isOpen();
    }
}
