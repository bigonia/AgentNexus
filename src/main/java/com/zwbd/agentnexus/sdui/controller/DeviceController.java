package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.dto.SduiClaimDeviceRequest;
import com.zwbd.agentnexus.sdui.dto.SduiDeviceDetailResponse;
import com.zwbd.agentnexus.sdui.model.DeviceConnectionLog;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import com.zwbd.agentnexus.sdui.repo.DeviceConnectionLogRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceTelemetryRepository;
import com.zwbd.agentnexus.sdui.service.SduiDeviceService;
import com.zwbd.agentnexus.sdui.service.TelemetryTrendService;
import com.zwbd.agentnexus.sdui.v2.business.BusinessConfig;
import com.zwbd.agentnexus.sdui.v2.business.BusinessConfigService;
import com.zwbd.agentnexus.sdui.v2.business.ResponseStep;
import com.zwbd.agentnexus.sdui.v2.business.TriggerBinding;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilityQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 设备台账 API。
 *
 * <p>只承载设备本身：身份、在线态、认领、遥测、连接历史、当前生效的业务绑定。能力细节由能力域
 * 承担，命令/请求细节由调试域承担——本控制器不再做任何命令派发。</p>
 *
 * <p>在线态与能力都只有一个来源：v2 连接注册表与设备声明的能力 Schema。</p>
 *
 * <p>管理面边界见 {@code docs/sdui/PLATFORM_REFACTOR.md} §3；端点明细以本控制器和 OpenAPI 为准。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sdui/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final SduiDeviceService deviceService;
    private final CapabilityQueryService capabilities;
    private final BusinessConfigService businessConfigService;
    private final TelemetryTrendService trendService;
    private final SduiDeviceTelemetryRepository telemetryRepository;
    private final DeviceConnectionLogRepository connectionLogRepository;

    // ── 设备列表 ──

    @GetMapping
    public ApiResponse<Map<String, Object>> listDevices(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String registrationStatus,
            @RequestParam(required = false) String board,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "lastSeenAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<SduiDevice> allDevices = deviceService.listDevices();

        if (status != null && !status.isBlank()) {
            allDevices = allDevices.stream()
                    .filter(d -> status.equalsIgnoreCase(d.getStatus())).toList();
        }
        if (registrationStatus != null && !registrationStatus.isBlank()) {
            allDevices = allDevices.stream()
                    .filter(d -> registrationStatus.equalsIgnoreCase(d.getRegistrationStatus())).toList();
        }
        if (search != null && !search.isBlank()) {
            String q = search.toLowerCase();
            allDevices = allDevices.stream()
                    .filter(d -> (d.getName() != null && d.getName().toLowerCase().contains(q))
                            || d.getDeviceId().toLowerCase().contains(q)).toList();
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (SduiDevice d : allDevices) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceId", d.getDeviceId());
            item.put("name", d.getName());
            item.put("status", capabilities.online(d.getDeviceId()) ? "ONLINE" : "OFFLINE");
            item.put("registrationStatus", d.getRegistrationStatus());
            item.put("board", capabilities.boardOf(d.getDeviceId()).orElse(null));
            item.put("capabilitySyncState", capabilities.sync(d.getDeviceId()).get("state"));
            item.put("currentAppId", d.getCurrentAppId());
            item.put("lastSeenAt", d.getLastSeenAt() != null ? d.getLastSeenAt().toString() : null);
            item.put("connectedAt", d.getConnectedAt() != null ? d.getConnectedAt().toString() : null);
            item.put("connectionCount", d.getConnectionCount());
            item.put("claimedAt", d.getClaimedAt() != null ? d.getClaimedAt().toString() : null);
            item.put("createdAt", d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);
            SduiDeviceTelemetry latest = telemetryRepository.findFirstByDeviceIdOrderByCreatedAtDesc(d.getDeviceId());
            if (latest != null) {
                item.put("lastTelemetry", buildTelemetrySummary(latest));
            }
            items.add(item);
        }

        if (board != null && !board.isBlank()) {
            items = items.stream().filter(i -> board.equals(i.get("board"))).toList();
        }

        Comparator<Map<String, Object>> comparator = switch (sortBy != null ? sortBy : "lastSeenAt") {
            case "name" -> Comparator.comparing(m -> (String) m.get("name"), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "status" -> Comparator.comparing(m -> (String) m.get("status"));
            default -> Comparator.comparing(m -> (String) m.get("lastSeenAt"), Comparator.nullsLast(Comparator.reverseOrder()));
        };
        if ("asc".equalsIgnoreCase(sortDir)) comparator = comparator.reversed();
        items.sort(comparator);

        int totalCount = items.size();
        int start = Math.max(page, 0) * Math.max(size, 1);
        int end = Math.min(start + Math.max(size, 1), totalCount);
        List<Map<String, Object>> paged = (start < totalCount) ? items.subList(start, end) : List.of();
        long onlineCount = items.stream().filter(i -> "ONLINE".equals(i.get("status"))).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", totalCount);
        result.put("onlineCount", (int) onlineCount);
        result.put("offlineCount", totalCount - (int) onlineCount);
        result.put("page", page);
        result.put("size", size);
        result.put("items", paged);
        return ApiResponse.ok(result);
    }

    @GetMapping("/unclaimed")
    public ApiResponse<List<SduiDevice>> unclaimedDevices() {
        return ApiResponse.ok(deviceService.listUnclaimedDevices());
    }

    // ── 设备详情 ──

    @GetMapping("/{deviceId}")
    public ApiResponse<SduiDeviceDetailResponse> deviceDetail(@PathVariable String deviceId) {
        Optional<SduiDevice> deviceOpt = deviceService.getDevice(deviceId);
        if (deviceOpt.isEmpty()) {
            return ApiResponse.error(40400, "device not found");
        }
        SduiDevice d = deviceOpt.get();
        Map<String, Object> summary = capabilities.summary(deviceId);
        Map<String, Object> sync = capabilities.sync(deviceId);

        @SuppressWarnings("unchecked")
        Map<String, Object> surface = (Map<String, Object>) summary.get("surface");

        SduiDeviceTelemetry latestTelemetry =
                telemetryRepository.findFirstByDeviceIdOrderByCreatedAtDesc(deviceId);

        return ApiResponse.ok(SduiDeviceDetailResponse.builder()
                .deviceId(d.getDeviceId())
                .name(d.getName())
                .notes(d.getNotes())
                .status(capabilities.online(deviceId) ? "ONLINE" : "OFFLINE")
                .registrationStatus(d.getRegistrationStatus())
                .board((String) summary.get("board"))
                .protocolVersion((String) summary.get("protocolVersion"))
                .schemaVersion((String) summary.get("schemaVersion"))
                .capabilityHash((String) summary.get("capabilityHash"))
                .capabilitySyncState(String.valueOf(sync.get("state")))
                .businessAllowed(Boolean.TRUE.equals(sync.get("businessAllowed")))
                .capabilitySummary(summary)
                .surface(surface)
                .connectedAt(d.getConnectedAt())
                .sessionId(d.getSessionId())
                .connectionCount(d.getConnectionCount())
                .totalUptimeS(d.getTotalUptimeS())
                .lastSeenAt(d.getLastSeenAt())
                .claimedAt(d.getClaimedAt())
                .createdAt(d.getCreatedAt())
                .lastTelemetry(latestTelemetry != null ? buildTelemetrySummary(latestTelemetry) : null)
                .build());
    }

    @PostMapping("/{deviceId}/claim")
    public ApiResponse<SduiDevice> claimDevice(@PathVariable String deviceId,
                                                @Valid @RequestBody SduiClaimDeviceRequest request) {
        return ApiResponse.ok(deviceService.claimDevice(deviceId, request.claimCode(), request.deviceName()));
    }

    @DeleteMapping("/{deviceId}")
    public ApiResponse<Void> deleteDevice(@PathVariable String deviceId) {
        deviceService.deleteDevice(deviceId);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/{deviceId}")
    public ApiResponse<SduiDevice> updateDevice(@PathVariable String deviceId,
                                                 @RequestBody Map<String, Object> body) {
        String name = body.get("name") instanceof String s ? s : null;
        String notes = body.get("notes") instanceof String s ? s : null;
        if (name == null && notes == null) {
            return ApiResponse.error(40000, "at least one of 'name' or 'notes' is required");
        }
        return ApiResponse.ok(deviceService.updateDevice(deviceId, name, notes));
    }

    // ── 当前生效的业务绑定 ──

    /**
     * 设备上此刻挂着什么：触发绑定、云端 Trigger token 是否存在、平台侧上下文引用、本地响应序列。
     *
     * <p>这是闭环第⑤⑥步的唯一可观测出口——排查"按钮按了没反应"时，先看这里有没有那条绑定。
     * token 只以"存在与否"的形式暴露，前端不感知其取值。</p>
     */
    @GetMapping("/{deviceId}/bindings")
    public ApiResponse<Map<String, Object>> bindings(@PathVariable String deviceId) {
        Optional<BusinessConfigService.ActiveBusinessState> active = businessConfigService.activeState(deviceId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("online", capabilities.online(deviceId));
        result.put("active", active.isPresent());
        result.put("maxBindingsPerConfig", businessConfigService.maxBindingsPerConfig());

        if (active.isEmpty()) {
            result.put("appliedAt", null);
            result.put("configVersion", null);
            result.put("bindings", List.of());
            return ApiResponse.ok(result);
        }

        BusinessConfig config = active.get().config();
        result.put("appliedAt", active.get().appliedAt() != null
                ? active.get().appliedAt().toString() : null);
        result.put("configVersion", config.configVersion());

        List<Map<String, Object>> bindings = new ArrayList<>();
        for (TriggerBinding binding : config.triggers()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("triggerId", binding.triggerId());
            entry.put("source", binding.source() != null ? binding.source().wire() : null);
            entry.put("hasToken", binding.token() != null && !binding.token().isBlank());
            entry.put("contextRef", binding.contextRef());
            List<Map<String, Object>> responses = new ArrayList<>();
            for (ResponseStep step : binding.responses()) {
                Map<String, Object> stepEntry = new LinkedHashMap<>();
                stepEntry.put("action", step.action());
                stepEntry.put("params", step.params());
                responses.add(stepEntry);
            }
            entry.put("responses", responses);
            bindings.add(entry);
        }
        result.put("bindings", bindings);
        return ApiResponse.ok(result);
    }

    // ── 遥测 ──

    @GetMapping("/{deviceId}/telemetry")
    public ApiResponse<Map<String, Object>> telemetry(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 200);
        Page<SduiDeviceTelemetry> telemetryPage = deviceService.getTelemetry(deviceId, normalizedPage, normalizedSize);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", telemetryPage.getContent());
        result.put("page", telemetryPage.getNumber());
        result.put("size", telemetryPage.getSize());
        result.put("totalElements", telemetryPage.getTotalElements());
        result.put("totalPages", telemetryPage.getTotalPages());
        result.put("first", telemetryPage.isFirst());
        result.put("last", telemetryPage.isLast());
        result.put("empty", telemetryPage.isEmpty());
        return ApiResponse.ok(result);
    }

    @GetMapping("/{deviceId}/telemetry/trends")
    public ApiResponse<Map<String, Object>> telemetryTrends(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "24h") String range,
            @RequestParam(defaultValue = "auto") String bucket,
            @RequestParam(defaultValue = "all") String metrics) {
        int bucketSec = "auto".equalsIgnoreCase(bucket)
                ? TelemetryTrendService.autoBucketSecondsForRange(range)
                : parseBucketSeconds(bucket, range);
        return ApiResponse.ok(trendService.buildTrends(deviceId, range, bucketSec, metrics));
    }

    // ── 连接历史 ──

    @GetMapping("/{deviceId}/connection-log")
    public ApiResponse<Map<String, Object>> connectionLog(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<DeviceConnectionLog> logs = connectionLogRepository.findTop50ByDeviceIdOrderByEventAtDesc(deviceId);

        Map<String, Object> currentSession = new LinkedHashMap<>();
        Optional<SduiDevice> deviceOpt = deviceService.getDevice(deviceId);
        if (deviceOpt.isPresent()) {
            SduiDevice device = deviceOpt.get();
            currentSession.put("connectedAt", device.getConnectedAt() != null ? device.getConnectedAt().toString() : null);
            currentSession.put("sessionId", device.getSessionId());
            if (device.getConnectedAt() != null && capabilities.online(deviceId)) {
                currentSession.put("durationS", Duration.between(device.getConnectedAt(), LocalDateTime.now()).getSeconds());
            }
        }

        long totalConnected = connectionLogRepository.countByDeviceIdAndEventType(deviceId, "CONNECTED");
        long totalDisconnected = connectionLogRepository.countByDeviceIdAndEventType(deviceId, "DISCONNECTED");

        List<Object[]> reasonRows = connectionLogRepository.countDisconnectReasonsByDeviceId(deviceId);
        Map<String, Long> disconnectReasons = new LinkedHashMap<>();
        for (Object[] row : reasonRows) {
            disconnectReasons.put((String) row[0], (Long) row[1]);
        }

        List<Map<String, Object>> events = new ArrayList<>();
        int startIdx = Math.max(page, 0) * Math.max(size, 1);
        int endIdx = Math.min(startIdx + Math.max(size, 1), logs.size());
        for (int i = startIdx; i < endIdx && i < logs.size(); i++) {
            DeviceConnectionLog log = logs.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("eventType", log.getEventType());
            entry.put("sessionId", log.getSessionId());
            entry.put("ipAddress", log.getIpAddress());
            entry.put("disconnectReason", log.getDisconnectReason());
            entry.put("eventAt", log.getEventAt() != null ? log.getEventAt().toString() : null);
            events.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("currentSession", currentSession);
        result.put("stats", Map.of(
                "totalConnections", (int) totalConnected,
                "totalDisconnections", (int) totalDisconnected,
                "disconnectReasons", disconnectReasons));
        result.put("events", events);
        result.put("page", page);
        result.put("size", size);
        result.put("totalEvents", logs.size());
        return ApiResponse.ok(result);
    }

    // ── 辅助 ──

    private Map<String, Object> buildTelemetrySummary(SduiDeviceTelemetry t) {
        Map<String, Object> telem = new LinkedHashMap<>();

        Map<String, Object> wifi = new LinkedHashMap<>();
        wifi.put("rssi", t.getWifiRssi());
        wifi.put("ip", t.getIp());
        telem.put("wifi", wifi);

        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("freeHeapInternal", t.getFreeHeapInternal());
        memory.put("largestHeapInternal", t.getLargestHeapInternal());
        memory.put("freeHeapDma", t.getFreeHeapDma());
        memory.put("largestHeapDma", t.getLargestHeapDma());
        memory.put("freeHeapPsram", t.getFreeHeapPsram());
        memory.put("largestHeapPsram", t.getLargestHeapPsram());
        memory.put("freeHeapTotal", t.getFreeHeapTotal());
        memory.put("fragInternalPct", t.getFragInternalPct());
        memory.put("fragDmaPct", t.getFragDmaPct());
        memory.put("fragPsramPct", t.getFragPsramPct());
        telem.put("memory", memory);

        Map<String, Object> temp = new LinkedHashMap<>();
        temp.put("celsius", t.getTemperature());
        telem.put("temperature", temp);

        Map<String, Object> power = new LinkedHashMap<>();
        power.put("supported", t.getPowerSupported());
        power.put("batteryMv", t.getBatteryMv());
        power.put("batteryPct", t.getBatteryPct());
        power.put("charging", t.getCharging());
        power.put("extPowerPresent", t.getExtPowerPresent());
        power.put("extPowerCtrl", t.getExtPowerCtrl());
        power.put("extPowerOn", t.getExtPowerOn());
        telem.put("power", power);
        telem.put("uptimeS", t.getUptimeS());
        telem.put("lastHeartbeatAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        return telem;
    }

    private static int parseBucketSeconds(String bucket, String range) {
        return switch (bucket) {
            case "1m" -> 60;
            case "5m" -> 300;
            case "10m" -> 600;
            case "1h" -> 3600;
            case "6h" -> 21600;
            default -> TelemetryTrendService.autoBucketSecondsForRange(range);
        };
    }
}
