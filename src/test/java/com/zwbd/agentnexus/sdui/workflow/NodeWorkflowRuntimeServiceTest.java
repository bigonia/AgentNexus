package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.handler.EventInputHandler;
import com.zwbd.agentnexus.sdui.artifact.SduiArtifactService;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeWorkflowRuntimeServiceTest {

    private EventInputHandler eventInputHandler;
    private NodeWorkflowService workflowService;
    private NodeWorkflowDeploymentService deploymentService;
    private NodeWorkflowDeploymentRepository deploymentRepository;
    private NodeWorkflowRunRepository runRepository;
    private NodeWorkflowRunStepRepository stepRepository;
    private CapabilityNodeExecutorService executorService;
    private NodeWorkflowRunContextService contextService;
    private NodeWorkflowParameterResolver parameterResolver;
    private EventInputHandler.PayloadEventListener listener;

    @BeforeEach
    void setUp() {
        eventInputHandler = mock(EventInputHandler.class);
        workflowService = mock(NodeWorkflowService.class);
        deploymentService = mock(NodeWorkflowDeploymentService.class);
        deploymentRepository = mock(NodeWorkflowDeploymentRepository.class);
        runRepository = mock(NodeWorkflowRunRepository.class);
        stepRepository = mock(NodeWorkflowRunStepRepository.class);
        executorService = mock(CapabilityNodeExecutorService.class);
        contextService = new NodeWorkflowRunContextService(mock(SduiArtifactService.class));
        parameterResolver = new NodeWorkflowParameterResolver();

        new NodeWorkflowRuntimeService(eventInputHandler, workflowService, deploymentService,
                deploymentRepository, runRepository, stepRepository, executorService, contextService, parameterResolver);
        ArgumentCaptor<EventInputHandler.PayloadEventListener> captor =
                ArgumentCaptor.forClass(EventInputHandler.PayloadEventListener.class);
        verify(eventInputHandler).addPayloadListener(captor.capture());
        listener = captor.getValue();
    }

    @Test
    void realEventCreatesRunAndStep() {
        NodeWorkflowDeploymentEntity deployment = deployment();
        NodeWorkflowDefinition workflow = workflow();
        when(deploymentRepository.findByStatus("active")).thenReturn(List.of(deployment));
        when(workflowService.workflow("wf-1")).thenReturn(workflow);
        when(executorService.execute(eq("dev-b"), eq("rgb.effect"), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "sent",
                "sent", true
        ));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            NodeWorkflowRunEntity run = invocation.getArgument(0);
            if (run.getId() == null) run.setId("run-1");
            if (run.getStartedAt() == null) run.setStartedAt(LocalDateTime.now());
            return run;
        });
        when(runRepository.findById("run-1")).thenAnswer(invocation -> Optional.of(new NodeWorkflowRunEntity()));

        listener.onEvent(new EventPayload(
                "input:buttons.pwr.short_press",
                "dev-a",
                "",
                "",
                "pwr",
                0,
                null,
                System.currentTimeMillis(),
                Map.of()
        ));

        verify(executorService).execute(eq("dev-b"), eq("rgb.effect"),
                eq(Map.of("mode", "solid", "r", 0, "g", 120, "b", 255)), anyMap());
        ArgumentCaptor<NodeWorkflowRunStepEntity> stepCaptor = ArgumentCaptor.forClass(NodeWorkflowRunStepEntity.class);
        verify(stepRepository).save(stepCaptor.capture());
        assertEquals("passed", stepCaptor.getValue().getStatus());
        assertEquals("target_rgb", stepCaptor.getValue().getNodeId());
        assertEquals(Map.of("mode", "solid", "r", 0, "g", 120, "b", 255), stepCaptor.getValue().getInputParams());
    }

    @Test
    void laterStepCanReferenceEarlierStepArtifact() {
        NodeWorkflowDeploymentEntity deployment = deployment();
        NodeWorkflowDefinition workflow = new NodeWorkflowDefinition(
                "wf-1",
                "record-to-play",
                List.of(
                        new NodeWorkflowSlot("source", "type-a", "Source", List.of()),
                        new NodeWorkflowSlot("target", "type-a", "Target", List.of())
                ),
                List.of(
                        new NodeWorkflowNode("source_button", "source", "button.trigger",
                                Map.of("eventId", "input:buttons.pwr.short_press", "nodeId", "pwr")),
                        new NodeWorkflowNode("record", "target", "audio.record", Map.of("control", "stop")),
                        new NodeWorkflowNode("play", "target", "audio.play",
                                Map.of("artifact_id", "$nodes.record.artifact.artifactRef"))
                ),
                List.of(
                        new NodeWorkflowEdge("source_button", "record"),
                        new NodeWorkflowEdge("record", "play")
                )
        );
        when(deploymentRepository.findByStatus("active")).thenReturn(List.of(deployment));
        when(workflowService.workflow("wf-1")).thenReturn(workflow);
        when(executorService.execute(eq("dev-b"), eq("audio.record"), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "sent",
                "sent", true,
                "artifact", Map.of("artifactRef", "artifact:dev-b:audio-record-latest", "text", "hello")
        ));
        when(executorService.execute(eq("dev-b"), eq("audio.play"), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "sent",
                "sent", true
        ));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            NodeWorkflowRunEntity run = invocation.getArgument(0);
            if (run.getId() == null) run.setId("run-ctx");
            if (run.getStartedAt() == null) run.setStartedAt(LocalDateTime.now());
            return run;
        });

        listener.onEvent(new EventPayload(
                "input:buttons.pwr.short_press",
                "dev-a",
                "",
                "",
                "pwr",
                0,
                null,
                System.currentTimeMillis(),
                Map.of()
        ));

        verify(executorService).execute(eq("dev-b"), eq("audio.play"),
                eq(Map.of("artifact_id", "artifact:dev-b:audio-record-latest")), anyMap());
    }

    @Test
    void multilayerDagRunsInTopologicalOrder() {
        NodeWorkflowDeploymentEntity deployment = deployment();
        NodeWorkflowDefinition workflow = new NodeWorkflowDefinition(
                "wf-1",
                "record-play-ui",
                List.of(
                        new NodeWorkflowSlot("source", "type-a", "Source", List.of()),
                        new NodeWorkflowSlot("target", "type-a", "Target", List.of())
                ),
                List.of(
                        new NodeWorkflowNode("source_button", "source", "button.trigger",
                                Map.of("eventId", "input:buttons.pwr.short_press", "nodeId", "pwr")),
                        new NodeWorkflowNode("record", "target", "audio.record", Map.of("control", "stop")),
                        new NodeWorkflowNode("play", "target", "audio.play",
                                Map.of("artifact_id", "$nodes.record.artifact.artifactRef")),
                        new NodeWorkflowNode("show", "target", "ui.update",
                                Map.of("patch", Map.of(
                                        "pageId", "main",
                                        "patches", List.of(Map.of(
                                                "sectionId", "text1",
                                                "op", "update",
                                                "sectionType", "text_section",
                                                "fields", Map.of("body", "$nodes.record.artifact.text")
                                        ))
                                )))
                ),
                List.of(
                        new NodeWorkflowEdge("source_button", "record"),
                        new NodeWorkflowEdge("record", "play"),
                        new NodeWorkflowEdge("play", "show")
                )
        );
        when(deploymentRepository.findByStatus("active")).thenReturn(List.of(deployment));
        when(workflowService.workflow("wf-1")).thenReturn(workflow);
        when(executorService.execute(eq("dev-b"), eq("audio.record"), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "sent",
                "sent", true,
                "artifact", Map.of("artifactRef", "artifact:dev-b:audio-record-latest", "text", "hello")
        ));
        when(executorService.execute(eq("dev-b"), eq("audio.play"), anyMap(), anyMap())).thenReturn(Map.of("status", "sent", "sent", true));
        when(executorService.execute(eq("dev-b"), eq("ui.update"), anyMap(), anyMap())).thenReturn(Map.of("status", "sent", "sent", true));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            NodeWorkflowRunEntity run = invocation.getArgument(0);
            if (run.getId() == null) run.setId("run-dag");
            if (run.getStartedAt() == null) run.setStartedAt(LocalDateTime.now());
            return run;
        });

        listener.onEvent(new EventPayload(
                "input:buttons.pwr.short_press",
                "dev-a",
                "",
                "",
                "pwr",
                0,
                null,
                System.currentTimeMillis(),
                Map.of()
        ));

        var order = inOrder(executorService);
        order.verify(executorService).execute(eq("dev-b"), eq("audio.record"), eq(Map.of("control", "stop")), anyMap());
        order.verify(executorService).execute(eq("dev-b"), eq("audio.play"),
                eq(Map.of("artifact_id", "artifact:dev-b:audio-record-latest")), anyMap());
        order.verify(executorService).execute(eq("dev-b"), eq("ui.update"), anyMap(), anyMap());
    }

    @Test
    void missingReferenceFailsAndStopsFollowingNodes() {
        NodeWorkflowDeploymentEntity deployment = deployment();
        NodeWorkflowDefinition workflow = new NodeWorkflowDefinition(
                "wf-1",
                "bad-ref",
                List.of(
                        new NodeWorkflowSlot("source", "type-a", "Source", List.of()),
                        new NodeWorkflowSlot("target", "type-a", "Target", List.of())
                ),
                List.of(
                        new NodeWorkflowNode("source_button", "source", "button.trigger",
                                Map.of("eventId", "input:buttons.pwr.short_press", "nodeId", "pwr")),
                        new NodeWorkflowNode("play", "target", "audio.play",
                                Map.of("artifact_id", "$nodes.record.artifact.artifactRef")),
                        new NodeWorkflowNode("rgb", "target", "rgb.effect", Map.of("mode", "solid"))
                ),
                List.of(
                        new NodeWorkflowEdge("source_button", "play"),
                        new NodeWorkflowEdge("play", "rgb")
                )
        );
        when(deploymentRepository.findByStatus("active")).thenReturn(List.of(deployment));
        when(workflowService.workflow("wf-1")).thenReturn(workflow);
        when(runRepository.save(any())).thenAnswer(invocation -> {
            NodeWorkflowRunEntity run = invocation.getArgument(0);
            if (run.getId() == null) run.setId("run-failed");
            if (run.getStartedAt() == null) run.setStartedAt(LocalDateTime.now());
            return run;
        });

        listener.onEvent(new EventPayload(
                "input:buttons.pwr.short_press",
                "dev-a",
                "",
                "",
                "pwr",
                0,
                null,
                System.currentTimeMillis(),
                Map.of()
        ));

        verify(executorService, never()).execute(anyString(), anyString(), anyMap());
        ArgumentCaptor<NodeWorkflowRunStepEntity> stepCaptor = ArgumentCaptor.forClass(NodeWorkflowRunStepEntity.class);
        verify(stepRepository).save(stepCaptor.capture());
        assertEquals("failed", stepCaptor.getValue().getStatus());
        assertEquals("play", stepCaptor.getValue().getNodeId());
    }

    private NodeWorkflowDeploymentEntity deployment() {
        NodeWorkflowDeploymentEntity deployment = new NodeWorkflowDeploymentEntity();
        deployment.setId("dep-1");
        deployment.setWorkflowId("wf-1");
        deployment.setStatus("active");
        deployment.setSlotBindings(Map.of("source", "dev-a", "target", "dev-b"));
        return deployment;
    }

    private NodeWorkflowDefinition workflow() {
        return new NodeWorkflowDefinition(
                "wf-1",
                "button-to-rgb",
                List.of(
                        new NodeWorkflowSlot("source", "type-a", "Source", List.of()),
                        new NodeWorkflowSlot("target", "type-a", "Target", List.of())
                ),
                List.of(
                        new NodeWorkflowNode("source_button", "source", "button.trigger",
                                Map.of("eventId", "input:buttons.pwr.short_press", "nodeId", "pwr")),
                        new NodeWorkflowNode("target_rgb", "target", "rgb.effect",
                                Map.of("mode", "solid", "r", 0, "g", 120, "b", 255))
                ),
                List.of(new NodeWorkflowEdge("source_button", "target_rgb"))
        );
    }
}
