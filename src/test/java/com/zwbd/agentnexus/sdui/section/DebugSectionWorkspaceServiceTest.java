package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.protocol.catalog.FieldSpec;
import com.zwbd.agentnexus.sdui.protocol.catalog.SectionSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DebugSectionWorkspaceServiceTest {

    private DeviceCapabilityProjection capabilityProjection;
    private SectionOrchestrationService orchestrationService;
    private SectionTypeCatalog mockCatalog;
    private DebugSectionWorkspaceService service;

    @BeforeEach
    void setUp() {
        capabilityProjection = mock(DeviceCapabilityProjection.class);
        orchestrationService = mock(SectionOrchestrationService.class);
        mockCatalog = mock(SectionTypeCatalog.class);
        when(mockCatalog.get(anyString())).thenReturn(Optional.empty());
        when(mockCatalog.isValidType(anyString())).thenReturn(true);
        service = new DebugSectionWorkspaceService(capabilityProjection, new SectionDataCodec(mockCatalog), orchestrationService, mockCatalog);

        when(capabilityProjection.sections("dev-1")).thenReturn(List.of(
                new SectionSpec(
                        "hero_section",
                        List.of(
                                new FieldSpec("value", "string"),
                                new FieldSpec("label", "string"),
                                new FieldSpec("subtitle", "string"),
                                new FieldSpec("tone", "string"),
                                new FieldSpec("iconSrc", "string"),
                                new FieldSpec("iconSymbol", "string"),
                                new FieldSpec("progress", "int")
                        ),
                        true,
                        List.of("add", "update", "remove"),
                        List.of()
                ),
                new SectionSpec(
                        "text_section",
                        List.of(
                                new FieldSpec("title", "string"),
                                new FieldSpec("body", "string")
                        ),
                        true,
                        List.of("add", "update", "remove"),
                        List.of()
                )
        ));
        when(orchestrationService.sendScene(eq("dev-1"), any())).thenReturn(true);
        when(orchestrationService.sendPatch(eq("dev-1"), any())).thenReturn(true);
    }

    @Test
    void pushCreatesPageState() {
        Map<String, Object> result = service.push("dev-1", Map.of(
                "layout", "vertical_scroll",
                "sections", List.of(Map.of(
                        "sectionType", "hero_section",
                        "fields", Map.of(
                                "value", "85%",
                                "label", "CPU",
                                "subtitle", "Normal",
                                "tone", "primary",
                                "progress", 85
                        )
                ))
        ));

        assertTrue(String.valueOf(result.get("pageId")).startsWith("debug_page_"));
        assertEquals(1, result.get("sectionsBuilt"));
        @SuppressWarnings("unchecked")
        List<String> createdSectionIds = (List<String>) result.get("sectionIds");
        assertEquals(1, createdSectionIds.size());
        assertTrue(createdSectionIds.get(0).startsWith("section_"));

        // Single-page model: getState returns {deviceId, page: {...}}
        Map<String, Object> state = service.getState("dev-1");
        assertEquals("dev-1", state.get("deviceId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) state.get("page");
        assertNotNull(page);
        assertEquals(result.get("pageId"), page.get("pageId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) page.get("sections");
        assertEquals(1, sections.size());
        assertEquals(createdSectionIds.get(0), sections.get(0).get("sectionId"));

        verify(orchestrationService).sendScene(eq("dev-1"), any());
    }

    @Test
    void patchAddUpdateRemoveMutatesPageInOrder() {
        Map<String, Object> pushResult = service.push("dev-1", Map.of(
                "sections", List.of(Map.of(
                        "sectionType", "hero_section",
                        "fields", Map.of(
                                "value", "85%",
                                "label", "CPU",
                                "subtitle", "Normal",
                                "tone", "primary",
                                "progress", 85
                        )
                ))
        ));
        @SuppressWarnings("unchecked")
        List<String> createdSectionIds = (List<String>) pushResult.get("sectionIds");
        String heroSectionId = createdSectionIds.get(0);

        // Patch no longer needs pageId — operates on the single current page
        Map<String, Object> addResult = service.patch("dev-1", Map.of(
                "patches", List.of(Map.of(
                        "op", "add",
                        "sectionType", "text_section",
                        "fields", Map.of(
                                "title", "Tips",
                                "body", "Hello"
                        )
                ))
        ));
        @SuppressWarnings("unchecked")
        List<String> sectionIdsAfterAdd = (List<String>) addResult.get("sectionIds");
        assertEquals(2, sectionIdsAfterAdd.size());
        String textSectionId = sectionIdsAfterAdd.stream()
                .filter(id -> !id.equals(heroSectionId))
                .findFirst()
                .orElseThrow();

        service.patch("dev-1", Map.of(
                "patches", List.of(Map.of(
                        "sectionId", heroSectionId,
                        "op", "update",
                        "fields", Map.of(
                                "value", "92%",
                                "progress", 92
                        )
                ))
        ));

        Map<String, Object> stateAfterUpdate = service.getState("dev-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) stateAfterUpdate.get("page");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) page.get("sections");
        assertEquals(List.of(heroSectionId, textSectionId),
                sections.stream().map(s -> String.valueOf(s.get("sectionId"))).toList());
        @SuppressWarnings("unchecked")
        Map<String, Object> heroFields = (Map<String, Object>) sections.get(0).get("fields");
        assertEquals("92%", heroFields.get("value"));
        assertEquals("CPU", heroFields.get("label"));
        assertEquals(92, heroFields.get("progress"));

        service.patch("dev-1", Map.of(
                "patches", List.of(Map.of(
                        "sectionId", textSectionId,
                        "op", "remove"
                ))
        ));

        Map<String, Object> stateAfterRemove = service.getState("dev-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> pageAfterRemove = (Map<String, Object>) stateAfterRemove.get("page");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sectionsAfterRemove =
                (List<Map<String, Object>>) pageAfterRemove.get("sections");
        assertEquals(1, sectionsAfterRemove.size());
        assertEquals(heroSectionId, sectionsAfterRemove.get(0).get("sectionId"));

        verify(orchestrationService, times(3)).sendPatch(eq("dev-1"), any());
    }

    @Test
    void patchRejectsUnsupportedSectionType() {
        service.push("dev-1", Map.of(
                "sections", List.of(Map.of(
                        "sectionType", "hero_section",
                        "fields", Map.of(
                                "value", "85%",
                                "label", "CPU",
                                "subtitle", "Normal",
                                "tone", "primary",
                                "progress", 85
                        )
                ))
        ));

        // No pageId needed — operates on the current page
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.patch("dev-1", Map.of(
                "patches", List.of(Map.of(
                        "op", "add",
                        "sectionType", "unknown_section",
                        "fields", Map.of("title", "X")
                ))
        )));

        assertEquals("unsupported sectionType: unknown_section", ex.getMessage());
    }

    @Test
    void getStateReturnsNullPageWhenDeviceNotInitialized() {
        Map<String, Object> state = service.getState("missing-device");

        assertEquals("missing-device", state.get("deviceId"));
        assertNull(state.get("page"));
    }

    @Test
    void clearRemovesPageState() {
        service.push("dev-1", Map.of(
                "sections", List.of(Map.of(
                        "sectionType", "hero_section",
                        "fields", Map.of(
                                "value", "85%",
                                "label", "CPU",
                                "subtitle", "Normal",
                                "tone", "primary",
                                "progress", 85
                        )
                ))
        ));

        Map<String, Object> cleared = service.clear("dev-1");

        assertEquals(true, cleared.get("cleared"));
        assertNull(service.getState("dev-1").get("page"));
    }
}
