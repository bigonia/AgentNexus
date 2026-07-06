package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.event.EventCatalogProperties;
import com.zwbd.agentnexus.sdui.event.EventRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Platform-defined catalog of all Section template types.
 *
 * Each Section type defines:
 * - display fields: what data the platform can send
 * - interaction events: what events the device can emit when user interacts with this section
 * - default constraints: rendering limits
 *
 * This catalog is loaded from {@code sdui-event-catalog.yml} at startup — the YAML file
 * is the single source of truth for all section type definitions, field labels, defaults,
 * and compact-mode filtering rules.
 *
 * The key addition over the existing SectionData sealed interface is {@code interactionEvents}:
 * they establish the bridge between Section UI interactions and workflow event triggers.
 */
@Slf4j
@Component
public class SectionTypeCatalog {

    private final EventRegistry eventRegistry;

    private final Map<String, SectionTypeDef> catalog = new LinkedHashMap<>();

    public SectionTypeCatalog(@Lazy EventRegistry eventRegistry) {
        this.eventRegistry = eventRegistry;
    }

    @PostConstruct
    void init() {
        for (EventCatalogProperties.SectionTypeEntry entry : eventRegistry.getSectionTypes()) {
            String type = entry.getType();
            boolean interactive = entry.getEvents() != null && !entry.getEvents().isEmpty();
            List<SectionFieldDef> displayFields = convertFields(entry.getFields());
            List<InteractionEvent> interactionEvents = convertInteractionEvents(entry.getEvents());
            Map<String, Integer> defaultConstraints = new LinkedHashMap<>();
            if (entry.getConstraints() != null) {
                entry.getConstraints().forEach((k, v) -> {
                    if (v instanceof Number n) defaultConstraints.put(k, n.intValue());
                });
            }
            Set<String> compactHiddenFields = entry.getCompactHiddenFields() != null
                    ? new LinkedHashSet<>(entry.getCompactHiddenFields()) : Set.of();

            catalog.put(type, new SectionTypeDef(
                    type, entry.getDisplayName(), interactive,
                    displayFields, interactionEvents, defaultConstraints, compactHiddenFields));
        }
        log.info("SectionTypeCatalog initialized from YAML: {} section types", catalog.size());
    }

    // ── Record types (public API, unchanged) ──

    public record SectionTypeDef(
            String type,                      // e.g. "hero_section"
            String displayName,               // e.g. "Hero 数据"
            boolean interactive,              // does this section produce events?
            List<SectionFieldDef> displayFields,  // fields with type metadata for dynamic form rendering
            List<InteractionEvent> interactionEvents,  // events this section can emit
            Map<String, Integer> defaultConstraints,   // e.g. {max_actions: 4}
            Set<String> compactHiddenFields   // JSON wire field names omitted in COMPACT mode
    ) {
        /** Convenience constructor with no compact-hidden fields. */
        public SectionTypeDef(String type, String displayName, boolean interactive,
                              List<SectionFieldDef> displayFields,
                              List<InteractionEvent> interactionEvents,
                              Map<String, Integer> defaultConstraints) {
            this(type, displayName, interactive, displayFields, interactionEvents,
                    defaultConstraints, Set.of());
        }

        /**
         * @return true if the given JSON wire field should be rendered in the specified mode.
         */
        public boolean isFieldVisible(String jsonFieldName, SectionRenderMode mode) {
            if (mode == SectionRenderMode.RICH) return true;
            return !compactHiddenFields.contains(jsonFieldName);
        }

        public Set<String> compactHiddenFields() {
            return compactHiddenFields;
        }
    }

    public record SectionFieldDef(
            String name,
            String type,        // "string" | "int" | "boolean" | "enum" | "color" | "array" | "object"
            String label,
            Object defaultValue,
            Integer min,
            Integer max,
            List<String> options,       // for enum type
            String description,         // tooltip / help text
            boolean required,           // whether the field is mandatory in the editor
            boolean parameterizable,    // whether this field can be bound as a template variable
            List<SectionFieldDef> children  // for array/object types
    ) {
        // Simple field (string, boolean) with default value
        public SectionFieldDef(String name, String type, String label, Object defaultValue) {
            this(name, type, label, defaultValue, null, null, null, null, false, false, null);
        }
        // Int field with min/max
        public SectionFieldDef(String name, String type, String label, Object defaultValue,
                               Integer min, Integer max) {
            this(name, type, label, defaultValue, min, max, null, null, false, false, null);
        }
        // Enum field
        public static SectionFieldDef enumField(String name, String label, String defaultValue,
                                                 List<String> options) {
            return new SectionFieldDef(name, "enum", label, defaultValue, null, null, options, null, false, false, null);
        }
        // Array field
        public static SectionFieldDef arrayField(String name, String label,
                                                  List<SectionFieldDef> children) {
            return new SectionFieldDef(name, "array", label, null, null, null, null, null, false, false, children);
        }
        // Object field
        public static SectionFieldDef objectField(String name, String label,
                                                   List<SectionFieldDef> children) {
            return new SectionFieldDef(name, "object", label, null, null, null, null, null, false, false, children);
        }
    }

    public record InteractionEvent(
            String eventId,
            String description,
            List<ParamDef> params
    ) {}

    public record ParamDef(
            String name,
            String type,
            String description
    ) {}

    // ── Instance query methods (formerly static) ──

    public Optional<SectionTypeDef> get(String type) {
        return Optional.ofNullable(catalog.get(type));
    }

    public SectionTypeDef getOrThrow(String type) {
        SectionTypeDef def = catalog.get(type);
        if (def == null) throw new IllegalArgumentException("Unknown section type: " + type);
        return def;
    }

    public Map<String, SectionTypeDef> all() {
        return Collections.unmodifiableMap(catalog);
    }

    public Set<String> allTypes() {
        return Collections.unmodifiableSet(catalog.keySet());
    }

    /** Serialize a list of SectionFieldDef to frontend-friendly maps (all fields, no filtering). */
    public List<Map<String, Object>> fieldsToMaps(List<SectionFieldDef> fields) {
        return fieldsToMaps(fields, SectionRenderMode.RICH, Set.of());
    }

    public Map<String, Object> defaultFieldValues(List<SectionFieldDef> fields) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (SectionFieldDef field : fields) {
            defaults.put(field.name(), defaultValueFor(field));
        }
        return defaults;
    }

    public Map<String, Object> defaultFieldValues(String sectionType) {
        SectionTypeDef def = getOrThrow(sectionType);
        return defaultFieldValues(def.displayFields());
    }

    public Map<String, Object> sampleFieldValues(List<SectionFieldDef> fields) {
        Map<String, Object> sample = new LinkedHashMap<>();
        for (SectionFieldDef field : fields) {
            sample.put(field.name(), sampleValueFor(field));
        }
        return sample;
    }

    /**
     * Serialize fields to frontend-friendly maps, filtering out compact-hidden fields
     * when the render mode is COMPACT.
     */
    public List<Map<String, Object>> fieldsToMaps(List<SectionFieldDef> fields,
                                                   SectionRenderMode mode,
                                                   Set<String> compactHiddenFields) {
        if (mode == SectionRenderMode.RICH || compactHiddenFields.isEmpty()) {
            return fieldsToMapsUnfiltered(fields);
        }
        return fieldsToMapsFiltered(fields, compactHiddenFields, "");
    }

    /** All interaction event IDs across all section types. */
    public Set<String> allInteractionEventIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (SectionTypeDef def : catalog.values()) {
            for (InteractionEvent evt : def.interactionEvents()) {
                ids.add(evt.eventId());
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    /** Interaction events emitted by a specific section type. */
    public List<InteractionEvent> getInteractionEvents(String sectionType) {
        SectionTypeDef def = catalog.get(sectionType);
        return def != null ? def.interactionEvents() : List.of();
    }

    /** Which section types emit a given interaction event. */
    public List<String> getSectionTypesForEvent(String eventId) {
        List<String> types = new ArrayList<>();
        for (SectionTypeDef def : catalog.values()) {
            for (InteractionEvent evt : def.interactionEvents()) {
                if (evt.eventId().equals(eventId)) {
                    types.add(def.type());
                    break;
                }
            }
        }
        return types;
    }

    /** All interactive section types (ones that produce events). */
    public List<SectionTypeDef> interactiveTypes() {
        return catalog.values().stream()
                .filter(SectionTypeDef::interactive)
                .toList();
    }

    /** Display-only section types (no interaction events). */
    public List<SectionTypeDef> displayOnlyTypes() {
        return catalog.values().stream()
                .filter(d -> !d.interactive())
                .toList();
    }

    public String toWireName(String type) {
        SectionTypeDef def = catalog.get(type);
        return def != null ? def.type() : type;
    }

    /** Returns true if the given wire name (e.g. "hero_section") is a registered section type. */
    public boolean isValidType(String type) {
        return type != null && catalog.containsKey(type);
    }

    // ── Private helpers ──

    private List<SectionFieldDef> convertFields(List<EventCatalogProperties.FieldEntry> entries) {
        if (entries == null || entries.isEmpty()) return List.of();
        List<SectionFieldDef> result = new ArrayList<>();
        for (EventCatalogProperties.FieldEntry e : entries) {
            result.add(convertField(e));
        }
        return result;
    }

    private SectionFieldDef convertField(EventCatalogProperties.FieldEntry e) {
        String type = e.getType() != null ? e.getType() : "string";
        String label = e.getLabel() != null ? e.getLabel() : e.getName();
        Object defaultValue = e.getDefaultValue() != null ? e.getDefaultValue() : javaDefaultFor(type, e.getValues());
        Integer min = toInt(e.getMin());
        Integer max = toInt(e.getMax());
        List<String> options = "enum".equals(type) ? e.getValues() : null;
        String description = e.getDescription();
        boolean required = e.isRequired();
        boolean parameterizable = e.isParameterizable();
        List<SectionFieldDef> children = convertFields(e.getChildren());
        return new SectionFieldDef(e.getName(), type, label, defaultValue, min, max, options, description, required, parameterizable, children);
    }

    private List<InteractionEvent> convertInteractionEvents(List<EventCatalogProperties.EventEntry> entries) {
        if (entries == null || entries.isEmpty()) return List.of();
        List<InteractionEvent> result = new ArrayList<>();
        for (EventCatalogProperties.EventEntry e : entries) {
            String eventId = e.getId();
            // Strip namespace prefix for catalog events (e.g. "ui:overlay.confirm" → "overlay.confirm")
            String shortId = eventId != null && eventId.contains(":") ? eventId.substring(eventId.indexOf(':') + 1) : eventId;
            String description = e.getDescription() != null ? e.getDescription() : e.getDisplayName();
            List<ParamDef> params = convertPayloadSchema(e.getPayloadSchema());
            result.add(new InteractionEvent(shortId, description, params));
        }
        return result;
    }

    private List<ParamDef> convertPayloadSchema(List<EventCatalogProperties.FieldEntry> entries) {
        if (entries == null || entries.isEmpty()) return List.of();
        List<ParamDef> result = new ArrayList<>();
        for (EventCatalogProperties.FieldEntry e : entries) {
            result.add(new ParamDef(e.getName(), e.getType() != null ? e.getType() : "string",
                    e.getDescription() != null ? e.getDescription() : ""));
        }
        return result;
    }

    private static Object javaDefaultFor(String type, List<String> enumValues) {
        return switch (type) {
            case "string", "color" -> "";
            case "int" -> 0;
            case "boolean" -> false;
            case "enum" -> enumValues != null && !enumValues.isEmpty() ? enumValues.get(0) : "";
            case "array" -> List.of();
            case "object" -> Map.of();
            default -> null;
        };
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static Object defaultValueFor(SectionFieldDef field) {
        if ("array".equals(field.type())) {
            return List.of();
        }
        if ("object".equals(field.type())) {
            return field.children() != null ? defaultFieldValuesStatic(field.children()) : Map.of();
        }
        if (field.defaultValue() != null) {
            return field.defaultValue();
        }
        return switch (field.type()) {
            case "string", "color", "enum" -> "";
            case "int" -> 0;
            case "boolean" -> false;
            default -> null;
        };
    }

    // Static overload for use inside instance methods during field-default construction
    private static Map<String, Object> defaultFieldValuesStatic(List<SectionFieldDef> fields) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (SectionFieldDef field : fields) {
            defaults.put(field.name(), defaultValueForStatic(field));
        }
        return defaults;
    }

    private static Object defaultValueForStatic(SectionFieldDef field) {
        if ("array".equals(field.type())) return List.of();
        if ("object".equals(field.type())) return field.children() != null ? defaultFieldValuesStatic(field.children()) : Map.of();
        if (field.defaultValue() != null) return field.defaultValue();
        return switch (field.type()) {
            case "string", "color", "enum" -> "";
            case "int" -> 0;
            case "boolean" -> false;
            default -> null;
        };
    }

    private static Object sampleValueFor(SectionFieldDef field) {
        if ("array".equals(field.type())) {
            if (field.children() == null || field.children().isEmpty()) {
                return List.of();
            }
            Map<String, Object> sample = new LinkedHashMap<>();
            for (SectionFieldDef child : field.children()) {
                sample.put(child.name(), sampleValueFor(child));
            }
            return List.of(sample);
        }
        if ("object".equals(field.type())) {
            return field.children() != null ? defaultFieldValuesStatic(field.children()) : Map.of();
        }
        if ("enum".equals(field.type()) && field.options() != null && !field.options.isEmpty()) {
            return field.options().get(0);
        }
        if ("string".equals(field.type()) || "color".equals(field.type())) {
            return field.label() + "示例";
        }
        if ("int".equals(field.type())) {
            if (field.defaultValue() instanceof Number n) {
                return n.intValue();
            }
            return field.min() != null ? field.min() : 1;
        }
        if ("boolean".equals(field.type())) {
            return !Boolean.FALSE.equals(field.defaultValue());
        }
        return defaultValueForStatic(field);
    }

    // ── fieldsToMaps helpers ──

    private static List<Map<String, Object>> fieldsToMapsUnfiltered(List<SectionFieldDef> fields) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SectionFieldDef f : fields) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("type", f.type());
            m.put("label", f.label());
            m.put("required", f.required());
            m.put("parameterizable", f.parameterizable());
            if (f.defaultValue() != null) m.put("default", f.defaultValue());
            if (f.min() != null) m.put("min", f.min());
            if (f.max() != null) m.put("max", f.max());
            if (f.options() != null) m.put("options", f.options());
            if (f.description() != null) m.put("description", f.description());
            if (f.children() != null) m.put("children", fieldsToMapsUnfiltered(f.children()));
            result.add(m);
        }
        return result;
    }

    private static List<Map<String, Object>> fieldsToMapsFiltered(List<SectionFieldDef> fields,
                                                                    Set<String> compactHiddenFields,
                                                                    String parentPath) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SectionFieldDef f : fields) {
            if (!parentPath.isEmpty()) {
                String nestedPath = parentPath + "[]. " + f.name();
                if (compactHiddenFields.contains(nestedPath)) continue;
            } else {
                if (compactHiddenFields.contains(f.name())) continue;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("type", f.type());
            m.put("label", f.label());
            m.put("required", f.required());
            m.put("parameterizable", f.parameterizable());
            if (f.defaultValue() != null) m.put("default", f.defaultValue());
            if (f.min() != null) m.put("min", f.min());
            if (f.max() != null) m.put("max", f.max());
            if (f.options() != null) m.put("options", f.options());
            if (f.description() != null) m.put("description", f.description());
            if (f.children() != null) {
                m.put("children", fieldsToMapsFiltered(f.children(), compactHiddenFields, f.name()));
            }
            result.add(m);
        }
        return result;
    }
}
