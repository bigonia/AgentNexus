package com.zwbd.agentnexus.sdui.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.capability.CapabilityCatalog;
import com.zwbd.agentnexus.sdui.capability.CapabilityRegistry;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceProtocolCatalog;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNodeRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SduiCapabilityServiceTest {

    private SduiDeviceRepository deviceRepository;
    private CommandSchemaRegistry schemaRegistry;
    private CapabilityNodeRegistry nodeRegistry;
    private CapabilityRegistry capabilityRegistry;
    private SduiCapabilityService capabilityService;
    private CapabilityCatalog registryCatalog;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(SduiDeviceRepository.class);
        schemaRegistry = mock(CommandSchemaRegistry.class);
        nodeRegistry = mock(CapabilityNodeRegistry.class);
        registryCatalog = new CapabilityCatalog();
        ReflectionTestUtils.invokeMethod(registryCatalog, "load");
        capabilityRegistry = new CapabilityRegistry(registryCatalog);
        DeviceProtocolCatalog protocolCatalog = new DeviceProtocolCatalog(registryCatalog);
        capabilityService = new SduiCapabilityService(
                deviceRepository,
                new ObjectMapper(),
                schemaRegistry,
                nodeRegistry,
                capabilityRegistry,
                registryCatalog,
                protocolCatalog
        );
    }

    @Test
    void rehydratesRegistryFromStoredCapabilitiesSnapshot() {
        String deviceId = "1020BA3D35D0";
        SduiDevice device = new SduiDevice();
        device.setDeviceId(deviceId);
        device.setCapabilitiesSnapshot("""
                {
                  "capability_schema": "capability.v2",
                  "protocol_version": "sdui.topic.v1",
                  "board": "ESP32-S3",
                  "screen": {"w": 466, "h": 466, "shape": "round"},
                  "input_mode": "touch",
                  "inputs": ["motion"],
                  "outputs": ["device.reboot"],
                  "display": {
                    "transport": "ui3_binary:SECTION_SCENE",
                    "size_class": "large",
                    "section_types": ["hero_section"],
                    "layouts": ["vertical_scroll"]
                  }
                }
                """);
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        assertTrue(capabilityRegistry.getDeviceSnapshot(deviceId).isEmpty());

        var caps = capabilityService.getCapabilities(deviceId);

        assertTrue(caps.isPresent());
        assertTrue(capabilityRegistry.supportsEvent(deviceId, "input:motion.imu.shake"));
        verify(schemaRegistry).loadFromCapabilities(eq(deviceId), any());
    }

    @Test
    void buildsCapabilityMetadataAndNormalizedTreeFromStoredSnapshot() {
        String deviceId = "esp32-a1b2";
        SduiDevice device = new SduiDevice();
        device.setDeviceId(deviceId);
        device.setCapabilitiesSchemaVersion("capability.v2");
        device.setCapabilitiesSnapshot("""
                {
                  "capability_schema": "capability.v2",
                  "protocol_version": "sdui.topic.v1",
                  "board": "ESP32-S3",
                  "screen": {"w": 128, "h": 128, "shape": "rect"},
                  "input_mode": "keys",
                  "inputs": ["buttons.pwr", "audio.record"],
                  "outputs": ["display.brightness", "rgb.effect"],
                  "display": {
                    "transport": "ui3_binary:SECTION_SCENE",
                    "size_class": "small",
                    "section_types": ["hero_section", "action_section"],
                    "layouts": ["vertical_scroll", "fixed_single"]
                  }
                }
                """);
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        var metadata = capabilityService.buildCapabilityMetadata(deviceId);
        var tree = capabilityService.buildCapabilityDebugView(deviceId);

        assertEquals("ok", metadata.get("status"));
        assertEquals("OK", metadata.get("normalizedStatus"));
        assertTrue(metadata.containsKey("rawPayload"));

        assertEquals("ok", tree.get("status"));
        assertEquals("debug", tree.get("view"));
        assertTrue(tree.containsKey("deviceProfile"));
        assertTrue(tree.containsKey("hardwareInputs"));
        assertTrue(tree.containsKey("deviceOutputs"));
        assertTrue(tree.containsKey("sectionSupport"));
    }
}
