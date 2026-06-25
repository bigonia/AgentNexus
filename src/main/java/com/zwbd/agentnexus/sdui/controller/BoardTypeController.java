package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.CapabilityRegistry;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalog;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalogService;
import com.zwbd.agentnexus.sdui.event.EventRegistry;
import com.zwbd.agentnexus.sdui.protocol.catalog.CommandSpec;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.section.SectionEditorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * Board-level capability query API.
 *
 * Resolves a board identifier (e.g., "ESP32-S3-LCD-0.85") to an example
 * online device of that board, then delegates to the same capability projection
 * used by the debug endpoints.  This closes the loop for the state-machine
 * editor: when a user selects a board, the editor can fetch all available
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
    private final CapabilityNodeCatalogService nodeCatalogService;

    // ── List all boards ──

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listBoards() {
        return ApiResponse.ok(capabilityRegistry.getBoardTypesAsList());
    }

    @GetMapping("/{board}")
    public ApiResponse<Map<String, Object>> getBoard(@PathVariable String board) {
        return capabilityRegistry.getBoardType(board)
                .map(info -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("board", info.board());
                    map.put("label", info.label());
                    map.put("inputEvents", new ArrayList<>(info.inputEvents()));
                    map.put("outputCommands", new ArrayList<>(info.outputCommands()));
                    map.put("sectionTypes", new ArrayList<>(info.sectionTypes()));
                    map.put("deviceCount", info.deviceCount());
                    map.put("onlineCount", info.onlineCount());
                    map.put("exampleDeviceIds", info.exampleDeviceIds());
                    map.put("lastSeen", info.lastSeen().toString());
                    return ApiResponse.ok(map);
                })
                .orElse(ApiResponse.error(40400, "board not found: " + board));
    }

    // ── Commands by board ──

    @GetMapping("/{board}/commands")
    public ApiResponse<Map<String, Object>> commandsByBoard(@PathVariable String board) {
        return resolveExampleDevice(board, (deviceId) -> {
            List<Map<String, Object>> deviceCommands = new ArrayList<>();
            for (CommandSpec command : capabilityProjection.commands(deviceId)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("command", command.id());
                entry.put("params", command.params().stream()
                        .map(f -> Map.of("name", f.name(), "type", (Object) f.type()))
                        .toList());
                deviceCommands.add(entry);
            }

            CapabilityRegistry.BoardInfo boardInfo = capabilityRegistry.getBoardType(board).orElse(null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("board", board);
            data.put("label", boardInfo != null ? boardInfo.label() : null);
            data.put("exampleDeviceId", deviceId);
            data.put("online", sessionManager.isDeviceOnline(deviceId));
            data.put("deviceCommands", deviceCommands);
            return ApiResponse.ok(data);
        });
    }

    // ── Sections / section-editor by board ──

    @GetMapping("/{board}/sections")
    public ApiResponse<Map<String, Object>> sectionsByBoard(@PathVariable String board) {
        return resolveExampleDevice(board, (deviceId) -> {
            Map<String, Object> editor = new LinkedHashMap<>(sectionEditorService.buildSectionEditor(deviceId));

            CapabilityRegistry.BoardInfo boardInfo = capabilityRegistry.getBoardType(board).orElse(null);

            editor.put("board", board);
            editor.put("label", boardInfo != null ? boardInfo.label() : null);
            editor.put("exampleDeviceId", deviceId);
            editor.put("online", sessionManager.isDeviceOnline(deviceId));
            return ApiResponse.ok(editor);
        });
    }

    // ── Events by board ──

    @GetMapping("/{board}/events")
    public ApiResponse<Map<String, Object>> eventsByBoard(
            @PathVariable String board,
            @RequestParam(required = false) List<String> sectionTypes) {
        return resolveExampleDevice(board, (deviceId) -> {
            // Build unified event catalog via CapabilityRegistry (single source of truth)
            Set<String> filter = sectionTypes != null && !sectionTypes.isEmpty()
                    ? new LinkedHashSet<>(sectionTypes) : Set.of();
            Map<String, Object> catalog2 = capabilityRegistry.buildEventCatalog(deviceId, filter);

            CapabilityRegistry.BoardInfo boardInfo = capabilityRegistry.getBoardType(board).orElse(null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("board", board);
            data.put("label", boardInfo != null ? boardInfo.label() : null);
            data.put("exampleDeviceId", deviceId);
            data.put("online", sessionManager.isDeviceOnline(deviceId));

            // Backward-compatible keys
            data.put("deviceEvents", catalog2.get("sectionEvents"));
            data.put("physicalInputs", catalog2.get("physicalInputs"));
            data.put("mediaCapabilities", catalog2.get("mediaCapabilities"));
            data.put("eventOptions", eventRegistry.getFlatEventOptions());
            data.put("eventTree", eventRegistry.getPublicInboundEventTree());
            data.put("availableTriggers", catalog2.get("availableTriggers"));

            return ApiResponse.ok(data);
        });
    }

    @GetMapping("/{board}/capability-nodes")
    public ApiResponse<Map<String, Object>> capabilityNodesByBoard(@PathVariable String board) {
        return resolveExampleDevice(board, (deviceId) -> {
            CapabilityRegistry.BoardInfo boardInfo = capabilityRegistry.getBoardType(board).orElse(null);
            CapabilityNodeCatalog catalog = nodeCatalogService.buildForDevice(deviceId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("board", board);
            data.put("label", boardInfo != null ? boardInfo.label() : null);
            data.put("exampleDeviceId", deviceId);
            data.put("online", sessionManager.isDeviceOnline(deviceId));
            data.put("status", catalog.status());
            data.put("nodes", catalog.nodes());
            data.put("unresolvedNodes", catalog.unresolvedNodes());
            return ApiResponse.ok(data);
        });
    }

    // ── Internal ──

    /**
     * Resolve a board identifier to an example online device and execute the callback.
     * Falls back to any device of that board if no online device is available.
     */
    private ApiResponse<Map<String, Object>> resolveExampleDevice(
            String board,
            java.util.function.Function<String, ApiResponse<Map<String, Object>>> callback) {

        CapabilityRegistry.BoardInfo boardInfo = capabilityRegistry.getBoardType(board).orElse(null);
        if (boardInfo == null) {
            return ApiResponse.error(40400, "board not found: " + board);
        }

        // Prefer an online device
        String deviceId = boardInfo.exampleDeviceIds().stream()
                .filter(sessionManager::isDeviceOnline)
                .findFirst()
                .orElse(null);

        // Fall back to any example device
        if (deviceId == null && !boardInfo.exampleDeviceIds().isEmpty()) {
            deviceId = boardInfo.exampleDeviceIds().get(0);
        }

        if (deviceId == null) {
            return ApiResponse.error(40400,
                    "no example device available for board: " + board
                    + ". Wait for a device of this board to connect.");
        }

        return callback.apply(deviceId);
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
