package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.capability.CapabilityContract;
import com.zwbd.agentnexus.sdui.capability.CapabilityContractService;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.protocol.catalog.FieldSpec;
import com.zwbd.agentnexus.sdui.protocol.catalog.SectionSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SectionEditorServiceTest {

    private CapabilityContractService contractService;
    private DeviceCapabilityProjection capabilityProjection;
    private SectionEditorService sectionEditorService;

    @BeforeEach
    void setUp() {
        contractService = mock(CapabilityContractService.class);
        capabilityProjection = mock(DeviceCapabilityProjection.class);
        sectionEditorService = new SectionEditorService(contractService, capabilityProjection);
    }

    @Test
    void buildSectionEditorReturnsProjectedSectionTypes() {
        when(contractService.buildContract("dev-1")).thenReturn(contractWithDisplay());
        when(capabilityProjection.sections("dev-1")).thenReturn(List.of(
                new SectionSpec(
                        "hero_section",
                        List.of(
                                new FieldSpec("value", "string"),
                                new FieldSpec("label", "string"),
                                new FieldSpec("subtitle", "string"),
                                new FieldSpec("tone", "enum"),
                                new FieldSpec("iconSrc", "enum"),
                                new FieldSpec("iconSymbol", "enum"),
                                new FieldSpec("progress", "int")
                        ),
                        true,
                        List.of("add", "update"),
                        List.of("action_click")
                )
        ));

        Map<String, Object> editor = sectionEditorService.buildSectionEditor("dev-1");

        assertEquals("rich", editor.get("renderMode"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sectionTypes = (List<Map<String, Object>>) editor.get("sectionTypes");
        assertEquals(1, sectionTypes.size());
        assertEquals("hero_section", sectionTypes.get(0).get("type"));
        assertTrue(sectionTypes.get(0).containsKey("displayFields"));
        assertTrue(sectionTypes.get(0).containsKey("events"));
        assertTrue(sectionTypes.get(0).containsKey("patchOps"));
    }

    @Test
    void buildSectionEditorFiltersCompactHiddenFields() {
        when(contractService.buildContract("dev-compact")).thenReturn(contractWithDisplay("small"));
        when(capabilityProjection.sections("dev-compact")).thenReturn(List.of(
                new SectionSpec(
                        "hero_section",
                        List.of(
                                new FieldSpec("value", "string"),
                                new FieldSpec("label", "string"),
                                new FieldSpec("subtitle", "string"),
                                new FieldSpec("tone", "enum"),
                                new FieldSpec("iconSrc", "enum"),
                                new FieldSpec("iconSymbol", "enum"),
                                new FieldSpec("progress", "int")
                        ),
                        true,
                        List.of("add", "update"),
                        List.of()
                )
        ));

        Map<String, Object> editor = sectionEditorService.buildSectionEditor("dev-compact");

        assertEquals("compact", editor.get("renderMode"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sectionTypes = (List<Map<String, Object>>) editor.get("sectionTypes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> displayFields = (List<Map<String, Object>>) sectionTypes.get(0).get("displayFields");

        assertEquals(List.of("value", "label", "tone"),
                displayFields.stream().map(field -> (String) field.get("name")).toList());
    }

    private CapabilityContract contractWithDisplay() {
        return contractWithDisplay("large");
    }

    private CapabilityContract contractWithDisplay(String sizeClass) {
        CapabilityContract.ContractCapability hero = new CapabilityContract.ContractCapability(
                "hero_section",
                "device",
                true,
                "display",
                "Hero 数据",
                "展示型 Section",
                Map.of(
                        "displayFields", SectionTypeCatalog.fieldsToMaps(
                                SectionTypeCatalog.getOrThrow("hero_section").displayFields()),
                        "interactionEvents", List.of()
                ),
                Map.of("transport", "mqtt"),
                Map.of(),
                "device.ui.section"
        );
        return new CapabilityContract(
                "dev-1",
                "ok",
                List.of(),
                List.of(),
                new CapabilityContract.DisplayContract(
                        "device",
                        true,
                        sizeClass,
                        List.of("vertical_scroll"),
                        List.of(hero),
                        Map.of("maxSections", 3),
                        Map.of("transport", "mqtt"),
                        "device.ui"
                ),
                List.of(),
                List.of()
        );
    }
}
