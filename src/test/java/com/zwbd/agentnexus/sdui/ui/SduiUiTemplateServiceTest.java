package com.zwbd.agentnexus.sdui.ui;

import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.section.*;
import com.zwbd.agentnexus.sdui.ui.repo.SduiUiTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SduiUiTemplateServiceTest {

    private SduiUiTemplateRepository repository;
    private SectionTypeCatalog mockCatalog;
    private PageService mockPageService;
    private SduiUiTemplateService service;

    @BeforeEach
    void setUp() {
        repository = mock(SduiUiTemplateRepository.class);
        mockCatalog = mock(SectionTypeCatalog.class);
        when(mockCatalog.get(anyString())).thenReturn(Optional.empty());
        when(mockCatalog.defaultFieldValues(anyString())).thenReturn(Map.of());
        when(mockCatalog.isValidType(anyString())).thenReturn(true);
        // Stub getOrThrow for parameterizable validation in normalizeDefinition
        SectionTypeCatalog.SectionFieldDef bodyField = new SectionTypeCatalog.SectionFieldDef(
                "body", "string", "正文", "", null, null, null, null, false, true, null);
        SectionTypeCatalog.SectionFieldDef titleField = new SectionTypeCatalog.SectionFieldDef(
                "title", "string", "标题", "", null, null, null, null, false, true, null);
        SectionTypeCatalog.SectionTypeDef textTypeDef = new SectionTypeCatalog.SectionTypeDef(
                "text_section", "文本", false,
                List.of(titleField, bodyField), List.of(), Map.of(), Set.of());
        when(mockCatalog.getOrThrow("text_section")).thenReturn(textTypeDef);
        // action_section with nested-path support
        SectionTypeCatalog.SectionFieldDef actionId = new SectionTypeCatalog.SectionFieldDef(
                "id", "string", "按钮ID", "", null, null, null, null, false, false, null);
        SectionTypeCatalog.SectionFieldDef actionLabel = new SectionTypeCatalog.SectionFieldDef(
                "label", "string", "按钮文本", "", null, null, null, null, false, true, null);
        SectionTypeCatalog.SectionFieldDef actionTone = new SectionTypeCatalog.SectionFieldDef(
                "tone", "enum", "样式", "primary", null, null,
                List.of("primary", "secondary", "success", "warning", "danger"), null, false, true, null);
        SectionTypeCatalog.SectionFieldDef actionEnabled = new SectionTypeCatalog.SectionFieldDef(
                "enabled", "boolean", "启用", true, null, null, null, null, false, true, null);
        SectionTypeCatalog.SectionFieldDef actionsField = new SectionTypeCatalog.SectionFieldDef(
                "actions", "array", "按钮列表", null, null, null, null, null, false, false,
                List.of(actionId, actionLabel, actionTone, actionEnabled));
        SectionTypeCatalog.SectionTypeDef actionTypeDef = new SectionTypeCatalog.SectionTypeDef(
                "action_section", "操作按钮", true,
                List.of(actionsField), List.of(), Map.of(), Set.of());
        when(mockCatalog.getOrThrow("action_section")).thenReturn(actionTypeDef);
        mockPageService = mock(PageService.class);
        when(mockPageService.create(anyString(), anyString(), any())).thenAnswer(inv -> {
            SduiPageEntity page = new SduiPageEntity();
            page.setPageId("page_test1");
            page.setName(inv.getArgument(0));
            page.setLayout(inv.getArgument(1));
            return page;
        });
        when(mockPageService.findByPageId(anyString())).thenReturn(Optional.empty());
        when(mockPageService.requireByPageId(anyString())).thenAnswer(inv -> {
            SduiPageEntity page = new SduiPageEntity();
            page.setPageId(inv.getArgument(0));
            page.setName("test");
            page.setLayout("vertical_scroll");
            page.setSectionsJson("[{\"sectionId\":\"workflow_text\",\"sectionType\":\"text_section\",\"fields\":{\"title\":\"录音结果\",\"body\":\"等待录音...\"}}]");
            return page;
        });
        when(mockPageService.toPageDefinition(any())).thenAnswer(inv -> {
            SduiPageEntity page = inv.getArgument(0);
            LinkedHashMap<String, SectionPageDefinition.SectionDef> defs = new LinkedHashMap<>();
            defs.put("workflow_text", new SectionPageDefinition.SectionDef("workflow_text", "text_section",
                    Map.of("title", "录音结果", "body", "等待录音...")));
            return new SectionPageDefinition("main", SectionLayout.VERTICAL_SCROLL, false, 0, defs);
        });
        service = new SduiUiTemplateService(
                repository,
                new SectionDataCodec(mockCatalog),
                mock(SectionOrchestrationService.class),
                mock(DeviceCapabilityProjection.class),
                mockCatalog,
                mockPageService
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
        assertEquals("page_test1", created.get("pageId"));
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
        entity.setPageId("page_test1");
        entity.setDefinition(service.normalizeDefinition(textTemplate()));
        when(repository.findById("tpl-1")).thenReturn(Optional.of(entity));

        Map<String, Object> preview = service.preview("tpl-1", Map.of(
                "variables", Map.of("recordText", "hello")
        ));

        assertEquals(false, preview.get("sent"));
        assertEquals("page_test1", preview.get("pageId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scene = (Map<String, Object>) preview.get("scene");
        assertEquals("main", scene.get("pageId"));
        assertTrue(scene.toString().contains("hello"));
    }

    @Test
    void updatePreservesExistingTemplateKeyWhenBodyOmitsIt() {
        SduiUiTemplateEntity entity = templateEntity();
        when(repository.findById("tpl-1")).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> body = new LinkedHashMap<>(textTemplate());
        body.remove("templateKey");
        body.put("name", "更新后的模板");

        Map<String, Object> updated = service.update("tpl-1", body);

        assertEquals("record_result_view", updated.get("templateKey"));
        assertEquals("record_result_view", entity.getTemplateKey());
        verify(mockPageService).update(eq("page_test1"), eq("更新后的模板"), eq("vertical_scroll"), eq(false), eq(0), any());
    }

    @Test
    void updateRejectsTemplateKeyChanges() {
        SduiUiTemplateEntity entity = templateEntity();
        when(repository.findById("tpl-1")).thenReturn(Optional.of(entity));

        Map<String, Object> body = new LinkedHashMap<>(textTemplate());
        body.put("templateKey", "new_template_key");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.update("tpl-1", body));

        assertEquals("templateKey cannot be changed after creation: record_result_view", error.getMessage());
        verify(repository, never()).save(any());
        verify(mockPageService, never()).update(anyString(), anyString(), anyString(), anyBoolean(), anyInt(), any());
    }

    @Test
    void createsTemplateWithNestedArrayPathVariable() {
        when(repository.findByTemplateKey("action_template")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            SduiUiTemplateEntity entity = invocation.getArgument(0);
            entity.setId("tpl-2");
            return entity;
        });

        Map<String, Object> created = service.create(actionTemplate());

        assertEquals("tpl-2", created.get("templateId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> definition = (Map<String, Object>) created.get("definition");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variables = (List<Map<String, Object>>) definition.get("variables");
        assertEquals(2, variables.size());
        assertEquals("actions.primary.label", variables.get(0).get("field"));
        assertEquals("actions.secondary.enabled", variables.get(1).get("field"));
    }

    @Test
    void rejectsNestedPathOnNonParameterizableChild() {
        Map<String, Object> template = new LinkedHashMap<>(actionTemplate());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variables = new ArrayList<>((List<Map<String, Object>>) template.get("variables"));
        // Try to bind to "id" field which is NOT parameterizable
        variables.add(Map.of(
                "variableKey", "badVar",
                "sectionId", "action_sec",
                "field", "actions.primary.id",
                "type", "string",
                "defaultValue", ""
        ));
        template.put("variables", variables);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(template));
        assertTrue(error.getMessage().contains("not parameterizable"));
    }

    @Test
    void rejectsNestedPathOnNonArrayField() {
        Map<String, Object> template = new LinkedHashMap<>(textTemplate());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variables = new ArrayList<>((List<Map<String, Object>>) template.get("variables"));
        variables.clear();
        variables.add(Map.of(
                "variableKey", "badVar",
                "sectionId", "workflow_text",
                "field", "body.invalid.child",  // 3-segment on non-array field
                "type", "string",
                "defaultValue", ""
        ));
        template.put("variables", variables);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(template));
        assertTrue(error.getMessage().contains("3-segment path only supported for array fields"));
    }

    @Test
    void deepSetUpdatesNestedFieldInArray() {
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Object> btn1 = new LinkedHashMap<>(Map.of("id", "btn_a", "label", "旧标签", "enabled", true));
        Map<String, Object> btn2 = new LinkedHashMap<>(Map.of("id", "btn_b", "label", "保留标签", "enabled", false));
        fields.put("actions", new ArrayList<>(List.of(btn1, btn2)));

        SduiUiTemplateService.deepSet(fields, "actions.btn_a.label", "新标签");

        assertEquals("新标签", btn1.get("label"));
        assertEquals("保留标签", btn2.get("label")); // untouched
    }

    @Test
    void rejectsWholeArrayBindingWhenParentNotParameterizable() {
        Map<String, Object> template = new LinkedHashMap<>(actionTemplate());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variables = new ArrayList<>((List<Map<String, Object>>) template.get("variables"));
        variables.clear();
        // Try single-segment binding to "actions" — not parameterizable
        variables.add(Map.of(
                "variableKey", "allActions",
                "sectionId", "action_sec",
                "field", "actions",
                "type", "array",
                "defaultValue", List.of()
        ));
        template.put("variables", variables);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(template));
        assertTrue(error.getMessage().contains("not parameterizable"));
    }

    @Test
    void deepSetSimpleFieldDelegatesToPut() {
        Map<String, Object> fields = new LinkedHashMap<>();
        SduiUiTemplateService.deepSet(fields, "title", "hello");
        assertEquals("hello", fields.get("title"));
    }

    private Map<String, Object> actionTemplate() {
        return Map.of(
                "templateKey", "action_template",
                "name", "操作模板",
                "pageId", "main",
                "layout", "vertical_scroll",
                "sections", List.of(Map.of(
                        "sectionId", "action_sec",
                        "sectionType", "action_section",
                        "fields", Map.of("actions", List.of(
                                Map.of("id", "primary", "label", "主要操作", "tone", "primary", "enabled", true),
                                Map.of("id", "secondary", "label", "次要操作", "tone", "secondary", "enabled", true)
                        ))
                )),
                "variables", List.of(
                        Map.of("variableKey", "btnLabel", "sectionId", "action_sec",
                                "field", "actions.primary.label", "type", "string", "defaultValue", "主要操作"),
                        Map.of("variableKey", "btnEnabled", "sectionId", "action_sec",
                                "field", "actions.secondary.enabled", "type", "boolean", "defaultValue", true)
                ),
                "mockData", Map.of()
        );
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

    private SduiUiTemplateEntity templateEntity() {
        SduiUiTemplateEntity entity = new SduiUiTemplateEntity();
        entity.setId("tpl-1");
        entity.setTemplateKey("record_result_view");
        entity.setName("录音结果展示");
        entity.setPageId("page_test1");
        entity.setDefinition(service.normalizeDefinition(textTemplate()));
        return entity;
    }
}
