package com.zwbd.agentnexus.sdui.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceProtocolCatalog;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import com.zwbd.agentnexus.sdui.service.AudioService;
import com.zwbd.agentnexus.sdui.service.CommandSchemaRegistry;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNodeRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityContractServiceTest {

    private SduiCapabilityService capabilityService;
    private CapabilityContractService contractService;

    @BeforeEach
    void setUp() {
        SduiDeviceRepository deviceRepository = mock(SduiDeviceRepository.class);
        when(deviceRepository.findById(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        CommandSchemaRegistry schemaRegistry = mock(CommandSchemaRegistry.class);
        CapabilityNodeRegistry nodeRegistry = mock(CapabilityNodeRegistry.class);
        CapabilityCatalog capabilityCatalog = new CapabilityCatalog();
        ReflectionTestUtils.invokeMethod(capabilityCatalog, "load");
        CapabilityRegistry capabilityRegistry = new CapabilityRegistry(capabilityCatalog);
        DeviceProtocolCatalog protocolCatalog = new DeviceProtocolCatalog(capabilityCatalog);
        capabilityService = new SduiCapabilityService(
                deviceRepository,
                new ObjectMapper(),
                schemaRegistry,
                nodeRegistry,
                capabilityRegistry,
                capabilityCatalog,
                protocolCatalog
        );
        AudioService audioService = mock(AudioService.class);
        PlatformCapabilityRegistry platformCapabilityRegistry = new PlatformCapabilityRegistry(audioService);
        DeviceCapabilityProjection capabilityProjection = new DeviceCapabilityProjection(protocolCatalog, capabilityService);
        contractService = new CapabilityContractService(
                capabilityService,
                capabilityCatalog,
                platformCapabilityRegistry,
                capabilityProjection
        );
    }

    @Test
    void buildsResolvedContractFromDeviceSnapshotAndPlatformCatalog() {
        String deviceId = "esp32-contract";
        String rawJson = """
                {
                  "capability_schema": "capability.v2",
                  "protocol_version": "sdui.topic.v1",
                  "board": "ESP32-S3",
                  "screen": {"w": 466, "h": 466, "shape": "round"},
                  "input_mode": "touch",
                  "inputs": ["buttons.pwr"],
                  "outputs": ["rgb.effect"],
                  "display": {
                    "transport": "ui3_binary:SECTION_SCENE",
                    "size_class": "large",
                    "section_types": ["hero_section", "action_section"],
                    "layouts": ["vertical_scroll"]
                  }
                }
                """;

        capabilityService.onCapabilitiesReport(deviceId,
                com.zwbd.agentnexus.sdui.protocol.CapabilitySnapshotParser.parse(rawJson, new ObjectMapper()),
                rawJson);

        CapabilityContract contract = contractService.buildContract(deviceId);

        assertEquals("ok", contract.status());
        assertFalse(contract.inputs().isEmpty());
        assertFalse(contract.outputs().isEmpty());
        assertEquals("large", contract.display().sizeClass());
        assertEquals(2, contract.display().sectionTypes().size());
        assertTrue(contract.platformCapabilities().stream()
                .map(CapabilityContract.ContractCapability::id)
                .anyMatch(id -> id.startsWith("platform.audio.")));
    }

    @Test
    void unresolvedCapabilitiesAreSurfacedInsteadOfSilentlyDropped() {
        String deviceId = "esp32-unresolved";
        String rawJson = """
                {
                  "capability_schema": "capability.v2",
                  "protocol_version": "sdui.topic.v1",
                  "board": "ESP32-S3",
                  "screen": {"w": 128, "h": 128, "shape": "rect"},
                  "input_mode": "keys",
                  "inputs": ["unknown.input"],
                  "outputs": ["unknown.output"],
                  "display": {
                    "transport": "ui3_binary:SECTION_SCENE",
                    "size_class": "small",
                    "section_types": ["unknown_section"],
                    "layouts": ["vertical_scroll"]
                  }
                }
                """;

        capabilityService.onCapabilitiesReport(deviceId,
                com.zwbd.agentnexus.sdui.protocol.CapabilitySnapshotParser.parse(rawJson, new ObjectMapper()),
                rawJson);

        CapabilityContract contract = contractService.buildContract(deviceId);

        assertEquals("partial", contract.status());
        assertEquals(3, contract.unresolved().size());
        assertTrue(contract.outputs().stream().anyMatch(cap -> !cap.supported()));
        assertTrue(contract.display().sectionTypes().stream().anyMatch(cap -> !cap.supported()));
    }
}
