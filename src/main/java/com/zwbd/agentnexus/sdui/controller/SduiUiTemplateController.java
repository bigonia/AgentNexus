package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.ui.SduiUiTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sdui/ui-templates")
@RequiredArgsConstructor
public class SduiUiTemplateController {

    private final SduiUiTemplateService templateService;

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(templateService.create(body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(templateService.list());
    }

    @GetMapping("/{templateId}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String templateId) {
        try {
            return ApiResponse.ok(templateService.get(templateId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @PutMapping("/{templateId}")
    public ApiResponse<Map<String, Object>> update(@PathVariable String templateId,
                                                   @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(templateService.update(templateId, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @DeleteMapping("/{templateId}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String templateId) {
        try {
            return ApiResponse.ok(templateService.delete(templateId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @PostMapping("/{templateId}/preview")
    public ApiResponse<Map<String, Object>> preview(@PathVariable String templateId,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ApiResponse.ok(templateService.preview(templateId, body != null ? body : Map.of()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }
}
