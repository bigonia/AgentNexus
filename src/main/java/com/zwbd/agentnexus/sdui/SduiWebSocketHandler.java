package com.zwbd.agentnexus.sdui;

import com.zwbd.agentnexus.sdui.service.audio.AudioRecordSessionManager;
import com.zwbd.agentnexus.sdui.service.CommandService;
import com.zwbd.agentnexus.sdui.ui.DevicePrimaryUiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * @Author: wnli
 * @Date: 2026/3/12 16:03
 * @Desc:
 * Spring WebSocket 底层处理器
 * 负责接收底层连接事件和原始文本数据
 *
 * <p>从 WebSocket URL 查询参数 {@code deviceId}/{@code deviceid}/{@code device_id} 中提取设备标识，
 * 在连接建立时立即注册会话映射，消除二进制帧（音频块等）在
 * 首条文本消息到达前的"无主"窗口期。</p>
 */
@Slf4j
@Component
public class SduiWebSocketHandler extends TextWebSocketHandler {

    private static final String[] DEVICE_ID_PARAMS = {"deviceId", "deviceid", "device_id"};

    @Autowired
    private MessageRouter messageRouter;

    @Autowired
    private DeviceSessionManager sessionManager;

    @Autowired
    private AudioRecordSessionManager recordSessionManager;

    @Autowired
    private CommandService commandService;

    @Autowired
    private DevicePrimaryUiService primaryUiService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        DeviceIdQueryParam deviceIdParam = extractDeviceIdFromUri(session);
        if (deviceIdParam != null) {
            String deviceId = deviceIdParam.value();
            sessionManager.registerSession(deviceId, session);
            recordSessionManager.resumeSession(deviceId);
            dispatchDeferredAudioStop(deviceId);
            restorePrimaryUi(deviceId);
            log.info("WebSocket 连接已建立并绑定设备: session={}, device={}, queryParam={}",
                    session.getId(), deviceId, deviceIdParam.name());
        } else {
            log.info("新的 WebSocket 连接已建立: {} (URL 未携带 deviceId，"
                    + "将在首条文本消息后绑定)", session.getId());
        }
    }

    @Deprecated(forRemoval = true)
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        // 将原始 JSON 报文抛给路由器处理
        messageRouter.routeMessage(session, payload);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        byte[] frame = new byte[message.getPayload().remaining()];
        message.getPayload().get(frame);
        messageRouter.routeBinaryMessage(session, frame);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        // 连接断开时，挂起该设备正在进行的录音会话（如有），
        // 允许同一设备在宽限期内重连后继续追加 PCM 数据。
        String deviceId = sessionManager.getDeviceIdBySessionId(session.getId());
        if (deviceId != null
                && sessionManager.isSameSession(deviceId, session)
                && recordSessionManager.suspendSession(deviceId)) {
            log.info("设备 {} 断开连接，已挂起进行中的录音会话，等待重连恢复", deviceId);
        }

        log.info("WebSocket 连接已关闭: {}, 状态码: {}", session.getId(), status.getCode());
        // 清理会话
        sessionManager.removeSession(session);
    }

    /**
     * 从 WebSocket 会话的 URI 查询参数中提取 {@code deviceId}。
     *
     * @return deviceId，不存在时返回 null
     */
    private DeviceIdQueryParam extractDeviceIdFromUri(WebSocketSession session) {
        if (session.getUri() == null) return null;
        try {
            var params = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
            for (String paramName : DEVICE_ID_PARAMS) {
                String value = params.getFirst(paramName);
                if (value != null && !value.isBlank()) {
                    return new DeviceIdQueryParam(paramName, value.trim());
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to extract deviceId from WebSocket URI: {}", e.getMessage());
            return null;
        }
    }

    private void dispatchDeferredAudioStop(String deviceId) {
        if (!recordSessionManager.consumeStopOnReconnect(deviceId)) {
            return;
        }
        try {
            var result = commandService.dispatchCommand(deviceId, "audio.record.stop", java.util.Map.of());
            log.info("Deferred audio record stop dispatched after reconnect: device={}, sent={}, cmdId={}",
                    deviceId, result.sent(), result.cmdId());
            if (!result.sent()) {
                recordSessionManager.requestStopOnReconnect(deviceId, "deferred_stop_send_failed");
            }
        } catch (Exception e) {
            recordSessionManager.requestStopOnReconnect(deviceId, "deferred_stop_error");
            log.warn("Deferred audio record stop failed after reconnect: device={}, error={}",
                    deviceId, e.getMessage());
        }
    }

    private void restorePrimaryUi(String deviceId) {
        try {
            primaryUiService.restorePrimary(deviceId);
        } catch (Exception e) {
            log.warn("Failed to restore primary UI after device connect: device={}, error={}",
                    deviceId, e.getMessage());
        }
    }

    private record DeviceIdQueryParam(String name, String value) {
    }
}
