package com.zwbd.agentnexus.sdui.v2.debug;

import com.zwbd.agentnexus.sdui.v2.audio.AudioCommandService;
import com.zwbd.agentnexus.sdui.v2.business.BusinessConfigService;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilityQueryService;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilitySchemaV2;
import com.zwbd.agentnexus.sdui.v2.display.DisplayCommandService;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import com.zwbd.agentnexus.sdui.v2.system.SystemCommandService;
import com.zwbd.agentnexus.sdui.v2.transport.PlatformRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 调试域的下行出口。
 *
 * <p>调试面板与工作流走的是<b>同一条出站路径</b>（{@code PlatformRequestService}）与同一套状态机
 * （显示会话、音频流方向、系统期望值）。差别只有"谁决定动作"：调试是人选的，业务是工作流算出来的。
 * 如果调试另开一条捷径，就会出现"调试能通、业务失败"这种最难排查的情况。</p>
 *
 * <p>动作可达性按设备能力 Schema 判定：Schema 已声明该动作时要求 {@code usableIn} 含
 * {@code request}；Schema 未声明时放行但在结果里标注 {@code declared=false}，便于在终端固件尚未
 * 声明能力时仍能调试底层通道。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformRequestDispatcher {

    private static final long AWAIT_TIMEOUT_MS = 8000L;

    private final PlatformRequestService requests;
    private final DisplayCommandService display;
    private final AudioCommandService audio;
    private final SystemCommandService system;
    private final BusinessConfigService business;
    private final CapabilityQueryService capabilities;
    private final DebugStreamHub hub;

    /** 一次调试请求的结果。{@code extra} 承载服务侧的附加信息（如期望系统状态）。 */
    public record Result(String requestId, String name, boolean ok, String error,
                         boolean declared, boolean reachable, Map<String, Object> extra) {}

    /**
     * 下达一次 v2 请求。
     *
     * @param name   v2 请求名，取值见 {@code V2Names}
     * @param params 请求参数
     */
    public Result dispatch(String deviceId, String name, Map<String, Object> params) {
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        String requestId = UUID.randomUUID().toString();

        Result result = execute(deviceId, requestId, name, safeParams);
        hub.record(deviceId, journalEntry(requestId, name, safeParams, result));
        return result;
    }

    private Result execute(String deviceId, String requestId, String name, Map<String, Object> params) {
        if (name == null || name.isBlank()) {
            return failure(requestId, name, "invalid_value", "name is required");
        }
        if (!capabilities.online(deviceId)) {
            return failure(requestId, name, ProtocolErrors.NOT_CONNECTED, "device is offline");
        }

        CapabilitySchemaV2.ActionSpec spec = null;
        CapabilitySchemaV2 schema = capabilities.schemaOf(deviceId).orElse(null);
        if (schema != null) {
            spec = schema.action(name);
        }
        if (spec != null && !spec.usableInRequest()) {
            return new Result(requestId, name, false, ProtocolErrors.UNSUPPORTED,
                    true, false, Map.of("reason", "动作 " + name + " 未声明可用于平台请求（usableIn 不含 request）"));
        }
        boolean declared = spec != null;

        try {
            CompletableFuture<PlatformRequestService.Outcome> pending = route(deviceId, name, params);
            if (pending == null) {
                return failure(requestId, name, ProtocolErrors.UNKNOWN_NAME, "no handler for request: " + name);
            }
            PlatformRequestService.Outcome outcome = await(pending);
            return new Result(requestId, name, outcome.ok(), outcome.error(), declared, true, Map.of());
        } catch (IllegalArgumentException e) {
            return failure(requestId, name, ProtocolErrors.INVALID_VALUE, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("调试请求失败: device={}, name={}", deviceId, name, e);
            return failure(requestId, name, "internal_error", String.valueOf(e.getMessage()));
        }
    }

    /** 按名称前缀路由到对应的状态化服务；未登记的名称走通用下发。 */
    private CompletableFuture<PlatformRequestService.Outcome> route(String deviceId, String name,
                                                                   Map<String, Object> params) {
        return switch (name) {
            case V2Names.SYSTEM_VOLUME_SET -> system.setVolume(deviceId, intParam(params, "value"));
            case V2Names.SYSTEM_BRIGHTNESS_SET -> system.setBrightness(deviceId, intParam(params, "value"));
            case V2Names.SYSTEM_REBOOT -> system.reboot(deviceId);
            case V2Names.SYSTEM_PROVISIONING_START -> system.startProvisioning(deviceId);
            case V2Names.AUDIO_START -> audio.startDownlink(deviceId);
            case V2Names.AUDIO_STOP -> audio.stopDownlink(deviceId);
            case V2Names.AUDIO_ABORT -> audio.abortDownlink(deviceId, stringParam(params, "reason"));
            case V2Names.DISPLAY_SECTION -> display.sendSection(deviceId, params.get("section"));
            case V2Names.DISPLAY_IMAGE_END -> display.endImage(deviceId);
            case V2Names.DISPLAY_CANVAS_CLOSE -> display.closeCanvas(deviceId);
            case V2Names.BUSINESS_TRIGGER -> business.trigger(deviceId, stringParam(params, "token"));
            case V2Names.BUSINESS_RESET -> business.reset(deviceId);
            default -> requests.send(deviceId, name, params);
        };
    }

    // ── 主视图下发 ─────────────────────────────────────────────────────────

    /**
     * 下发主视图。v2 只有完整替换，没有增量 Patch——因此 {@code section} 模式始终是整段 Section。
     *
     * @param body {@code {mode: section|image|canvas, ...}}；image / canvas 可带 {@code payloadBase64}
     */
    public Result publishView(String deviceId, Map<String, Object> body) {
        String mode = stringParam(body, "mode");
        String requestId = UUID.randomUUID().toString();
        if (!capabilities.online(deviceId)) {
            return failure(requestId, "view." + mode, ProtocolErrors.NOT_CONNECTED, "device is offline");
        }
        Map<String, Object> params = new LinkedHashMap<>(body);

        Result result;
        try {
            result = switch (mode) {
                case "section" -> {
                    if (body.get("section") == null) {
                        yield failure(requestId, "display.section", ProtocolErrors.INVALID_VALUE,
                                "section is required when mode=section");
                    }
                    yield from(requestId, "display.section",
                            await(display.sendSection(deviceId, body.get("section"))));
                }
                case "image" -> publishImage(deviceId, requestId, body);
                case "canvas" -> publishCanvas(deviceId, requestId, body);
                default -> failure(requestId, "view", ProtocolErrors.INVALID_VALUE,
                        "mode must be one of: section / image / canvas");
            };
        } catch (IllegalArgumentException e) {
            result = failure(requestId, "view." + mode, ProtocolErrors.INVALID_VALUE, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("主视图下发失败: device={}, mode={}", deviceId, mode, e);
            result = failure(requestId, "view." + mode, "internal_error", String.valueOf(e.getMessage()));
        }
        hub.record(deviceId, journalEntry(requestId, "view." + mode, params, result));
        return result;
    }

    private Result publishImage(String deviceId, String requestId, Map<String, Object> body) {
        int width = intParam(body, "width");
        int height = intParam(body, "height");
        int paletteSize = intParam(body, "paletteSize");
        long expectedBytes = body.get("expectedBytes") instanceof Number n
                ? n.longValue() : Math.max(1, (long) width * height);
        byte[] payload = decodePayload(body);

        PlatformRequestService.Outcome begin = await(display.beginImage(
                deviceId, width, height, paletteSize, expectedBytes, paletteRgb565(body)));
        if (!begin.ok()) {
            return from(requestId, V2Names.DISPLAY_IMAGE_BEGIN, begin);
        }
        if (payload.length > 0) {
            display.sendImageChunk(deviceId, payload);
        }
        return from(requestId, V2Names.DISPLAY_IMAGE_END, await(display.endImage(deviceId)));
    }

    private Result publishCanvas(String deviceId, String requestId, Map<String, Object> body) {
        int width = intParam(body, "width");
        int height = intParam(body, "height");
        int paletteSize = intParam(body, "paletteSize");
        Integer maxFps = body.get("maxFps") instanceof Number n ? n.intValue() : null;

        PlatformRequestService.Outcome open = await(display.openCanvas(
                deviceId, width, height, paletteSize, maxFps, paletteRgb565(body)));
        if (!open.ok()) {
            return from(requestId, V2Names.DISPLAY_CANVAS_OPEN, open);
        }
        byte[] payload = decodePayload(body);
        if (payload.length > 0) {
            display.sendCanvasFrame(deviceId, payload);
        }
        return from(requestId, V2Names.DISPLAY_CANVAS_OPEN, open);
    }

    // ── 工具 ───────────────────────────────────────────────────────────────

    private PlatformRequestService.Outcome await(CompletableFuture<PlatformRequestService.Outcome> future) {
        try {
            return future.get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return PlatformRequestService.Outcome.failure(ProtocolErrors.TIMEOUT);
        }
    }

    private Result from(String requestId, String name, PlatformRequestService.Outcome outcome) {
        return new Result(requestId, name, outcome.ok(), outcome.error(), true, true, Map.of());
    }

    private Result failure(String requestId, String name, String error, String reason) {
        Map<String, Object> extra = reason == null ? Map.of() : Map.of("reason", reason);
        return new Result(requestId, name, false, error, false, false, extra);
    }

    private Map<String, Object> journalEntry(String requestId, String name,
                                             Map<String, Object> params, Result result) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("requestId", requestId);
        entry.put("name", name);
        entry.put("params", params);
        entry.put("ok", result.ok());
        if (result.error() != null) {
            entry.put("error", result.error());
        }
        entry.put("declared", result.declared());
        if (!result.extra().isEmpty()) {
            entry.put("detail", result.extra());
        }
        entry.put("at", Instant.now().toString());
        return entry;
    }

    private static int intParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Integer.parseInt(s.trim());
        }
        throw new IllegalArgumentException("参数 " + key + " 必须是整数");
    }

    private static String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static byte[] decodePayload(Map<String, Object> body) {
        Object encoded = body.get("payloadBase64");
        if (encoded == null) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(String.valueOf(encoded));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("payloadBase64 不是合法的 Base64");
        }
    }

    private static int[] paletteRgb565(Map<String, Object> body) {
        Object raw = body.get("paletteRgb565");
        if (!(raw instanceof java.util.List<?> list)) {
            return new int[0];
        }
        return list.stream()
                .map(item -> item instanceof Number n ? n.intValue() : 0)
                .mapToInt(Integer::intValue)
                .toArray();
    }
}
