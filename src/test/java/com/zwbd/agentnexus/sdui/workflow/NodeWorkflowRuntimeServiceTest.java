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
        parameterResolver = new NodeWorkflowParameterResolver(new NodeTypeRegistry());

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
                                Map.of("artifact_id", Map.of("$ref", "record")))
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
                "artifact", Map.of("artifactId", "abc-123", "artifactRef", "artifact:dev-b:latest", "text", "hello")
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

        // $ref resolution extracts artifactId from audio.record output
        verify(executorService).execute(eq("dev-b"), eq("audio.play"),
                eq(Map.of("artifact_id", "abc-123")), anyMap());
    }

    @Test
    void skippedNodeDoesNotFailRun() {
        NodeWorkflowDeploymentEntity deployment = deployment();
        NodeWorkflowDefinition workflow = new NodeWorkflowDefinition(
                "wf-1",
                "skip-play",
                List.of(
                        new NodeWorkflowSlot("source", "type-a", "Source", List.of()),
                        new NodeWorkflowSlot("target", "type-a", "Target", List.of())
                ),
                List.of(
                        new NodeWorkflowNode("source_button", "source", "button.trigger",
                                Map.of("eventId", "input:buttons.pwr.short_press", "nodeId", "pwr")),
                        // audio.record starts recording — no artifact produced
                        new NodeWorkflowNode("record", "target", "audio.record", Map.of("control", "start")),
                        new NodeWorkflowNode("play", "target", "audio.play",
                                Map.of("artifact_id", Map.of("$ref", "record"))),
                        new NodeWorkflowNode("rgb", "target", "rgb.effect", Map.of("mode", "solid"))
                ),
                List.of(
                        new NodeWorkflowEdge("source_button", "record"),
                        new NodeWorkflowEdge("record", "play"),
                        new NodeWorkflowEdge("play", "rgb")
                )
        );
        when(deploymentRepository.findByStatus("active")).thenReturn(List.of(deployment));
        when(workflowService.workflow("wf-1")).thenReturn(workflow);
        // record starts — no artifact in result
        when(executorService.execute(eq("dev-b"), eq("audio.record"), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "sent", "sent", true, "recording", true
        ));
        when(executorService.execute(eq("dev-b"), eq("audio.play"), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "skipped", "reason", "artifact not yet available"
        ));
        when(executorService.execute(eq("dev-b"), eq("rgb.effect"), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "sent", "sent", true
        ));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            NodeWorkflowRunEntity run = invocation.getArgument(0);
            if (run.getId() == null) run.setId("run-skip");
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

        // play receives empty artifact_id (ref resolves to empty since no artifact in record result)
        // executor returns skipped status → run continues to rgb
        verify(executorService).execute(eq("dev-b"), eq("audio.play"),
                eq(Map.of("artifact_id", "")), anyMap());
        verify(executorService).execute(eq("dev-b"), eq("rgb.effect"), anyMap(), anyMap());
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
