package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.CapabilityRegistry;
import com.zwbd.agentnexus.sdui.capability.CapabilityValidator;
import com.zwbd.agentnexus.sdui.section.SectionRenderMode;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import com.zwbd.agentnexus.sdui.service.AudioService;
import com.zwbd.agentnexus.sdui.service.ConsoleLayoutService;
import com.zwbd.agentnexus.sdui.service.EventStreamService;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sdui/devices/{deviceId}/console")
@RequiredArgsConstructor
public class DeviceConsoleController {

    private final ConsoleLayoutService layoutService;
    private final EventStreamService eventStreamService;
    private final AudioService audioService;
    private final DeviceSessionManager sessionManager;
    private final SduiCapabilityService capabilityService;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityValidator capabilityValidator;

    @GetMapping("/layout")
    public ApiResponse<Map<String, Object>> layout(@PathVariable String deviceId) {
        Map<String, Object> layout = layoutService.buildLayout(deviceId);
        layout.put("ttsAvailable", audioService.isTtsAvailable());
        return ApiResponse.ok(layout);
    }

    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventStream(@PathVariable String deviceId) {
        return eventStreamService.subscribe(deviceId);
    }

    // ── Capability inspection ──

    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Object>> deviceCapabilities(@PathVariable String deviceId) {
        Map<String, Object> summary = capabilityValidator.buildDeviceCapabilitySummary(deviceId);
        summary.put("online", sessionManager.isDeviceOnline(deviceId));

        // Resolve device render mode from its reported size class
        SectionRenderMode renderMode = capabilityRegistry.getDeviceSnapshot(deviceId)
                .map(caps -> SectionRenderMode.fromSizeClass(caps.sizeClass()))
                .orElse(SectionRenderMode.RICH);

        List<Map<String, Object>> sectionTypeList = new ArrayList<>();
        for (SectionTypeCatalog.SectionTypeDef def : SectionTypeCatalog.all().values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", def.type());
            entry.put("displayName", def.displayName());
            entry.put("interactive", def.interactive());
            entry.put("displayFields", SectionTypeCatalog.fieldsToMaps(
                    def.displayFields(), renderMode, def.compactHiddenFields()));
            entry.put("interactionEvents", def.interactionEvents().stream()
                    .map(e -> Map.of("eventId", e.eventId(), "description", e.description(),
                            "params", e.params().stream()
                                    .map(p -> Map.of("name", p.name(), "type", p.type(),
                                            "description", p.description()))
                                    .toList()))
                    .toList());
            entry.put("defaultConstraints", def.defaultConstraints());
            sectionTypeList.add(entry);
        }
        summary.put("sectionTypeCatalog", sectionTypeList);
        summary.put("renderMode", renderMode.name().toLowerCase());

        return ApiResponse.ok(summary);
    }

    @GetMapping("/section/types")
    public ApiResponse<Map<String, Object>> sectionTypes(@PathVariable String deviceId) {
        List<Map<String, Object>> types = new ArrayList<>();
        var deviceCaps = capabilityRegistry.getDeviceSnapshot(deviceId);

        // Resolve device render mode from its reported size class
        SectionRenderMode renderMode = deviceCaps
                .map(caps -> SectionRenderMode.fromSizeClass(caps.sizeClass()))
                .orElse(SectionRenderMode.RICH);

        for (SectionTypeCatalog.SectionTypeDef def : SectionTypeCatalog.all().values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", def.type());
            entry.put("displayName", def.displayName());
            entry.put("interactive", def.interactive());
            entry.put("displayFields", SectionTypeCatalog.fieldsToMaps(
                    def.displayFields(), renderMode, def.compactHiddenFields()));
            entry.put("interactionEvents", def.interactionEvents().stream()
                    .map(e -> Map.of(
                            "eventId", e.eventId(),
                            "description", e.description(),
                            "params", e.params().stream()
                                    .map(p -> Map.of("name", p.name(), "type", p.type(),
                                            "description", p.description()))
                                    .toList()))
                    .toList());
            entry.put("deviceSupported", deviceCaps
                    .map(c -> c.supportsSection(def.type()))
                    .orElse(false));
            types.add(entry);
        }

        return ApiResponse.ok(Map.of(
                "deviceId", deviceId,
                "online", sessionManager.isDeviceOnline(deviceId),
                "renderMode", renderMode.name().toLowerCase(),
                "sectionTypes", types
        ));
    }
}
