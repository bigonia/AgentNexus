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
        if (!root.has("device_profile") && !root.has("inputs") && !root.has("outputs")
                && (root.has("board") || root.has("screen_w") || root.has("shape"))) {
            return parseFlatDeviceInfo(root);
        }

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

    /**
     * Parse ESP32 firmware flat device-info format.
     * Example: {"board":"ESP32-S3-LCD-0.85","audio":true,"screen_w":128,"screen_h":128,
     *            "shape":"rect","input":"keys","rgb":true,"power":true,...}
     */
    private static CapabilitySchema.CapabilitySnapshot parseFlatDeviceInfo(JsonNode root) {
        CapabilitySchema.DeviceProfile deviceProfile = new CapabilitySchema.DeviceProfile(
                root.path("shape").asText(null),
                root.path("screen_w").asInt(0),
                root.path("screen_h").asInt(0),
                root.path("input").asText(null),
                false
        );

        List<CapabilitySchema.InputCapability> inputs = new ArrayList<>();
        String inputType = root.path("input").asText("");
        if (!inputType.isEmpty()) {
            List<String> events = switch (inputType) {
                case "keys" -> List.of("click", "long_press");
                case "touch" -> List.of("click", "swipe");
                case "encoder" -> List.of("rotate", "click");
                default -> List.of("click");
            };
            inputs.add(new CapabilitySchema.InputCapability("input", inputType, true, events));
        }
        if (root.path("imu").asBoolean(false)) {
            inputs.add(new CapabilitySchema.InputCapability("imu", "imu", true,
                    List.of("motion", "orientation")));
        }

        List<CapabilitySchema.OutputCapability> outputs = new ArrayList<>();
        CapabilitySchema.SectionCapability section = null;

        int screenW = root.path("screen_w").asInt(0);
        int screenH = root.path("screen_h").asInt(0);
        if (screenW > 0 && screenH > 0) {
            List<String> displayCommands = List.of("display.section.render", "display.section.patch");
            List<String> supportedTypes = List.of(
                    "hero_section", "metric_section", "chart_section",
                    "action_section", "progress_section", "text_section",
                    "list_section", "toggle_section");
            List<String> supportedLayouts = List.of("vertical_scroll", "fixed_single");
            Map<String, Integer> limits = buildScreenLimits(Math.max(screenW, screenH));
            section = new CapabilitySchema.SectionCapability(
                    true, displayCommands, "binary", supportedTypes, supportedLayouts, limits);
            outputs.add(new CapabilitySchema.OutputCapability(
                    "display", "display.section", "lcd", true, displayCommands, List.of()));
        }

        if (root.path("audio").asBoolean(false)) {
            outputs.add(new CapabilitySchema.OutputCapability(
                    "audio", "audio.prompt", "i2s", true,
                    List.of("audio.prompt.play", "audio.stream.play", "audio.volume.set"),
                    List.of()));
        }

        if (root.path("rgb").asBoolean(false)) {
            outputs.add(new CapabilitySchema.OutputCapability(
                    "rgb", "rgb.effect", "led", true,
                    List.of("rgb.effect.set", "rgb.off"),
                    List.of()));
        }

        if (root.path("power").asBoolean(false) && root.path("ext_power_ctrl").asBoolean(false)) {
            outputs.add(new CapabilitySchema.OutputCapability(
                    "power", "device.reboot", "pmu", true,
                    List.of("device.reboot"),
                    List.of()));
        }

        return new CapabilitySchema.CapabilitySnapshot(deviceProfile, inputs, outputs, section);
    }

    private static Map<String, Integer> buildScreenLimits(int maxDim) {
        Map<String, Integer> limits = new LinkedHashMap<>();
        limits.put("max_sections_per_page", 6);
        if (maxDim < 200) {
            limits.put("max_metrics", 4);
            limits.put("max_points", 8);
            limits.put("max_actions", 2);
            limits.put("max_list_items", 3);
            limits.put("max_toggle_options", 3);
        } else if (maxDim >= 400) {
            limits.put("max_metrics", 8);
            limits.put("max_points", 32);
            limits.put("max_actions", 4);
            limits.put("max_list_items", 8);
            limits.put("max_toggle_options", 6);
        } else {
            limits.put("max_metrics", 6);
            limits.put("max_points", 16);
            limits.put("max_actions", 3);
            limits.put("max_list_items", 5);
            limits.put("max_toggle_options", 5);
        }
        return limits;
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
