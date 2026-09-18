package com.zwbd.agentnexus.sdui.v2.session;

import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台侧未完成请求登记表。
 *
 * <p>04_PROTOCOL_MODEL.md §5 规定「终端对整个请求只返回一次最终成功或错误」，因此平台侧
 * 每个出站请求只需要一个登记项和一个终态。§9 又要求"对未收到结果的普通请求执行超时处理"，
 * 且 §6 规定断线中的请求"不自动重放"。</p>
 *
 * <p>文档没有定义登记结构，这是平台侧必要的自实现（见缺口说明与
 * {@code 12_DESIGN_NOTES.md} §4.1）：连接被接管时，旧连接上所有未完成请求立即按失败结束，
 * 而不是等待超时，这样调用方无需感知连接代次。</p>
 */
@Slf4j
@Component
public class PendingRequestRegistry {

    private final Map<String, Map<String, PendingRequest>> byDevice = new ConcurrentHashMap<>();

    /** 登记一个未完成请求。同一 {@code id} 重复登记视为编码错误并直接覆盖。 */
    public PendingRequest register(String deviceId, String id, String name, long timeoutMs) {
        PendingRequest request = new PendingRequest(id, deviceId, name, now() + timeoutMs);
        Map<String, PendingRequest> deviceRequests = byDevice.computeIfAbsent(deviceId, key -> new ConcurrentHashMap<>());
        PendingRequest replaced = deviceRequests.put(id, request);
        if (replaced != null) {
            log.warn("重复的请求 id 被覆盖: device={}, id={}, name={}", deviceId, id, name);
        }
        return request;
    }

    /**
     * 用终端返回的结果完成一个请求。
     *
     * @return 完成的请求；若 id 未登记（迟到、重复或已被接管清理）返回空
     */
    public Optional<Completion> complete(String deviceId, String id, boolean ok, String error) {
        Map<String, PendingRequest> deviceRequests = byDevice.get(deviceId);
        if (deviceRequests == null) {
            return Optional.empty();
        }
        PendingRequest request = deviceRequests.remove(id);
        if (request == null) {
            return Optional.empty();
        }
        return Optional.of(new Completion(deviceId, id, request.name(), ok, ok ? null : error));
    }

    /**
     * 把某设备全部未完成请求按指定错误结束。
     *
     * <p>用于连接被新连接接管：此时旧连接不会再返回结果。</p>
     */
    public List<Completion> failAll(String deviceId, String error) {
        Map<String, PendingRequest> deviceRequests = byDevice.remove(deviceId);
        if (deviceRequests == null || deviceRequests.isEmpty()) {
            return List.of();
        }
        List<Completion> completions = new ArrayList<>(deviceRequests.size());
        for (PendingRequest request : deviceRequests.values()) {
            completions.add(new Completion(deviceId, request.id(), request.name(), false, error));
        }
        log.info("设备接管清理未完成请求: device={}, count={}, error={}", deviceId, completions.size(), error);
        return completions;
    }

    /** 扫描并结束已超时的请求。 */
    public List<Completion> sweep() {
        long now = now();
        List<Completion> expired = new ArrayList<>();
        for (Map.Entry<String, Map<String, PendingRequest>> entry : byDevice.entrySet()) {
            String deviceId = entry.getKey();
            Map<String, PendingRequest> deviceRequests = entry.getValue();
            for (PendingRequest request : deviceRequests.values()) {
                if (request.deadlineMs() <= now && deviceRequests.remove(request.id(), request)) {
                    expired.add(new Completion(deviceId, request.id(), request.name(), false, ProtocolErrors.TIMEOUT));
                }
            }
        }
        return expired;
    }

    public int pendingCount(String deviceId) {
        Map<String, PendingRequest> deviceRequests = byDevice.get(deviceId);
        return deviceRequests == null ? 0 : deviceRequests.size();
    }

    public int pendingCount() {
        return byDevice.values().stream().mapToInt(Map::size).sum();
    }

    public void clear() {
        byDevice.clear();
    }

    protected long now() {
        return System.currentTimeMillis();
    }

    /** 未完成请求。 */
    public record PendingRequest(String id, String deviceId, String name, long deadlineMs) {}

    /** 请求终态。{@code error} 取值来自 {@code ProtocolErrors}。 */
    public record Completion(String deviceId, String id, String name, boolean ok, String error) {

        public boolean isTimeout() {
            return ProtocolErrors.TIMEOUT.equals(error);
        }
    }
}
