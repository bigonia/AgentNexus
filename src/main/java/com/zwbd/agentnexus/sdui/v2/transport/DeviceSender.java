package com.zwbd.agentnexus.sdui.v2.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.zwbd.agentnexus.sdui.v2.V2ProtocolProperties;
import com.zwbd.agentnexus.sdui.v2.protocol.BinaryDataType;
import com.zwbd.agentnexus.sdui.v2.protocol.BinaryFrameCodecV2;
import com.zwbd.agentnexus.sdui.v2.protocol.EnvelopeCodec;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolException;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnection;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnectionRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * v2 出站发送器。
 *
 * <p>04_PROTOCOL_MODEL.md §8 要求"单个 Binary chunk 必须有大小上限、控制消息优先于 Canvas 和遥测、
 * 所有发送与接收队列有固定上限"。本类实现这三条约束的具体形态。</p>
 *
 * <p>两类发送语义刻意分开：</p>
 * <ul>
 *   <li>{@link #sendControl}/{@link #sendBinary}：有界的同步发送。队列满时明确失败，不阻塞等待，
 *       也不无限缓存（02_SYSTEM_BOUNDARY.md §3「不允许通过无限缓存维持表面成功」）。</li>
 *   <li>{@link #sendCanvasFrame}：非阻塞的"只保留最新帧"发送。消费不足时丢弃旧帧，
 *       对应 03_UI_MODEL.md §4.3 与 04_PROTOCOL_MODEL.md §8。</li>
 * </ul>
 */
@Slf4j
@Component
public class DeviceSender {

    private final DeviceConnectionRegistry connections;
    private final EnvelopeCodec codec;
    private final V2ProtocolProperties properties;

    /** 每设备在途控制消息计数，用于实现有界队列。 */
    private final Map<String, AtomicInteger> inFlightControl = new ConcurrentHashMap<>();

    /** 每设备 Canvas 待发帧槽位，深度固定为 1。 */
    private final Map<String, AtomicReference<byte[]>> canvasSlots = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> canvasDraining = new ConcurrentHashMap<>();
    private final AtomicLong droppedCanvasFrames = new AtomicLong();

    private final ExecutorService canvasExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "sdui-v2-canvas-sender");
        thread.setDaemon(true);
        return thread;
    });

    public DeviceSender(DeviceConnectionRegistry connections, EnvelopeCodec codec, V2ProtocolProperties properties) {
        this.connections = connections;
        this.codec = codec;
        this.properties = properties;
    }

    // ── 控制面 ──────────────────────────────────────────────────────────────

    /** 发送一个 JSON 控制报文（请求或结果）。队列满或设备离线时返回 false。 */
    public boolean sendControl(String deviceId, JsonNode envelope) {
        return sendText(deviceId, codec.toJson(envelope));
    }

    public boolean sendText(String deviceId, String json) {
        Optional<DeviceConnection> connection = connections.find(deviceId);
        if (connection.isEmpty() || !connection.get().isOpen()) {
            log.debug("设备不在线，控制消息丢弃: device={}", deviceId);
            return false;
        }
        AtomicInteger inFlight = inFlightControl.computeIfAbsent(deviceId, key -> new AtomicInteger());
        int queued = inFlight.incrementAndGet();
        if (queued > properties.getMaxControlQueue()) {
            inFlight.decrementAndGet();
            log.warn("设备控制队列已满，拒绝发送: device={}, queued={}, limit={}",
                    deviceId, queued - 1, properties.getMaxControlQueue());
            return false;
        }
        try {
            return writeText(connection.get(), json);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    // ── 数据面 ──────────────────────────────────────────────────────────────

    /** 发送一个二进制帧。超出单帧上限时抛出 {@code frame_too_large}。 */
    public boolean sendBinary(String deviceId, BinaryDataType dataType, byte[] payload) {
        byte[] frame = BinaryFrameCodecV2.encode(dataType, payload, properties.getMaxBinaryFrameBytes());
        return sendBinaryFrame(deviceId, frame);
    }

    private boolean sendBinaryFrame(String deviceId, byte[] frame) {
        Optional<DeviceConnection> connection = connections.find(deviceId);
        if (connection.isEmpty() || !connection.get().isOpen()) {
            return false;
        }
        AtomicInteger inFlight = inFlightControl.computeIfAbsent(deviceId, key -> new AtomicInteger());
        int queued = inFlight.incrementAndGet();
        if (queued > properties.getMaxControlQueue()) {
            inFlight.decrementAndGet();
            log.warn("设备发送队列已满，二进制帧被丢弃: device={}, dataType 帧", deviceId);
            return false;
        }
        try {
            return writeBinary(connection.get(), frame);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /**
     * 非阻塞发送一帧 Canvas 数据。
     *
     * <p>实现 03_UI_MODEL.md §4.3「消费速度不足时不能无限排队，应优先保留最新帧」：
     * 待发槽位深度固定为 1，新帧覆盖尚未发出的旧帧并计数。</p>
     */
    public void sendCanvasFrame(String deviceId, byte[] frame) {
        AtomicReference<byte[]> slot = canvasSlots.computeIfAbsent(deviceId, key -> new AtomicReference<>());
        byte[] replaced = slot.getAndSet(frame);
        if (replaced != null) {
            droppedCanvasFrames.incrementAndGet();
        }
        AtomicBoolean draining = canvasDraining.computeIfAbsent(deviceId, key -> new AtomicBoolean());
        if (draining.compareAndSet(false, true)) {
            canvasExecutor.execute(() -> drainCanvas(deviceId, slot, draining));
        }
    }

    private void drainCanvas(String deviceId, AtomicReference<byte[]> slot, AtomicBoolean draining) {
        try {
            while (true) {
                byte[] frame = slot.getAndSet(null);
                if (frame == null) {
                    draining.set(false);
                    // 关闭窗口内可能刚写入新帧，再次抢占以确保不遗留
                    if (slot.get() != null && draining.compareAndSet(false, true)) {
                        continue;
                    }
                    return;
                }
                try {
                    sendBinary(deviceId, BinaryDataType.CANVAS, frame);
                } catch (ProtocolException e) {
                    log.warn("Canvas 帧发送被拒绝: device={}, error={}", deviceId, e.code());
                }
            }
        } catch (RuntimeException e) {
            draining.set(false);
            log.warn("Canvas 发送循环异常退出: device={}", deviceId, e);
        }
    }

    public long droppedCanvasFrames() {
        return droppedCanvasFrames.get();
    }

    public int inFlight(String deviceId) {
        AtomicInteger counter = inFlightControl.get(deviceId);
        return counter == null ? 0 : counter.get();
    }

    public void forgetDevice(String deviceId) {
        inFlightControl.remove(deviceId);
        canvasSlots.remove(deviceId);
        canvasDraining.remove(deviceId);
    }

    // ── 底层写 ──────────────────────────────────────────────────────────────

    private boolean writeText(DeviceConnection connection, String json) {
        WebSocketSession session = connection.getSession();
        try {
            synchronized (session) {
                if (!session.isOpen()) {
                    return false;
                }
                session.sendMessage(new TextMessage(json));
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            log.warn("发送文本消息失败: device={}, error={}", connection.getDeviceId(), e.getMessage());
            return false;
        }
    }

    private boolean writeBinary(DeviceConnection connection, byte[] frame) {
        WebSocketSession session = connection.getSession();
        try {
            synchronized (session) {
                if (!session.isOpen()) {
                    return false;
                }
                session.sendMessage(new BinaryMessage(frame));
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            log.warn("发送二进制帧失败: device={}, error={}", connection.getDeviceId(), e.getMessage());
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        canvasExecutor.shutdownNow();
    }
}
