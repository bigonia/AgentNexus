package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.debug.DebugArtifactStore;
import com.zwbd.agentnexus.sdui.debug.DebugSessionHandle;
import com.zwbd.agentnexus.sdui.debug.DebugSessionService;
import com.zwbd.agentnexus.sdui.debug.node.CapabilityNodeTestService;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilityQueryService;
import com.zwbd.agentnexus.sdui.v2.debug.DebugStreamHub;
import com.zwbd.agentnexus.sdui.v2.debug.PlatformRequestDispatcher;
import com.zwbd.agentnexus.sdui.v2.display.DisplayCommandService;
import com.zwbd.agentnexus.sdui.v2.display.DisplaySessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 调试域 API。
 *
 * <p>绕过工作流，直接对设备下达 v2 请求。它<b>不是第二条业务通道</b>：请求经同一个
 * {@link PlatformRequestDispatcher} 走同一条出站路径与同一套状态机，因此调试的结论对业务成立。</p>
 *
 * <p>这里不会出现"平台自己处理"的动作分支。终端只在能力 Schema 里声明 {@code binding} 的动作，
 * 平台发不出请求，接口会如实返回 {@code unsupported}，而不是伪造一条下行。</p>
 *
 * <p>管理面边界见 {@code docs/sdui/PLATFORM_REFACTOR.md} §3；端点明细以本控制器和 OpenAPI 为准。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sdui/debug")
@RequiredArgsConstructor
public class DebugController {

    private final PlatformRequestDispatcher dispatcher;
    private final CapabilityQueryService capabilities;
    private final DisplaySessionService displaySessions;
    private final DisplayCommandService displayCommands;
    private final DebugStreamHub streamHub;
    private final DebugSessionService sessionService;
    private final DebugArtifactStore artifactStore;
    private final CapabilityNodeTestService nodeTestService;

    // ── 请求下发 ──

    /** 下达一次 v2 请求：{@code {name, params}}。 */
    @PostMapping("/{deviceId}/request")
    public ApiResponse<PlatformRequestDispatcher.Result> request(@PathVariable String deviceId,
                                                               @RequestBody Map<String, Object> body) {
        String name = body.get("name") instanceof String s ? s : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> params = body.get("params") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        return ApiResponse.ok(dispatcher.dispatch(deviceId, name, params));
    }

    /** 下发主视图：{@code {mode: section|image|canvas, ...}}。v2 只有完整替换，没有增量 Patch。 */
    @PostMapping("/{deviceId}/view")
    public ApiResponse<PlatformRequestDispatcher.Result> publishView(@PathVariable String deviceId,
                                                                    @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(dispatcher.publishView(deviceId, body));
    }

    /** 当前显示会话状态：模式、已收字节、拒帧数。 */
    @GetMapping("/{deviceId}/view/state")
    public ApiResponse<Map<String, Object>> viewState(@PathVariable String deviceId) {
        DisplaySessionService.DeviceDisplayState state = displaySessions.snapshot(deviceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("online", capabilities.online(deviceId));
        result.put("mode", state.mode() == null ? null : state.mode().name().toLowerCase());
        result.put("imageBytesReceived", state.imageBytesReceived());
        result.put("canvasFramesReceived", state.canvasFramesReceived());
        result.put("canvasFramesRejected", state.canvasFramesRejected());
        displaySessions.imageSpec(deviceId).ifPresent(spec -> result.put("imageSpec", Map.of(
                "width", spec.width(), "height", spec.height(),
                "paletteSize", spec.paletteSize(), "expectedBytes", spec.expectedBytes())));
        displaySessions.canvasSpec(deviceId).ifPresent(spec -> result.put("canvasSpec", Map.of(
                "width", spec.width(), "height", spec.height(),
                "paletteSize", spec.paletteSize(), "maxFps", spec.maxFps() == null ? 0 : spec.maxFps())));
        return ApiResponse.ok(result);
    }

    /** 清空显示会话，不向设备下发任何东西。 */
    @DeleteMapping("/{deviceId}/view/state")
    public ApiResponse<Map<String, Object>> clearViewState(@PathVariable String deviceId) {
        displayCommands.clear(deviceId);
        return viewState(deviceId);
    }

    // ── 请求目录与历史 ──

    /** 可下达的请求目录：设备 Schema 中声明了 {@code usableIn=request} 的动作。 */
    @GetMapping("/{deviceId}/requests")
    public ApiResponse<Map<String, Object>> availableRequests(@PathVariable String deviceId) {
        List<Map<String, Object>> requestable = capabilities.actions(deviceId).stream()
                .filter(action -> Boolean.TRUE.equals(action.get("usableInRequest")))
                .toList();
        List<Map<String, Object>> bindingOnly = capabilities.actions(deviceId).stream()
                .filter(action -> Boolean.TRUE.equals(action.get("usableInBinding")))
                .filter(action -> !Boolean.TRUE.equals(action.get("usableInRequest")))
                .map(action -> Map.<String, Object>of(
                        "name", action.get("name"),
                        "reason", "只声明可用于本地响应序列，平台发不出请求"))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("online", capabilities.online(deviceId));
        result.put("requests", requestable);
        result.put("bindingOnly", bindingOnly);
        return ApiResponse.ok(result);
    }

    /** 调试下达的请求历史（内存环形缓冲，最新在前）。 */
    @GetMapping("/{deviceId}/requests/history")
    public ApiResponse<Map<String, Object>> requestHistory(@PathVariable String deviceId,
                                                          @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(Map.of(
                "deviceId", deviceId,
                "history", streamHub.journal(deviceId, limit)));
    }

    /** 单条请求详情。 */
    @GetMapping("/{deviceId}/requests/{requestId}")
    public ApiResponse<Map<String, Object>> requestDetail(@PathVariable String deviceId,
                                                          @PathVariable String requestId) {
        return streamHub.entry(deviceId, requestId)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error(40400, "request not found: " + requestId));
    }

    /** 请求结果 SSE。 */
    @GetMapping(value = "/{deviceId}/requests/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter requestStream(@PathVariable String deviceId) {
        return streamHub.subscribeRequests(deviceId);
    }

    /** 终端事件 SSE：交互上报、业务清理、连接接管。 */
    @GetMapping(value = "/{deviceId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventStream(@PathVariable String deviceId) {
        return streamHub.subscribeEvents(deviceId);
    }

    // ── 节点测试 ──

    @PostMapping("/{deviceId}/node-tests/input")
    public ApiResponse<Map<String, Object>> createInputNodeTest(@PathVariable String deviceId,
                                                                @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(nodeTestService.createInputTest(deviceId, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @GetMapping("/{deviceId}/node-tests/{testId}")
    public ApiResponse<Map<String, Object>> getNodeTest(@PathVariable String deviceId,
                                                        @PathVariable String testId) {
        return nodeTestService.getTest(deviceId, testId)
                .map(ApiResponse::ok)
                .orElse(ApiResponse.error(40400, "node test not found: " + testId));
    }

    @PostMapping("/{deviceId}/node-tests/output")
    public ApiResponse<Map<String, Object>> executeOutputNodeTest(@PathVariable String deviceId,
                                                                  @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(nodeTestService.executeOutputTest(deviceId, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    // ── 调试会话 ──

    @GetMapping("/{deviceId}/sessions")
    public ApiResponse<Map<String, Object>> listSessions(@PathVariable String deviceId) {
        List<Map<String, Object>> items = sessionService.listSessions(deviceId).stream()
                .map(this::sessionToMap)
                .toList();
        return ApiResponse.ok(Map.of(
                "deviceId", deviceId,
                "online", capabilities.online(deviceId),
                "sessions", items));
    }

    @GetMapping("/{deviceId}/sessions/{sessionId}")
    public ApiResponse<Map<String, Object>> getSession(@PathVariable String deviceId,
                                                       @PathVariable String sessionId) {
        return sessionService.getSession(deviceId, sessionId)
                .map(s -> ApiResponse.ok(sessionToMap(s)))
                .orElse(ApiResponse.error(40400, "session not found: " + sessionId));
    }

    // ── 调试产物 ──

    @GetMapping("/{deviceId}/artifacts/{artifactId}")
    public ApiResponse<Map<String, Object>> getArtifact(@PathVariable String deviceId,
                                                        @PathVariable String artifactId) {
        Optional<DebugArtifactStore.Artifact> opt = artifactStore.get(deviceId, artifactId);
        if (opt.isEmpty()) {
            return ApiResponse.error(40400, "artifact not found: " + artifactId);
        }
        DebugArtifactStore.Artifact a = opt.get();
        Map<String, Object> data = new LinkedHashMap<>(a.metadata());
        data.put("deviceId", deviceId);
        data.put("artifactId", a.artifactId());
        data.put("type", a.type());
        data.put("mimeType", a.mimeType());
        data.put("blobBytes", a.blob() != null ? a.blob().length : 0);
        data.put("createdAt", a.createdAt());
        data.put("createdAtIso", Instant.ofEpochMilli(a.createdAt()).toString());
        return ApiResponse.ok(data);
    }

    @GetMapping("/{deviceId}/artifacts/{artifactId}/blob")
    public ResponseEntity<byte[]> getArtifactBlob(@PathVariable String deviceId,
                                                   @PathVariable String artifactId) {
        Optional<DebugArtifactStore.Artifact> opt = artifactStore.get(deviceId, artifactId);
        if (opt.isEmpty() || opt.get().blob() == null) {
            return ResponseEntity.notFound().build();
        }
        DebugArtifactStore.Artifact a = opt.get();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(a.mimeType()));
        headers.setContentDispositionFormData("inline", deviceId + "-" + artifactId);
        headers.setContentLength(a.blob().length);
        return ResponseEntity.ok().headers(headers).body(a.blob());
    }

    // ── 内部 ──

    private Map<String, Object> sessionToMap(DebugSessionHandle s) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", s.sessionId());
        data.put("deviceId", s.deviceId());
        data.put("type", s.type());
        data.put("status", s.status());
        data.put("startedAt", s.startedAt());
        data.put("startedAtIso", Instant.ofEpochMilli(s.startedAt()).toString());
        data.put("elapsedMs", System.currentTimeMillis() - s.startedAt());
        data.putAll(s.metrics());
        return data;
    }
}
