package com.zwbd.agentnexus.sdui;

import com.zwbd.agentnexus.sdui.service.audio.AudioRecordSessionManager;
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
 * <p>从 WebSocket URL 查询参数 {@code deviceId} 中提取设备标识，
 * 在连接建立时立即注册会话映射，消除二进制帧（音频块等）在
 * 首条文本消息到达前的"无主"窗口期。</p>
 */
@Slf4j
@Component
public class SduiWebSocketHandler extends TextWebSocketHandler {

    private static final String DEVICE_ID_PARAM = "deviceId";

    @Autowired
    private MessageRouter messageRouter;

    @Autowired
    private DeviceSessionManager sessionManager;

    @Autowired
    private AudioRecordSessionManager recordSessionManager;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String deviceId = extractDeviceIdFromUri(session);
        if (deviceId != null) {
            sessionManager.registerSession(deviceId, session);
            log.info("WebSocket 连接已建立并绑定设备: session={}, device={}",
                    session.getId(), deviceId);
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
        // 连接断开时，取消该设备正在进行的录音会话（如有），
        // 避免重连后 PCM 数据追加到旧缓冲区导致数据损坏。
        String deviceId = sessionManager.getDeviceIdBySessionId(session.getId());
        if (deviceId != null && recordSessionManager.isRecording(deviceId)) {
            recordSessionManager.cancelSession(deviceId);
            log.info("设备 {} 断开连接，已取消进行中的录音会话", deviceId);
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
    private String extractDeviceIdFromUri(WebSocketSession session) {
        if (session.getUri() == null) return null;
        try {
            var params = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
            return params.getFirst(DEVICE_ID_PARAM);
        } catch (Exception e) {
            log.debug("Failed to extract deviceId from WebSocket URI: {}", e.getMessage());
            return null;
        }
    }
}