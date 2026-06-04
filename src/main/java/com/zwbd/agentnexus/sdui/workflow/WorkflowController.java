package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNodeRegistry;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sdui/workflow")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final CapabilityNodeRegistry nodeRegistry;

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

    @PostMapping("/definition/validate")
    public ApiResponse<Map<String, Object>> validateDefinition(@RequestBody WorkflowDefinitionEntity entity) {
        return ApiResponse.ok(workflowService.validateDefinition(entity));
    }

    @GetMapping("/node-types")
    public ApiResponse<Map<String, Object>> nodeTypes(
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String deviceType) {
        if (deviceId != null && !deviceId.isEmpty()) {
            return ApiResponse.ok(workflowService.getNodeTypes(deviceId));
        }
        if (deviceType != null && !deviceType.isEmpty()) {
            return ApiResponse.ok(workflowService.getNodeTypesForDeviceType(deviceType));
        }
        return ApiResponse.ok(workflowService.getNodeTypes());
    }

    @GetMapping("/nodes")
    public ApiResponse<List<NodeSchema>> listNodes(@RequestParam(required = false) String deviceId) {
        if (deviceId != null && !deviceId.isEmpty()) {
            return ApiResponse.ok(nodeRegistry.getDeviceSchemas(deviceId));
        }
        return ApiResponse.ok(nodeRegistry.getGlobalSchemas());
    }

    // ── Device-centric workflow management ──

    @GetMapping("/{deviceId}/list")
    public ApiResponse<List<Map<String, Object>>> listDeviceWorkflows(@PathVariable String deviceId) {
        return ApiResponse.ok(workflowService.listDeviceWorkflows(deviceId));
    }

    @PostMapping("/{deviceId}/load")
    public ApiResponse<Map<String, Object>> loadWorkflow(@PathVariable String deviceId,
                                                          @RequestParam String definitionId,
                                                          @RequestParam(defaultValue = "strict") String mode) {
        try {
            if (!List.of("strict", "adaptive", "force").contains(mode)) {
                return ApiResponse.error(40000, "Invalid mode: " + mode + ". Use strict, adaptive, or force.");
            }
            return ApiResponse.ok(workflowService.loadWorkflow(deviceId, definitionId, mode));
        } catch (Exception e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @PostMapping("/{deviceId}/validate/{definitionId}")
    public ApiResponse<Map<String, Object>> validateWorkflow(@PathVariable String deviceId,
                                                              @PathVariable String definitionId) {
        try {
            var issues = workflowService.validateWorkflowCapabilities(deviceId, definitionId);
            return ApiResponse.ok(Map.of(
                    "deviceId", deviceId,
                    "definitionId", definitionId,
                    "valid", issues.isEmpty(),
                    "issues", issues
            ));
        } catch (Exception e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @DeleteMapping("/{deviceId}/{definitionId}")
    public ApiResponse<Map<String, Object>> unloadWorkflow(@PathVariable String deviceId,
                                                            @PathVariable String definitionId) {
        return ApiResponse.ok(workflowService.unloadWorkflow(deviceId, definitionId));
    }

    @PostMapping("/{deviceId}/unload")
    public ApiResponse<Map<String, Object>> unloadWorkflow(@PathVariable String deviceId) {
        return ApiResponse.ok(workflowService.unloadWorkflow(deviceId));
    }

    @PostMapping("/{deviceId}/{definitionId}/pause")
    public ApiResponse<Map<String, Object>> pauseWorkflow(@PathVariable String deviceId,
                                                           @PathVariable String definitionId) {
        return ApiResponse.ok(workflowService.pauseWorkflow(deviceId, definitionId));
    }

    @PostMapping("/{deviceId}/{definitionId}/resume")
    public ApiResponse<Map<String, Object>> resumeWorkflow(@PathVariable String deviceId,
                                                            @PathVariable String definitionId) {
        return ApiResponse.ok(workflowService.resumeWorkflow(deviceId, definitionId));
    }

    @GetMapping("/{deviceId}/{definitionId}/status")
    public ApiResponse<Map<String, Object>> workflowStatus(@PathVariable String deviceId,
                                                            @PathVariable String definitionId) {
        return ApiResponse.ok(workflowService.getWorkflowStatus(deviceId, definitionId));
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

    @GetMapping("/{deviceId}/{definitionId}/variables")
    public ApiResponse<Map<String, Object>> getVariables(@PathVariable String deviceId,
                                                         @PathVariable String definitionId) {
        return ApiResponse.ok(workflowService.getVariables(deviceId, definitionId));
    }

    @GetMapping("/{deviceId}/{definitionId}/executions")
    public ApiResponse<List<ExecutionRecordEntity>> getWorkflowExecutions(
            @PathVariable String deviceId,
            @PathVariable String definitionId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(workflowService.getExecutionRecords(deviceId, definitionId, limit));
    }

    @GetMapping("/{deviceId}/executions")
    public ApiResponse<List<ExecutionRecordEntity>> getDeviceExecutions(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(workflowService.getExecutionRecords(deviceId, limit));
    }

    @GetMapping("/{deviceId}/triggers")
    public ApiResponse<List<Map<String, Object>>> getTriggerStatus(@PathVariable String deviceId) {
        return ApiResponse.ok(workflowService.getTriggerStatus(deviceId));
    }

    @PostMapping("/device/{deviceId}/message")
    public ApiResponse<Map<String, Object>> receiveDeviceMessage(@PathVariable String deviceId,
                                                                  @RequestBody Map<String, Object> body) {
        String messageType = body.get("messageType") instanceof String s ? s : "text";
        String sourceType = body.get("sourceType") instanceof String s ? s : "external";
        String sourceId = body.get("sourceId") instanceof String s ? s : "unknown";
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = body.get("payload") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        DeviceMessage message = new DeviceMessage(messageType, sourceType, sourceId, deviceId, payload);
        return ApiResponse.ok(workflowService.receiveMessage(deviceId, message));
    }

    @GetMapping("/{deviceId}/commands")
    public ApiResponse<List<Map<String, Object>>> deviceCommands(@PathVariable String deviceId) {
        return ApiResponse.ok(workflowService.discoverDeviceCommands(deviceId));
    }

    @GetMapping("/space/commands")
    public ApiResponse<Map<String, Object>> spaceCommands() {
        return ApiResponse.ok(workflowService.discoverSpaceCommands());
    }

    @GetMapping("/{deviceId}/slots")
    public ApiResponse<Map<String, Object>> deviceSlots(@PathVariable String deviceId) {
        return ApiResponse.ok(workflowService.getSlotStatus(deviceId));
    }

    // ── App Store endpoints ──

    @PostMapping("/store/publish/{id}")
    public ApiResponse<Map<String, Object>> publishToStore(@PathVariable String id) {
        return ApiResponse.ok(workflowService.publishDefinition(id));
    }

    @PostMapping("/store/unpublish/{id}")
    public ApiResponse<Map<String, Object>> unpublishFromStore(@PathVariable String id) {
        return ApiResponse.ok(workflowService.unpublishDefinition(id));
    }

    @GetMapping("/store/browse")
    public ApiResponse<List<Map<String, Object>>> browseStore(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String deviceId) {
        return ApiResponse.ok(workflowService.browseStore(category, deviceId));
    }

    @GetMapping("/store/categories")
    public ApiResponse<List<String>> storeCategories() {
        return ApiResponse.ok(workflowService.getStoreCategories());
    }

    @GetMapping("/store/compatibility/{id}")
    public ApiResponse<Map<String, Object>> checkCompatibility(@PathVariable String id,
                                                                @RequestParam String deviceId) {
        return ApiResponse.ok(workflowService.checkCompatibility(id, deviceId));
    }
}
