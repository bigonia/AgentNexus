package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.event.EventRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sdui/events")
@RequiredArgsConstructor
public class EventCatalogController {

    private final EventRegistry eventRegistry;

    @GetMapping("/catalog")
    public ApiResponse<Map<String, Object>> catalog() {
        return ApiResponse.ok(eventRegistry.catalogForEditor());
    }

    @GetMapping("/commands")
    public ApiResponse<Object> commands() {
        return ApiResponse.ok(eventRegistry.getOutboundEventTree());
    }

    @GetMapping("/sections")
    public ApiResponse<Object> sections() {
        return ApiResponse.ok(eventRegistry.catalogForEditor().get("sections"));
    }

    @GetMapping("/inbound-events")
    public ApiResponse<Object> inboundEvents() {
        return ApiResponse.ok(eventRegistry.getInboundEventTree());
    }

    @PostMapping("/validate")
    public ApiResponse<Map<String, Object>> validate(@RequestBody Map<String, Object> body) {
        String eventId = string(body.get("eventId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = body.get("payload") instanceof Map<?, ?> map
                ? normalize(map) : Map.of();
        return ApiResponse.ok(eventRegistry.validatePayload(eventId, payload).toMap());
    }

    @PostMapping("/commands/validate")
    public ApiResponse<Map<String, Object>> validateCommand(@RequestBody Map<String, Object> body) {
        String commandId = string(body.get("commandId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = body.get("params") instanceof Map<?, ?> map
                ? normalize(map) : Map.of();
        return ApiResponse.ok(eventRegistry.validateCommand(commandId, params).toMap());
    }

    @PostMapping("/sections/validate")
    public ApiResponse<Map<String, Object>> validateSection(@RequestBody Map<String, Object> body) {
        String sectionType = string(body.get("sectionType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = body.get("fields") instanceof Map<?, ?> map
                ? normalize(map) : Map.of();
        return ApiResponse.ok(eventRegistry.validateSectionFields(sectionType, fields).toMap());
    }

    private Map<String, Object> normalize(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
