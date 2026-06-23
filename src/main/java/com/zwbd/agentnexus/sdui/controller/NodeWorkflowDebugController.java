package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.workflow.NodeWorkflowDeploymentService;
import com.zwbd.agentnexus.sdui.workflow.NodeWorkflowRuntimeService;
import com.zwbd.agentnexus.sdui.workflow.NodeWorkflowService;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sdui/debug/node-workflows")
@RequiredArgsConstructor
public class NodeWorkflowDebugController {

    private final NodeWorkflowService workflowService;
    private final NodeWorkflowDeploymentService deploymentService;
    private final NodeWorkflowRuntimeService runtimeService;

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody NodeWorkflowDefinition definition) {
        try {
            return ApiResponse.ok(workflowService.create(definition));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @PostMapping("/{workflowId}/deploy")
    public ApiResponse<Map<String, Object>> deploy(@PathVariable String workflowId,
                                                   @RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(deploymentService.deploy(workflowId, body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @GetMapping("/{workflowId}/deployments/{deploymentId}")
    public ApiResponse<Map<String, Object>> getDeployment(@PathVariable String workflowId,
                                                          @PathVariable String deploymentId) {
        try {
            return ApiResponse.ok(deploymentService.get(workflowId, deploymentId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(40000, e.getMessage());
        }
    }

    @DeleteMapping("/{workflowId}/deployments/{deploymentId}")
    public ApiResponse<Map<String, Object>> deleteDeployment(@PathVariable String workflowId,
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
            return ApiResponse.ok(runtimeService.testTrigger(
                    workflowId,
                    deploymentId,
                    body != null ? body : Map.of()
            ));
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
}
