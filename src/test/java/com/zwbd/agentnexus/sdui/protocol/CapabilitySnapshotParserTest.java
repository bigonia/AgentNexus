package com.zwbd.agentnexus.sdui.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapabilitySnapshotParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseV2FullCapability() throws Exception {
        String json = """
        {
          "capability_schema": "capability.v2",
          "protocol_version": "sdui.topic.v1",
          "board": "ESP32-S3-Touch-AMOLED-1.75C",
          "screen": {"w": 466, "h": 466, "shape": "round"},
          "input_mode": "touch",
          "inputs": ["audio.record", "motion"],
          "outputs": ["display.brightness", "device.reboot", "audio.stream", "audio.volume"],
          "display": {
            "transport": "ui3_binary:SECTION_SCENE",
            "size_class": "large",
            "section_types": ["hero_section", "metric_section", "chart_section"],
            "layouts": ["vertical_scroll", "horizontal_pages", "fixed_single"]
          }
        }""";

        CapabilitySchema.CapabilitySnapshot caps = CapabilitySnapshotParser.parse(json, mapper);

        assertEquals("capability.v2", caps.schemaVersion());
        assertEquals("sdui.topic.v1", caps.protocolVersion());
        assertEquals("ESP32-S3-Touch-AMOLED-1.75C", caps.board());

        assertNotNull(caps.screen());
        assertEquals(466, caps.screen().w());
        assertEquals(466, caps.screen().h());
        assertEquals("round", caps.screen().shape());

        assertEquals("touch", caps.inputMode());

        assertEquals(2, caps.inputs().size());
        assertTrue(caps.inputs().contains("audio.record"));
        assertTrue(caps.inputs().contains("motion"));

        assertEquals(4, caps.outputs().size());
        assertTrue(caps.outputs().contains("display.brightness"));

        assertNotNull(caps.display());
        assertEquals("ui3_binary:SECTION_SCENE", caps.display().transport());
        assertEquals("large", caps.display().sizeClass());
        assertEquals(3, caps.display().sectionTypes().size());
        assertTrue(caps.display().supportsType("hero_section"));
        assertFalse(caps.display().supportsType("unknown_type"));
        assertTrue(caps.display().supportsLayout("vertical_scroll"));
        assertFalse(caps.display().supportsLayout("overlay"));
    }

    @Test
    void parseV2WithoutDisplay() throws Exception {
        String json = """
        {
          "capability_schema": "capability.v2",
          "board": "ESP32-C3-Mini",
          "screen": {"w": 0, "h": 0, "shape": null},
          "input_mode": null,
          "inputs": ["buttons.boot"],
          "outputs": ["device.reboot"]
        }""";

        CapabilitySchema.CapabilitySnapshot caps = CapabilitySnapshotParser.parse(json, mapper);
        assertNull(caps.display());
        assertEquals("ESP32-C3-Mini", caps.board());
        assertEquals(0, caps.screen().w());
        assertEquals(1, caps.inputs().size());
        assertTrue(caps.inputs().contains("buttons.boot"));
        assertEquals(1, caps.outputs().size());
    }

    @Test
    void parseV2Lcd085() throws Exception {
        String json = """
        {
          "capability_schema": "capability.v2",
          "protocol_version": "sdui.topic.v1",
          "board": "ESP32-S3-LCD-0.85",
          "screen": {"w": 128, "h": 128, "shape": "rect"},
          "input_mode": "keys",
          "inputs": ["buttons.boot", "buttons.plus", "audio.record"],
          "outputs": ["display.brightness", "device.reboot", "audio.stream", "audio.volume", "rgb.effect"],
          "display": {
            "transport": "ui3_binary:SECTION_SCENE",
            "size_class": "small",
            "section_types": ["hero_section", "metric_section", "chart_section", "timer_section"],
            "layouts": ["vertical_scroll", "horizontal_pages", "fixed_single", "overlay"]
          }
        }""";

        CapabilitySchema.CapabilitySnapshot caps = CapabilitySnapshotParser.parse(json, mapper);

        assertEquals("small", caps.display().sizeClass());
        assertEquals("keys", caps.inputMode());
        assertEquals(128, caps.screen().w());
        assertEquals("rect", caps.screen().shape());
        assertEquals(3, caps.inputs().size());
        assertEquals(5, caps.outputs().size());
        assertTrue(caps.inputs().contains("buttons.boot"));
        assertTrue(caps.outputs().contains("rgb.effect"));
    }
}
