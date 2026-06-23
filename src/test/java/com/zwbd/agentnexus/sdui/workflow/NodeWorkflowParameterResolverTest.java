package com.zwbd.agentnexus.sdui.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NodeWorkflowParameterResolverTest {

    private final NodeWorkflowParameterResolver resolver = new NodeWorkflowParameterResolver();

    @Test
    void resolvesFullReferencesAsRawValues() {
        Map<String, Object> context = Map.of(
                "event", Map.of("nodeId", "pwr"),
                "nodes", Map.of("record", Map.of("artifact", Map.of("artifactRef", "artifact:dev:latest")))
        );

        assertEquals("pwr", resolver.resolve("$event.nodeId", context));
        assertEquals("artifact:dev:latest", resolver.resolve("$nodes.record.artifact.artifactRef", context));
    }

    @Test
    void resolvesNestedTemplates() {
        Map<String, Object> context = Map.of(
                "event", Map.of("nodeId", "pwr"),
                "nodes", Map.of("record", Map.of("artifact", Map.of("text", "hello")))
        );

        Map<String, Object> resolved = resolver.resolveParams(Map.of(
                "text", "button=$event.nodeId text=$nodes.record.artifact.text",
                "items", List.of("$event.nodeId")
        ), context);

        assertEquals("button=pwr text=hello", resolved.get("text"));
        assertEquals(List.of("pwr"), resolved.get("items"));
    }

    @Test
    void missingReferenceFailsClearly() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("$nodes.record.artifact", Map.of("nodes", Map.of())));

        assertTrue(error.getMessage().contains("missing reference path"));
    }
}
