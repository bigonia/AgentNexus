package com.zwbd.agentnexus.sdui.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.artifact.SduiArtifactService;
import com.zwbd.agentnexus.sdui.workflow.NodeWorkflowDeploymentService;
import com.zwbd.agentnexus.sdui.workflow.NodeWorkflowManagementService;
import com.zwbd.agentnexus.sdui.workflow.NodeWorkflowRuntimeService;
import com.zwbd.agentnexus.sdui.workflow.NodeWorkflowService;
import com.zwbd.agentnexus.sdui.ui.DevicePrimaryUiService;
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

class NodeWorkflowControllerTest {

    private ObjectMapper objectMapper;
    private NodeWorkflowService workflowService;
    private NodeWorkflowDeploymentService deploymentService;
    private NodeWorkflowRuntimeService runtimeService;
    private NodeWorkflowManagementService managementService;
    private SduiArtifactService artifactService;
    private DevicePrimaryUiService primaryUiService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        workflowService = mock(NodeWorkflowService.class);
        deploymentService = mock(NodeWorkflowDeploymentService.class);
        runtimeService = mock(NodeWorkflowRuntimeService.class);
        managementService = mock(NodeWorkflowManagementService.class);
        artifactService = mock(SduiArtifactService.class);
        primaryUiService = mock(DevicePrimaryUiService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new NodeWorkflowController(workflowService, deploymentService, runtimeService, managementService, artifactService, primaryUiService)).build();
    }

    @Test
    void workflowCrudRoutesReturnApiResponse() throws Exception {
        when(workflowService.create(any())).thenReturn(Map.of("workflowId", "wf-1"));
        when(workflowService.list()).thenReturn(List.of(Map.of("workflowId", "wf-1")));
        when(workflowService.get("wf-1")).thenReturn(Map.of("workflowId", "wf-1"));
        when(workflowService.update(eq("wf-1"), any())).thenReturn(Map.of("workflowId", "wf-1", "version", 2));
        when(workflowService.delete("wf-1")).thenReturn(Map.of("deleted", true));

        String body = objectMapper.writeValueAsString(Map.of(
                "name", "button-to-rgb",
                "slots", List.of(),
                "nodes", List.of(),
                "edges", List.of()
        ));

        mockMvc.perform(post("/api/v1/sdui/node-workflows").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.workflowId").value("wf-1"));
        mockMvc.perform(get("/api/v1/sdui/node-workflows"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].workflowId").value("wf-1"));
        mockMvc.perform(get("/api/v1/sdui/node-workflows/wf-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.workflowId").value("wf-1"));
        mockMvc.perform(put("/api/v1/sdui/node-workflows/wf-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        mockMvc.perform(delete("/api/v1/sdui/node-workflows/wf-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void deploymentAndRunRoutesReturnApiResponse() throws Exception {
        when(deploymentService.deploy(eq("wf-1"), anyMap())).thenReturn(Map.of("deploymentId", "dep-1"));
        when(deploymentService.list("wf-1")).thenReturn(List.of(Map.of("deploymentId", "dep-1")));
        when(deploymentService.get("wf-1", "dep-1")).thenReturn(Map.of("deploymentId", "dep-1", "status", "active"));
        when(deploymentService.stop("wf-1", "dep-1")).thenReturn(Map.of("stopped", true));
        when(runtimeService.testTrigger(eq("wf-1"), eq("dep-1"), anyMap())).thenReturn(Map.of("runId", "run-1"));
        when(runtimeService.listRuns("wf-1", "dep-1")).thenReturn(List.of(Map.of("runId", "run-1")));
        when(runtimeService.getRun("wf-1", "run-1")).thenReturn(Map.of("runId", "run-1", "steps", List.of()));
        when(artifactService.listByRun("wf-1", "run-1")).thenReturn(List.of(Map.of("artifactId", "art-1")));

        mockMvc.perform(post("/api/v1/sdui/node-workflows/wf-1/deployments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("slotBindings", Map.of("source", "a")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deploymentId").value("dep-1"));
        mockMvc.perform(get("/api/v1/sdui/node-workflows/wf-1/deployments"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].deploymentId").value("dep-1"));
        mockMvc.perform(post("/api/v1/sdui/node-workflows/wf-1/deployments/dep-1/test-trigger")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.runId").value("run-1"));
        mockMvc.perform(get("/api/v1/sdui/node-workflows/wf-1/deployments/dep-1/runs"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].runId").value("run-1"));
        mockMvc.perform(get("/api/v1/sdui/node-workflows/wf-1/runs/run-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.runId").value("run-1"));
        mockMvc.perform(get("/api/v1/sdui/node-workflows/wf-1/runs/run-1/artifacts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].artifactId").value("art-1"));
        mockMvc.perform(delete("/api/v1/sdui/node-workflows/wf-1/deployments/dep-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.stopped").value(true));
    }

    @Test
    void managementRoutesReturnApiResponse() throws Exception {
        when(managementService.overview()).thenReturn(Map.of("workflowCount", 1, "activeDeploymentCount", 1));
        when(managementService.deployments("active")).thenReturn(List.of(Map.of("deploymentId", "dep-1")));
        when(managementService.deviceDeployments("dev-a")).thenReturn(List.of(Map.of("deviceId", "dev-a")));
        when(managementService.conflicts()).thenReturn(List.of(Map.of("type", "blocking_conflict")));
        when(managementService.recentRuns("failed", 10)).thenReturn(List.of(Map.of("runId", "run-1")));
        when(managementService.inspectDeployment(eq("wf-1"), anyMap())).thenReturn(Map.of("valid", true));
        when(primaryUiService.setPrimary(eq("dev-a"), anyMap())).thenReturn(Map.of("deviceId", "dev-a", "deploymentId", "dep-1"));
        when(primaryUiService.setPrimaryForDeployment(eq("dep-1"), eq("dev-a"), anyMap()))
                .thenReturn(Map.of("deviceId", "dev-a", "deploymentId", "dep-1", "primaryUi", true));
        when(primaryUiService.getPrimary("dev-a")).thenReturn(Map.of("deviceId", "dev-a", "deploymentId", "dep-1"));
        when(primaryUiService.clearPrimary("dev-a")).thenReturn(Map.of("deviceId", "dev-a", "cleared", true));

        mockMvc.perform(get("/api/v1/sdui/node-workflows/management/overview"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.workflowCount").value(1));
        mockMvc.perform(get("/api/v1/sdui/node-workflows/management/deployments?status=active"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].deploymentId").value("dep-1"));
        mockMvc.perform(get("/api/v1/sdui/node-workflows/management/devices/dev-a/deployments"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].deviceId").value("dev-a"));
        mockMvc.perform(get("/api/v1/sdui/node-workflows/management/conflicts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].type").value("blocking_conflict"));
        mockMvc.perform(get("/api/v1/sdui/node-workflows/management/runs/recent?status=failed&limit=10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].runId").value("run-1"));
        mockMvc.perform(post("/api/v1/sdui/node-workflows/management/devices/dev-a/primary-ui")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("deploymentId", "dep-1", "slotId", "source", "templateKey", "main"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deploymentId").value("dep-1"));
        mockMvc.perform(get("/api/v1/sdui/node-workflows/management/devices/dev-a/primary-ui"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deploymentId").value("dep-1"));
        mockMvc.perform(delete("/api/v1/sdui/node-workflows/management/devices/dev-a/primary-ui"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.cleared").value(true));
        mockMvc.perform(post("/api/v1/sdui/node-workflows/management/deployments/dep-1/devices/dev-a/primary-ui")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.primaryUi").value(true));
        mockMvc.perform(post("/api/v1/sdui/node-workflows/wf-1/deployments/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("slotBindings", Map.of("source", "dev-a")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.valid").value(true));
    }
}
