package com.zwbd.agentnexus.sdui.v2.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.zwbd.agentnexus.sdui.v2.protocol.Envelope;
import com.zwbd.agentnexus.sdui.v2.protocol.EnvelopeCodec;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolException;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnection;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnectionRegistry;
import com.zwbd.agentnexus.sdui.v2.session.DeviceHandshake;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v2 WebSocket 接入端点（{@code /ws/sdui/v2}）。
 *
 * <p>与旧 {@code SduiWebSocketHandler} 的差异：</p>
 * <ul>
 *   <li>不再对每条消息重复解析 {@code device_id}；设备标识只在连接建立时确定一次
 *       （04_PROTOCOL_MODEL.md §2）；</li>
 *   <li>不再在连接建立时自动重放 UI 或派发命令；接管后由 {@link V2SessionBootstrapService}
 *       按"先确认能力、再重发业务配置"的顺序处理；</li>
 *   <li>上行消息做连接代次校验，旧连接的迟到消息被明确忽略。</li>
 * </ul>
 *
 * <p>握手字段的承载位置为缺口 G1：优先取连接查询参数，缺失时允许由首条
 * {@code device.hello} 请求补齐。</p>
 */
@Slf4j
@Component
public class SduiV2WebSocketHandler extends TextWebSocketHandler {

    private static final String[] DEVICE_ID_PARAMS = {"deviceId", "deviceid", "device_id"};
    private static final String[] PROTOCOL_VERSION_PARAMS = {"protocolVersion", "protocol_version"};
    private static final String[] CAPABILITY_HASH_PARAMS = {"capabilityHash", "capability_hash"};

    private final DeviceConnectionRegistry connections;
    private final EnvelopeCodec codec;
    private final SduiV2MessageRouter router;
    private final DeviceSender sender;

    public SduiV2WebSocketHandler(DeviceConnectionRegistry connections,
                                  EnvelopeCodec codec,
                                  SduiV2MessageRouter router,
                                  DeviceSender sender) {
        this.connections = connections;
        this.codec = codec;
        this.router = router;
        this.sender = sender;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Map<String, List<String>> params = queryParams(session);
        String deviceId = firstNonNull(params, DEVICE_ID_PARAMS);
        if (deviceId == null) {
            log.info("v2 连接已建立，等待 device.hello 绑定设备: session={}", session.getId());
            return;
        }
        DeviceHandshake handshake = DeviceHandshake.of(deviceId,
                firstNonNull(params, PROTOCOL_VERSION_PARAMS),
                firstNonNull(params, CAPABILITY_HASH_PARAMS));
        connections.attach(deviceId, session, handshake);
        log.info("v2 设备连接已建立: device={}, session={}, protocolVersion={}, capabilityHash={}",
                deviceId, session.getId(), handshake.protocolVersion(), handshake.capabilityHash());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        String deviceId = connections.deviceIdOfSession(session.getId());
        if (deviceId == null) {
            deviceId = bindViaHello(session, payload);
            if (deviceId == null) {
                return;
            }
        }
        Long generation = currentGeneration(deviceId, session);
        if (generation == null) {
            return;
        }
        router.onText(deviceId, generation, payload);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String deviceId = connections.deviceIdOfSession(session.getId());
        if (deviceId == null) {
            log.debug("未绑定设备的连接发送二进制帧，已忽略: session={}", session.getId());
            return;
        }
        Long generation = currentGeneration(deviceId, session);
        if (generation == null) {
            return;
        }
        byte[] frame = new byte[message.getPayload().remaining()];
        message.getPayload().get(frame);
        router.onBinary(deviceId, generation, frame);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String deviceId = connections.deviceIdOfSession(session.getId());
        connections.detach(session);
        // 04§6：网络断开不触发业务 reset，也不清理终端业务状态；平台侧等待接管或超时兜底
        log.info("v2 连接已关闭: device={}, session={}, status={}", deviceId, session.getId(), status.getCode());
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 用首条 {@code device.hello} 绑定设备标识。
     *
     * @return 绑定后的设备标识；无法绑定返回 null
     */
    private String bindViaHello(WebSocketSession session, String payload) {
        Envelope envelope;
        try {
            envelope = codec.decode(payload);
        } catch (ProtocolException e) {
            log.warn("未绑定设备的连接发送了非法报文，已忽略: session={}, error={}", session.getId(), e.code());
            return null;
        }
        if (!(envelope instanceof Envelope.Request request) || !V2Names.DEVICE_HELLO.equals(request.name())) {
            log.warn("未绑定设备的首条消息不是 device.hello，已忽略: session={}", session.getId());
            return null;
        }
        JsonNode body = request.body();
        String deviceId = textOf(body, "deviceId", "device_id");
        if (deviceId == null) {
            log.warn("device.hello 缺少设备标识: session={}", session.getId());
            return null;
        }
        DeviceHandshake handshake = DeviceHandshake.of(deviceId,
                textOf(body, "protocolVersion", "protocol_version"),
                textOf(body, "capabilityHash", "capability_hash"));
        connections.attach(deviceId, session, handshake);
        sender.sendControl(deviceId, codec.encodeResult(request.id(), true, null));
        log.info("通过 device.hello 绑定设备: device={}, session={}", deviceId, session.getId());
        return deviceId;
    }

    /**
     * 代次校验。连接已被新连接接管时返回 null，消息被忽略（04_PROTOCOL_MODEL.md §2）。
     */
    private Long currentGeneration(String deviceId, WebSocketSession session) {
        Optional<DeviceConnection> current = connections.find(deviceId);
        if (current.isEmpty() || !session.getId().equals(current.get().sessionId())) {
            log.debug("忽略已被接管连接的迟到消息: device={}, session={}", deviceId, session.getId());
            return null;
        }
        long generation = current.get().getGeneration();
        if (!connections.isCurrent(deviceId, generation)) {
            return null;
        }
        return generation;
    }

    private static Map<String, List<String>> queryParams(WebSocketSession session) {
        if (session.getUri() == null) {
            return Map.of();
        }
        try {
            return UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
        } catch (RuntimeException e) {
            log.debug("解析连接参数失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String firstNonNull(Map<String, List<String>> params, String[] names) {
        for (String name : names) {
            List<String> values = params.get(name);
            if (values == null || values.isEmpty()) {
                continue;
            }
            String value = values.get(0);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String textOf(JsonNode body, String... names) {
        if (body == null) {
            return null;
        }
        for (String name : names) {
            JsonNode value = body.get(name);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
    }
}
