package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.protocol.catalog.FieldSpec;
import com.zwbd.agentnexus.sdui.protocol.catalog.SectionSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DebugSectionWorkspaceServiceTest {

    private DeviceCapabilityProjection capabilityProjection;
    private SectionOrchestrationService orchestrationService;
    private DebugSectionWorkspaceService service;

    @BeforeEach
    void setUp() {
        capabilityProjection = mock(DeviceCapabilityProjection.class);
        orchestrationService = mock(SectionOrchestrationService.class);
        service = new DebugSectionWorkspaceService(capabilityProjection, new SectionDataCodec(), orchestrationService);

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
    void pushCreatesWorkspaceState() {
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

        Map<String, Object> state = service.getState("dev-1");
        assertEquals(result.get("pageId"), state.get("activePageId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pages = (List<Map<String, Object>>) state.get("pages");
        assertEquals(1, pages.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) pages.get(0).get("sections");
        assertEquals(createdSectionIds.get(0), sections.get(0).get("sectionId"));

        verify(orchestrationService).sendScene(eq("dev-1"), any());
    }

    @Test
    void patchAddUpdateRemoveMutatesWorkspaceInOrder() {
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
        String pageId = String.valueOf(pushResult.get("pageId"));
        @SuppressWarnings("unchecked")
        List<String> createdSectionIds = (List<String>) pushResult.get("sectionIds");
        String heroSectionId = createdSectionIds.get(0);

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
                "pageId", pageId,
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
        List<Map<String, Object>> pages = (List<Map<String, Object>>) stateAfterUpdate.get("pages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) pages.get(0).get("sections");
        assertEquals(List.of(heroSectionId, textSectionId),
                sections.stream().map(s -> String.valueOf(s.get("sectionId"))).toList());
        @SuppressWarnings("unchecked")
        Map<String, Object> heroFields = (Map<String, Object>) sections.get(0).get("fields");
        assertEquals("92%", heroFields.get("value"));
        assertEquals("CPU", heroFields.get("label"));
        assertEquals(92, heroFields.get("progress"));

        service.patch("dev-1", Map.of(
                "pageId", pageId,
                "patches", List.of(Map.of(
                        "sectionId", textSectionId,
                        "op", "remove"
                ))
        ));

        Map<String, Object> stateAfterRemove = service.getState("dev-1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pagesAfterRemove = (List<Map<String, Object>>) stateAfterRemove.get("pages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sectionsAfterRemove =
                (List<Map<String, Object>>) pagesAfterRemove.get(0).get("sections");
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
    void getStateReturnsEmptyWorkspaceWhenDeviceNotInitialized() {
        Map<String, Object> state = service.getState("missing-device");

        assertEquals("missing-device", state.get("deviceId"));
        assertNull(state.get("activePageId"));
        assertEquals(List.of(), state.get("pages"));
    }

    @Test
    void clearRemovesWorkspaceState() {
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
        assertEquals(List.of(), cleared.get("pages"));
        assertEquals(List.of(), service.getState("dev-1").get("pages"));
    }
}
