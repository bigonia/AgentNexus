package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.artifact.SduiArtifactService;
import com.zwbd.agentnexus.sdui.workflow.NodeWorkflowDeploymentService;
import com.zwbd.agentnexus.sdui.workflow.NodeWorkflowManagementService;
import com.zwbd.agentnexus.sdui.workflow.NodeWorkflowRuntimeService;
import com.zwbd.agentnexus.sdui.workflow.NodeWorkflowService;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sdui/node-workflows")
@RequiredArgsConstructor
public class NodeWorkflowController {

    private final NodeWorkflowService workflowService;
    private final NodeWorkflowDeploymentService deploymentService;
    private final NodeWorkflowRuntimeService runtimeService;
    private final NodeWorkflowManagementService managementService;
    private final SduiArtifactService artifactService;

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody NodeWorkflowDefinition definition) {
        try {
            return ApiResponse.ok(workflowService.create(definition));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(workflowService.list());
    }

    @GetMapping("/{workflowId}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String workflowId) {
        try {
            return ApiResponse.ok(workflowService.get(workflowId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @PutMapping("/{workflowId}")
    public ApiResponse<Map<String, Object>> update(@PathVariable String workflowId,
                                                   @RequestBody NodeWorkflowDefinition definition) {
        try {
            return ApiResponse.ok(workflowService.update(workflowId, definition));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @DeleteMapping("/{workflowId}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String workflowId) {
        try {
            return ApiResponse.ok(workflowService.delete(workflowId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @PostMapping("/validate")
    public ApiResponse<Map<String, Object>> validate(@RequestBody NodeWorkflowDefinition definition) {
        return ApiResponse.ok(workflowService.validate(definition));
    }

    @GetMapping("/management/overview")
    public ApiResponse<Map<String, Object>> managementOverview() {
        return ApiResponse.ok(managementService.overview());
    }

    @GetMapping("/management/deployments")
    public ApiResponse<List<Map<String, Object>>> managementDeployments(
            @RequestParam(defaultValue = "active") String status) {
        return ApiResponse.ok(managementService.deployments(status));
    }

    @GetMapping("/management/devices/{deviceId}/deployments")
    public ApiResponse<List<Map<String, Object>>> managementDeviceDeployments(@PathVariable String deviceId) {
        return ApiResponse.ok(managementService.deviceDeployments(deviceId));
    }

    @GetMapping("/management/conflicts")
    public ApiResponse<List<Map<String, Object>>> managementConflicts() {
        return ApiResponse.ok(managementService.conflicts());
    }

    @GetMapping("/management/runs/recent")
    public ApiResponse<List<Map<String, Object>>> managementRecentRuns(
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(managementService.recentRuns(status, limit));
    }

    @PostMapping("/{workflowId}/deployments")
    public ApiResponse<Map<String, Object>> deploy(@PathVariable String workflowId,
                                                   @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(deploymentService.deploy(workflowId, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @PostMapping("/{workflowId}/deployments/validate")
    public ApiResponse<Map<String, Object>> validateDeployment(@PathVariable String workflowId,
                                                              @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(deploymentService.validateDeployment(workflowId, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @PostMapping("/{workflowId}/deployments/inspect")
    public ApiResponse<Map<String, Object>> inspectDeployment(@PathVariable String workflowId,
                                                             @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ApiResponse.ok(managementService.inspectDeployment(workflowId, body != null ? body : Map.of()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @GetMapping("/{workflowId}/deployments")
    public ApiResponse<List<Map<String, Object>>> deployments(@PathVariable String workflowId) {
        try {
            return ApiResponse.ok(deploymentService.list(workflowId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @GetMapping("/{workflowId}/deployments/{deploymentId}")
    public ApiResponse<Map<String, Object>> deployment(@PathVariable String workflowId,
                                                       @PathVariable String deploymentId) {
        try {
            return ApiResponse.ok(deploymentService.get(workflowId, deploymentId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @DeleteMapping("/{workflowId}/deployments/{deploymentId}")
    public ApiResponse<Map<String, Object>> stopDeployment(@PathVariable String workflowId,
                                                           @PathVariable String deploymentId) {
        try {
            return ApiResponse.ok(deploymentService.stop(workflowId, deploymentId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @PostMapping("/{workflowId}/deployments/{deploymentId}/test-trigger")
    public ApiResponse<Map<String, Object>> testTrigger(@PathVariable String workflowId,
                                                        @PathVariable String deploymentId,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ApiResponse.ok(runtimeService.testTrigger(workflowId, deploymentId, body != null ? body : Map.of()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @GetMapping("/{workflowId}/deployments/{deploymentId}/runs")
    public ApiResponse<List<Map<String, Object>>> runs(@PathVariable String workflowId,
                                                       @PathVariable String deploymentId) {
        try {
            return ApiResponse.ok(runtimeService.listRuns(workflowId, deploymentId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @GetMapping("/{workflowId}/runs/{runId}")
    public ApiResponse<Map<String, Object>> run(@PathVariable String workflowId,
                                                @PathVariable String runId) {
        try {
            return ApiResponse.ok(runtimeService.getRun(workflowId, runId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40400, e.getMessage());
        }
    }

    @GetMapping("/{workflowId}/runs/{runId}/artifacts")
    public ApiResponse<List<Map<String, Object>>> runArtifacts(@PathVariable String workflowId,
                                                               @PathVariable String runId) {
        return ApiResponse.ok(artifactService.listByRun(workflowId, runId));
    }
}
