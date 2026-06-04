package com.zwbd.agentnexus.sdui.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.StreamSupport;

public final class CapabilitySnapshotParser {

    private CapabilitySnapshotParser() {}

    public static CapabilitySchema.CapabilitySnapshot parse(String json, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return new CapabilitySchema.CapabilitySnapshot(
                    root.path("capability_schema").asText(null),
                    root.path("protocol_version").asText(null),
                    root.path("board").asText(null),
                    parseScreen(root.path("screen")),
                    root.path("input_mode").asText(null),
                    stringList(root.path("inputs")),
                    stringList(root.path("outputs")),
                    parseDisplay(root.path("display"))
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse capability snapshot", e);
        }
    }

    private static CapabilitySchema.ScreenInfo parseScreen(JsonNode screenNode) {
        if (screenNode.isMissingNode()) {
            return new CapabilitySchema.ScreenInfo(0, 0, null);
        }
        return new CapabilitySchema.ScreenInfo(
                screenNode.path("w").asInt(0),
                screenNode.path("h").asInt(0),
                screenNode.path("shape").asText(null)
        );
    }

    private static CapabilitySchema.DisplayInfo parseDisplay(JsonNode displayNode) {
        if (displayNode.isMissingNode()) return null;
        return new CapabilitySchema.DisplayInfo(
                displayNode.path("transport").asText(null),
                displayNode.path("size_class").asText(null),
                stringList(displayNode.path("section_types")),
                stringList(displayNode.path("layouts"))
        );
    }

    private static List<String> stringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return Collections.emptyList();
        return StreamSupport.stream(arr.spliterator(), false)
                .map(JsonNode::asText).collect(java.util.stream.Collectors.toList());
    }
}
