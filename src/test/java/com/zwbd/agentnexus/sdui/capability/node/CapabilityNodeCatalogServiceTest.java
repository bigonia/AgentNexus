package com.zwbd.agentnexus.sdui.capability.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.CapabilityCatalog;
import com.zwbd.agentnexus.sdui.capability.CapabilityContractService;
import com.zwbd.agentnexus.sdui.capability.CapabilityRegistry;
import com.zwbd.agentnexus.sdui.capability.PlatformCapabilityRegistry;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySnapshotParser;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceProtocolCatalog;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import com.zwbd.agentnexus.sdui.service.AudioService;
import com.zwbd.agentnexus.sdui.service.CommandSchemaRegistry;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityNodeCatalogServiceTest {

    private ObjectMapper objectMapper;
    private SduiCapabilityService capabilityService;
    private CapabilityNodeCatalogService nodeCatalogService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        SduiDeviceRepository deviceRepository = mock(SduiDeviceRepository.class);
        when(deviceRepository.findById(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        CommandSchemaRegistry schemaRegistry = mock(CommandSchemaRegistry.class);
        CapabilityCatalog capabilityCatalog = new CapabilityCatalog();
        ReflectionTestUtils.invokeMethod(capabilityCatalog, "load");
        CapabilityRegistry capabilityRegistry = new CapabilityRegistry(capabilityCatalog);
        DeviceProtocolCatalog protocolCatalog = new DeviceProtocolCatalog(capabilityCatalog);
        capabilityService = new SduiCapabilityService(
                deviceRepository,
                objectMapper,
                schemaRegistry,
                capabilityRegistry,
                capabilityCatalog,
                protocolCatalog
        );
        PlatformCapabilityRegistry platformCapabilityRegistry =
                new PlatformCapabilityRegistry(mock(AudioService.class));
        DeviceCapabilityProjection projection = new DeviceCapabilityProjection(protocolCatalog, capabilityService);
        CapabilityContractService contractService = new CapabilityContractService(
                capabilityService,
                capabilityCatalog,
                platformCapabilityRegistry,
                projection
        );
        DeviceSessionManager sessionManager = mock(DeviceSessionManager.class);
        when(sessionManager.isDeviceOnline(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        nodeCatalogService = new CapabilityNodeCatalogService(
                capabilityService,
                contractService,
                capabilityCatalog,
                sessionManager
        );
    }

    @Test
    void buildsDeviceFunctionNodesFromCapabilitySnapshot() {
        report("dev-node", """
                {
                  "capability_schema": "capability.v2",
                  "protocol_version": "sdui.topic.v1",
                  "board": "ESP32-S3",
                  "screen": {"w": 128, "h": 128, "shape": "rect"},
                  "input_mode": "keys",
                  "inputs": ["buttons.pwr", "audio.record"],
                  "outputs": ["rgb.effect", "audio.record", "audio.stream"],
                  "display": {
                    "transport": "ui3_binary:SECTION_SCENE",
                    "size_class": "small",
                    "section_types": ["hero_section"],
                    "layouts": ["vertical_scroll"]
                  }
                }
                """);

        CapabilityNodeCatalog catalog = nodeCatalogService.buildForDevice("dev-node");

        assertTrue(catalog.online());
        assertEquals("ok", catalog.status());
        assertTrue(hasNode(catalog, "button.trigger"));
        assertTrue(hasNode(catalog, "rgb.effect"));
        assertTrue(hasNode(catalog, "audio.record"));
        assertTrue(hasNode(catalog, "audio.play"));
        assertTrue(hasNode(catalog, "ui.update"));
        assertTrue(hasNode(catalog, "display.section"));

        CapabilityNodeDefinition audioRecord = node(catalog, "audio.record");
        assertEquals(CapabilityNodeRuntimeMode.SESSION, audioRecord.runtimeMode());
        assertTrue(audioRecord.artifacts().stream().anyMatch(a -> "audio_file".equals(a.name())));
        assertTrue(audioRecord.artifacts().stream().anyMatch(a -> "text".equals(a.name())));

        CapabilityNodeDefinition rgb = node(catalog, "rgb.effect");
        assertTrue(rgb.parameters().stream().anyMatch(p -> "off".equals(p.get("name"))));
        assertTrue(rgb.parameters().stream().anyMatch(p -> "r".equals(p.get("name"))));
        assertTrue(rgb.parameters().stream().anyMatch(p -> "mode".equals(p.get("name"))));

        CapabilityNodeDefinition play = node(catalog, "audio.play");
        assertTrue(play.parameters().stream().anyMatch(p -> "text".equals(p.get("name"))));
    }

    @Test
    void exposesUnresolvedCapabilitiesWithoutGeneratingExecutableNodes() {
        report("dev-unresolved-node", """
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
                """);

        CapabilityNodeCatalog catalog = nodeCatalogService.buildForDevice("dev-unresolved-node");

        assertEquals("partial", catalog.status());
        assertFalse(catalog.unresolvedNodes().isEmpty());
        assertFalse(hasNode(catalog, "button.trigger"));
        assertFalse(hasNode(catalog, "rgb.effect"));
        assertTrue(hasNode(catalog, "ui.update"));
    }

    private void report(String deviceId, String rawJson) {
        capabilityService.onCapabilitiesReport(deviceId,
                CapabilitySnapshotParser.parse(rawJson, objectMapper),
                rawJson);
    }

    private boolean hasNode(CapabilityNodeCatalog catalog, String nodeType) {
        return catalog.nodes().stream().anyMatch(node -> nodeType.equals(node.nodeType()));
    }

    private CapabilityNodeDefinition node(CapabilityNodeCatalog catalog, String nodeType) {
        return catalog.nodes().stream()
                .filter(node -> nodeType.equals(node.nodeType()))
                .findFirst()
                .orElseThrow();
    }
}
