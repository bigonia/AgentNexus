package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.artifact.SduiArtifactService;
import com.zwbd.agentnexus.sdui.v2.business.BusinessInteraction;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowDeploymentEntity;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowRunEntity;
import com.zwbd.agentnexus.sdui.workflow.entity.NodeWorkflowRunStepEntity;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowDefinition;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowEdge;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowNode;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowSlot;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowDeploymentRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowRunRepository;
import com.zwbd.agentnexus.sdui.workflow.repo.NodeWorkflowRunStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 交互上报驱动的平台续接。
 *
 * <p>这些用例固定 P5b 的几条关键约定：只有能解析出工作流上下文的上报才续接、平台步骤只跑部署时
 * 固化下来的那一份、单步失败即中止整条续接、终端本地动作不算失败。</p>
 */
class NodeWorkflowRuntimeServiceTest {

    private NodeWorkflowService workflowService;
    private NodeWorkflowDeploymentService deploymentService;
    private NodeWorkflowDeploymentRepository deploymentRepository;
    private NodeWorkflowRunRepository runRepository;
    private NodeWorkflowRunStepRepository stepRepository;
    private WorkflowPlatformStepExecutor executor;
    private NodeWorkflowRuntimeService runtime;

    @BeforeEach
    void setUp() {
        workflowService = mock(NodeWorkflowService.class);
        deploymentService = mock(NodeWorkflowDeploymentService.class);
        deploymentRepository = mock(NodeWorkflowDeploymentRepository.class);
        runRepository = mock(NodeWorkflowRunRepository.class);
        stepRepository = mock(NodeWorkflowRunStepRepository.class);
        executor = mock(WorkflowPlatformStepExecutor.class);

        runtime = new NodeWorkflowRuntimeService(
                workflowService,
                deploymentService,
                deploymentRepository,
                runRepository,
                stepRepository,
                executor,
                new NodeWorkflowRunContextService(mock(SduiArtifactService.class)),
                new NodeWorkflowParameterResolver(new NodeTypeRegistry()));
        stubRunSave();
    }

    @Test
    @DisplayName("跨 slot 的平台步骤按部署时固化的快照执行，并落到目标设备")
    void executesPersistedPlatformStepOnTargetDevice() {
        NodeWorkflowDeploymentEntity deployment = deployment();
        deployment.setBusinessConfigs(businessConfigs("dev-a", platformStep(
                "source_button", "target", "target_rgb", "rgb.effect",
                Map.of("mode", "solid", "r", 0, "g", 120, "b", 255), "跨 slot 节点")));
        when(workflowService.workflow("wf-1")).thenReturn(workflow());
        when(deploymentRepository.findByWorkflowIdAndStatusOrderByDeployedAtDesc("wf-1", "active"))
                .thenReturn(List.of(deployment));
        when(executor.execute(eq("dev-b"), eq("rgb.effect"), anyMap(), anyMap()))
                .thenReturn(new WorkflowPlatformStepExecutor.StepOutcome(
                        true, WorkflowPlatformStepExecutor.STATUS_DELIVERED, Map.of("source", "scene")));

        runtime.onInteraction(interaction("dev-a", "wf:wf-1:source_button"));

        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(executor).execute(eq("dev-b"), eq("rgb.effect"),
                eq(Map.of("mode", "solid", "r", 0, "g", 120, "b", 255)), contextCaptor.capture());
        assertEquals("run-1", contextCaptor.getValue().get("runId"));
        assertEquals("source_button", contextCaptor.getValue().get("triggerNodeId"));
        assertEquals("target", contextCaptor.getValue().get("slotId"));

        NodeWorkflowRunEntity run = capturedRun();
        assertEquals("passed", run.getStatus());
        assertEquals("source_button", run.getTriggerNodeId());
        assertEquals("platform.interaction", run.getTriggerEvent().get("source"));
    }

    @Test
    @DisplayName("平台步骤失败时中止后续步骤并把运行标记为失败")
    void failingStepStopsTheRest() {
        NodeWorkflowDeploymentEntity deployment = deployment();
        deployment.setBusinessConfigs(businessConfigs("dev-a",
                platformStep("source_button", "target", "target_rgb", "rgb.effect", Map.of(), "跨 slot 节点"),
                platformStep("source_button", "target", "target_section", "display.section",
                        Map.of("scene", Map.of()), "需要平台渲染")));
        when(workflowService.workflow("wf-1")).thenReturn(workflow());
        when(deploymentRepository.findByWorkflowIdAndStatusOrderByDeployedAtDesc("wf-1", "active"))
                .thenReturn(List.of(deployment));
        when(executor.execute(eq("dev-b"), eq("rgb.effect"), anyMap(), anyMap()))
                .thenReturn(new WorkflowPlatformStepExecutor.StepOutcome(
                        false, WorkflowPlatformStepExecutor.STATUS_FAILED, Map.of("reason", "设备不在线")));

        runtime.onInteraction(interaction("dev-a", "wf:wf-1:source_button"));

        verify(executor, never()).execute(eq("dev-b"), eq("display.section"), anyMap(), anyMap());
        NodeWorkflowRunEntity run = capturedRun();
        assertEquals("failed", run.getStatus());
        assertEquals("设备不在线", run.getError());
    }

    @Test
    @DisplayName("终端本地动作不算失败，运行继续")
    void terminalActionRequiredIsNotFailure() {
        NodeWorkflowDeploymentEntity deployment = deployment();
        deployment.setBusinessConfigs(businessConfigs("dev-a", platformStep(
                "source_button", "target", "target_rgb", "rgb.effect",
                Map.of("mode", "solid"), "RGB 只能由终端执行")));
        when(workflowService.workflow("wf-1")).thenReturn(workflow());
        when(deploymentRepository.findByWorkflowIdAndStatusOrderByDeployedAtDesc("wf-1", "active"))
                .thenReturn(List.of(deployment));
        when(executor.execute(eq("dev-b"), eq("rgb.effect"), anyMap(), anyMap()))
                .thenReturn(new WorkflowPlatformStepExecutor.StepOutcome(
                        true, WorkflowPlatformStepExecutor.STATUS_TERMINAL_ACTION_REQUIRED,
                        Map.of("reason", "RGB 只能由终端执行")));

        runtime.onInteraction(interaction("dev-a", "wf:wf-1:source_button"));

        assertEquals("passed", capturedRun().getStatus());
        ArgumentCaptor<NodeWorkflowRunStepEntity> stepCaptor =
                ArgumentCaptor.forClass(NodeWorkflowRunStepEntity.class);
        verify(stepRepository).save(stepCaptor.capture());
        assertEquals(WorkflowPlatformStepExecutor.STATUS_TERMINAL_ACTION_REQUIRED, stepCaptor.getValue().getStatus());
        assertNull(stepCaptor.getValue().getError());
    }

    @Test
    @DisplayName("非工作流场景的 contextRef 不触发续接")
    void ignoresNonWorkflowContextRef() {
        runtime.onInteraction(interaction("dev-a", "binding:button.ok"));

        verify(deploymentRepository, never())
                .findByWorkflowIdAndStatusOrderByDeployedAtDesc(any(), any());
        verify(executor, never()).execute(any(), any(), anyMap(), anyMap());
    }

    @Test
    @DisplayName("找不到活动部署时静默丢弃，不产生运行记录")
    void dropsWhenNoActiveDeployment() {
        when(deploymentRepository.findByWorkflowIdAndStatusOrderByDeployedAtDesc("wf-1", "active"))
                .thenReturn(List.of());

        runtime.onInteraction(interaction("dev-a", "wf:wf-1:source_button"));

        verify(executor, never()).execute(any(), any(), anyMap(), anyMap());
        verify(runRepository, never()).save(any());
    }

    @Test
    @DisplayName("从未参与部署的设备上报不会被其他设备的部署接住")
    void ignoresDeviceNotPartOfDeployment() {
        NodeWorkflowDeploymentEntity deployment = deployment();
        deployment.setBusinessConfigs(businessConfigs("dev-a", platformStep(
                "source_button", "target", "target_rgb", "rgb.effect", Map.of(), "跨 slot 节点")));
        when(workflowService.workflow("wf-1")).thenReturn(workflow());
        when(deploymentRepository.findByWorkflowIdAndStatusOrderByDeployedAtDesc("wf-1", "active"))
                .thenReturn(List.of(deployment));

        runtime.onInteraction(interaction("dev-x", "wf:wf-1:source_button"));

        verify(executor, never()).execute(any(), any(), anyMap(), anyMap());
    }

    @Test
    @DisplayName("触发节点已被删除时丢弃上报，不执行任何步骤")
    void dropsWhenTriggerNodeRemoved() {
        NodeWorkflowDeploymentEntity deployment = deployment();
        deployment.setBusinessConfigs(businessConfigs("dev-a", platformStep(
                "gone", "target", "target_rgb", "rgb.effect", Map.of(), "跨 slot 节点")));
        when(workflowService.workflow("wf-1")).thenReturn(workflow());
        when(deploymentRepository.findByWorkflowIdAndStatusOrderByDeployedAtDesc("wf-1", "active"))
                .thenReturn(List.of(deployment));

        runtime.onInteraction(interaction("dev-a", "wf:wf-1:gone"));

        verify(executor, never()).execute(any(), any(), anyMap(), anyMap());
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private void stubRunSave() {
        when(runRepository.save(any())).thenAnswer(invocation -> {
            NodeWorkflowRunEntity run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId("run-1");
            }
            if (run.getStartedAt() == null) {
                run.setStartedAt(LocalDateTime.now());
            }
            return run;
        });
    }

    private static BusinessInteraction interaction(String deviceId, String contextRef) {
        return new BusinessInteraction(deviceId, "rt_token", "button.ok", contextRef, Instant.now());
    }

    /**
     * 部署记录里固化给某台设备的形态。
     *
     * <p>只放平台步骤：配置草稿由 {@code BusinessConfigService} 持有，运行时不读它。</p>
     */
    private static Map<String, Object> businessConfigs(String deviceId, Map<String, Object>... steps) {
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("platformSteps", List.of(steps));
        Map<String, Object> all = new LinkedHashMap<>();
        all.put(deviceId, device);
        return all;
    }

    private static Map<String, Object> platformStep(String triggerNodeId, String slotId, String nodeId,
                                                     String nodeType, Map<String, Object> params, String reason) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("triggerNodeId", triggerNodeId);
        step.put("slotId", slotId);
        step.put("nodeId", nodeId);
        step.put("nodeType", nodeType);
        step.put("params", params);
        step.put("reason", reason);
        return step;
    }

    private static NodeWorkflowDeploymentEntity deployment() {
        NodeWorkflowDeploymentEntity deployment = new NodeWorkflowDeploymentEntity();
        deployment.setId("dep-1");
        deployment.setWorkflowId("wf-1");
        deployment.setStatus("active");
        deployment.setSlotBindings(Map.of("source", "dev-a", "target", "dev-b"));
        return deployment;
    }

    private static NodeWorkflowDefinition workflow() {
        return new NodeWorkflowDefinition(
                "wf-1",
                "button-to-rgb",
                List.of(
                        new NodeWorkflowSlot("source", "type-a", "Source", List.of()),
                        new NodeWorkflowSlot("target", "type-a", "Target", List.of())
                ),
                List.of(
                        new NodeWorkflowNode("source_button", "source", "button.trigger",
                                Map.of("eventId", "button.ok")),
                        new NodeWorkflowNode("target_rgb", "target", "rgb.effect", Map.of("mode", "solid")),
                        new NodeWorkflowNode("target_section", "target", "display.section", Map.of())
                ),
                List.of(
                        new NodeWorkflowEdge("source_button", "target_rgb"),
                        new NodeWorkflowEdge("source_button", "target_section")
                )
        );
    }

    private NodeWorkflowRunEntity capturedRun() {
        ArgumentCaptor<NodeWorkflowRunEntity> captor = ArgumentCaptor.forClass(NodeWorkflowRunEntity.class);
        verify(runRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }
}
