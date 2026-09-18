package com.zwbd.agentnexus.sdui.v2.debug;

import com.zwbd.agentnexus.sdui.v2.business.BusinessClearedEvent;
import com.zwbd.agentnexus.sdui.v2.business.BusinessInteraction;
import com.zwbd.agentnexus.sdui.v2.session.DeviceConnectionTakenOverEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调试域的实时视图：请求日志与两条 SSE 流。
 *
 * <p>它只承载<b>调试可观测性</b>，不参与任何业务状态。因此有两处刻意为之的边界：</p>
 *
 * <ul>
 *   <li><b>请求日志只记录调试下达的请求</b>（内存环形缓冲，重启即失）。工作流驱动的执行不看这里，
 *       看 {@code /node-workflows/{id}/runs/{runId}}——那是有持久化运行记录的正式路径。</li>
 *   <li><b>事件流只转发平台内部已有事件</b>（交互上报、业务清理、连接接管），不自造事件类型。
 *       平台不镜像终端的微观执行状态，所以这里也不会看到"按钮按下"这类终端内部过程。</li>
 * </ul>
 */
@Slf4j
@Service
public class DebugStreamHub {

    private static final int JOURNAL_LIMIT = 200;
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<String, Deque<Map<String, Object>>> journals = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> eventEmitters = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> requestEmitters = new ConcurrentHashMap<>();

    // ── 请求日志 ───────────────────────────────────────────────────────────

    /** 记录一条调试请求。返回值即入日志的条目。 */
    public Map<String, Object> record(String deviceId, Map<String, Object> entry) {
        Deque<Map<String, Object>> journal = journals.computeIfAbsent(deviceId, key -> new ArrayDeque<>());
        synchronized (journal) {
            journal.addFirst(entry);
            while (journal.size() > JOURNAL_LIMIT) {
                journal.removeLast();
            }
        }
        broadcast(requestEmitters, deviceId, "request", entry);
        return entry;
    }

    /** 最近请求，最新在前。 */
    public List<Map<String, Object>> journal(String deviceId, int limit) {
        Deque<Map<String, Object>> journal = journals.get(deviceId);
        if (journal == null) {
            return List.of();
        }
        int size = Math.max(1, Math.min(limit, JOURNAL_LIMIT));
        synchronized (journal) {
            return new ArrayList<>(journal).subList(0, Math.min(size, journal.size()));
        }
    }

    /** 按 requestId 查一条请求。 */
    public Optional<Map<String, Object>> entry(String deviceId, String requestId) {
        return journal(deviceId, JOURNAL_LIMIT).stream()
                .filter(item -> requestId != null && requestId.equals(item.get("requestId")))
                .findFirst();
    }

    // ── SSE ────────────────────────────────────────────────────────────────

    public SseEmitter subscribeEvents(String deviceId) {
        return subscribe(eventEmitters, deviceId);
    }

    public SseEmitter subscribeRequests(String deviceId) {
        return subscribe(requestEmitters, deviceId);
    }

    // ── 平台内部事件转发 ───────────────────────────────────────────────────

    @EventListener
    public void onInteraction(BusinessInteraction interaction) {
        broadcast(eventEmitters, interaction.deviceId(), "platform.interaction",
                payload("platform.interaction", interaction.deviceId(),
                        "token", interaction.token(),
                        "triggerId", interaction.triggerId(),
                        "contextRef", interaction.contextRef(),
                        "receivedAt", interaction.receivedAt() == null ? null : interaction.receivedAt().toString()));
    }

    @EventListener
    public void onBusinessCleared(BusinessClearedEvent event) {
        broadcast(eventEmitters, event.deviceId(), "business.cleared",
                payload("business.cleared", event.deviceId(),
                        "reason", event.reason() == null ? null : event.reason().name()));
    }

    @EventListener
    public void onConnectionTakenOver(DeviceConnectionTakenOverEvent event) {
        broadcast(eventEmitters, event.deviceId(), "device.connection",
                payload("device.connection", event.deviceId(),
                        "previousGeneration", event.previousGeneration(),
                        "currentGeneration", event.currentGeneration(),
                        "firstConnection", event.isFirstConnection()));
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private SseEmitter subscribe(Map<String, List<SseEmitter>> registry, String deviceId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        registry.computeIfAbsent(deviceId, key -> new ArrayList<>()).add(emitter);
        Runnable cleanup = () -> {
            List<SseEmitter> emitters = registry.get(deviceId);
            if (emitters != null) {
                emitters.remove(emitter);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("open").data(Map.of("deviceId", deviceId)));
        } catch (IOException e) {
            cleanup.run();
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private void broadcast(Map<String, List<SseEmitter>> registry, String deviceId,
                           String eventName, Map<String, Object> data) {
        List<SseEmitter> emitters = registry.get(deviceId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : List.copyOf(emitters)) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (Exception e) {
                log.debug("调试流推送失败，移除订阅者: device={}, event={}", deviceId, eventName);
                emitters.remove(emitter);
            }
        }
    }

    /** 构造事件负载，跳过 null 值——前端不必区分"字段缺失"与"值为空"。 */
    private static Map<String, Object> payload(String event, String deviceId, Object... keyValues) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event", event);
        data.put("deviceId", deviceId);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                data.put(String.valueOf(keyValues[i]), value);
            }
        }
        return data;
    }
}
