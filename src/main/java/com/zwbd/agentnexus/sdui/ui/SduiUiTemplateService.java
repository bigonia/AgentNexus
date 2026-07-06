package com.zwbd.agentnexus.sdui.ui;

import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.section.*;
import com.zwbd.agentnexus.sdui.ui.repo.SduiUiTemplateRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SduiUiTemplateService {

    private static final String DEFAULT_PAGE_ID = "main";
    private static final String DEFAULT_LAYOUT = "vertical_scroll";

    private final SduiUiTemplateRepository repository;
    private final SectionDataCodec sectionDataCodec;
    private final SectionOrchestrationService sectionOrchestrationService;
    private final DeviceCapabilityProjection capabilityProjection;
    private final SectionTypeCatalog sectionTypeCatalog;
    private final PageService pageService;

    public SduiUiTemplateService(SduiUiTemplateRepository repository,
                                 SectionDataCodec sectionDataCodec,
                                 SectionOrchestrationService sectionOrchestrationService,
                                 DeviceCapabilityProjection capabilityProjection,
                                 SectionTypeCatalog sectionTypeCatalog,
                                 PageService pageService) {
        this.repository = repository;
        this.sectionDataCodec = sectionDataCodec;
        this.sectionOrchestrationService = sectionOrchestrationService;
        this.capabilityProjection = capabilityProjection;
        this.sectionTypeCatalog = sectionTypeCatalog;
        this.pageService = pageService;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        Map<String, Object> definition = normalizeDefinition(body);
        String templateKey = string(definition.get("templateKey"));
        repository.findByTemplateKey(templateKey).ifPresent(existing -> {
            throw new IllegalArgumentException("templateKey already exists: " + templateKey);
        });
        // Create page entity from sections+layout
        String name = string(definition.getOrDefault("name", templateKey));
        String board = string(definition.get("board"));
        String layout = string(definition.get("layout"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) definition.get("sections");
        SduiPageEntity page = pageService.create(name, layout, sections);
        // Store slim definition (without sections/layout/pageId) + pageId reference
        SduiUiTemplateEntity entity = new SduiUiTemplateEntity();
        entity.setTemplateKey(templateKey);
        entity.setName(name);
        entity.setBoard(board.isBlank() ? null : board);
        entity.setPageId(page.getPageId());
        entity.setDefinition(slimDefinition(definition));
        return toMap(repository.save(entity), true);
    }

    public List<Map<String, Object>> list() {
        return repository.findAllByOrderByUpdatedAtDesc().stream()
                .map(entity -> toMap(entity, false))
                .toList();
    }

    public Map<String, Object> get(String templateId) {
        return toMap(require(templateId), true);
    }

    @Transactional
    public Map<String, Object> update(String templateId, Map<String, Object> body) {
        SduiUiTemplateEntity entity = require(templateId);
        Map<String, Object> definition = normalizeDefinition(body, entity.getTemplateKey());
        String templateKey = string(definition.get("templateKey"));
        if (!entity.getTemplateKey().equals(templateKey)) {
            throw new IllegalArgumentException("templateKey cannot be changed after creation: " + entity.getTemplateKey());
        }
        entity.setName(string(definition.getOrDefault("name", templateKey)));
        String board = string(definition.get("board"));
        entity.setBoard(board.isBlank() ? null : board);
        entity.setDefinition(slimDefinition(definition));
        // Update associated page
        String layout = string(definition.get("layout"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) definition.get("sections");
        if (entity.getPageId() != null) {
            pageService.update(entity.getPageId(), entity.getName(), layout, false, 0, sections);
        } else {
            SduiPageEntity page = pageService.create(entity.getName(), layout, sections);
            entity.setPageId(page.getPageId());
        }
        return toMap(repository.save(entity), true);
    }

    @Transactional
    public Map<String, Object> delete(String templateId) {
        SduiUiTemplateEntity entity = require(templateId);
        if (entity.getPageId() != null) {
            pageService.delete(entity.getPageId());
        }
        repository.delete(entity);
        return Map.of("deleted", true, "templateId", templateId, "templateKey", entity.getTemplateKey());
    }

    public Map<String, Object> preview(String templateId, Map<String, Object> body) {
        SduiUiTemplateEntity entity = require(templateId);
        Map<String, Object> variables = map(body.getOrDefault("variables", entity.getDefinition().get("mockData")));
        // Build scene from PageEntity + template variables
        SectionScene scene = buildSceneFromPage(entity, variables);
        boolean push = Boolean.TRUE.equals(body.get("push"));
        String deviceId = string(body.get("deviceId"));
        boolean sent = false;
        if (push) {
            if (deviceId.isBlank()) {
                throw new IllegalArgumentException("deviceId is required when push=true");
            }
            validateDeviceSupportsTemplate(deviceId, entity.getDefinition());
            sent = sectionOrchestrationService.sendScene(deviceId, scene);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateId", entity.getId());
        result.put("templateKey", entity.getTemplateKey());
        result.put("pageId", entity.getPageId());
        result.put("variables", variables);
        result.put("scene", sceneToMap(scene));
        result.put("sent", sent);
        return result;
    }

    public SduiUiTemplateEntity requireByKey(String templateKey) {
        return repository.findByTemplateKey(templateKey)
                .orElseThrow(() -> new IllegalArgumentException("ui template not found: " + templateKey));
    }

    public SduiUiTemplateEntity require(String templateIdOrKey) {
        return repository.findById(templateIdOrKey)
                .or(() -> repository.findByTemplateKey(templateIdOrKey))
                .orElseThrow(() -> new IllegalArgumentException("ui template not found: " + templateIdOrKey));
    }

    public Map<String, Object> normalizeDefinition(Map<String, Object> body) {
        return normalizeDefinition(body, "");
    }

    private Map<String, Object> normalizeDefinition(Map<String, Object> body, String defaultTemplateKey) {
        Map<String, Object> source = map(body.getOrDefault("definition", body));
        String templateKey = string(source.getOrDefault("templateKey", body.getOrDefault("templateKey", defaultTemplateKey)));
        String name = string(source.getOrDefault("name", body.getOrDefault("name", "")));
        // 未提供 templateKey 时自动生成：名称 slug + 短 UUID
        if (templateKey.isBlank()) {
            String base = name.isBlank() ? "template" : name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
            templateKey = base + "_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }
        String board = string(source.get("board"));
        String pageId = string(source.getOrDefault("pageId", DEFAULT_PAGE_ID));
        String layout = string(source.getOrDefault("layout", DEFAULT_LAYOUT));
        if (SectionLayout.fromWireName(layout) == null) {
            throw new IllegalArgumentException("unsupported layout: " + layout);
        }

        List<Map<String, Object>> sections = listOfMaps(source.get("sections"));
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("sections is required");
        }
        List<Map<String, Object>> normalizedSections = new ArrayList<>();
        Set<String> sectionIds = new LinkedHashSet<>();
        Set<String> sectionTypes = new LinkedHashSet<>();
        Map<String, String> sectionTypeBySectionId = new LinkedHashMap<>();
        for (Map<String, Object> section : sections) {
            String sectionId = string(section.get("sectionId"));
            String sectionType = string(section.getOrDefault("sectionType", section.get("type")));
            if (sectionId.isBlank()) throw new IllegalArgumentException("sectionId is required");
            if (!sectionIds.add(sectionId)) throw new IllegalArgumentException("duplicate sectionId: " + sectionId);
            if (!sectionTypeCatalog.isValidType(sectionType)) {
                throw new IllegalArgumentException("unsupported sectionType: " + sectionType);
            }
            Map<String, Object> fields = sectionDataCodec.normalizeFields(sectionType, map(section.get("fields")));
            if (sectionDataCodec.buildSectionData(sectionType, fields, sectionId) == null) {
                throw new IllegalArgumentException("invalid fields for section: " + sectionId);
            }
            normalizedSections.add(new LinkedHashMap<>(Map.of(
                    "sectionId", sectionId,
                    "sectionType", sectionType,
                    "fields", fields
            )));
            sectionTypes.add(sectionType);
            sectionTypeBySectionId.put(sectionId, sectionType);
        }

        List<Map<String, Object>> normalizedVariables = new ArrayList<>();
        Set<String> variableKeys = new LinkedHashSet<>();
        for (Map<String, Object> variable : listOfMaps(source.get("variables"))) {
            String variableKey = string(variable.getOrDefault("variableKey", variable.get("key")));
            String sectionId = string(variable.get("sectionId"));
            String field = string(variable.getOrDefault("field", variable.get("fieldPath")));
            if (variableKey.isBlank()) throw new IllegalArgumentException("variableKey is required");
            if (!variableKeys.add(variableKey)) throw new IllegalArgumentException("duplicate variableKey: " + variableKey);
            if (!sectionIds.contains(sectionId)) throw new IllegalArgumentException("variable references unknown sectionId: " + sectionId);
            if (field.isBlank()) throw new IllegalArgumentException("variable field is required: " + variableKey);
            // Validate path against section type catalog (supports nested paths like actions.primary.label)
            String sectionType = sectionTypeBySectionId.get(sectionId);
            validateFieldPath(sectionType, field, variableKey);
            Map<String, Object> normalizedVariable = new LinkedHashMap<>();
            normalizedVariable.put("variableKey", variableKey);
            normalizedVariable.put("sectionId", sectionId);
            normalizedVariable.put("field", field);
            normalizedVariable.put("type", string(variable.getOrDefault("type", "string")));
            normalizedVariable.put("defaultValue", variable.getOrDefault("defaultValue", ""));
            normalizedVariables.add(normalizedVariable);
        }

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("templateKey", templateKey);
        definition.put("name", name);
        if (!board.isBlank()) definition.put("board", board);
        definition.put("pageId", pageId);
        definition.put("layout", layout);
        definition.put("sections", normalizedSections);
        definition.put("variables", normalizedVariables);
        definition.put("mockData", new LinkedHashMap<>(map(source.get("mockData"))));
        definition.put("requiredSectionTypes", new ArrayList<>(sectionTypes));
        return definition;
    }

    public void validateDeviceSupportsTemplate(String deviceId, Map<String, Object> definition) {
        Set<String> supported = new LinkedHashSet<>();
        capabilityProjection.sections(deviceId).forEach(section -> supported.add(section.type()));
        for (Object item : list(definition.get("requiredSectionTypes"))) {
            String sectionType = string(item);
            if (!supported.contains(sectionType)) {
                throw new IllegalArgumentException("device " + deviceId + " does not support UI sectionType " + sectionType);
            }
        }
    }

    /**
     * Strip sections, layout, and pageId from definition — those now live in PageEntity.
     * The slim definition keeps only variables, mockData, requiredSectionTypes, and metadata.
     */
    private Map<String, Object> slimDefinition(Map<String, Object> full) {
        Map<String, Object> slim = new LinkedHashMap<>();
        slim.put("templateKey", full.get("templateKey"));
        slim.put("name", full.get("name"));
        slim.put("variables", full.getOrDefault("variables", List.of()));
        slim.put("mockData", full.getOrDefault("mockData", Map.of()));
        slim.put("requiredSectionTypes", full.getOrDefault("requiredSectionTypes", List.of()));
        return slim;
    }

    /**
     * Build a SectionScene by loading the PageEntity (for sections+layout)
     * and merging template variables.
     */
    private SectionScene buildSceneFromPage(SduiUiTemplateEntity entity, Map<String, Object> variables) {
        if (entity.getPageId() == null) {
            throw new IllegalArgumentException("template has no associated page: " + entity.getId());
        }
        SduiPageEntity page = pageService.requireByPageId(entity.getPageId());
        SectionPageDefinition pageDef = pageService.toPageDefinition(page);
        // Merge variables from template definition into page sections
        Map<String, Object> values = variableValues(entity.getDefinition(), variables);
        LinkedHashMap<String, SectionPageDefinition.SectionDef> merged = new LinkedHashMap<>();
        for (var entry : pageDef.sections().entrySet()) {
            String sectionId = entry.getKey();
            SectionPageDefinition.SectionDef def = entry.getValue();
            Map<String, Object> fields = new LinkedHashMap<>(def.fields());
            for (Map<String, Object> variable : listOfMaps(entity.getDefinition().get("variables"))) {
                if (sectionId.equals(string(variable.get("sectionId")))) {
                    String field = string(variable.get("field"));
                    String variableKey = string(variable.get("variableKey"));
                    if (values.containsKey(variableKey)) {
                        deepSet(fields, field, values.get(variableKey));
                    }
                }
            }
            merged.put(sectionId, new SectionPageDefinition.SectionDef(sectionId, def.sectionType(), fields));
        }
        SectionPageDefinition resolvedPage = new SectionPageDefinition(
                pageDef.pageId(), pageDef.layout(), pageDef.autoScroll(), pageDef.autoScrollMs(), merged);
        return resolvedPage.toScene(sectionDataCodec);
    }

    public SectionPageDefinition toPageDefinition(Map<String, Object> definition, String pageId) {
        if (pageId == null || pageId.isBlank()) {
            throw new IllegalArgumentException("pageId is required");
        }
        SduiPageEntity page = pageService.requireByPageId(pageId);
        SectionPageDefinition pageDef = pageService.toPageDefinition(page);
        // Merge variables
        Map<String, Object> values = variableValues(definition, Map.of());
        LinkedHashMap<String, SectionPageDefinition.SectionDef> merged = new LinkedHashMap<>();
        for (var entry : pageDef.sections().entrySet()) {
            String sectionId = entry.getKey();
            SectionPageDefinition.SectionDef def = entry.getValue();
            Map<String, Object> fields = new LinkedHashMap<>(def.fields());
            for (Map<String, Object> variable : listOfMaps(definition.get("variables"))) {
                if (sectionId.equals(string(variable.get("sectionId")))) {
                    String field = string(variable.get("field"));
                    String variableKey = string(variable.get("variableKey"));
                    if (values.containsKey(variableKey)) {
                        deepSet(fields, field, values.get(variableKey));
                    }
                }
            }
            merged.put(sectionId, new SectionPageDefinition.SectionDef(sectionId, def.sectionType(), fields));
        }
        return new SectionPageDefinition(pageDef.pageId(), pageDef.layout(),
                pageDef.autoScroll(), pageDef.autoScrollMs(), merged);
    }

    /**
     * Build a SectionScene from a template entity and resolved variables.
     * Used by WorkflowUiContextService during deployment initialization.
     */
    public SectionScene toScene(SduiUiTemplateEntity entity, Map<String, Object> variables) {
        return buildSceneFromPage(entity, variables);
    }

    Map<String, Object> variableValues(Map<String, Object> definition, Map<String, Object> overrides) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map<String, Object> variable : listOfMaps(definition.get("variables"))) {
            values.put(string(variable.get("variableKey")), variable.getOrDefault("defaultValue", ""));
        }
        values.putAll(map(definition.get("mockData")));
        values.putAll(map(overrides));
        return values;
    }

    public Map<String, Object> toMap(SduiUiTemplateEntity entity, boolean includeDefinition) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("templateId", entity.getId());
        data.put("id", entity.getId());
        data.put("templateKey", entity.getTemplateKey());
        data.put("name", entity.getName());
        data.put("board", entity.getBoard());
        data.put("status", entity.getStatus());
        data.put("pageId", entity.getPageId());
        if (includeDefinition) data.put("definition", entity.getDefinition());
        // Include associated page data if available
        if (includeDefinition && entity.getPageId() != null) {
            pageService.findByPageId(entity.getPageId()).ifPresent(page -> {
                data.put("page", pageSectionToMap(page));
            });
        }
        data.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        data.put("updatedAt", entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        return data;
    }

    private Map<String, Object> pageSectionToMap(SduiPageEntity page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageId", page.getPageId());
        result.put("name", page.getName());
        result.put("layout", page.getLayout());
        result.put("autoScroll", page.isAutoScroll());
        result.put("autoScrollMs", page.getAutoScrollMs());
        SectionPageDefinition pageDef = pageService.toPageDefinition(page);
        List<Map<String, Object>> sections = new ArrayList<>();
        for (var entry : pageDef.sections().entrySet()) {
            sections.add(Map.of(
                    "sectionId", entry.getValue().sectionId(),
                    "sectionType", entry.getValue().sectionType(),
                    "fields", entry.getValue().fields()
            ));
        }
        result.put("sections", sections);
        return result;
    }

    Map<String, Object> sceneToMap(SectionScene scene) {
        List<Map<String, Object>> sections = new ArrayList<>();
        for (SectionEntry entry : scene.sections()) {
            sections.add(new LinkedHashMap<>(Map.of(
                    "sectionId", entry.sectionId(),
                    "sectionType", entry.type(),
                    "fields", sectionDataCodec.toFieldMap(entry.data())
            )));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageId", scene.pageId());
        result.put("layout", scene.layout().wireName());
        result.put("autoScroll", scene.autoScroll());
        result.put("autoScrollMs", scene.autoScrollMs());
        result.put("sections", sections);
        return result;
    }

    static Map<String, Object> map(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    static List<?> list(Object raw) {
        return raw instanceof List<?> list ? list : List.of();
    }

    static List<Map<String, Object>> listOfMaps(Object raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add(map(map));
                }
            }
        }
        return result;
    }

    static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    // ── Nested field path support ──

    /**
     * Validate a variable field path against the section type catalog.
     * Supports:
     * <ul>
     *   <li>{@code title} — top-level scalar field</li>
     *   <li>{@code timer.elapsedMs} — object field with child access</li>
     *   <li>{@code actions.primary.label} — array field with id-based element lookup + child access</li>
     * </ul>
     */
    private void validateFieldPath(String sectionType, String fieldPath, String variableKey) {
        String[] segments = fieldPath.split("\\.");
        SectionTypeCatalog.SectionTypeDef typeDef = sectionTypeCatalog.getOrThrow(sectionType);
        SectionTypeCatalog.SectionFieldDef rootField = findField(typeDef.displayFields(), segments[0]);

        if (rootField == null) {
            throw new IllegalArgumentException(
                    "field '" + segments[0] + "' not found in section type '" + sectionType + "': " + variableKey);
        }

        // Single-segment — must be marked parameterizable
        if (segments.length == 1) {
            if (!rootField.parameterizable()) {
                throw new IllegalArgumentException(
                        "field '" + segments[0] + "' in section type '" + sectionType + "' is not parameterizable: " + variableKey);
            }
            return;
        }

        // Multi-segment — root just needs valid structure; only the leaf must be parameterizable
        if (segments.length == 2) {
            if (!"object".equals(rootField.type())) {
                throw new IllegalArgumentException(
                        "2-segment path only supported for object fields, got '" + rootField.type() + "': " + variableKey);
            }
            validateLeafChild(rootField, segments[1], variableKey);
            return;
        }

        if (segments.length == 3) {
            if (!"array".equals(rootField.type())) {
                throw new IllegalArgumentException(
                        "3-segment path only supported for array fields, got '" + rootField.type() + "': " + variableKey);
            }
            if (rootField.children() == null || rootField.children().stream().noneMatch(f -> "id".equals(f.name()))) {
                throw new IllegalArgumentException(
                        "array field '" + rootField.name() + "' has no 'id' child — id-based lookup not supported: " + variableKey);
            }
            validateLeafChild(rootField, segments[2], variableKey);
            return;
        }

        throw new IllegalArgumentException("unsupported field path depth (max 3 segments): " + variableKey);
    }

    private void validateLeafChild(SectionTypeCatalog.SectionFieldDef parent, String childName, String variableKey) {
        if (parent.children() == null || parent.children().isEmpty()) {
            throw new IllegalArgumentException(
                    "field '" + parent.name() + "' has no children, cannot access '" + childName + "': " + variableKey);
        }
        SectionTypeCatalog.SectionFieldDef child = findField(parent.children(), childName);
        if (child == null) {
            throw new IllegalArgumentException(
                    "child field '" + childName + "' not found in '" + parent.name() + "': " + variableKey);
        }
        if (!child.parameterizable()) {
            throw new IllegalArgumentException(
                    "child field '" + childName + "' is not parameterizable: " + variableKey);
        }
    }

    private static SectionTypeCatalog.SectionFieldDef findField(
            List<SectionTypeCatalog.SectionFieldDef> fields, String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }

    /**
     * Set a value at a nested path within a fields map. Mutates the map in-place.
     * <p>
     * Path format:
     * <ul>
     *   <li>{@code "title"} — {@code fields.put("title", value)}</li>
     *   <li>{@code "timer.elapsedMs"} — navigate into nested map by key</li>
     *   <li>{@code "actions.primary.label"} — find array element by id, then set field</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    static void deepSet(Map<String, Object> fields, String path, Object value) {
        String[] segments = path.split("\\.");
        if (segments.length == 1) {
            fields.put(segments[0], value);
            return;
        }
        Object current = fields.get(segments[0]);
        if (current == null) {
            throw new IllegalArgumentException("field '" + segments[0] + "' is null, cannot navigate into: " + path);
        }
        deepSetInternal(current, segments, 1, value, path);
    }

    @SuppressWarnings("unchecked")
    private static void deepSetInternal(Object current, String[] segments, int index, Object value, String fullPath) {
        if (index >= segments.length) return;

        if (current instanceof List<?> list) {
            // Array — match element by id (the id is at segments[index])
            String targetId = segments[index];
            for (Object item : list) {
                if (item instanceof Map<?, ?> m && targetId.equals(String.valueOf(m.get("id")))) {
                    Map<String, Object> map = (Map<String, Object>) m;
                    int nextIdx = index + 1; // skip the id-lookup segment to reach the target field
                    if (nextIdx >= segments.length) return;
                    if (nextIdx == segments.length - 1) {
                        map.put(segments[nextIdx], value);
                    } else {
                        deepSetInternal(map.get(segments[nextIdx]), segments, nextIdx + 1, value, fullPath);
                    }
                    return;
                }
            }
            throw new IllegalArgumentException("array element not found by id '" + targetId + "': " + fullPath);
        }

        if (current instanceof Map<?, ?> map) {
            // Object — navigate by key
            Map<String, Object> m = (Map<String, Object>) map;
            if (index == segments.length - 1) {
                m.put(segments[index], value);
            } else {
                deepSetInternal(m.get(segments[index]), segments, index + 1, value, fullPath);
            }
            return;
        }

        throw new IllegalArgumentException("cannot navigate into non-container field at '" + segments[index - 1] + "': " + fullPath);
    }
}
