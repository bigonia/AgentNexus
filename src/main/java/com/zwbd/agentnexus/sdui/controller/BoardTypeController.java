package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.CapabilityRegistry;
import com.zwbd.agentnexus.sdui.event.EventDefinition;
import com.zwbd.agentnexus.sdui.event.EventRegistry;
import com.zwbd.agentnexus.sdui.protocol.catalog.CommandSpec;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.section.SectionEditorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * Board-type-level capability query API.
 *
 * Resolves a board type key (e.g., "board:ESP32-S3-LCD-0.85:0") to an example
 * online device of that type, then delegates to the same capability projection
 * used by the debug endpoints.  This closes the loop for the state-machine
 * editor: when a user selects a board type, the editor can fetch all available
 * commands, sections, and events *before* binding a specific device.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sdui/board-types")
@RequiredArgsConstructor
public class BoardTypeController {

    private final CapabilityRegistry capabilityRegistry;
    private final DeviceCapabilityProjection capabilityProjection;
    private final SectionEditorService sectionEditorService;
    private final EventRegistry eventRegistry;
    private final DeviceSessionManager sessionManager;

    // ── List all device types ──

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listTypes() {
        return ApiResponse.ok(capabilityRegistry.getDeviceTypesAsList());
    }

    @GetMapping("/{typeKey}")
    public ApiResponse<Map<String, Object>> getType(@PathVariable String typeKey) {
        return capabilityRegistry.getDeviceType(typeKey)
                .map(info -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("key", info.key());
                    map.put("board", info.board());
                    map.put("label", info.label());
                    map.put("labelSource", info.labelSource());
                    map.put("hasVariants", info.hasVariants());
                    map.put("inputEvents", new ArrayList<>(info.inputEvents()));
                    map.put("outputCommands", new ArrayList<>(info.outputCommands()));
                    map.put("sectionTypes", new ArrayList<>(info.sectionTypes()));
                    map.put("deviceCount", info.deviceCount());
                    map.put("onlineCount", info.onlineCount());
                    map.put("exampleDeviceIds", info.exampleDeviceIds());
                    map.put("lastSeen", info.lastSeen().toString());
                    return ApiResponse.ok(map);
                })
                .orElse(ApiResponse.error(40400, "board type not found: " + typeKey));
    }

    // ── Commands by board type ──

    @GetMapping("/{typeKey}/commands")
    public ApiResponse<Map<String, Object>> commandsByType(@PathVariable String typeKey) {
        return resolveExampleDevice(typeKey, (deviceId) -> {
            List<Map<String, Object>> deviceCommands = new ArrayList<>();
            for (CommandSpec command : capabilityProjection.commands(deviceId)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("command", command.id());
                entry.put("params", command.params().stream()
                        .map(f -> Map.of("name", f.name(), "type", (Object) f.type()))
                        .toList());
                deviceCommands.add(entry);
            }

            CapabilityRegistry.DeviceTypeInfo typeInfo = capabilityRegistry.getDeviceType(typeKey).orElse(null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("typeKey", typeKey);
            data.put("board", typeInfo != null ? typeInfo.board() : null);
            data.put("label", typeInfo != null ? typeInfo.label() : null);
            data.put("exampleDeviceId", deviceId);
            data.put("online", sessionManager.isDeviceOnline(deviceId));
            data.put("deviceCommands", deviceCommands);
            return ApiResponse.ok(data);
        });
    }

    // ── Sections / section-editor by board type ──

    @GetMapping("/{typeKey}/sections")
    public ApiResponse<Map<String, Object>> sectionsByType(@PathVariable String typeKey) {
        return resolveExampleDevice(typeKey, (deviceId) -> {
            Map<String, Object> editor = new LinkedHashMap<>(sectionEditorService.buildSectionEditor(deviceId));

            CapabilityRegistry.DeviceTypeInfo typeInfo = capabilityRegistry.getDeviceType(typeKey).orElse(null);

            editor.put("typeKey", typeKey);
            editor.put("board", typeInfo != null ? typeInfo.board() : null);
            editor.put("label", typeInfo != null ? typeInfo.label() : null);
            editor.put("exampleDeviceId", deviceId);
            editor.put("online", sessionManager.isDeviceOnline(deviceId));
            return ApiResponse.ok(editor);
        });
    }

    // ── Events by board type ──

    @GetMapping("/{typeKey}/events")
    public ApiResponse<Map<String, Object>> eventsByType(@PathVariable String typeKey) {
        return resolveExampleDevice(typeKey, (deviceId) -> {
            // Device-specific public trigger events (user interactions + system events)
            List<EventDefinition> deviceEventDefs = capabilityRegistry.getDeviceEventDefinitions(deviceId);
            List<Map<String, Object>> deviceEvents = new ArrayList<>();
            for (EventDefinition def : deviceEventDefs) {
                if (def.isPublicTrigger()) {
                    deviceEvents.add(def.toPublicMap());
                }
            }

            // Physical input events (buttons, motion sensors) — grouped, no params
            List<Map<String, Object>> physicalInputs = capabilityRegistry.getDevicePhysicalInputs(deviceId);

            // Platform-mediated media capabilities (audio recording, etc.)
            List<Map<String, Object>> mediaCapabilities = capabilityRegistry.getDeviceMediaCapabilities(deviceId);

            CapabilityRegistry.DeviceTypeInfo typeInfo = capabilityRegistry.getDeviceType(typeKey).orElse(null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("typeKey", typeKey);
            data.put("board", typeInfo != null ? typeInfo.board() : null);
            data.put("label", typeInfo != null ? typeInfo.label() : null);
            data.put("exampleDeviceId", deviceId);
            data.put("online", sessionManager.isDeviceOnline(deviceId));

            // deviceEvents: section interaction events this device supports
            data.put("deviceEvents", deviceEvents);
            // physicalInputs: device physical inputs (buttons, motion) — grouped by input
            data.put("physicalInputs", physicalInputs);
            // mediaCapabilities: platform-mediated capabilities (audio recording, etc.)
            data.put("mediaCapabilities", mediaCapabilities);
            // eventOptions: flat list of all public trigger events for editor dropdowns
            data.put("eventOptions", eventRegistry.getFlatEventOptions());
            // eventTree: categorized public trigger event tree (user interaction + system)
            data.put("eventTree", eventRegistry.getPublicInboundEventTree());

            // ── Build merged availableTriggers (deduped, ready for dropdown) ──
            List<Map<String, Object>> availableTriggers = new ArrayList<>();
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();

            // Section interaction events
            for (Map<String, Object> dev : deviceEvents) {
                String eid = string(dev.get("eventId"));
                if (!eid.isBlank() && seen.add(eid)) {
                    availableTriggers.add(Map.of(
                            "eventId", eid,
                            "displayName", string(dev.get("displayName")),
                            "category", string(dev.get("category")),
                            "source", string(dev.get("sourceCapability"))
                    ));
                }
            }
            // Physical input events (flatten grouped structure)
            for (Map<String, Object> input : physicalInputs) {
                String inputName = string(input.get("inputName"));
                String inputLabel = string(input.get("displayName"));
                if (input.get("events") instanceof List<?> evts) {
                    for (Object e : evts) {
                        if (e instanceof Map<?, ?> em) {
                            String eid = string(em.get("eventId"));
                            if (!eid.isBlank() && seen.add(eid)) {
                                availableTriggers.add(Map.of(
                                        "eventId", eid,
                                        "displayName", inputLabel + " · " + string(em.get("displayName")),
                                        "category", "USER_INTERACTION",
                                        "source", inputName
                                ));
                            }
                        }
                    }
                }
            }
            // Media trigger events
            for (Map<String, Object> mc : mediaCapabilities) {
                String capName = string(mc.get("capabilityName"));
                String capLabel = string(mc.get("displayName"));
                if (mc.get("triggerEvents") instanceof List<?> triggers) {
                    for (Object t : triggers) {
                        if (t instanceof Map<?, ?> tm) {
                            String eid = string(tm.get("eventId"));
                            if (!eid.isBlank() && seen.add(eid)) {
                                availableTriggers.add(Map.of(
                                        "eventId", eid,
                                        "displayName", capLabel + " · " + string(tm.get("displayName")),
                                        "category", "SYSTEM_EVENT",
                                        "source", capName
                                ));
                            }
                        }
                    }
                }
            }
            // System events from EventRegistry (dedup — already in eventOptions)
            for (Map<String, String> opt : eventRegistry.getFlatEventOptions()) {
                String eid = opt.get("value");
                if (eid != null && seen.add(eid)) {
                    availableTriggers.add(Map.of(
                            "eventId", eid,
                            "displayName", opt.getOrDefault("label", eid),
                            "category", opt.getOrDefault("category", ""),
                            "source", opt.getOrDefault("source", "")
                    ));
                }
            }
            data.put("availableTriggers", availableTriggers);

            return ApiResponse.ok(data);
        });
    }

    // ── Internal ──

    /**
     * Resolve a board type key to an example online device and execute the callback.
     * Falls back to any device of that type if no online device is available.
     */
    private ApiResponse<Map<String, Object>> resolveExampleDevice(
            String typeKey,
            java.util.function.Function<String, ApiResponse<Map<String, Object>>> callback) {

        CapabilityRegistry.DeviceTypeInfo typeInfo = capabilityRegistry.getDeviceType(typeKey).orElse(null);
        if (typeInfo == null) {
            return ApiResponse.error(40400, "board type not found: " + typeKey);
        }

        // Prefer an online device
        String deviceId = typeInfo.exampleDeviceIds().stream()
                .filter(sessionManager::isDeviceOnline)
                .findFirst()
                .orElse(null);

        // Fall back to any example device
        if (deviceId == null && !typeInfo.exampleDeviceIds().isEmpty()) {
            deviceId = typeInfo.exampleDeviceIds().get(0);
        }

        if (deviceId == null) {
            return ApiResponse.error(40400,
                    "no example device available for board type: " + typeKey
                    + ". Wait for a device of this type to connect.");
        }

        return callback.apply(deviceId);
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
