package com.zwbd.agentnexus.sdui.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NodeWorkflowParameterResolverTest {

    private final NodeTypeRegistry typeRegistry = new NodeTypeRegistry();
    private final NodeWorkflowParameterResolver resolver = new NodeWorkflowParameterResolver(typeRegistry);

    @Test
    void staticValuesPassThrough() {
        Map<String, Object> resolved = resolver.resolveParams(
                Map.of("control", "toggle", "preset", "notification"),
                "audio.record",
                Map.of("nodes", Map.of())
        );
        assertEquals("toggle", resolved.get("control"));
        assertEquals("notification", resolved.get("preset"));
    }

    @Test
    void refResolvesByType() {
        Map<String, Object> context = Map.of("nodes", Map.of(
                "rec", Map.of(
                        "nodeType", "audio.record",
                        "artifact", Map.of("artifactId", "abc-123", "durationMs", 2000)
                )
        ));
        Object result = resolver.resolveParamRef("artifact_id",
                Map.of("$ref", "rec"), "audio.play", context);
        assertEquals("abc-123", result);
    }

    @Test
    void unresolvedRefReturnsEmpty() {
        Map<String, Object> context = Map.of("nodes", Map.of(
                "rec", Map.of("nodeType", "audio.record") // no artifact field
        ));
        Object result = resolver.resolveParamRef("artifact_id",
                Map.of("$ref", "rec"), "audio.play", context);
        assertEquals("", result);
    }

    @Test
    void refToUnknownNodeReturnsEmpty() {
        Object result = resolver.resolveParamRef("artifact_id",
                Map.of("$ref", "no_such_node"), "audio.play",
                Map.of("nodes", Map.of()));
        assertEquals("", result);
    }

    @Test
    void nonRefMapPassesThrough() {
        // A map that isn't a $ref (has extra keys) is treated as static
        Object result = resolver.resolveParamRef("scene",
                Map.of("pageId", "main", "sections", List.of()),
                "display.section",
                Map.of("nodes", Map.of()));
        assertTrue(result instanceof Map);
    }

    @Test
    void validateRefsDetectsUnknownNode() {
        List<String> errors = resolver.validateReferences(
                Map.of("artifact_id", Map.of("$ref", "ghost")),
                "audio.play", "play_node",
                Set.of("rec", "play_node"),
                Map.of("play_node", Set.of("rec"))
        );
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("unknown node"));
    }

    @Test
    void validateRefsDetectsNonUpstreamTarget() {
        List<String> errors = resolver.validateReferences(
                Map.of("artifact_id", Map.of("$ref", "downstream_node")),
                "audio.play", "play_node",
                Set.of("rec", "play_node", "downstream_node"),
                Map.of("play_node", Set.of("rec")) // downstream_node not in upstream set
        );
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("not upstream"));
    }

    @Test
    void validateRefsPassesForValidRef() {
        List<String> errors = resolver.validateReferences(
                Map.of("artifact_id", Map.of("$ref", "rec")),
                "audio.play", "play_node",
                Set.of("rec", "play_node"),
                Map.of("play_node", Set.of("rec"))
        );
        assertTrue(errors.isEmpty());
    }
}
