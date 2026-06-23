package com.zwbd.agentnexus.sdui.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.workflow.NodeWorkflowDeploymentService;
import com.zwbd.agentnexus.sdui.workflow.NodeWorkflowRuntimeService;
import com.zwbd.agentnexus.sdui.workflow.NodeWorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NodeWorkflowDebugControllerTest {

    private ObjectMapper objectMapper;
    private NodeWorkflowService workflowService;
    private NodeWorkflowDeploymentService deploymentService;
    private NodeWorkflowRuntimeService runtimeService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        workflowService = mock(NodeWorkflowService.class);
        deploymentService = mock(NodeWorkflowDeploymentService.class);
        runtimeService = mock(NodeWorkflowRuntimeService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new NodeWorkflowDebugController(workflowService, deploymentService, runtimeService)).build();
    }

    @Test
    void createWorkflowReturnsWorkflowId() throws Exception {
        when(workflowService.create(any())).thenReturn(Map.of("workflowId", "wf-1"));

        mockMvc.perform(post("/api/v1/sdui/debug/node-workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "button-to-rgb",
                                "slots", List.of(),
                                "nodes", List.of(),
                                "edges", List.of()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(20000))
                .andExpect(jsonPath("$.data.workflowId").value("wf-1"));
    }

    @Test
    void deployAndReadDeployment() throws Exception {
        when(deploymentService.deploy(eq("wf-1"), anyMap())).thenReturn(Map.of("deploymentId", "dep-1"));
        when(deploymentService.get("wf-1", "dep-1")).thenReturn(Map.of(
                "deploymentId", "dep-1",
                "status", "active"
        ));

        mockMvc.perform(post("/api/v1/sdui/debug/node-workflows/wf-1/deploy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "slotBindings", Map.of("source", "dev-a", "target", "dev-b")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deploymentId").value("dep-1"));

        mockMvc.perform(get("/api/v1/sdui/debug/node-workflows/wf-1/deployments/dep-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("active"));
    }

    @Test
    void testTriggerAndRunsUseExpectedRoutes() throws Exception {
        when(runtimeService.testTrigger(eq("wf-1"), eq("dep-1"), anyMap())).thenReturn(Map.of(
                "runId", "run-1",
                "status", "passed"
        ));
        when(runtimeService.listRuns("wf-1", "dep-1")).thenReturn(List.of(Map.of(
                "runId", "run-1",
                "status", "passed"
        )));

        mockMvc.perform(post("/api/v1/sdui/debug/node-workflows/wf-1/deployments/dep-1/test-trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-1"));

        mockMvc.perform(get("/api/v1/sdui/debug/node-workflows/wf-1/deployments/dep-1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].runId").value("run-1"));
    }
}
