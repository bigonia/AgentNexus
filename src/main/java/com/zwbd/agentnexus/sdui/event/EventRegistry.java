package com.zwbd.agentnexus.sdui.event;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class EventRegistry {

    private final EventCatalogLoader loader;
    private final Map<String, EventDefinition> commandEvents = new LinkedHashMap<>();
    private final Map<String, EventDefinition> sectionEvents = new LinkedHashMap<>();
    private Map<String, EventCatalogProperties.SectionTypeEntry> sectionTypes = Map.of();
    private final Map<String, String> rawEventAliases = new LinkedHashMap<>();

    public EventRegistry(EventCatalogLoader loader) {
        this.loader = loader;
    }

    @PostConstruct
    public void init() {
        for (EventDefinition def : loader.commandEvents()) {
            commandEvents.put(def.eventId(), def);
            registerAlias(def);
        }
        for (EventDefinition def : loader.sectionEvents()) {
            sectionEvents.put(def.eventId(), def);
            registerAlias(def);
        }
        sectionTypes = loader.sectionTypes();
        log.info("EventRegistry initialized from config: {} command events, {} section events",
                commandEvents.size(), sectionEvents.size());
    }

    public Optional<EventDefinition> getCommandEvent(String id) {
        return Optional.ofNullable(commandEvents.get(resolveEventId(id)));
    }

    public Optional<EventDefinition> getSectionEvent(String id) {
        return Optional.ofNullable(sectionEvents.get(resolveEventId(id)));
    }

    public Optional<EventDefinition> getInboundEvent(String eventId) {
        String resolved = resolveEventId(eventId);
        EventDefinition section = sectionEvents.get(resolved);
        if (section != null && section.direction() == EventDefinition.Direction.INBOUND) {
            return Optional.of(section);
        }
        EventDefinition command = commandEvents.get(resolved);
        if (command != null && command.direction() == EventDefinition.Direction.INBOUND) {
            return Optional.of(command);
        }
        return Optional.empty();
    }

    public Optional<EventDefinition> getOutboundEvent(String commandId) {
        return getCommandEvent(commandId)
                .filter(def -> def.direction() == EventDefinition.Direction.OUTBOUND);
    }

    public Collection<EventDefinition> getAllInboundEvents() {
        List<EventDefinition> result = new ArrayList<>();
        sectionEvents.values().stream()
                .filter(def -> def.direction() == EventDefinition.Direction.INBOUND)
                .forEach(result::add);
        commandEvents.values().stream()
                .filter(def -> def.direction() == EventDefinition.Direction.INBOUND)
                .forEach(result::add);
        return List.copyOf(result);
    }

    public Collection<EventDefinition> getAllOutboundEvents() {
        return commandEvents.values().stream()
                .filter(def -> def.direction() == EventDefinition.Direction.OUTBOUND)
                .toList();
    }

    public Collection<EventDefinition> getAllCommandEvents() {
        return List.copyOf(commandEvents.values());
    }

    public Collection<EventDefinition> getAllSectionEvents() {
        return List.copyOf(sectionEvents.values());
    }

    public boolean isKnownEvent(String eventId) {
        String resolved = resolveEventId(eventId);
        return commandEvents.containsKey(resolved) || sectionEvents.containsKey(resolved);
    }

    public Set<String> getKnownEventIds() {
        Set<String> result = new LinkedHashSet<>();
        result.addAll(sectionEvents.keySet());
        result.addAll(commandEvents.keySet());
        result.addAll(rawEventAliases.keySet());
        return result;
    }

    public Set<String> getKnownCommandIds() {
        return Set.copyOf(commandEvents.keySet());
    }

    public String resolveEventId(String rawEventId) {
        if (rawEventId == null || rawEventId.isBlank()) {
            return null;
        }
        if (commandEvents.containsKey(rawEventId) || sectionEvents.containsKey(rawEventId)) {
            return rawEventId;
        }
        return rawEventAliases.getOrDefault(rawEventId, rawEventId);
    }

    public EventPayload normalizePayload(EventPayload payload) {
        if (payload == null) {
            return null;
        }
        String resolved = resolvePayloadEventId(payload);
        if (resolved == null || resolved.equals(payload.eventId())) {
            return payload;
        }
        return payload.withEventId(resolved);
    }

    public ValidationResult validatePayload(String eventId, Map<String, Object> payload) {
        String resolved = resolveEventId(eventId);
        EventDefinition def = sectionEvents.get(resolved);
        if (def == null) {
            def = commandEvents.get(resolved);
        }
        if (def == null) {
            return ValidationResult.invalid(List.of(
                    new ValidationError("event." + eventId, "UNKNOWN_EVENT", "unknown event: " + eventId)));
        }
        return validateFields(def.payloadSchema(), payload != null ? payload : Map.of(), "event." + resolved);
    }

    public ValidationResult validateCommand(String commandId, Map<String, Object> params) {
        EventDefinition def = commandEvents.get(resolveEventId(commandId));
        if (def == null || def.direction() != EventDefinition.Direction.OUTBOUND) {
            return ValidationResult.invalid(List.of(
                    new ValidationError("command." + commandId, "UNKNOWN_COMMAND", "unknown command event: " + commandId)));
        }
        return validateFields(def.payloadSchema(), params != null ? params : Map.of(), "command." + def.eventId());
    }

    public ValidationResult validateSectionFields(String sectionType, Map<String, Object> fields) {
        EventCatalogProperties.SectionTypeEntry section = sectionTypes.get(sectionType);
        if (section == null) {
            return ValidationResult.invalid(List.of(
                    new ValidationError("section." + sectionType, "UNKNOWN_SECTION_TYPE", "unknown section type: " + sectionType)));
        }
        List<EventDefinition.ParamDef> schema = section.getFields().stream()
                .map(field -> new EventDefinition.ParamDef(
                        field.getName(), normalizeType(field.getType()), field.isRequired(),
                        field.getMin(), field.getMax(), field.getValues(), field.getDescription()))
                .toList();
        return validateFields(schema, fields != null ? fields : Map.of(), "section." + sectionType);
    }

    public boolean isKnownSectionType(String sectionType) {
        return sectionTypes.containsKey(sectionType);
    }

    /** All section type entries loaded from YAML — consumed by SectionTypeCatalog. */
    public Collection<EventCatalogProperties.SectionTypeEntry> getSectionTypes() {
        return List.copyOf(sectionTypes.values());
    }

    public Collection<EventDefinition> getSectionEvents() {
        return List.copyOf(sectionEvents.values());
    }

    public Map<String, Object> catalogForEditor() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commands", commandCatalog());
        result.put("sections", sectionCatalog());
        return result;
    }

    /** Full inbound event tree — all categories (for internal/debug use). */
    public List<Map<String, Object>> getInboundEventTree() {
        List<Map<String, Object>> tree = new ArrayList<>();
        // Section interaction events → USER_INTERACTION category
        List<Map<String, Object>> sectionGroups = groupedPublicSectionEvents();
        if (!sectionGroups.isEmpty()) {
            tree.add(Map.of("category", "USER_INTERACTION", "label", "用户交互",
                    "capabilities", sectionGroups));
        }
        // System events → SYSTEM_EVENT category
        List<Map<String, Object>> systemEvents = groupedSystemEvents();
        if (!systemEvents.isEmpty()) {
            tree.add(Map.of("category", "SYSTEM_EVENT", "label", "系统事件",
                    "capabilities", systemEvents));
        }
        // Internal lifecycle events — excluded from public tree
        return tree;
    }

    /**
     * Public inbound event tree — only public-trigger categories (USER_INTERACTION + SYSTEM_EVENT).
     * Uses {@link EventDefinition#toPublicMap()} to exclude transport and internal fields.
     */
    public List<Map<String, Object>> getPublicInboundEventTree() {
        return getInboundEventTree(); // already filters to public-only
    }

    public List<Map<String, Object>> getOutboundEventTree() {
        return commandCatalog();
    }

    /**
     * Flat event options for editor dropdowns — public trigger events only
     * ({@code USER_INTERACTION} + {@code SYSTEM_EVENT}).
     * Excludes internal lifecycle events (command ACK, audio streaming, etc.).
     */
    public List<Map<String, String>> getFlatEventOptions() {
        List<Map<String, String>> result = new ArrayList<>();
        for (EventDefinition def : getAllInboundEvents()) {
            if (!def.isPublicTrigger()) {
                continue;
            }
            result.add(Map.of(
                    "value", def.eventId(),
                    "label", def.displayName(),
                    "category", def.category().name(),
                    "source", def.sourceCapability()
            ));
        }
        return result;
    }

    /** Get all public trigger events (for state-machine and board-type APIs). */
    public List<EventDefinition> getPublicTriggerEvents() {
        List<EventDefinition> result = new ArrayList<>();
        for (EventDefinition def : getAllInboundEvents()) {
            if (def.isPublicTrigger()) {
                result.add(def);
            }
        }
        return result;
    }

    public List<EventDefinition> getEventsByCategory(EventDefinition.EventCategory category) {
        List<EventDefinition> result = new ArrayList<>();
        commandEvents.values().stream().filter(def -> def.category() == category).forEach(result::add);
        sectionEvents.values().stream().filter(def -> def.category() == category).forEach(result::add);
        return result;
    }

    private String resolvePayloadEventId(EventPayload payload) {
        if (payload.eventId() != null && isKnownEvent(payload.eventId())) {
            return resolveEventId(payload.eventId());
        }
        String rawName = string(payload.rawFields().get("eventName"));
        for (EventDefinition def : sectionEvents.values()) {
            EventDefinition.TransportInfo transport = def.transport();
            if (transport == null) {
                continue;
            }
            boolean nameMatches = rawName.equals(transport.eventName()) || payload.eventId() != null && payload.eventId().equals(transport.eventName());
            boolean kindMatches = transport.eventKind() == null || transport.eventKind() == payload.kind();
            if (nameMatches && kindMatches) {
                return def.eventId();
            }
        }
        return resolveEventId(payload.eventId());
    }

    private ValidationResult validateFields(List<EventDefinition.ParamDef> schema,
                                            Map<String, Object> payload,
                                            String errorPath) {
        List<ValidationError> errors = new ArrayList<>();
        for (EventDefinition.ParamDef field : schema) {
            String fieldPath = errorPath + ".params." + field.name();
            Object value = payload.get(field.name());
            if (value == null && field.required()) {
                errors.add(new ValidationError(fieldPath, "MISSING_REQUIRED_FIELD",
                        errorPath + " missing required field: " + field.name()));
                continue;
            }
            if (value == null) {
                continue;
            }
            validateType(field, value, fieldPath, errors);
            validateRange(field, value, fieldPath, errors);
            if (field.values() != null && !field.values().isEmpty() && !field.values().contains(String.valueOf(value))) {
                errors.add(new ValidationError(fieldPath, "INVALID_ENUM_VALUE",
                        errorPath + " field " + field.name() + " must be one of " + field.values()));
            }
        }
        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.invalid(errors);
    }

    private void validateType(EventDefinition.ParamDef field, Object value, String fieldPath, List<ValidationError> errors) {
        String type = normalizeType(field.type());
        boolean ok = switch (type) {
            case "string", "color", "enum" -> value instanceof String;
            case "int" -> value instanceof Number || string(value).matches("-?\\d+");
            case "float" -> value instanceof Number || string(value).matches("-?\\d+(\\.\\d+)?");
            case "boolean" -> value instanceof Boolean || "true".equalsIgnoreCase(string(value)) || "false".equalsIgnoreCase(string(value));
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true;
        };
        if (!ok) {
            errors.add(new ValidationError(fieldPath, "TYPE_MISMATCH",
                    fieldPath + " must be " + type));
        }
    }

    private void validateRange(EventDefinition.ParamDef field, Object value, String fieldPath, List<ValidationError> errors) {
        if (field.min() == null && field.max() == null) {
            return;
        }
        Double number = toDouble(value);
        if (number == null) {
            return;
        }
        Double min = toDouble(field.min());
        Double max = toDouble(field.max());
        if (min != null && number < min) {
            errors.add(new ValidationError(fieldPath, "VALUE_OUT_OF_RANGE",
                    fieldPath + " must be >= " + field.min()));
        }
        if (max != null && number > max) {
            errors.add(new ValidationError(fieldPath, "VALUE_OUT_OF_RANGE",
                    fieldPath + " must be <= " + field.max()));
        }
    }

    private List<Map<String, Object>> commandCatalog() {
        Map<String, List<EventDefinition>> grouped = new LinkedHashMap<>();
        for (EventDefinition def : commandEvents.values()) {
            grouped.computeIfAbsent(def.sourceCapability(), ignored -> new ArrayList<>()).add(def);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        grouped.forEach((source, events) -> result.add(Map.of(
                "capability", source,
                "label", source,
                "commands", events.stream().map(EventDefinition::toMap).toList()
        )));
        return result;
    }

    private List<Map<String, Object>> sectionCatalog() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (EventCatalogProperties.SectionTypeEntry section : sectionTypes.values()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", section.getType());
            map.put("displayName", section.getDisplayName());
            map.put("operations", section.getOperations());
            map.put("fields", section.getFields().stream().map(this::fieldToMap).toList());
            map.put("constraints", section.getConstraints());
            if (section.getCompactHiddenFields() != null && !section.getCompactHiddenFields().isEmpty()) {
                map.put("compactHiddenFields", section.getCompactHiddenFields());
            }
            map.put("events", sectionEvents.values().stream()
                    .filter(def -> section.getType().equals(def.sourceCapability()))
                    .map(EventDefinition::toMap)
                    .toList());
            result.add(map);
        }
        return result;
    }

    private List<Map<String, Object>> groupedPublicSectionEvents() {
        Map<String, List<EventDefinition>> grouped = new LinkedHashMap<>();
        sectionEvents.values().forEach(def -> grouped.computeIfAbsent(def.sourceCapability(), ignored -> new ArrayList<>()).add(def));
        List<Map<String, Object>> result = new ArrayList<>();
        grouped.forEach((source, events) -> result.add(Map.of(
                "capability", source,
                "label", source,
                "events", events.stream().map(EventDefinition::toPublicMap).toList()
        )));
        return result;
    }

    private List<Map<String, Object>> groupedSystemEvents() {
        List<EventDefinition> systemEvents = commandEvents.values().stream()
                .filter(def -> def.category() == EventDefinition.EventCategory.SYSTEM_EVENT)
                .toList();
        if (systemEvents.isEmpty()) {
            return List.of();
        }
        return List.of(Map.of(
                "capability", "system",
                "label", "系统事件",
                "events", systemEvents.stream().map(EventDefinition::toPublicMap).toList()
        ));
    }

    /** @deprecated Internal lifecycle events are no longer in the public tree. */
    @Deprecated
    private List<Map<String, Object>> groupedCommandLifecycleEvents() {
        List<EventDefinition> lifecycle = commandEvents.values().stream()
                .filter(EventDefinition::isCommandLifecycle)
                .toList();
        if (lifecycle.isEmpty()) {
            return List.of();
        }
        return List.of(Map.of(
                "capability", "command.lifecycle",
                "label", "内部生命周期",
                "events", lifecycle.stream().map(EventDefinition::toMap).toList()
        ));
    }

    private Map<String, Object> fieldToMap(EventCatalogProperties.FieldEntry field) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", field.getName());
        map.put("type", normalizeType(field.getType()));
        map.put("required", field.isRequired());
        if (field.getLabel() != null) map.put("label", field.getLabel());
        if (field.getDefaultValue() != null) map.put("default", field.getDefaultValue());
        if (field.getMin() != null) map.put("min", field.getMin());
        if (field.getMax() != null) map.put("max", field.getMax());
        if (field.getValues() != null && !field.getValues().isEmpty()) map.put("values", field.getValues());
        if (field.getDescription() != null) map.put("description", field.getDescription());
        if (field.getChildren() != null && !field.getChildren().isEmpty()) {
            map.put("children", field.getChildren().stream().map(this::fieldToMap).toList());
        }
        return map;
    }

    private void registerAlias(EventDefinition def) {
        EventDefinition.TransportInfo transport = def.transport();
        if (transport != null && transport.eventName() != null && !transport.eventName().isBlank()) {
            rawEventAliases.putIfAbsent(transport.eventName(), def.eventId());
        }
    }

    private Double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "string";
        }
        return switch (type) {
            case "integer", "number" -> "int";
            case "bool" -> "boolean";
            default -> type;
        };
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record ValidationError(String path, String code, String message) {
        public Map<String, Object> toMap() {
            return Map.of("path", path, "code", code, "message", message);
        }
    }

    public record ValidationResult(boolean valid, List<ValidationError> errors) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult invalid(List<ValidationError> errors) {
            return new ValidationResult(false, List.copyOf(errors));
        }

        public Map<String, Object> toMap() {
            return Map.of(
                    "valid", valid,
                    "errors", errors.stream().map(ValidationError::toMap).toList()
            );
        }

        /** @deprecated for backward compat in runtime callers that still use string errors */
        public List<String> errorMessages() {
            return errors.stream().map(ValidationError::message).toList();
        }
    }
}
