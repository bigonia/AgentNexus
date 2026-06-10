package com.zwbd.agentnexus.sdui.capability;

import com.zwbd.agentnexus.sdui.service.AudioService;
import com.zwbd.agentnexus.sdui.service.CommandSchemaRegistry;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNodeRegistry;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapabilityInvocationValidatorTest {

    private CommandSchemaRegistry commandSchemaRegistry;
    private SduiCapabilityService capabilityService;
    private CapabilityContractService contractService;
    private PlatformCapabilityRegistry platformCapabilityRegistry;
    private CapabilityNodeRegistry nodeRegistry;
    private CapabilityInvocationValidator validator;

    @BeforeEach
    void setUp() {
        commandSchemaRegistry = mock(CommandSchemaRegistry.class);
        capabilityService = mock(SduiCapabilityService.class);
        contractService = mock(CapabilityContractService.class);
        nodeRegistry = mock(CapabilityNodeRegistry.class);
        AudioService audioService = mock(AudioService.class);
        when(audioService.getPresets()).thenReturn(List.of(Map.of("id", "notification")));
        when(audioService.isTtsAvailable()).thenReturn(true);
        when(audioService.isSttAvailable()).thenReturn(true);
        platformCapabilityRegistry = new PlatformCapabilityRegistry(audioService);
        validator = new CapabilityInvocationValidator(
                commandSchemaRegistry,
                capabilityService,
                contractService,
                platformCapabilityRegistry,
                nodeRegistry
        );
    }

    @Test
    void validatesPlatformDebugInvocationAndAppliesDefaults() {
        CapabilityInvocationValidator.ValidationResult result =
                validator.validateDebugInvocation("dev-1", "audio.prompt.play", Map.of());

        assertTrue(result.valid());
        assertEquals("notification", result.normalizedParams().get("preset"));
    }

    @Test
    void rejectsUnsupportedSectionTypeForDevicePatchNode() {
        NodeSchema patchSchema = new NodeSchema(
                "device.section.patch",
                "更新 Section",
                "向设备推送 Section 内容更新",
                "device",
                "layout-dashboard",
                List.of(
                        new NodeSchema.ParamDef("slotBinding", "string", true, null, "slot"),
                        new NodeSchema.ParamDef("sectionType", "string", true, null, "type"),
                        new NodeSchema.ParamDef("data", "object", true, null, "data")
                ),
                List.of(),
                false,
                5000,
                true,
                "device",
                "section_patch",
                "device.ui.section",
                Map.of("requiresSectionType", true)
        );
        when(nodeRegistry.getDeviceSchemas("dev-1")).thenReturn(List.of(patchSchema));
        List<CapabilityContract.ContractCapability> platformCapabilities = platformCapabilityRegistry.listCapabilities().stream()
                .map(def -> new CapabilityContract.ContractCapability(
                        def.id(),
                        "platform",
                        def.available(),
                        def.category(),
                        def.displayName(),
                        def.description(),
                        def.schema(),
                        def.protocol(),
                        def.constraints(),
                        def.runtimeHandler()
                ))
                .toList();
        CapabilityContract contract = new CapabilityContract(
                "dev-1",
                "ok",
                List.of(),
                List.of(),
                new CapabilityContract.DisplayContract(
                        "device",
                        true,
                        "large",
                        List.of("vertical_scroll"),
                        List.of(new CapabilityContract.ContractCapability(
                                "hero_section",
                                "device",
                                true,
                                "display",
                                "Hero",
                                "hero",
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                "device.ui.section"
                        )),
                        Map.of(),
                        Map.of(),
                        "device.ui"
                ),
                platformCapabilities,
                List.of()
        );
        when(contractService.buildContract("dev-1")).thenReturn(contract);

        CapabilityInvocationValidator.ValidationResult result = validator.validateNodeAction(
                "dev-1",
                "device.section.patch",
                Map.of(
                        "slotBinding", "home/banner",
                        "sectionType", "unknown_section",
                        "data", Map.of("title", "Hello")
                )
        );

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(msg -> msg.contains("Unsupported sectionType")));
    }
}
