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

    public SduiUiTemplateService(SduiUiTemplateRepository repository,
                                 SectionDataCodec sectionDataCodec,
                                 SectionOrchestrationService sectionOrchestrationService,
                                 DeviceCapabilityProjection capabilityProjection,
                                 SectionTypeCatalog sectionTypeCatalog) {
        this.repository = repository;
        this.sectionDataCodec = sectionDataCodec;
        this.sectionOrchestrationService = sectionOrchestrationService;
        this.capabilityProjection = capabilityProjection;
        this.sectionTypeCatalog = sectionTypeCatalog;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        Map<String, Object> definition = normalizeDefinition(body);
        String templateKey = string(definition.get("templateKey"));
        repository.findByTemplateKey(templateKey).ifPresent(existing -> {
            throw new IllegalArgumentException("templateKey already exists: " + templateKey);
        });
        SduiUiTemplateEntity entity = new SduiUiTemplateEntity();
        entity.setTemplateKey(templateKey);
        entity.setName(string(definition.getOrDefault("name", templateKey)));
        entity.setDefinition(definition);
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
        Map<String, Object> definition = normalizeDefinition(body);
        String templateKey = string(definition.get("templateKey"));
        repository.findByTemplateKey(templateKey)
                .filter(existing -> !existing.getId().equals(entity.getId()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("templateKey already exists: " + templateKey);
                });
        entity.setTemplateKey(templateKey);
        entity.setName(string(definition.getOrDefault("name", templateKey)));
        entity.setDefinition(definition);
        return toMap(repository.save(entity), true);
    }

    @Transactional
    public Map<String, Object> delete(String templateId) {
        SduiUiTemplateEntity entity = require(templateId);
        repository.delete(entity);
        return Map.of("deleted", true, "templateId", templateId, "templateKey", entity.getTemplateKey());
    }

    public Map<String, Object> preview(String templateId, Map<String, Object> body) {
        SduiUiTemplateEntity entity = require(templateId);
        Map<String, Object> variables = map(body.getOrDefault("variables", entity.getDefinition().get("mockData")));
        SectionScene scene = toScene(entity.getDefinition(), variables);
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
        Map<String, Object> source = map(body.getOrDefault("definition", body));
        String templateKey = string(source.getOrDefault("templateKey", body.get("templateKey")));
        String name = string(source.getOrDefault("name", body.getOrDefault("name", "")));
        // 未提供 templateKey 时自动生成：名称 slug + 短 UUID
        if (templateKey.isBlank()) {
            String base = name.isBlank() ? "template" : name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
            templateKey = base + "_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }
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
            if (field.isBlank() || field.contains(".")) {
                throw new IllegalArgumentException("variable field must be a top-level section field: " + variableKey);
            }
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
     * Convert a stored template definition (with variables resolved) into a
     * {@link SectionPageDefinition} for validation, scene construction, or
     * event catalog queries.
     */
    public SectionPageDefinition toPageDefinition(Map<String, Object> definition, Map<String, Object> variables) {
        Map<String, Object> values = variableValues(definition, variables);
        LinkedHashMap<String, SectionPageDefinition.SectionDef> sectionDefs = new LinkedHashMap<>();

        for (Map<String, Object> section : listOfMaps(definition.get("sections"))) {
            String sectionId = string(section.get("sectionId"));
            String sectionType = string(section.get("sectionType"));
            Map<String, Object> fields = new LinkedHashMap<>(map(section.get("fields")));
            for (Map<String, Object> variable : listOfMaps(definition.get("variables"))) {
                if (sectionId.equals(string(variable.get("sectionId")))) {
                    String field = string(variable.get("field"));
                    String variableKey = string(variable.get("variableKey"));
                    if (values.containsKey(variableKey)) {
                        fields.put(field, values.get(variableKey));
                    }
                }
            }
            sectionDefs.put(sectionId, new SectionPageDefinition.SectionDef(sectionId, sectionType, fields));
        }

        String pageId = string(definition.getOrDefault("pageId", DEFAULT_PAGE_ID));
        SectionLayout layout = SectionLayout.fromWireName(string(definition.getOrDefault("layout", DEFAULT_LAYOUT)));
        return new SectionPageDefinition(pageId, layout != null ? layout : SectionLayout.VERTICAL_SCROLL, false, 0, sectionDefs);
    }

    SectionScene toScene(Map<String, Object> definition, Map<String, Object> variables) {
        SectionPageDefinition page = toPageDefinition(definition, variables);
        return page.toScene(sectionDataCodec);
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
        data.put("status", entity.getStatus());
        if (includeDefinition) data.put("definition", entity.getDefinition());
        data.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        data.put("updatedAt", entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        return data;
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
}
