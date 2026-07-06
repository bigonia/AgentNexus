package com.zwbd.agentnexus.sdui;

import com.zwbd.agentnexus.common.web.GlobalContext;
import com.zwbd.agentnexus.sdui.model.DeviceConnectionLog;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.repo.DeviceConnectionLogRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
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

    private final SduiDeviceRepository deviceRepository;
    private final DeviceConnectionLogRepository connectionLogRepository;

    public DeviceSessionManager(SduiDeviceRepository deviceRepository,
                                DeviceConnectionLogRepository connectionLogRepository) {
        this.deviceRepository = deviceRepository;
        this.connectionLogRepository = connectionLogRepository;
    }

    /**
     * 注册/更新设备会话
     */
    public void registerSession(String deviceId, WebSocketSession session) {
        WebSocketSession existing = sessionMap.get(deviceId);
        if (existing != null && existing.getId().equals(session.getId())) {
            return;
        }
        WebSocketSession oldSession = sessionMap.put(deviceId, session);
        if (oldSession != null && !oldSession.getId().equals(session.getId())) {
            sessionIdToDeviceIdMap.remove(oldSession.getId());
            // Previous session replaced by new one — log disconnect for old session
            logConnectionEvent(deviceId, oldSession, "DISCONNECTED", "duplicate_login");
        }
        sessionIdToDeviceIdMap.put(session.getId(), deviceId);

        // Update device connection metadata
        deviceRepository.findById(deviceId).ifPresent(device -> {
            device.setConnectedAt(LocalDateTime.now());
            device.setSessionId(session.getId());
            device.setConnectionCount((device.getConnectionCount() != null ? device.getConnectionCount() : 0) + 1);
            deviceRepository.save(device);
        });

        // Log connect event
        logConnectionEvent(deviceId, session, "CONNECTED", null);
        log.info("设备已注册上线: {}, 当前在线总数: {}", deviceId, sessionMap.size());
    }

    /**
     * 移除设备会话
     */
    public void removeSession(WebSocketSession session) {
        String deviceId = sessionIdToDeviceIdMap.remove(session.getId());
        if (deviceId != null) {
            sessionMap.computeIfPresent(deviceId, (key, current) -> {
                if (current.getId().equals(session.getId())) {
                    logConnectionEvent(deviceId, session, "DISCONNECTED", "session_closed");
                    log.info("设备已离线: {}, 当前在线总数: {}", deviceId, sessionMap.size() - 1);
                    return null;
                }
                log.info("设备 {} 已使用新会话重连，保留在线状态", deviceId);
                return current;
            });
        }
    }

    /**
     * 主动断开设备连接并记录原因
     */
    public void disconnectDevice(String deviceId) {
        disconnectDevice(deviceId, "server_initiated");
    }

    public void disconnectDevice(String deviceId, String reason) {
        WebSocketSession session = sessionMap.remove(deviceId);
        if (session != null) {
            sessionIdToDeviceIdMap.remove(session.getId());
            logConnectionEvent(deviceId, session, "DISCONNECTED", reason);
            try {
                session.close();
            } catch (IOException e) {
                log.warn("关闭设备 {} 连接时出错: {}", deviceId, e.getMessage());
            }
            log.info("设备已断开连接: {}", deviceId);
        }
    }

    private void logConnectionEvent(String deviceId, WebSocketSession session, String eventType, String reason) {
        try {
            DeviceConnectionLog logEntry = new DeviceConnectionLog();
            logEntry.setUserId(getCurrentUserId());
            logEntry.setDeviceId(deviceId);
            logEntry.setEventType(eventType);
            logEntry.setSessionId(session.getId());
            logEntry.setEventAt(LocalDateTime.now());
            if (reason != null) {
                logEntry.setDisconnectReason(reason);
            }
            // Extract IP from remote address
            if (session.getRemoteAddress() != null && session.getRemoteAddress() instanceof InetSocketAddress) {
                logEntry.setIpAddress(((InetSocketAddress) session.getRemoteAddress()).getAddress().getHostAddress());
            }
            connectionLogRepository.save(logEntry);
        } catch (Exception e) {
            log.warn("Failed to log connection event for device {}: {}", deviceId, e.getMessage());
        }
    }

    private String getCurrentUserId() {
        try {
            return GlobalContext.getUserId();
        } catch (Exception e) {
            return "";
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

    public boolean sendBinaryFrame(String deviceId, byte[] frame) {
        WebSocketSession session = sessionMap.get(deviceId);
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new BinaryMessage(frame));
                return true;
            } catch (IOException e) {
                log.error("向设备 {} 发送二进制帧失败", deviceId, e);
                return false;
            }
        }
        log.warn("设备 {} 不在线或会话已关闭，二进制帧下发失败", deviceId);
        return false;
    }

    /**
     * 判断设备是否在线
     */
    public boolean isDeviceOnline(String deviceId) {
        WebSocketSession session = sessionMap.get(deviceId);
        return session != null && session.isOpen();
    }

    public String getDeviceIdBySessionId(String sessionId) {
        return sessionIdToDeviceIdMap.get(sessionId);
    }

    public boolean isSameSession(String deviceId, WebSocketSession incomingSession) {
        if (incomingSession == null) {
            return false;
        }
        WebSocketSession current = sessionMap.get(deviceId);
        return current != null && incomingSession.getId().equals(current.getId());
    }
}
