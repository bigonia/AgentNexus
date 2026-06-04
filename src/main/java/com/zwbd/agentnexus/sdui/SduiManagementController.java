package com.zwbd.agentnexus.sdui;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.CapabilityRegistry;
import com.zwbd.agentnexus.sdui.dto.SduiClaimDeviceRequest;
import com.zwbd.agentnexus.sdui.dto.SduiDeviceDetailResponse;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceCommandRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceTelemetryRepository;
import com.zwbd.agentnexus.sdui.section.*;
import com.zwbd.agentnexus.sdui.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/sdui")
@RequiredArgsConstructor
public class SduiManagementController {

    private final SduiDeviceService deviceService;
    private final SduiCapabilityService capabilityService;
    private final SduiOpsService opsService;
    private final SduiDeviceCommandRepository commandRepository;
    private final SduiDeviceTelemetryRepository telemetryRepository;
    private final CommandDispatcher commandDispatcher;
    private final CommandSchemaRegistry schemaRegistry;
    private final AudioService audioService;
    private final RgbControlService rgbControlService;
    private final DeviceSessionManager sessionManager;
    private final SectionOrchestrationService sectionService;
    private final SectionAutoUpdateScheduler autoUpdateScheduler;
    private final HealthScoreEvaluator healthScoreEvaluator;
    private final CapabilityRegistry capabilityRegistry;

    // ── Device types ──

    /**
     * List all auto-discovered device types (capability profiles).
     * Types naturally emerge from connected device capability reports —
     * no manual configuration needed. Grouped by board identifier.
     */
    @GetMapping("/device-types")
    public ApiResponse<Map<String, Object>> deviceTypes() {
        List<Map<String, Object>> types = capabilityRegistry.getDeviceTypesAsList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("types", types);
        result.put("totalCount", types.size());
        return ApiResponse.ok(result);
    }

    @GetMapping("/devices")
    public ApiResponse<Map<String, Object>> devices(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String registrationStatus,
            @RequestParam(required = false) String board,
            @RequestParam(required = false) String health,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "lastSeenAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<SduiDevice> allDevices = deviceService.listDevices();

        // ── Apply filters ──
        if (status != null && !status.isBlank()) {
            allDevices = allDevices.stream()
                    .filter(d -> status.equalsIgnoreCase(d.getStatus()))
                    .collect(Collectors.toList());
        }
        if (registrationStatus != null && !registrationStatus.isBlank()) {
            allDevices = allDevices.stream()
                    .filter(d -> registrationStatus.equalsIgnoreCase(d.getRegistrationStatus()))
                    .collect(Collectors.toList());
        }
        if (search != null && !search.isBlank()) {
            String q = search.toLowerCase();
            allDevices = allDevices.stream()
                    .filter(d -> (d.getName() != null && d.getName().toLowerCase().contains(q))
                            || d.getDeviceId().toLowerCase().contains(q))
                    .collect(Collectors.toList());
        }

        // ── Build enriched items ──
        List<Map<String, Object>> items = new ArrayList<>();
        for (SduiDevice d : allDevices) {
            boolean online = sessionManager.isDeviceOnline(d.getDeviceId());
            SduiDeviceTelemetry latestTelemetry = telemetryRepository.findFirstByDeviceIdOrderByCreatedAtDesc(d.getDeviceId());
            Optional<CapabilitySchema.CapabilitySnapshot> capsOpt = capabilityService.getCapabilities(d.getDeviceId());
            Map<String, Object> healthResult = healthScoreEvaluator.evaluate(latestTelemetry, d.getConnectionCount(), online);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceId", d.getDeviceId());
            item.put("name", d.getName());
            item.put("status", online ? "ONLINE" : "OFFLINE");
            item.put("registrationStatus", d.getRegistrationStatus());
            item.put("board", capsOpt.map(CapabilitySchema.CapabilitySnapshot::board).orElse(null));
            item.put("screenShape", capsOpt.map(c -> c.screen().shape()).orElse(null));
            item.put("inputMode", capsOpt.map(CapabilitySchema.CapabilitySnapshot::inputMode).orElse(null));
            item.put("sizeClass", capsOpt.map(c -> c.display().effectiveSizeClass()).orElse(null));
            item.put("healthScore", healthResult.get("score"));
            item.put("healthLevel", healthResult.get("level"));

            if (latestTelemetry != null) {
                Map<String, Object> lt = new LinkedHashMap<>();
                lt.put("wifiRssi", latestTelemetry.getWifiRssi());
                lt.put("batteryPct", latestTelemetry.getBatteryPct());
                lt.put("temperature", latestTelemetry.getTemperature());
                lt.put("freeHeapTotal", latestTelemetry.getFreeHeapTotal());
                lt.put("freeHeapInternal", latestTelemetry.getFreeHeapInternal());
                lt.put("fragInternalPct", latestTelemetry.getFragInternalPct());
                lt.put("uptimeS", latestTelemetry.getUptimeS());
                item.put("lastTelemetry", lt);
            }
            item.put("currentAppId", d.getCurrentAppId());
            item.put("lastSeenAt", d.getLastSeenAt() != null ? d.getLastSeenAt().toString() : null);
            item.put("connectedAt", d.getConnectedAt() != null ? d.getConnectedAt().toString() : null);
            item.put("connectionCount", d.getConnectionCount());
            item.put("claimedAt", d.getClaimedAt() != null ? d.getClaimedAt().toString() : null);
            item.put("createdAt", d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);

            items.add(item);
        }

        // ── Health filter ──
        if (health != null && !health.isBlank()) {
            items = items.stream()
                    .filter(i -> health.equalsIgnoreCase((String) i.get("healthLevel")))
                    .collect(Collectors.toList());
        }

        // ── Sort ──
        Comparator<Map<String, Object>> comparator = switch (sortBy != null ? sortBy : "lastSeenAt") {
            case "name" -> Comparator.comparing(m -> (String) m.get("name"), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "status" -> Comparator.comparing(m -> (String) m.get("status"));
            case "healthScore" -> Comparator.comparing(m -> (Integer) m.getOrDefault("healthScore", -1), Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.comparing(m -> (String) m.get("lastSeenAt"), Comparator.nullsLast(Comparator.reverseOrder()));
        };
        if ("asc".equalsIgnoreCase(sortDir)) {
            comparator = comparator.reversed();
        }
        items.sort(comparator);

        // ── Paginate ──
        int totalCount = items.size();
        int start = page * size;
        int end = Math.min(start + size, totalCount);
        List<Map<String, Object>> paged = (start < totalCount) ? items.subList(start, end) : List.of();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", totalCount);
        result.put("page", page);
        result.put("size", size);
        result.put("items", paged);
        return ApiResponse.ok(result);
    }

    @GetMapping("/devices/unclaimed")
    public ApiResponse<List<SduiDevice>> unclaimedDevices() {
        return ApiResponse.ok(deviceService.listUnclaimedDevices());
    }

    @GetMapping("/devices/{deviceId}")
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

        SduiDeviceTelemetry latestTelemetry = telemetryRepository.findFirstByDeviceIdOrderByCreatedAtDesc(deviceId);
        Map<String, Object> healthResult = healthScoreEvaluator.evaluate(latestTelemetry, d.getConnectionCount(), online);

        List<SduiDeviceDetailResponse.RecentCommand> recentCommands =
                commandRepository.findTop10ByDeviceIdOrderByCreatedAtDesc(deviceId)
                        .stream().map(c -> SduiDeviceDetailResponse.RecentCommand.builder()
                                .cmdId(c.getCmdId())
                                .action(c.getAction())
                                .status(c.getStatus())
                                .reason(c.getReason())
                                .createdAt(c.getCreatedAt())
                                .build())
                        .collect(Collectors.toList());

        // Build last telemetry summary
        Map<String, Object> lastTelem = new LinkedHashMap<>();
        if (latestTelemetry != null) {
            lastTelem.put("wifiRssi", latestTelemetry.getWifiRssi());
            lastTelem.put("ip", latestTelemetry.getIp());
            lastTelem.put("temperature", latestTelemetry.getTemperature());
            lastTelem.put("freeHeapInternal", latestTelemetry.getFreeHeapInternal());
            lastTelem.put("freeHeapTotal", latestTelemetry.getFreeHeapTotal());
            lastTelem.put("fragInternalPct", latestTelemetry.getFragInternalPct());
            lastTelem.put("batteryPct", latestTelemetry.getBatteryPct());
            lastTelem.put("batteryMv", latestTelemetry.getBatteryMv());
            lastTelem.put("charging", latestTelemetry.getCharging());
            lastTelem.put("uptimeS", latestTelemetry.getUptimeS());
            lastTelem.put("lastHeartbeatAt", latestTelemetry.getCreatedAt() != null ? latestTelemetry.getCreatedAt().toString() : null);
        }

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) healthResult.get("warnings");

        SduiDeviceDetailResponse detail = SduiDeviceDetailResponse.builder()
                .deviceId(d.getDeviceId())
                .name(d.getName())
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
                .healthScore((Integer) healthResult.get("score"))
                .healthLevel((String) healthResult.get("level"))
                .healthWarnings(warnings)
                .lastTelemetry(lastTelem.isEmpty() ? null : lastTelem)
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

    @PostMapping("/devices/{deviceId}/claim")
    public ApiResponse<SduiDevice> claimDevice(@PathVariable String deviceId,
                                                @Valid @RequestBody SduiClaimDeviceRequest request) {
        return ApiResponse.ok(deviceService.claimDevice(deviceId, request.claimCode(), request.deviceName()));
    }

    @DeleteMapping("/devices/{deviceId}")
    public ApiResponse<Void> deleteDevice(@PathVariable String deviceId) {
        deviceService.deleteDevice(deviceId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/devices/{deviceId}/telemetry")
    public ApiResponse<List<SduiDeviceTelemetry>> telemetry(@PathVariable String deviceId) {
        return ApiResponse.ok(deviceService.telemetry(deviceId));
    }

    // ── Unified Command API ──

    /**
     * Execute a command on a device. The unified entry point replacing
     * /control, /console/audio/*, /console/rgb/*, /console/system/reboot,
     * and /console/debug/command.
     * <p>
     * Commands requiring server-side processing (audio.prompt.play, audio.tts.speak)
     * are handled transparently — the backend generates PCM/TTS data before dispatch.
     */
    @PostMapping("/devices/{deviceId}/command")
    public ApiResponse<Map<String, Object>> executeCommand(@PathVariable String deviceId,
                                                           @RequestBody Map<String, Object> body) {
        if (!sessionManager.isDeviceOnline(deviceId)) {
            return ApiResponse.error(40000, "device is offline");
        }

        String command = (String) body.getOrDefault("command", "");
        if (command.isBlank()) {
            return ApiResponse.error(40000, "command is required");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.get("params");

        // ── Commands with server-side processing (validate internally, skip schema) ──

        // Audio: server generates PCM data from preset name
        if ("audio.prompt.play".equals(command)) {
            String preset = params != null ? (String) params.getOrDefault("preset", "notification") : "notification";
            if (!audioService.isValidPreset(preset)) {
                return ApiResponse.error(40000, "invalid preset: " + preset);
            }
            AudioService.PlayResult playResult = audioService.playPreset(deviceId, preset);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sent", playResult.sent());
            result.put("deviceId", deviceId);
            result.put("command", command);
            result.put("cmdId", playResult.cmdId());
            result.put("samples", playResult.samples());
            result.put("durationMs", playResult.durationMs());
            result.put("status", playResult.sent() ? "ACKED" : "SEND_FAILED");
            return ApiResponse.ok(result);
        }
        // Audio: server synthesizes TTS
        if ("audio.tts.speak".equals(command)) {
            String text = params != null ? (String) params.getOrDefault("text", "") : "";
            if (text.isBlank()) {
                return ApiResponse.error(40000, "text is required for audio.tts.speak");
            }
            AudioService.PlayResult playResult = audioService.playTts(deviceId, text);
            if (!playResult.sent()) {
                return ApiResponse.error(40000, "TTS synthesis failed");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sent", true);
            result.put("deviceId", deviceId);
            result.put("command", command);
            result.put("cmdId", playResult.cmdId());
            result.put("status", "ACKED");
            return ApiResponse.ok(result);
        }
        // Audio: server performs speech-to-text transcription
        if ("audio.stt.transcribe".equals(command)) {
            String audioDataB64 = params != null ? (String) params.getOrDefault("audioData", "") : "";
            if (audioDataB64.isBlank()) {
                return ApiResponse.error(40000, "audioData is required for audio.stt.transcribe");
            }
            if (!audioService.isSttAvailable()) {
                return ApiResponse.error(40000, "STT is not available — no SttProvider configured");
            }
            String format = params != null ? (String) params.getOrDefault("format", "wav") : "wav";
            byte[] audioBytes;
            try {
                audioBytes = java.util.Base64.getDecoder().decode(audioDataB64);
            } catch (IllegalArgumentException e) {
                return ApiResponse.error(40000, "Invalid base64 audioData: " + e.getMessage());
            }
            String transcription = audioService.transcribeAudio(audioBytes, format);
            if (transcription == null || transcription.isEmpty()) {
                return ApiResponse.error(40000, "STT transcription failed or returned empty result");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sent", true);
            result.put("deviceId", deviceId);
            result.put("command", command);
            result.put("transcription", transcription);
            result.put("status", "OK");
            return ApiResponse.ok(result);
        }
        // RGB: server converts hex color → r/g/b integers, resolves action name
        if ("rgb.effect.set".equals(command)) {
            String mode = params != null ? (String) params.getOrDefault("mode", "solid") : "solid";
            String color = params != null ? (String) params.get("color") : null;
            Integer periodMs = null;
            if (params != null && params.get("periodMs") instanceof Number n) {
                periodMs = n.intValue();
            }
            Map<String, Object> rgbResult = rgbControlService.apply(deviceId, mode, color, periodMs);
            if (Boolean.FALSE.equals(rgbResult.get("sent"))) {
                return ApiResponse.error(40000, (String) rgbResult.getOrDefault("error", "unknown error"));
            }
            Map<String, Object> result = new LinkedHashMap<>(rgbResult);
            result.put("command", command);
            result.put("status", "ACKED");
            return ApiResponse.ok(result);
        }
        if ("rgb.off".equals(command)) {
            Map<String, Object> rgbResult = rgbControlService.apply(deviceId, "off", null, null);
            Map<String, Object> result = new LinkedHashMap<>(rgbResult);
            result.put("command", command);
            result.put("status", Boolean.TRUE.equals(rgbResult.get("sent")) ? "ACKED" : "SEND_FAILED");
            return ApiResponse.ok(result);
        }

        // ── Generic commands: validate against device schema ──

        Map<String, Object> effectiveParams = schemaRegistry.applyDefaults(deviceId, command, params);
        List<String> validationErrors = schemaRegistry.validate(deviceId, command, effectiveParams);

        if (!validationErrors.isEmpty()) {
            Map<String, Object> errorResponse = new LinkedHashMap<>();
            errorResponse.put("sent", false);
            errorResponse.put("deviceId", deviceId);
            errorResponse.put("command", command);
            errorResponse.put("status", "VALIDATION_FAILED");
            errorResponse.put("validationErrors", validationErrors);
            return ApiResponse.ok(errorResponse);
        }

        CommandDispatcher.DispatchResult result = commandDispatcher.dispatch(deviceId, command, effectiveParams);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sent", result.sent());
        response.put("deviceId", deviceId);
        response.put("command", command);
        response.put("cmdId", result.cmdId());
        response.put("status", result.sent() ? "ACKED" : "SEND_FAILED");
        return ApiResponse.ok(response);
    }

    /**
     * List available commands for a device with their parameter schemas.
     * Frontend uses this to render dynamic forms (dropdowns, color pickers, sliders).
     */
    @GetMapping("/devices/{deviceId}/commands")
    public ApiResponse<Map<String, Object>> deviceCommands(@PathVariable String deviceId) {
        Set<String> availableCommands = capabilityService.getAvailableCommands(deviceId);
        Map<String, CommandSchemaRegistry.CommandSchema> schemas = schemaRegistry.getAllSchemas(deviceId);

        List<Map<String, Object>> commands = new ArrayList<>();
        for (String cmd : availableCommands) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("command", cmd);
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
                    param.put("required", f.required());
                    if (f.defaultValue() != null) param.put("default", f.defaultValue());
                    if (f.min() != null) param.put("min", f.min());
                    if (f.max() != null) param.put("max", f.max());
                    if (f.values() != null) param.put("options", f.values());
                    paramList.add(param);
                }
                entry.put("params", paramList);
                // Mark commands that get server-side processing
                entry.put("serverSideProcessing", "audio.prompt.play".equals(cmd) || "audio.tts.speak".equals(cmd));
            }
            commands.add(entry);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceId", deviceId);
        data.put("online", sessionManager.isDeviceOnline(deviceId));
        data.put("commands", commands);
        return ApiResponse.ok(data);
    }

    // ── Unified Section API ──

    /**
     * Push a section scene to a device. Supports three modes:
     * <ol>
     *   <li>Custom sections: provide {@code sections} array with type + fields</li>
     *   <li>Preset: provide {@code preset} name (full_dashboard, hero_dashboard, etc.)</li>
     *   <li>Auto-rotate: include {@code autoRotate: {enabled: true, intervalMs: 3000}}</li>
     * </ol>
     */
    @PostMapping("/devices/{deviceId}/section")
    public ApiResponse<Map<String, Object>> pushSection(@PathVariable String deviceId,
                                                        @RequestBody Map<String, Object> body) {
        if (!sessionManager.isDeviceOnline(deviceId)) {
            return ApiResponse.error(40000, "device is offline");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deviceId", deviceId);

        // Handle auto-rotate toggling
        @SuppressWarnings("unchecked")
        Map<String, Object> autoRotate = (Map<String, Object>) body.get("autoRotate");
        if (autoRotate != null && Boolean.TRUE.equals(autoRotate.get("enabled"))) {
            String preset = (String) body.getOrDefault("preset", "full_dashboard");
            long intervalMs = autoRotate.get("intervalMs") instanceof Number n
                    ? n.longValue() : 3000L;
            if (intervalMs < 500) {
                return ApiResponse.error(40000, "intervalMs must be >= 500");
            }
            boolean started = autoUpdateScheduler.start(deviceId, preset, intervalMs);
            response.put("autoRotateStarted", started);
            response.put("preset", preset);
            response.put("intervalMs", intervalMs);
            return ApiResponse.ok(response);
        }
        if (autoRotate != null && Boolean.FALSE.equals(autoRotate.get("enabled"))) {
            boolean stopped = autoUpdateScheduler.stop(deviceId);
            response.put("autoRotateStopped", stopped);
            return ApiResponse.ok(response);
        }

        // Resolve preset or build custom sections
        String preset = (String) body.get("preset");
        if (preset != null && !preset.isBlank()) {
            SectionScene scene = resolvePreset(preset);
            if (scene == null) {
                return ApiResponse.error(40000, "Unknown preset: " + preset
                        + ". Available: " + autoUpdateScheduler.getPresetNames());
            }
            boolean sent = sectionService.sendScene(deviceId, scene);
            response.put("sent", sent);
            response.put("preset", preset);
            response.put("pageId", scene.pageId());
            response.put("sectionCount", scene.sections().size());
            return ApiResponse.ok(response);
        }

        // Custom sections
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawSections = (List<Map<String, Object>>) body.get("sections");
        if (rawSections == null || rawSections.isEmpty()) {
            return ApiResponse.error(40000, "either 'preset' or 'sections' is required");
        }

        String pageId = (String) body.getOrDefault("pageId", "custom_page");
        String layoutStr = (String) body.getOrDefault("layout", "vertical_scroll");
        boolean autoScroll = Boolean.TRUE.equals(body.get("autoScroll"));
        int autoScrollMs = body.get("autoScrollMs") instanceof Number n ? n.intValue() : 0;

        List<Map<String, Object>> validationErrors = new ArrayList<>();
        List<SectionEntry> entries = new ArrayList<>();

        for (var rawSec : rawSections) {
            String sectionId = (String) rawSec.get("sectionId");
            String type = (String) rawSec.get("type");
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) rawSec.get("fields");

            var typeDef = SectionTypeCatalog.get(type);
            if (typeDef.isEmpty()) {
                validationErrors.add(Map.of(
                        "sectionId", sectionId,
                        "error", "Unknown section type: " + type,
                        "knownTypes", SectionTypeCatalog.allTypes()
                ));
                continue;
            }

            SectionData data = buildSectionData(type, fields, sectionId);
            if (data == null) {
                validationErrors.add(Map.of(
                        "sectionId", sectionId,
                        "type", type,
                        "error", "Failed to build section data from fields"
                ));
                continue;
            }

            SectionType st = SectionType.fromWireName(type);
            entries.add(new SectionEntry(st, sectionId, data));
        }

        boolean sent = false;
        if (!entries.isEmpty()) {
            SectionLayout layout = parseLayout(layoutStr);
            SectionScene scene = new SectionScene(pageId, layout, autoScroll, autoScrollMs, entries);
            sent = sectionService.sendScene(deviceId, scene);
        }

        response.put("sent", sent);
        response.put("sectionsBuilt", entries.size());
        response.put("sectionsRequested", rawSections.size());
        if (!validationErrors.isEmpty()) {
            response.put("validationErrors", validationErrors);
        }
        return ApiResponse.ok(response);
    }

    @GetMapping("/ops/overview")
    public ApiResponse<Map<String, Object>> opsOverview() {
        return ApiResponse.ok(opsService.overview());
    }

    // ── Helpers ──

    private SectionScene resolvePreset(String preset) {
        return switch (preset) {
            case "hero_dashboard" -> SectionPresets.heroDashboard();
            case "metrics_grid" -> SectionPresets.metricsGrid();
            case "chart_trend" -> SectionPresets.chartTrend();
            case "full_dashboard" -> SectionPresets.fullDashboard();
            case "system_overview" -> SectionPresets.systemOverview();
            default -> null;
        };
    }

    private SectionLayout parseLayout(String layout) {
        if (layout == null) return SectionLayout.VERTICAL_SCROLL;
        SectionLayout parsed = SectionLayout.fromWireName(layout);
        // If the wire name didn't match any known layout, fromWireName returns VERTICAL_SCROLL
        if (parsed == SectionLayout.VERTICAL_SCROLL && !"vertical_scroll".equals(layout)) {
            log.warn("Unknown layout '{}', falling back to vertical_scroll", layout);
        }
        return parsed;
    }

    private SectionData buildSectionData(String type, Map<String, Object> fields, String sectionId) {
        if (fields == null) fields = Map.of();
        try {
            return switch (type) {
                case "hero_section" -> new SectionData.HeroData(
                        stringField(fields, "value", ""),
                        stringField(fields, "label", ""),
                        stringField(fields, "subtitle", ""),
                        stringField(fields, "tone", "primary"),
                        stringField(fields, "iconSrc", ""),
                        stringField(fields, "iconSymbol", null),
                        intField(fields, "progress", 0));
                case "metric_section" -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rawMetrics = (List<Map<String, Object>>) fields.get("metrics");
                    List<SectionData.MetricData.MetricEntry> metrics = new ArrayList<>();
                    if (rawMetrics != null) {
                        for (var m : rawMetrics) {
                            metrics.add(new SectionData.MetricData.MetricEntry(
                                    stringField(m, "label", ""),
                                    stringField(m, "value", "")));
                        }
                    }
                    yield new SectionData.MetricData(metrics);
                }
                case "chart_section" -> {
                    @SuppressWarnings("unchecked")
                    List<Integer> points = (List<Integer>) fields.get("points");
                    yield new SectionData.ChartData(
                            stringField(fields, "title", ""),
                            points != null ? points : List.of(),
                            intField(fields, "progress", 0));
                }
                case "timer_section" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> timer = (Map<String, Object>) fields.get("timer");
                    long elapsed = timer != null && timer.get("elapsedMs") instanceof Number n
                            ? n.longValue() : 0L;
                    boolean running = timer != null && Boolean.TRUE.equals(timer.get("running"));
                    yield new SectionData.TimerData(
                            stringField(fields, "title", ""),
                            intField(fields, "progress", 0),
                            new SectionData.TimerData.Timer(elapsed, running));
                }
                case "image_section" -> new SectionData.ImageData(
                        stringField(fields, "iconSrc", ""),
                        stringField(fields, "title", ""),
                        stringField(fields, "subtitle", ""));
                case "action_section" -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rawActions = (List<Map<String, Object>>) fields.get("actions");
                    List<SectionData.ActionData.ActionButton> actions = new ArrayList<>();
                    if (rawActions != null) {
                        for (var a : rawActions) {
                            actions.add(new SectionData.ActionData.ActionButton(
                                    stringField(a, "id", sectionId + "_action_" + actions.size()),
                                    stringField(a, "label", ""),
                                    stringField(a, "tone", "primary"),
                                    a.get("enabled") instanceof Boolean b ? b : true));
                        }
                    }
                    yield new SectionData.ActionData(actions);
                }
                case "progress_section" -> new SectionData.ProgressData(
                        stringField(fields, "title", ""),
                        intField(fields, "progress", 0),
                        stringField(fields, "progressText", ""));
                case "text_section" -> new SectionData.TextData(
                        stringField(fields, "title", ""),
                        stringField(fields, "body", ""));
                case "overlay_section" -> new SectionData.OverlayData(
                        stringField(fields, "title", ""),
                        stringField(fields, "body", ""),
                        stringField(fields, "tone", "neutral"),
                        intField(fields, "unreadCount", 0),
                        intField(fields, "autoHideMs", 0));
                case "list_section" -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rawItems = (List<Map<String, Object>>) fields.get("items");
                    List<SectionData.ListData.ListItem> items = new ArrayList<>();
                    if (rawItems != null) {
                        for (var item : rawItems) {
                            items.add(new SectionData.ListData.ListItem(
                                    stringField(item, "id", sectionId + "_item_" + items.size()),
                                    stringField(item, "title", ""),
                                    stringField(item, "subtitle", ""),
                                    stringField(item, "tone", "neutral"),
                                    stringField(item, "iconSrc", null)));
                        }
                    }
                    yield new SectionData.ListData(items);
                }
                case "toggle_section" -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rawOptions = (List<Map<String, Object>>) fields.get("options");
                    List<SectionData.ToggleData.ToggleOption> options = new ArrayList<>();
                    if (rawOptions != null) {
                        for (var opt : rawOptions) {
                            options.add(new SectionData.ToggleData.ToggleOption(
                                    stringField(opt, "id", sectionId + "_opt_" + options.size()),
                                    stringField(opt, "label", ""),
                                    opt.get("active") instanceof Boolean b ? b : false));
                        }
                    }
                    yield new SectionData.ToggleData(options);
                }
                case "nav_section" -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rawTabs = (List<Map<String, Object>>) fields.get("tabs");
                    List<SectionData.NavData.NavTab> tabs = new ArrayList<>();
                    if (rawTabs != null) {
                        for (var tab : rawTabs) {
                            tabs.add(new SectionData.NavData.NavTab(
                                    stringField(tab, "id", sectionId + "_tab_" + tabs.size()),
                                    stringField(tab, "label", "")));
                        }
                    }
                    yield new SectionData.NavData(tabs, intField(fields, "activeTab", 0));
                }
                default -> null;
            };
        } catch (Exception e) {
            log.warn("Failed to build section data for type={}: {}", type, e.getMessage());
            return null;
        }
    }

    private String stringField(Map<String, Object> fields, String key, String defaultVal) {
        Object val = fields.get(key);
        return val instanceof String s ? s : (val != null ? val.toString() : defaultVal);
    }

    private int intField(Map<String, Object> fields, String key, int defaultVal) {
        Object val = fields.get(key);
        return val instanceof Number n ? n.intValue() : defaultVal;
    }
}
