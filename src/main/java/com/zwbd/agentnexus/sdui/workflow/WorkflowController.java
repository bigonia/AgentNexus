package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sdui/workflow")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @GetMapping("/definition")
    public ApiResponse<List<WorkflowDefinitionEntity>> listDefinitions() {
        return ApiResponse.ok(workflowService.listDefinitions());
    }

    @GetMapping("/definition/{id}")
    public ApiResponse<WorkflowDefinitionEntity> getDefinition(@PathVariable String id) {
        return workflowService.getDefinition(id)
                .map(ApiResponse::ok)
                .orElse(ApiResponse.error(40400, "definition not found"));
    }

    @PostMapping("/definition")
    public ApiResponse<WorkflowDefinitionEntity> createDefinition(@RequestBody WorkflowDefinitionEntity entity) {
        return ApiResponse.ok(workflowService.saveDefinition(entity));
    }

    @PutMapping("/definition/{id}")
    public ApiResponse<WorkflowDefinitionEntity> updateDefinition(@PathVariable String id,
                                                                   @RequestBody WorkflowDefinitionEntity entity) {
        entity.setId(id);
        return ApiResponse.ok(workflowService.saveDefinition(entity));
    }

    @DeleteMapping("/definition/{id}")
    public ApiResponse<Map<String, Object>> deleteDefinition(@PathVariable String id) {
        workflowService.deleteDefinition(id);
        return ApiResponse.ok(Map.of("deleted", id));
    }

    @GetMapping("/node-types")
    public ApiResponse<Map<String, Object>> nodeTypes() {
        return ApiResponse.ok(workflowService.getNodeTypes());
    }

    @PostMapping("/{deviceId}/load")
    public ApiResponse<Map<String, Object>> loadWorkflow(@PathVariable String deviceId,
                                                          @RequestParam String definitionId) {
        try {
            return ApiResponse.ok(workflowService.loadWorkflow(deviceId, definitionId));
        } catch (Exception e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @PostMapping("/{deviceId}/unload")
    public ApiResponse<Map<String, Object>> unloadWorkflow(@PathVariable String deviceId) {
        return ApiResponse.ok(workflowService.unloadWorkflow(deviceId));
    }

    @PostMapping("/{deviceId}/trigger/{triggerId}")
    public ApiResponse<Map<String, Object>> triggerManually(@PathVariable String deviceId,
                                                             @PathVariable String triggerId) {
        boolean sent = workflowService.triggerManually(deviceId, triggerId);
        return ApiResponse.ok(Map.of("sent", sent, "deviceId", deviceId, "triggerId", triggerId));
    }

    @GetMapping("/{deviceId}/status")
    public ApiResponse<Map<String, Object>> deviceStatus(@PathVariable String deviceId) {
        return ApiResponse.ok(workflowService.getDeviceStatus(deviceId));
    }
}
