package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.dto.ConsoleAudioPresetRequest;
import com.zwbd.agentnexus.sdui.dto.ConsoleRgbApplyRequest;
import com.zwbd.agentnexus.sdui.dto.ConsoleTtsRequest;
import com.zwbd.agentnexus.sdui.service.AudioService;
import com.zwbd.agentnexus.sdui.service.AudioService.PlayResult;
import com.zwbd.agentnexus.sdui.service.CommandDispatcher;
import com.zwbd.agentnexus.sdui.service.CommandSchemaRegistry;
import com.zwbd.agentnexus.sdui.service.ConsoleLayoutService;
import com.zwbd.agentnexus.sdui.service.EventStreamService;
import com.zwbd.agentnexus.sdui.service.RgbControlService;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sdui/devices/{deviceId}/console")
@RequiredArgsConstructor
public class DeviceConsoleController {

    private final ConsoleLayoutService layoutService;
    private final RgbControlService rgbControlService;
    private final EventStreamService eventStreamService;
    private final AudioService audioService;
    private final CommandDispatcher commandDispatcher;
    private final DeviceSessionManager sessionManager;
    private final SduiCapabilityService capabilityService;
    private final CommandSchemaRegistry schemaRegistry;

    @GetMapping("/layout")
    public ApiResponse<Map<String, Object>> layout(@PathVariable String deviceId) {
        Map<String, Object> layout = layoutService.buildLayout(deviceId);
        layout.put("ttsAvailable", audioService.isTtsAvailable());
        return ApiResponse.ok(layout);
    }

    @PostMapping("/audio/preset")
    public ApiResponse<Map<String, Object>> playPreset(@PathVariable String deviceId,
                                                        @Valid @RequestBody ConsoleAudioPresetRequest request) {
        if (!audioService.isValidPreset(request.preset())) {
            return ApiResponse.error(40000, "invalid preset: " + request.preset());
        }
        if (!sessionManager.isDeviceOnline(deviceId)) {
            return ApiResponse.error(40000, "device is offline");
        }
        PlayResult result = audioService.playPreset(deviceId, request.preset());
        return ApiResponse.ok(buildPresetResponse(deviceId, request.preset(), result));
    }

    @PostMapping("/audio/tts")
    public ApiResponse<Map<String, Object>> playTts(@PathVariable String deviceId,
                                                     @Valid @RequestBody ConsoleTtsRequest request) {
        if (!audioService.isTtsAvailable()) {
            return ApiResponse.error(40000, "TTS not available: no TtsProvider configured");
        }
        if (!sessionManager.isDeviceOnline(deviceId)) {
            return ApiResponse.error(40000, "device is offline");
        }
        PlayResult result = audioService.playTts(deviceId, request.text());
        if (!result.sent()) {
            return ApiResponse.error(40000, "TTS synthesis failed");
        }
        return ApiResponse.ok(buildTtsResponse(deviceId, request.text(), result));
    }

    @PostMapping("/rgb/apply")
    public ApiResponse<Map<String, Object>> applyRgb(@PathVariable String deviceId,
                                                      @Valid @RequestBody ConsoleRgbApplyRequest request) {
        if (!sessionManager.isDeviceOnline(deviceId)) {
            return ApiResponse.error(40000, "device is offline");
        }
        Map<String, Object> result = rgbControlService.apply(deviceId, request.mode(),
                request.color(), request.periodMs());
        if (Boolean.FALSE.equals(result.get("sent"))) {
            return ApiResponse.error(40000, (String) result.getOrDefault("error", "unknown error"));
        }
        return ApiResponse.ok(result);
    }

    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventStream(@PathVariable String deviceId) {
        return eventStreamService.subscribe(deviceId);
    }

    @PostMapping("/system/reboot")
    public ApiResponse<Map<String, Object>> reboot(@PathVariable String deviceId) {
        if (!sessionManager.isDeviceOnline(deviceId)) {
            return ApiResponse.error(40000, "device is offline");
        }
        CommandDispatcher.DispatchResult result = commandDispatcher.dispatch(deviceId, "device.reboot", null);
        return ApiResponse.ok(Map.of(
                "sent", result.sent(),
                "deviceId", deviceId,
                "cmdId", result.cmdId()
        ));
    }

    @GetMapping("/debug/commands")
    public ApiResponse<Map<String, Object>> debugCommands(@PathVariable String deviceId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceId", deviceId);
        data.put("online", sessionManager.isDeviceOnline(deviceId));
        data.put("availableCommands", capabilityService.getAvailableCommands(deviceId));
        data.put("schemas", schemaRegistry.getAllSchemas(deviceId));
        return ApiResponse.ok(data);
    }

    @PostMapping("/debug/command")
    public ApiResponse<Map<String, Object>> debugCommand(@PathVariable String deviceId,
                                                         @RequestBody Map<String, Object> body) {
        if (!sessionManager.isDeviceOnline(deviceId)) {
            return ApiResponse.error(40000, "device is offline");
        }

        String command = (String) body.getOrDefault("command", "rgb.off");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.get("params");
        String overrideAction = (String) body.get("action");

        // Apply defaults from schema
        Map<String, Object> effectiveParams = schemaRegistry.applyDefaults(deviceId, command, params);

        // Validate against schema
        List<String> validationErrors = schemaRegistry.validate(deviceId, command, effectiveParams);

        CommandDispatcher.DispatchResult result;
        if (!validationErrors.isEmpty()) {
            result = null; // Don't send, just show errors
        } else if (overrideAction != null) {
            result = commandDispatcher.dispatchWithAction(deviceId, command, overrideAction, effectiveParams);
        } else {
            result = commandDispatcher.dispatch(deviceId, command, effectiveParams);
        }

        SduiCapabilityService.CommandRoute route = capabilityService.resolveRoute(deviceId, command);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sent", result != null && result.sent());
        response.put("deviceId", deviceId);
        response.put("online", sessionManager.isDeviceOnline(deviceId));
        response.put("command", command);
        response.put("resolvedTopic", route.topic());
        response.put("resolvedAction", route.action());
        response.put("effectiveAction", result != null ? result.action() : null);
        response.put("appliedDefaults", !effectiveParams.equals(params));
        response.put("validationErrors", validationErrors);
        response.put("paramsSent", effectiveParams);
        response.put("payloadSent", result != null ? result.payload() : null);
        response.put("cmdId", result != null ? result.cmdId() : null);
        response.put("schema", schemaRegistry.getSchema(deviceId, command).orElse(null));
        response.put("availableCommands", capabilityService.getAvailableCommands(deviceId));

        if (!validationErrors.isEmpty() && result == null) {
            response.put("status", "VALIDATION_FAILED");
        } else if (result != null && result.sent()) {
            response.put("status", "SENT");
        } else {
            response.put("status", "SEND_FAILED");
        }
        return ApiResponse.ok(response);
    }

    private Map<String, Object> buildPresetResponse(String deviceId, String preset, PlayResult result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sent", result.sent());
        resp.put("deviceId", deviceId);
        resp.put("preset", preset);
        resp.put("cmdId", result.cmdId());
        resp.put("samples", result.samples());
        resp.put("durationMs", result.durationMs());
        return resp;
    }

    private Map<String, Object> buildTtsResponse(String deviceId, String text, PlayResult result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sent", result.sent());
        resp.put("deviceId", deviceId);
        resp.put("text", text);
        resp.put("cmdId", result.cmdId());
        resp.put("samples", result.samples());
        return resp;
    }
}
