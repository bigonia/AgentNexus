package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.CapabilityContract;
import com.zwbd.agentnexus.sdui.capability.CapabilityContractService;
import com.zwbd.agentnexus.sdui.capability.CapabilityRegistry;
import com.zwbd.agentnexus.sdui.capability.PlatformCapabilityRegistry;
import com.zwbd.agentnexus.sdui.dto.SduiClaimDeviceRequest;
import com.zwbd.agentnexus.sdui.dto.SduiDeviceDetailResponse;
import com.zwbd.agentnexus.sdui.model.DeviceConnectionLog;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.protocol.SduiRuntimeHandlers;
import com.zwbd.agentnexus.sdui.repo.DeviceConnectionLogRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceCommandRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceTelemetryRepository;
import com.zwbd.agentnexus.sdui.section.SectionRenderMode;
import com.zwbd.agentnexus.sdui.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Unified device management and snapshot API.
 * Merges device CRUD, telemetry trends, connection logs, and command discovery.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sdui/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final SduiDeviceService deviceService;
    private final SduiCapabilityService capabilityService;
    private final CommandSchemaRegistry schemaRegistry;
    private final DeviceSessionManager sessionManager;
    private final TelemetryTrendService trendService;
    private final CapabilityContractService contractService;
    private final PlatformCapabilityRegistry platformCapabilityRegistry;
    private final SduiDeviceCommandRepository commandRepository;
    private final SduiDeviceTelemetryRepository telemetryRepository;
    private final DeviceConnectionLogRepository connectionLogRepository;
    private final CapabilityRegistry capabilityRegistry;

    // ── Device list ──

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
            boolean online = sessionManager.isDeviceOnline(d.getDeviceId());
            SduiDeviceTelemetry latestTel = telemetryRepository.findFirstByDeviceIdOrderByCreatedAtDesc(d.getDeviceId());
            Optional<CapabilitySchema.CapabilitySnapshot> capsOpt = capabilityService.getCapabilities(d.getDeviceId());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceId", d.getDeviceId());
            item.put("name", d.getName());
            item.put("status", online ? "ONLINE" : "OFFLINE");
            item.put("registrationStatus", d.getRegistrationStatus());
            item.put("board", capsOpt.map(CapabilitySchema.CapabilitySnapshot::board).orElse(null));
            item.put("screenShape", capsOpt.map(c -> c.screen().shape()).orElse(null));
            item.put("inputMode", capsOpt.map(CapabilitySchema.CapabilitySnapshot::inputMode).orElse(null));
            item.put("sizeClass", capsOpt.map(c -> c.display().effectiveSizeClass()).orElse(null));

            if (latestTel != null) {
                item.put("lastTelemetry", buildTelemetrySummary(latestTel));
            }
            item.put("currentAppId", d.getCurrentAppId());
            item.put("lastSeenAt", d.getLastSeenAt() != null ? d.getLastSeenAt().toString() : null);
            item.put("connectedAt", d.getConnectedAt() != null ? d.getConnectedAt().toString() : null);
            item.put("connectionCount", d.getConnectionCount());
            item.put("claimedAt", d.getClaimedAt() != null ? d.getClaimedAt().toString() : null);
            item.put("createdAt", d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);
            items.add(item);
        }

        // Board filter
        if (board != null && !board.isBlank()) {
            items = items.stream()
                    .filter(i -> board.equals(i.get("board"))).toList();
        }

        // Sort
        Comparator<Map<String, Object>> comparator = switch (sortBy != null ? sortBy : "lastSeenAt") {
            case "name" -> Comparator.comparing(m -> (String) m.get("name"), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "status" -> Comparator.comparing(m -> (String) m.get("status"));
            default -> Comparator.comparing(m -> (String) m.get("lastSeenAt"), Comparator.nullsLast(Comparator.reverseOrder()));
        };
        if ("asc".equalsIgnoreCase(sortDir)) comparator = comparator.reversed();
        items.sort(comparator);

        // Paginate
        int totalCount = items.size();
        int start = page * size;
        int end = Math.min(start + size, totalCount);
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

    // ── Device detail ──

    @GetMapping("/{deviceId}")
    public ApiResponse<SduiDeviceDetailResponse> deviceDetail(@PathVariable String deviceId) {
        Optional<SduiDevice> deviceOpt = deviceService.getDevice(deviceId);
        if (deviceOpt.isEmpty()) {
            return ApiResponse.error(40400, "device not found");
        }
        SduiDevice d = deviceOpt.get();
        boolean online = sessionManager.isDeviceOnline(deviceId);
        Optional<CapabilitySchema.CapabilitySnapshot> capsOpt = capabilityService.getCapabilities(deviceId);
        CapabilitySchema.ScreenInfo screen = capsOpt.map(CapabilitySchema.CapabilitySnapshot::screen).orElse(null);
        String board = capsOpt.map(CapabilitySchema.CapabilitySnapshot::board).orElse(null);
        String inputMode = capsOpt.map(CapabilitySchema.CapabilitySnapshot::inputMode).orElse(null);
        String sizeClass = capsOpt.map(c -> c.display().effectiveSizeClass()).orElse(null);
        CapabilityContract contract = contractService.buildContract(deviceId);

        SduiDeviceTelemetry latestTel = telemetryRepository.findFirstByDeviceIdOrderByCreatedAtDesc(deviceId);

        List<SduiDeviceDetailResponse.RecentCommand> recentCommands =
                commandRepository.findTop10ByDeviceIdOrderByCreatedAtDesc(deviceId)
                        .stream().map(c -> SduiDeviceDetailResponse.RecentCommand.builder()
                                .cmdId(c.getCmdId())
                                .action(c.getAction())
                                .status(c.getStatus())
                                .reason(c.getReason())
                                .createdAt(c.getCreatedAt())
                                .build())
                        .toList();

        // Build capabilities summary inline so frontend doesn't need extra request
        Map<String, Object> capsSummary = new LinkedHashMap<>();
        capsSummary.put("board", board);
        capsSummary.put("screen", screen != null ? Map.of(
                "w", screen.w(), "h", screen.h(), "shape", screen.shape()) : null);
        capsSummary.put("inputMode", inputMode);
        capsSummary.put("sizeClass", sizeClass);
        capsSummary.put("renderMode", capsOpt
                .map(c -> SectionRenderMode.fromSizeClass(c.display().effectiveSizeClass()).name().toLowerCase())
                .orElse("rich"));

        SduiDeviceDetailResponse detail = SduiDeviceDetailResponse.builder()
                .deviceId(d.getDeviceId())
                .name(d.getName())
                .notes(d.getNotes())
                .status(online ? "ONLINE" : "OFFLINE")
                .registrationStatus(d.getRegistrationStatus())
                .board(board)
                .screenShape(screen != null ? screen.shape() : null)
                .screenWidth(screen != null ? screen.w() : 0)
                .screenHeight(screen != null ? screen.h() : 0)
                .inputMode(inputMode)
                .sizeClass(sizeClass)
                .availableCommands(capabilityService.getAvailableCommands(deviceId))
                .recentCommands(recentCommands)
                .capabilitiesSnapshot(d.getCapabilitiesSnapshot())
                .capabilitiesSummary(capsSummary)
                .capabilityContract(contract)
                .capabilityDebugMetadata(capabilityService.buildCapabilityDebugView(deviceId))
                .lastTelemetry(latestTel != null ? buildTelemetrySummary(latestTel) : null)
                .connectedAt(d.getConnectedAt())
                .sessionId(d.getSessionId())
                .connectionCount(d.getConnectionCount())
                .totalUptimeS(d.getTotalUptimeS())
                .lastSeenAt(d.getLastSeenAt())
                .claimedAt(d.getClaimedAt())
                .createdAt(d.getCreatedAt())
                .build();
        return ApiResponse.ok(detail);
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

    // ── Telemetry ──

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

    // ── Connection log ──

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
            if (device.getConnectedAt() != null && sessionManager.isDeviceOnline(deviceId)) {
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
        int startIdx = page * size;
        int endIdx = Math.min(startIdx + size, logs.size());
        for (int i = startIdx; i < endIdx; i++) {
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

    // ── Commands ──

    @GetMapping("/{deviceId}/commands")
    public ApiResponse<Map<String, Object>> deviceCommands(@PathVariable String deviceId) {
        Set<String> availableCommands = capabilityService.getAvailableCommands(deviceId);
        Map<String, CommandSchemaRegistry.CommandSchema> schemas = schemaRegistry.getAllSchemas(deviceId);

        List<Map<String, Object>> commands = new ArrayList<>();
        for (String cmd : availableCommands) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("command", cmd);
            entry.put("displayName", capabilityService.getCommandDisplayName(cmd));
            CommandSchemaRegistry.CommandSchema schema = schemas.get(cmd);
            if (schema != null) {
                entry.put("description", schema.description());
                List<Map<String, Object>> paramList = new ArrayList<>();
                for (var fieldEntry : schema.fields().entrySet()) {
                    CommandSchemaRegistry.FieldDef f = fieldEntry.getValue();
                    Map<String, Object> param = new LinkedHashMap<>();
                    param.put("name", fieldEntry.getKey());
                    param.put("type", f.type());
                    param.put("label", f.label() != null ? f.label() : fieldEntry.getKey());
                    paramList.add(param);
                }
                entry.put("params", paramList);
                entry.put("source", "device");
                entry.put("runtimeHandler", SduiRuntimeHandlers.DEVICE_COMMAND);
            }
            commands.add(entry);
        }

        for (var platformCapability : platformCapabilityRegistry.listCapabilities()) {
            if (!platformCapability.available()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("command", platformCapability.debugRouteId());
            entry.put("displayName", platformCapability.displayName());
            entry.put("description", platformCapability.description());
            entry.put("params", platformCapability.schema().getOrDefault("params", List.of()));
            entry.put("source", "platform");
            entry.put("runtimeHandler", platformCapability.runtimeHandler());
            commands.add(entry);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceId", deviceId);
        data.put("online", sessionManager.isDeviceOnline(deviceId));
        data.put("commands", commands);
        return ApiResponse.ok(data);
    }

    // ── Helpers ──

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
