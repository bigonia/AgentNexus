package com.zwbd.agentnexus.sdui.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.CapabilityInvocationValidator;
import com.zwbd.agentnexus.sdui.dto.SduiControlDispatchResult;
import com.zwbd.agentnexus.sdui.model.SduiDeviceCommand;

import com.zwbd.agentnexus.sdui.protocol.catalog.CommandSpec;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.protocol.catalog.FieldSpec;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceCommandRepository;
import com.zwbd.agentnexus.sdui.section.*;
import com.zwbd.agentnexus.sdui.service.*;
import com.zwbd.agentnexus.sdui.debug.DebugArtifactStore;
import com.zwbd.agentnexus.sdui.debug.DebugSessionHandle;
import com.zwbd.agentnexus.sdui.debug.DebugSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;

/**
 * Unified device debugging API.
 * Command execution, section debugging, input event monitoring, and command statistics.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sdui/debug")
@RequiredArgsConstructor
public class DebugController {

    private final CommandService commandService;
    private final CommandSchemaRegistry schemaRegistry;
    private final SduiCapabilityService capabilityService;
    private final DeviceCapabilityProjection capabilityProjection;
    private final CapabilityInvocationValidator invocationValidator;
    private final PlatformCapabilityRuntimeService platformRuntimeService;
    private final DeviceSessionManager sessionManager;
    private final SectionOrchestrationService sectionService;
    private final DebugSectionWorkspaceService debugSectionWorkspaceService;
    private final SectionEditorService sectionEditorService;
    private final EventStreamService eventStreamService;
    private final CommandResultStreamService commandResultStreamService;
    private final SduiDeviceCommandRepository commandRepository;
    private final DebugSessionService sessionService;
    private final DebugArtifactStore artifactStore;
    private final ObjectMapper objectMapper;

    // ── Command execution ──

    @PostMapping("/{deviceId}/command")
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

        CapabilityInvocationValidator.ValidationResult validation =
                invocationValidator.validateDebugInvocation(deviceId, command, params);
        if (!validation.valid()) {
            Map<String, Object> errorResponse = new LinkedHashMap<>();
            errorResponse.put("sent", false);
            errorResponse.put("deviceId", deviceId);
            errorResponse.put("command", command);
            errorResponse.put("status", "VALIDATION_FAILED");
            errorResponse.put("validationErrors", validation.errors());
            return ApiResponse.ok(errorResponse);
        }

        if (platformRuntimeService.supports(command)) {
            Map<String, Object> result = platformRuntimeService.execute(deviceId, command, validation.normalizedParams());
            if ("ERROR".equals(result.get("status"))) {
                return ApiResponse.error(40000, String.valueOf(result.getOrDefault("error", "platform capability error")));
            }
            return ApiResponse.ok(result);
        }

        SduiControlDispatchResult dispResult =
                commandService.dispatchCommand(deviceId, command, validation.normalizedParams());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sent", dispResult.sent());
        response.put("deviceId", deviceId);
        response.put("command", command);
        response.put("cmdId", dispResult.cmdId());
        response.put("dispatchStatus", dispResult.sent() ? "sent" : "send_failed");
        response.put("ackStatus", dispResult.status());
        return ApiResponse.ok(response);
    }

    // ── Command schemas ──

    @GetMapping("/{deviceId}/commands")
    public ApiResponse<Map<String, Object>> commandSchemas(@PathVariable String deviceId) {
        List<Map<String, Object>> deviceCommands = new ArrayList<>();
        for (CommandSpec command : capabilityProjection.commands(deviceId)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("command", command.id());
            entry.put("params", command.params().stream().map(this::fieldToMap).toList());
            deviceCommands.add(entry);
        }

        if (deviceCommands.isEmpty()) {
            boolean hasCaps = capabilityService.getCapabilities(deviceId).isPresent();
            log.info("No commands available for device {} (online={}, hasCapabilitySnapshot={})",
                    deviceId, sessionManager.isDeviceOnline(deviceId), hasCaps);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceId", deviceId);
        data.put("online", sessionManager.isDeviceOnline(deviceId));
        data.put("deviceCommands", deviceCommands);
        return ApiResponse.ok(data);
    }

    @GetMapping("/{deviceId}/commands/{cmdId}")
    public ApiResponse<Map<String, Object>> commandDetail(@PathVariable String deviceId,
                                                          @PathVariable String cmdId) {
        return commandRepository.findFirstByDeviceIdAndCmdIdOrderByCreatedAtDesc(deviceId, cmdId)
                .map(cmd -> ApiResponse.ok(toCommandDetail(cmd)))
                .orElse(ApiResponse.error(40400, "command not found"));
    }

    @GetMapping("/{deviceId}/commands/history")
    public ApiResponse<Map<String, Object>> commandHistory(@PathVariable String deviceId,
                                                           @RequestParam(defaultValue = "20") int limit) {
        int sanitizedLimit = Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> items = commandRepository.findHistoryByDeviceId(
                        deviceId, PageRequest.of(0, sanitizedLimit))
                .stream()
                .map(this::toCommandDetail)
                .toList();
        return ApiResponse.ok(Map.of(
                "deviceId", deviceId,
                "history", items
        ));
    }

    @GetMapping(value = "/{deviceId}/commands/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter commandStream(@PathVariable String deviceId) {
        return commandResultStreamService.subscribe(deviceId);
    }

    // ── Section push ──

    @PostMapping("/{deviceId}/section")
    public ApiResponse<Map<String, Object>> pushSection(@PathVariable String deviceId,
                                                         @RequestBody Map<String, Object> body) {
        if (!sessionManager.isDeviceOnline(deviceId)) {
            return ApiResponse.error(40000, "device is offline");
        }
        try {
            return ApiResponse.ok(debugSectionWorkspaceService.push(deviceId, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @GetMapping("/{deviceId}/section-editor")
    public ApiResponse<Map<String, Object>> sectionEditor(@PathVariable String deviceId) {
        Map<String, Object> editor = new LinkedHashMap<>(sectionEditorService.buildSectionEditor(deviceId));
        editor.put("online", sessionManager.isDeviceOnline(deviceId));
        return ApiResponse.ok(editor);
    }

    @PostMapping("/{deviceId}/section/patch")
    public ApiResponse<Map<String, Object>> patchSection(@PathVariable String deviceId,
                                                         @RequestBody Map<String, Object> body) {
        if (!sessionManager.isDeviceOnline(deviceId)) {
            return ApiResponse.error(40000, "device is offline");
        }
        try {
            return ApiResponse.ok(debugSectionWorkspaceService.patch(deviceId, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @GetMapping("/{deviceId}/section/state")
    public ApiResponse<Map<String, Object>> sectionState(@PathVariable String deviceId) {
        Map<String, Object> state = new LinkedHashMap<>(debugSectionWorkspaceService.getState(deviceId));
        state.put("online", sessionManager.isDeviceOnline(deviceId));
        return ApiResponse.ok(state);
    }

    @DeleteMapping("/{deviceId}/section/state")
    public ApiResponse<Map<String, Object>> clearSectionState(@PathVariable String deviceId) {
        Map<String, Object> state = new LinkedHashMap<>(debugSectionWorkspaceService.clear(deviceId));
        state.put("online", sessionManager.isDeviceOnline(deviceId));
        return ApiResponse.ok(state);
    }

    /**
     * Export the current debug workspace as a state-machine-compatible page state.
     * Call this after building a page in the section debug editor to get JSON
     * suitable for pasting into a state machine definition's {@code states} array.
     */
    @GetMapping("/{deviceId}/section/state/as-state")
    public ApiResponse<Map<String, Object>> sectionStateAsStateMachinePage(@PathVariable String deviceId) {
        Map<String, Object> result = new LinkedHashMap<>(
                debugSectionWorkspaceService.getStateAsStateMachinePage(deviceId));
        result.put("online", sessionManager.isDeviceOnline(deviceId));
        return ApiResponse.ok(result);
    }

    // ── SSE event stream ──

    @GetMapping(value = "/{deviceId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventStream(@PathVariable String deviceId) {
        return eventStreamService.subscribe(deviceId);
    }

    // ── Generic debug sessions ──

    /**
     * List all active debug sessions for a device.
     */
    @GetMapping("/{deviceId}/sessions")
    public ApiResponse<Map<String, Object>> listSessions(@PathVariable String deviceId) {
        List<Map<String, Object>> items = sessionService.listSessions(deviceId).stream()
                .map(this::sessionToMap)
                .toList();
        return ApiResponse.ok(Map.of(
                "deviceId", deviceId,
                "online", sessionManager.isDeviceOnline(deviceId),
                "sessions", items
        ));
    }

    /**
     * Get a specific debug session by id (e.g. "audio-record").
     */
    @GetMapping("/{deviceId}/sessions/{sessionId}")
    public ApiResponse<Map<String, Object>> getSession(@PathVariable String deviceId,
                                                       @PathVariable String sessionId) {
        return sessionService.getSession(deviceId, sessionId)
                .map(s -> ApiResponse.ok(sessionToMap(s)))
                .orElse(ApiResponse.error(40400, "session not found: " + sessionId));
    }

    // ── Generic debug artifacts ──

    /**
     * Get artifact metadata (sttText, durationMs, etc.) without the binary blob.
     * For the binary blob, use the .../blob endpoint.
     */
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

    /**
     * Download the binary blob for an artifact.
     * For audio recordings this returns the WAV file (Content-Type: audio/wav).
     */
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

    private Map<String, Object> toCommandDetail(SduiDeviceCommand cmd) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("cmdId", cmd.getCmdId());
        detail.put("deviceId", cmd.getDeviceId());
        detail.put("command", cmd.getCommand());
        detail.put("action", cmd.getAction());
        detail.put("params", parsePayload(cmd.getPayload()));
        detail.put("dispatchStatus", "FAILED".equalsIgnoreCase(cmd.getStatus()) ? "send_failed" : "sent");
        detail.put("ackStatus", cmd.getStatus());
        detail.put("reason", cmd.getReason());
        detail.put("createdAt", cmd.getCreatedAt() != null ? cmd.getCreatedAt().toString() : null);
        detail.put("ackAt", cmd.getAckTs() != null
                ? java.time.Instant.ofEpochMilli(cmd.getAckTs()).atOffset(java.time.ZoneOffset.UTC).toString()
                : null);
        return detail;
    }

    private Object parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return payload;
        }
    }


    private Map<String, Object> fieldToMap(FieldSpec field) {
        return Map.of(
                "name", field.name(),
                "type", field.type()
        );
    }

}
