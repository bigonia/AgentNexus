package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowDefinition;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowEdge;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowNode;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowSlot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NodeWorkflowSupportTest {

    @Test
    void validatesMultilayerDag() {
        List<String> errors = NodeWorkflowSupport.validateDefinition(chainWorkflow());

        assertTrue(errors.isEmpty(), String.join("; ", errors));
    }

    @Test
    void rejectsEdgeTargetingTrigger() {
        NodeWorkflowDefinition workflow = new NodeWorkflowDefinition(
                "wf",
                "invalid",
                slots(),
                List.of(
                        trigger(),
                        new NodeWorkflowNode("rgb", "target", "rgb.effect", Map.of())
                ),
                List.of(
                        new NodeWorkflowEdge("source_button", "rgb"),
                        new NodeWorkflowEdge("rgb", "source_button")
                )
        );

        List<String> errors = NodeWorkflowSupport.validateDefinition(workflow);

        assertTrue(errors.stream().anyMatch(error -> error.contains("edge to node must be output")));
    }

    @Test
    void rejectsCycles() {
        NodeWorkflowDefinition workflow = new NodeWorkflowDefinition(
                "wf",
                "cycle",
                slots(),
                List.of(
                        trigger(),
                        new NodeWorkflowNode("record", "target", "audio.record", Map.of()),
                        new NodeWorkflowNode("play", "target", "audio.play", Map.of())
                ),
                List.of(
                        new NodeWorkflowEdge("source_button", "record"),
                        new NodeWorkflowEdge("record", "play"),
                        new NodeWorkflowEdge("play", "record")
                )
        );

        List<String> errors = NodeWorkflowSupport.validateDefinition(workflow);

        assertTrue(errors.stream().anyMatch(error -> error.contains("acyclic")));
    }

    @Test
    void rejectsUnreachableOutputNodes() {
        NodeWorkflowDefinition workflow = new NodeWorkflowDefinition(
                "wf",
                "unreachable",
                slots(),
                List.of(
                        trigger(),
                        new NodeWorkflowNode("rgb", "target", "rgb.effect", Map.of()),
                        new NodeWorkflowNode("play", "target", "audio.play", Map.of())
                ),
                List.of(new NodeWorkflowEdge("source_button", "rgb"))
        );

        List<String> errors = NodeWorkflowSupport.validateDefinition(workflow);

        assertTrue(errors.stream().anyMatch(error -> error.contains("not reachable") && error.contains("play")));
    }

    @Test
    void buildsStableExecutionPlanForReachableSubgraph() {
        NodeWorkflowDefinition workflow = chainWorkflow();

        List<String> nodeIds = NodeWorkflowSupport.executionPlan(workflow, trigger()).stream()
                .map(NodeWorkflowNode::nodeId)
                .toList();

        assertEquals(List.of("record", "play", "show"), nodeIds);
    }

    private NodeWorkflowDefinition chainWorkflow() {
        return new NodeWorkflowDefinition(
                "wf",
                "chain",
                slots(),
                List.of(
                        trigger(),
                        new NodeWorkflowNode("record", "target", "audio.record", Map.of("control", "stop")),
                        new NodeWorkflowNode("play", "target", "audio.play",
                                Map.of("artifact_id", "$nodes.record.artifact.artifactRef")),
                        new NodeWorkflowNode("show", "target", "ui.update",
                                Map.of("patch", Map.of("pageId", "main", "patches", List.of())))
                ),
                List.of(
                        new NodeWorkflowEdge("source_button", "record"),
                        new NodeWorkflowEdge("record", "play"),
                        new NodeWorkflowEdge("play", "show")
                )
        );
    }

    private List<NodeWorkflowSlot> slots() {
        return List.of(
                new NodeWorkflowSlot("source", "type-a", "Source", List.of()),
                new NodeWorkflowSlot("target", "type-a", "Target", List.of())
        );
    }

    private NodeWorkflowNode trigger() {
        return new NodeWorkflowNode("source_button", "source", "button.trigger",
                Map.of("eventId", "input:buttons.pwr.short_press", "nodeId", "pwr"));
    }
}
