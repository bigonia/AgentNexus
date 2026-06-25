package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.node.CapabilityNodeCatalogService;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDefinitionEntity;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDeploymentEntity;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowRunEntity;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowDefinition;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowEdge;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowNode;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowSlot;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowDefinitionRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowDeploymentRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowRunRepository;
import com.zwbd.agentnexus.sdui.ui.WorkflowUiContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class NodeWorkflowManagementServiceTest {

    private NodeWorkflowService workflowService;
    private NodeWorkflowDeploymentRepository deploymentRepository;
    private NodeWorkflowRunRepository runRepository;
    private DeviceSessionManager sessionManager;
    private NodeWorkflowManagementService service;

    @BeforeEach
    void setUp() {
        workflowService = mock(NodeWorkflowService.class);
        deploymentRepository = mock(NodeWorkflowDeploymentRepository.class);
        runRepository = mock(NodeWorkflowRunRepository.class);
        sessionManager = mock(DeviceSessionManager.class);
        NodeWorkflowDefinitionRepository workflowRepository = mock(NodeWorkflowDefinitionRepository.class);
        NodeWorkflowDeploymentService deploymentService = new NodeWorkflowDeploymentService(
                workflowService,
                deploymentRepository,
                sessionManager,
                mock(CapabilityNodeCatalogService.class),
                mock(WorkflowUiContextService.class)
        );
        service = new NodeWorkflowManagementService(
                workflowService,
                deploymentService,
                workflowRepository,
                deploymentRepository,
                runRepository,
                sessionManager
        );
    }

    @Test
    void conflictsFindSameDeviceEventAndButtonAcrossActiveDeployments() {
        NodeWorkflowDeploymentEntity dep1 = deployment("dep-1", "wf-1");
        NodeWorkflowDeploymentEntity dep2 = deployment("dep-2", "wf-2");
        when(deploymentRepository.findByStatus("active")).thenReturn(List.of(dep1, dep2));
        when(workflowService.workflow("wf-1")).thenReturn(workflow("wf-1"));
        when(workflowService.workflow("wf-2")).thenReturn(workflow("wf-2"));
        when(workflowService.requireEntity("wf-1")).thenReturn(workflowEntity("wf-1", "one"));
        when(workflowService.requireEntity("wf-2")).thenReturn(workflowEntity("wf-2", "two"));

        List<Map<String, Object>> conflicts = service.conflicts();

        assertEquals(1, conflicts.size());
        assertEquals("blocking_conflict", conflicts.get(0).get("type"));
    }

    @Test
    void deploymentsIncludeRunCountsAndLastFailure() {
        NodeWorkflowDeploymentEntity deployment = deployment("dep-1", "wf-1");
        NodeWorkflowRunEntity failed = run("run-2", "failed", "boom");
        NodeWorkflowRunEntity passed = run("run-1", "passed", null);
        when(deploymentRepository.findByStatusOrderByDeployedAtDesc("active")).thenReturn(List.of(deployment));
        when(workflowService.requireEntity("wf-1")).thenReturn(workflowEntity("wf-1", "button workflow"));
        when(runRepository.findByDeploymentIdOrderByStartedAtDesc("dep-1")).thenReturn(List.of(failed, passed));
        when(sessionManager.isDeviceOnline("dev-a")).thenReturn(true);
        when(sessionManager.isDeviceOnline("dev-b")).thenReturn(true);

        List<Map<String, Object>> deployments = service.deployments("active");

        assertEquals(1, deployments.size());
        assertEquals("failed", deployments.get(0).get("lastStatus"));
        assertEquals("boom", deployments.get(0).get("lastError"));
        assertEquals(2, deployments.get(0).get("runCount"));
        assertEquals(1L, deployments.get(0).get("failedRunCount"));
    }

    private NodeWorkflowDeploymentEntity deployment(String id, String workflowId) {
        NodeWorkflowDeploymentEntity deployment = new NodeWorkflowDeploymentEntity();
        deployment.setId(id);
        deployment.setWorkflowId(workflowId);
        deployment.setWorkflowVersion(1);
        deployment.setStatus("active");
        deployment.setSlotBindings(Map.of("source", "dev-a", "target", "dev-b"));
        deployment.setDeployedAt(LocalDateTime.now());
        return deployment;
    }

    private NodeWorkflowRunEntity run(String id, String status, String error) {
        NodeWorkflowRunEntity run = new NodeWorkflowRunEntity();
        run.setId(id);
        run.setWorkflowId("wf-1");
        run.setDeploymentId("dep-1");
        run.setTriggerNodeId("source_button");
        run.setStatus(status);
        run.setError(error);
        run.setStartedAt(LocalDateTime.now());
        return run;
    }

    private NodeWorkflowDefinition workflow(String id) {
        return new NodeWorkflowDefinition(
                id,
                "workflow",
                List.of(
                        new NodeWorkflowSlot("source", "type-a", "Source", List.of()),
                        new NodeWorkflowSlot("target", "type-a", "Target", List.of())
                ),
                List.of(
                        new NodeWorkflowNode("source_button", "source", "button.trigger",
                                Map.of("eventId", "input:buttons.pwr.short_press", "nodeId", "pwr")),
                        new NodeWorkflowNode("target_rgb", "target", "rgb.effect", Map.of("mode", "solid"))
                ),
                List.of(new NodeWorkflowEdge("source_button", "target_rgb"))
        );
    }

    private NodeWorkflowDefinitionEntity workflowEntity(String id, String name) {
        NodeWorkflowDefinitionEntity entity = new NodeWorkflowDefinitionEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setVersion(1);
        entity.setStatus("draft");
        return entity;
    }
}
