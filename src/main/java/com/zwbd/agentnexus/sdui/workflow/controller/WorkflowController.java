package com.zwbd.agentnexus.sdui.workflow.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.workflow.service.WorkflowNodeCatalogService;
import com.zwbd.agentnexus.sdui.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/sdui/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowNodeCatalogService nodeCatalogService;

    @GetMapping("/nodes")
    public ApiResponse<Map<String, Object>> nodes(@RequestParam String deviceId) {
        return ApiResponse.ok(nodeCatalogService.buildCatalog(deviceId));
    }

    @PostMapping("/validate")
    public ApiResponse<Map<String, Object>> validate(@RequestParam(defaultValue = "") String deviceId,
                                                     @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(workflowService.validate(deviceId, body));
    }

    @GetMapping
    public ApiResponse<Object> list() {
        return ApiResponse.ok(workflowService.list());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(workflowService.create(body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String id) {
        try {
            return ApiResponse.ok(workflowService.get(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(workflowService.update(id, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id) {
        try {
            workflowService.delete(id);
            return ApiResponse.ok(Map.of("deleted", true, "id", id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @PostMapping("/{id}/bindings")
    public ApiResponse<Map<String, Object>> bind(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(workflowService.bind(id, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<Map<String, Object>> enable(@PathVariable String id) {
        try {
            return ApiResponse.ok(workflowService.setEnabled(id, true));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<Map<String, Object>> disable(@PathVariable String id) {
        try {
            return ApiResponse.ok(workflowService.setEnabled(id, false));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @PostMapping("/{id}/trigger")
    public ApiResponse<Map<String, Object>> trigger(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(workflowService.trigger(id, body));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @GetMapping("/{id}/runs")
    public ApiResponse<Object> runs(@PathVariable String id, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(workflowService.runs(id, limit));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<Map<String, Object>> runDetail(@PathVariable String runId) {
        try {
            return ApiResponse.ok(workflowService.runDetail(runId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }
}
