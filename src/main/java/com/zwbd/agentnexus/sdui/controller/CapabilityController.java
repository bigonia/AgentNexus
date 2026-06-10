package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.CapabilityContract;
import com.zwbd.agentnexus.sdui.capability.CapabilityContractService;
import com.zwbd.agentnexus.sdui.capability.CapabilityRegistry;
import com.zwbd.agentnexus.sdui.capability.CapabilityValidator;
import com.zwbd.agentnexus.sdui.capability.PlatformCapabilityRegistry;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.section.SectionEditorService;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Unified capability and event API.
 * Provides global catalog, per-device capabilities, event trees, and section type info.
 * All data is multi-level (category → capability → event/command) for consistency
 * with the workflow editor and debug tools.
 */
@RestController
@RequestMapping("/api/v1/sdui/capabilities")
@RequiredArgsConstructor
public class CapabilityController {

    private final CapabilityRegistry registry;
    private final CapabilityValidator validator;
    private final DeviceSessionManager sessionManager;
    private final SduiCapabilityService capabilityService;
    private final CapabilityContractService contractService;
    private final SectionEditorService sectionEditorService;
    private final DeviceCapabilityProjection capabilityProjection;

    // ── Global catalog ──

    @GetMapping("/catalog")
    public ApiResponse<Map<String, Object>> catalog() {
        Map<String, Object> stats = registry.stats();
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("stats", stats);
        catalog.put("protocol", capabilityProjection.protocol(""));

        return ApiResponse.ok(catalog);
    }

    // ── Per-device capabilities ──

    @GetMapping("/{deviceId}")
    public ApiResponse<Map<String, Object>> deviceCapabilities(@PathVariable String deviceId) {
        CapabilityContract contract = contractService.buildContract(deviceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("online", sessionManager.isDeviceOnline(deviceId));
        result.put("status", contract.status());
        result.put("contract", contract);
        result.put("unsupportedCapabilities", extractUnsupportedCapabilities(deviceId));
        return ApiResponse.ok(result);
    }

    @GetMapping("/{deviceId}/tree")
    public ApiResponse<Map<String, Object>> deviceCapabilityTree(@PathVariable String deviceId) {
        CapabilityContract contract = contractService.buildContract(deviceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("status", contract.status());
        result.put("contract", contract);
        result.put("online", sessionManager.isDeviceOnline(deviceId));
        return ApiResponse.ok(result);
    }

    @GetMapping("/{deviceId}/metadata")
    public ApiResponse<Map<String, Object>> deviceCapabilityMetadata(@PathVariable String deviceId) {
        Map<String, Object> result = capabilityService.buildCapabilityMetadata(deviceId);
        result.put("view", "debug");
        result.put("online", sessionManager.isDeviceOnline(deviceId));
        return ApiResponse.ok(result);
    }

    // ── Device events (terminal events, multi-level) ──

    @GetMapping("/{deviceId}/events")
    public ApiResponse<Map<String, Object>> deviceEvents(@PathVariable String deviceId) {
        return ApiResponse.ok(Map.of(
                "deviceId", deviceId,
                "online", sessionManager.isDeviceOnline(deviceId),
                "events", capabilityProjection.events(deviceId).stream().map(event -> event.toMap()).toList()
        ));
    }

    // ── Device section types ──

    @GetMapping("/{deviceId}/sections")
    public ApiResponse<Map<String, Object>> deviceSections(@PathVariable String deviceId) {
        return ApiResponse.ok(Map.of(
                "deviceId", deviceId,
                "online", sessionManager.isDeviceOnline(deviceId),
                "layouts", sectionEditorService.buildSectionEditor(deviceId).get("layouts"),
                "sections", capabilityProjection.sections(deviceId).stream().map(section -> section.toMap()).toList()
        ));
    }

    @GetMapping("/{deviceId}/commands")
    public ApiResponse<Map<String, Object>> deviceCommands(@PathVariable String deviceId) {
        return ApiResponse.ok(Map.of(
                "deviceId", deviceId,
                "online", sessionManager.isDeviceOnline(deviceId),
                "commands", capabilityProjection.commands(deviceId).stream().map(command -> command.toMap()).toList()
        ));
    }

    @GetMapping("/{deviceId}/protocol")
    public ApiResponse<Map<String, Object>> deviceProtocol(@PathVariable String deviceId) {
        Map<String, Object> protocol = new LinkedHashMap<>(capabilityProjection.protocol(deviceId));
        protocol.put("online", sessionManager.isDeviceOnline(deviceId));
        return ApiResponse.ok(protocol);
    }

    private Map<String, Object> extractUnsupportedCapabilities(String deviceId) {
        Map<String, Object> summary = validator.buildDeviceCapabilitySummary(deviceId);
        Object unsupported = summary.get("unsupportedCapabilities");
        if (unsupported instanceof Map<?, ?> unsupportedMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) unsupportedMap;
            return typed;
        }
        return Map.of();
    }
}
