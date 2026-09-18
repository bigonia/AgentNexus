package com.zwbd.agentnexus.sdui.v2.transport;

import com.zwbd.agentnexus.sdui.v2.V2ProtocolProperties;
import com.zwbd.agentnexus.sdui.v2.protocol.EnvelopeCodec;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnectionRegistry;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnectionTakenOverEvent;
import com.zwbd.agentnexus.sdui.v2.session.PendingRequestRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台 → 终端 的请求发起与结果等待。
 *
 * <p>统一所有出站控制的形态：业务配置、显示、音频、系统命令都通过本服务下发，
 * 不再存在旧实现中 {@code cmd/control} + {@code cmd/control_ack} 的专用路径，也不再有
 * "服务端自行处理"的动作分支（旧的 {@code dispatchServerHandledCommand}）。</p>
 *
 * <p>生命周期遵循 04_PROTOCOL_MODEL.md：</p>
 *
 * <pre>
 * 登记 → 发送 → 收到一次性 Result(id, ok) → 完成
 *              → 超时                       → TIMEOUT（不自动重放）
 *              → 连接被新连接接管            → NOT_CONNECTED（不等超时）
 * </pre>
 */
@Slf4j
@Service
public class PlatformRequestService {

    /** 请求终态。{@code error} 取值来自 {@code ProtocolErrors}。 */
    public record Outcome(boolean ok, String error) {

        private static final Outcome SUCCESS = new Outcome(true, null);

        public static Outcome success() {
            return SUCCESS;
        }

        public static Outcome failure(String error) {
            return new Outcome(false, error);
        }

        public boolean isTimeout() {
            return ProtocolErrors.TIMEOUT.equals(error);
        }
    }

    private final DeviceConnectionRegistry connections;
    private final PendingRequestRegistry pendingRequests;
    private final DeviceSender sender;
    private final EnvelopeCodec codec;
    private final V2ProtocolProperties properties;

    private final Map<String, CompletableFuture<Outcome>> futures = new ConcurrentHashMap<>();

    public PlatformRequestService(DeviceConnectionRegistry connections,
                                  PendingRequestRegistry pendingRequests,
                                  DeviceSender sender,
                                  EnvelopeCodec codec,
                                  V2ProtocolProperties properties) {
        this.connections = connections;
        this.pendingRequests = pendingRequests;
        this.sender = sender;
        this.codec = codec;
        this.properties = properties;
    }

    public CompletableFuture<Outcome> send(String deviceId, String name, Object body) {
        return send(deviceId, name, body, properties.getRequestTimeoutMs());
    }

    public CompletableFuture<Outcome> send(String deviceId, String name, Object body, long timeoutMs) {
        if (!connections.isOnline(deviceId)) {
            return CompletableFuture.completedFuture(Outcome.failure(ProtocolErrors.NOT_CONNECTED));
        }

        String requestId = newRequestId();
        String key = key(deviceId, requestId);
        CompletableFuture<Outcome> future = new CompletableFuture<>();
        futures.put(key, future);
        pendingRequests.register(deviceId, requestId, name, timeoutMs);

        boolean sent = sender.sendControl(deviceId, codec.encodeRequest(requestId, name, body));
        if (!sent) {
            PendingRequestRegistry.Completion completion =
                    pendingRequests.complete(deviceId, requestId, false, ProtocolErrors.QUEUE_FULL).orElse(null);
            if (completion != null) {
                settle(key, Outcome.failure(ProtocolErrors.QUEUE_FULL));
            } else {
                futures.remove(key);
                future.complete(Outcome.failure(ProtocolErrors.QUEUE_FULL));
            }
            log.warn("平台请求发送失败: device={}, name={}, id={}, error={}",
                    deviceId, name, requestId, ProtocolErrors.QUEUE_FULL);
        } else {
            log.debug("平台请求已下发: device={}, name={}, id={}", deviceId, name, requestId);
        }
        return future;
    }

    /** 终端返回结果时由路由器调用。 */
    public void onResult(String deviceId, String id, boolean ok, String error) {
        pendingRequests.complete(deviceId, id, ok, error)
                .ifPresentOrElse(
                        completion -> settle(key(completion.deviceId(), completion.id()),
                                new Outcome(completion.ok(), completion.error())),
                        () -> log.debug("收到未登记的请求结果（迟到或已在接管时结束）: device={}, id={}", deviceId, id));
    }

    /**
     * 连接被新连接接管：旧连接上所有未完成请求立即结束。
     *
     * <p>04_PROTOCOL_MODEL.md §2 要求旧连接后续消息全部忽略；若只依赖超时，
     * 调用方会在整个超时窗口内挂起。见 {@code 12_DESIGN_NOTES.md} §4.1。</p>
     */
    @EventListener
    public void onConnectionTakenOver(DeviceConnectionTakenOverEvent event) {
        List<PendingRequestRegistry.Completion> completions =
                pendingRequests.failAll(event.deviceId(), ProtocolErrors.NOT_CONNECTED);
        for (PendingRequestRegistry.Completion completion : completions) {
            settle(key(completion.deviceId(), completion.id()),
                    Outcome.failure(ProtocolErrors.NOT_CONNECTED));
        }
    }

    @Scheduled(fixedDelayString = "${sdui.v2.request-timeout-scan-ms:1000}")
    public void sweepTimeouts() {
        List<PendingRequestRegistry.Completion> expired = pendingRequests.sweep();
        for (PendingRequestRegistry.Completion completion : expired) {
            settle(key(completion.deviceId(), completion.id()), Outcome.failure(ProtocolErrors.TIMEOUT));
            log.warn("平台请求超时: device={}, name={}, id={}",
                    completion.deviceId(), completion.name(), completion.id());
        }
    }

    public int pendingCount() {
        return pendingRequests.pendingCount();
    }

    private void settle(String key, Outcome outcome) {
        CompletableFuture<Outcome> future = futures.remove(key);
        if (future != null) {
            future.complete(outcome);
        }
    }

    private static String key(String deviceId, String requestId) {
        return deviceId + '|' + requestId;
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
