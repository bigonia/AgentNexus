package com.zwbd.agentnexus.sdui.ui;

import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.section.SectionDataCodec;
import com.zwbd.agentnexus.sdui.section.SectionOrchestrationService;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import com.zwbd.agentnexus.sdui.ui.repo.SduiUiTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SduiUiTemplateServiceTest {

    private SduiUiTemplateRepository repository;
    private SectionTypeCatalog mockCatalog;
    private SduiUiTemplateService service;

    @BeforeEach
    void setUp() {
        repository = mock(SduiUiTemplateRepository.class);
        mockCatalog = mock(SectionTypeCatalog.class);
        when(mockCatalog.get(anyString())).thenReturn(Optional.empty());
        when(mockCatalog.defaultFieldValues(anyString())).thenReturn(Map.of());
        when(mockCatalog.isValidType(anyString())).thenReturn(true);
        service = new SduiUiTemplateService(
                repository,
                new SectionDataCodec(mockCatalog),
                mock(SectionOrchestrationService.class),
                mock(DeviceCapabilityProjection.class),
                mockCatalog
        );
    }

    @Test
    void createsTextTemplateWithVariableDefinition() {
        when(repository.findByTemplateKey("record_result_view")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            SduiUiTemplateEntity entity = invocation.getArgument(0);
            entity.setId("tpl-1");
            return entity;
        });

        Map<String, Object> created = service.create(textTemplate());

        assertEquals("tpl-1", created.get("templateId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> definition = (Map<String, Object>) created.get("definition");
        assertEquals("record_result_view", definition.get("templateKey"));
        assertEquals(List.of("text_section"), definition.get("requiredSectionTypes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variables = (List<Map<String, Object>>) definition.get("variables");
        assertEquals("recordText", variables.get(0).get("variableKey"));
    }

    @Test
    void previewBuildsSceneWithMockVariableValue() {
        SduiUiTemplateEntity entity = new SduiUiTemplateEntity();
        entity.setId("tpl-1");
        entity.setTemplateKey("record_result_view");
        entity.setName("录音结果展示");
        entity.setDefinition(service.normalizeDefinition(textTemplate()));
        when(repository.findById("tpl-1")).thenReturn(Optional.of(entity));

        Map<String, Object> preview = service.preview("tpl-1", Map.of(
                "variables", Map.of("recordText", "hello")
        ));

        assertEquals(false, preview.get("sent"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scene = (Map<String, Object>) preview.get("scene");
        assertEquals("main", scene.get("pageId"));
        assertTrue(scene.toString().contains("hello"));
    }

    private Map<String, Object> textTemplate() {
        return Map.of(
                "templateKey", "record_result_view",
                "name", "录音结果展示",
                "pageId", "main",
                "layout", "vertical_scroll",
                "sections", List.of(Map.of(
                        "sectionId", "workflow_text",
                        "sectionType", "text_section",
                        "fields", Map.of("title", "录音结果", "body", "等待录音...")
                )),
                "variables", List.of(Map.of(
                        "variableKey", "recordText",
                        "sectionId", "workflow_text",
                        "field", "body",
                        "type", "string",
                        "defaultValue", "等待录音..."
                )),
                "mockData", Map.of("recordText", "等待录音...")
        );
    }
}
