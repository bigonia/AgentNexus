package com.zwbd.agentnexus.sdui.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapabilitySnapshotParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseFullCapabilityWithSection() throws Exception {
        String json = """
        {
          "device_profile": {"shape": "round", "screen_w": 466, "screen_h": 466, "input_mode": "touch+buttons"},
          "inputs": [{"name": "buttons.input", "module": "board", "enabled": true, "events": ["press"]}],
          "outputs": [
            {"name": "audio.prompt.play", "module": "audio", "enabled": true, "commands": ["play"]},
            {"capability": "display.section", "enabled": true, "commands": ["render","patch"],
             "transport": "ui3_binary:SECTION_SCENE",
             "supported_section_types": ["hero_section","metric_section"],
             "supported_layouts": ["vertical_scroll","horizontal_pages"],
             "limits": {"max_sections_per_page": 12, "max_metrics": 4}}
          ]
        }""";

        CapabilitySchema.CapabilitySnapshot caps = CapabilitySnapshotParser.parse(json, mapper);

        assertEquals("round", caps.deviceProfile().shape());
        assertEquals(466, caps.deviceProfile().screenW());
        assertEquals(1, caps.inputs().size());
        assertEquals(2, caps.outputs().size());

        CapabilitySchema.SectionCapability sec = caps.section();
        assertNotNull(sec);
        assertTrue(sec.enabled());
        assertEquals(2, sec.supportedSectionTypes().size());
        assertTrue(sec.supportsType("hero_section"));
        assertFalse(sec.supportsType("unknown_type"));
        assertTrue(sec.supportsLayout("vertical_scroll"));
        assertEquals(12, sec.getLimit("max_sections_per_page"));
        assertEquals(4, sec.getLimit("max_metrics"));
        assertEquals(0, sec.getLimit("nonexistent"));
    }

    @Test
    void parseWithoutSectionReturnsNull() throws Exception {
        String json = """
        {"device_profile":{"shape":"square","screen_w":128,"screen_h":128},
         "inputs":[],"outputs":[{"name":"audio","enabled":true,"commands":["play"]}]}""";

        CapabilitySchema.CapabilitySnapshot caps = CapabilitySnapshotParser.parse(json, mapper);
        assertNull(caps.section());
        assertEquals("square", caps.deviceProfile().shape());
    }

    @Test
    void parseSectionWithDisabledSection() throws Exception {
        String json = """
        {"device_profile":{"shape":"round","screen_w":466,"screen_h":466},
         "inputs":[],"outputs":[
           {"capability":"display.section","enabled":false,"commands":[],"supported_section_types":[],"supported_layouts":[],"limits":{}}
         ]}""";

        CapabilitySchema.CapabilitySnapshot caps = CapabilitySnapshotParser.parse(json, mapper);
        assertNotNull(caps.section());
        assertFalse(caps.section().enabled());
    }
}
