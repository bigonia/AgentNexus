package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.service.SduiDeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/v1/sdui/section")
@RequiredArgsConstructor
public class SectionTriggerController {

    private final SectionOrchestrationService orchestrationService;
    private final SectionAutoUpdateScheduler autoUpdateScheduler;
    private final SduiDeviceService deviceService;

    @GetMapping("/presets")
    public ApiResponse<Map<String, Object>> listPresets() {
        return ApiResponse.ok(Map.of(
                "presets", autoUpdateScheduler.getPresetNames(),
                "description", "Available presets for manual sending or auto-update"
        ));
    }

    @PostMapping("/scene/{deviceId}")
    public ApiResponse<Map<String, Object>> sendPresetScene(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "full_dashboard") String preset) {
        SectionScene scene = resolvePreset(preset);
        if (scene == null) {
            return ApiResponse.error(40000, "Unknown preset: " + preset
                    + ". Available: " + autoUpdateScheduler.getPresetNames());
        }
        boolean sent = orchestrationService.sendScene(deviceId, scene);
        return ApiResponse.ok(Map.of(
                "sent", sent,
                "deviceId", deviceId,
                "preset", preset,
                "pageId", scene.pageId(),
                "sections", scene.sections().size()
        ));
    }

    @PostMapping("/auto/{deviceId}/start")
    public ApiResponse<Map<String, Object>> startAutoUpdate(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "full_dashboard") String preset,
            @RequestParam(defaultValue = "3000") long intervalMs) {
        if (intervalMs < 500) {
            return ApiResponse.error(40000, "intervalMs must be >= 500");
        }
        if (!autoUpdateScheduler.getPresetNames().contains(preset)) {
            return ApiResponse.error(40000, "Unknown preset: " + preset);
        }
        boolean started = autoUpdateScheduler.start(deviceId, preset, intervalMs);
        return ApiResponse.ok(Map.of(
                "started", started,
                "deviceId", deviceId,
                "preset", preset,
                "intervalMs", intervalMs
        ));
    }

    @PostMapping("/auto/{deviceId}/stop")
    public ApiResponse<Map<String, Object>> stopAutoUpdate(@PathVariable String deviceId) {
        boolean stopped = autoUpdateScheduler.stop(deviceId);
        return ApiResponse.ok(Map.of(
                "stopped", stopped,
                "deviceId", deviceId
        ));
    }

    @GetMapping("/auto/status")
    public ApiResponse<List<SectionAutoUpdateScheduler.AutoUpdateStatus>> autoStatus() {
        return ApiResponse.ok(autoUpdateScheduler.listStatus());
    }

    @GetMapping("/devices")
    public ApiResponse<List<?>> listDevicesWithSectionSupport() {
        // TODO: filter by section capability once query supports it
        return ApiResponse.ok(deviceService.listDevices());
    }

    @GetMapping("/capability/{deviceId}")
    public ApiResponse<Map<String, Object>> sectionCapability(@PathVariable String deviceId) {
        var sec = orchestrationService.getSectionCapability(deviceId);
        return ApiResponse.ok(Map.of(
                "deviceId", deviceId,
                "supportsSection", orchestrationService.supportsSection(deviceId),
                "capability", sec.orElse(null)
        ));
    }

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
}
