package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNodeRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Unified workflow API — definition CRUD, device binding, execution control,
 * runtime status, and webhook ingress.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sdui/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final CapabilityNodeRegistry nodeRegistry;
    private final TriggerScheduler triggerScheduler;
    private final WorkflowEditorService workflowEditorService;

    // ── Definition CRUD ──

    @GetMapping("/definitions")
    public ApiResponse<List<WorkflowDefinitionEntity>> listDefinitions() {
        return ApiResponse.ok(workflowService.listDefinitions());
    }

    @GetMapping("/definitions/{id}")
    public ApiResponse<WorkflowDefinitionEntity> getDefinition(@PathVariable String id) {
        return workflowService.getDefinition(id)
                .map(ApiResponse::ok)
                .orElse(ApiResponse.error(40400, "definition not found"));
    }

    @PostMapping("/definitions")
    public ApiResponse<WorkflowDefinitionEntity> createDefinition(@RequestBody WorkflowDefinitionEntity entity) {
        return ApiResponse.ok(workflowService.saveDefinition(entity));
    }

    @PutMapping("/definitions/{id}")
    public ApiResponse<WorkflowDefinitionEntity> updateDefinition(@PathVariable String id,
                                                                   @RequestBody WorkflowDefinitionEntity entity) {
        entity.setId(id);
        return ApiResponse.ok(workflowService.saveDefinition(entity));
    }

    @DeleteMapping("/definitions/{id}")
    public ApiResponse<Map<String, Object>> deleteDefinition(@PathVariable String id) {
        workflowService.deleteDefinition(id);
        return ApiResponse.ok(Map.of("deleted", id));
    }

    @PostMapping("/definitions/validate")
    public ApiResponse<Map<String, Object>> validateDefinition(@RequestBody WorkflowDefinitionEntity entity) {
        return ApiResponse.ok(workflowService.validateDefinition(entity));
    }

    @GetMapping("/nodes")
    public ApiResponse<Map<String, Object>> listNodes(@RequestParam(required = false) String deviceId) {
        if (deviceId != null && !deviceId.isEmpty()) {
            return ApiResponse.ok(workflowEditorService.buildNodeCatalog(nodeRegistry.getDeviceSchemas(deviceId)));
        }
        return ApiResponse.ok(workflowEditorService.buildNodeCatalog(nodeRegistry.getGlobalSchemas()));
    }

    @GetMapping("/triggers")
    public ApiResponse<Map<String, Object>> listTriggers(@RequestParam(required = false) String deviceId) {
        return ApiResponse.ok(workflowEditorService.buildTriggerCatalog(deviceId));
    }

    @GetMapping("/page-editor")
    public ApiResponse<Map<String, Object>> pageEditor(@RequestParam String deviceId) {
        return ApiResponse.ok(workflowEditorService.buildPageEditor(deviceId));
    }

    @PostMapping("/definitions/scaffold")
    public ApiResponse<WorkflowDefinitionEntity> scaffoldDefinition(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(workflowService.saveDefinition(workflowEditorService.scaffoldDefinition(body)));
    }

    @GetMapping("/definitions/{id}/editor-model")
    public ApiResponse<Map<String, Object>> editorModel(@PathVariable String id) {
        return ApiResponse.ok(workflowEditorService.buildEditorModel(id));
    }

    // ── Device workflow management ──

    @GetMapping("/devices/{deviceId}")
    public ApiResponse<List<Map<String, Object>>> listDeviceWorkflows(@PathVariable String deviceId) {
        return ApiResponse.ok(workflowService.listDeviceWorkflows(deviceId));
    }

    @PostMapping("/devices/{deviceId}/bind")
    public ApiResponse<Map<String, Object>> bindWorkflow(@PathVariable String deviceId,
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

    @DeleteMapping("/devices/{deviceId}/{definitionId}")
    public ApiResponse<Map<String, Object>> unbindWorkflow(@PathVariable String deviceId,
                                                            @PathVariable String definitionId) {
        return ApiResponse.ok(workflowService.unloadWorkflow(deviceId, definitionId));
    }

    // ── Execution control ──

    @PostMapping("/devices/{deviceId}/{definitionId}/pause")
    public ApiResponse<Map<String, Object>> pauseWorkflow(@PathVariable String deviceId,
                                                           @PathVariable String definitionId) {
        return ApiResponse.ok(workflowService.pauseWorkflow(deviceId, definitionId));
    }

    @PostMapping("/devices/{deviceId}/{definitionId}/resume")
    public ApiResponse<Map<String, Object>> resumeWorkflow(@PathVariable String deviceId,
                                                            @PathVariable String definitionId) {
        return ApiResponse.ok(workflowService.resumeWorkflow(deviceId, definitionId));
    }

    @PostMapping("/devices/{deviceId}/trigger/{triggerId}")
    public ApiResponse<Map<String, Object>> triggerManually(@PathVariable String deviceId,
                                                             @PathVariable String triggerId) {
        boolean sent = workflowService.triggerManually(deviceId, triggerId);
        return ApiResponse.ok(Map.of("sent", sent, "deviceId", deviceId, "triggerId", triggerId));
    }

    // ── Runtime status ──

    @GetMapping("/devices/{deviceId}/status")
    public ApiResponse<Map<String, Object>> deviceStatus(@PathVariable String deviceId) {
        return ApiResponse.ok(workflowService.getDeviceStatus(deviceId));
    }

    @GetMapping("/devices/{deviceId}/{definitionId}/status")
    public ApiResponse<Map<String, Object>> workflowStatus(@PathVariable String deviceId,
                                                            @PathVariable String definitionId) {
        return ApiResponse.ok(workflowService.getWorkflowStatus(deviceId, definitionId));
    }

    @GetMapping("/devices/{deviceId}/{definitionId}/variables")
    public ApiResponse<Map<String, Object>> getVariables(@PathVariable String deviceId,
                                                          @PathVariable String definitionId) {
        return ApiResponse.ok(workflowService.getVariables(deviceId, definitionId));
    }

    @GetMapping("/devices/{deviceId}/{definitionId}/executions")
    public ApiResponse<List<Map<String, Object>>> getExecutions(
            @PathVariable String deviceId,
            @PathVariable String definitionId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(workflowService.getExecutionRecords(deviceId, definitionId, limit));
    }

    @GetMapping("/devices/{deviceId}/executions")
    public ApiResponse<List<Map<String, Object>>> getDeviceExecutions(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(workflowService.getExecutionRecords(deviceId, limit));
    }

    @GetMapping("/devices/{deviceId}/triggers")
    public ApiResponse<List<Map<String, Object>>> getTriggerStatus(@PathVariable String deviceId) {
        return ApiResponse.ok(workflowService.getTriggerStatus(deviceId));
    }

    @GetMapping("/devices/{deviceId}/page-state")
    public ApiResponse<Map<String, Object>> devicePageState(@PathVariable String deviceId) {
        return ApiResponse.ok(workflowService.getPageState(deviceId));
    }

    // ── Message injection ──

    @PostMapping("/devices/{deviceId}/message")
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

    // ── Webhook ingress ──

    @PostMapping("/webhook/{path}")
    public Map<String, Object> handleWebhook(@PathVariable String path,
                                              @RequestBody(required = false) Map<String, Object> body) {
        String deviceId = triggerScheduler.findWebhookDevice(path);
        if (deviceId == null) {
            log.warn("No device registered for webhook path: {}", path);
            return Map.of("status", "no_device", "path", path);
        }

        Map<String, Object> payload = body != null ? body : Map.of();
        workflowService.fireEvent(deviceId, "webhook:" + path, payload);
        log.info("Webhook {} -> device {} payload={}", path, deviceId, payload);
        return Map.of("status", "ok", "deviceId", deviceId, "path", path);
    }
}
