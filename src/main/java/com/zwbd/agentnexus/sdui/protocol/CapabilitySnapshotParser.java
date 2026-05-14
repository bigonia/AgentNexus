package com.zwbd.agentnexus.sdui.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public final class CapabilitySnapshotParser {

    private CapabilitySnapshotParser() {}

    public static CapabilitySchema.CapabilitySnapshot parse(String json, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return parseRoot(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse capability snapshot", e);
        }
    }

    private static CapabilitySchema.CapabilitySnapshot parseRoot(JsonNode root) {
        JsonNode profile = root.path("device_profile");
        CapabilitySchema.DeviceProfile deviceProfile = new CapabilitySchema.DeviceProfile(
                profile.path("shape").asText(null),
                profile.path("screen_w").asInt(0),
                profile.path("screen_h").asInt(0),
                profile.path("input_mode").asText(null),
                profile.path("auto_sleep_by_inactive").asBoolean(false)
        );

        List<CapabilitySchema.InputCapability> inputs =
                parseArray(root.path("inputs"), CapabilitySnapshotParser::parseInput);
        List<CapabilitySchema.OutputCapability> outputs =
                parseArray(root.path("outputs"), CapabilitySnapshotParser::parseOutput);
        CapabilitySchema.SectionCapability section = parseArray(root.path("outputs"),
                CapabilitySnapshotParser::tryParseSection)
                .stream().filter(Objects::nonNull).findFirst().orElse(null);

        return new CapabilitySchema.CapabilitySnapshot(deviceProfile, inputs, outputs, section);
    }

    private static CapabilitySchema.InputCapability parseInput(JsonNode n) {
        return new CapabilitySchema.InputCapability(
                n.path("name").asText(""),
                n.path("module").asText(""),
                n.path("enabled").asBoolean(false),
                stringList(n.path("events"))
        );
    }

    private static CapabilitySchema.OutputCapability parseOutput(JsonNode n) {
        return new CapabilitySchema.OutputCapability(
                n.has("name") ? n.path("name").asText() : null,
                n.has("capability") ? n.path("capability").asText() : null,
                n.path("module").asText(""),
                n.path("enabled").asBoolean(false),
                stringList(n.path("commands")),
                stringList(n.path("legacy_topics"))
        );
    }

    private static CapabilitySchema.SectionCapability tryParseSection(JsonNode n) {
        String cap = n.path("capability").asText(null);
        if (!"display.section".equals(cap)) return null;
        Map<String, Integer> limits = new LinkedHashMap<>();
        JsonNode limNode = n.path("limits");
        limNode.fieldNames().forEachRemaining(k -> limits.put(k, limNode.path(k).asInt(0)));
        return new CapabilitySchema.SectionCapability(
                n.path("enabled").asBoolean(false),
                stringList(n.path("commands")),
                n.path("transport").asText(null),
                stringList(n.path("supported_section_types")),
                stringList(n.path("supported_layouts")),
                limits
        );
    }

    private static <T> List<T> parseArray(JsonNode arr, java.util.function.Function<JsonNode, T> mapper) {
        if (arr == null || !arr.isArray()) return Collections.emptyList();
        return StreamSupport.stream(arr.spliterator(), false)
                .map(mapper).collect(Collectors.toList());
    }

    private static List<String> stringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return Collections.emptyList();
        return StreamSupport.stream(arr.spliterator(), false)
                .map(JsonNode::asText).collect(Collectors.toList());
    }
}
