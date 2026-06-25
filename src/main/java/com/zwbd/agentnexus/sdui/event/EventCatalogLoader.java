package com.zwbd.agentnexus.sdui.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class EventCatalogLoader {

    private static final Set<String> FIELD_TYPES = Set.of(
            "string", "int", "float", "boolean", "bool", "enum", "array", "object", "color"
    );

    private EventCatalogProperties properties = new EventCatalogProperties();
    private final Map<String, EventDefinition> commandEvents = new LinkedHashMap<>();
    private final Map<String, EventDefinition> sectionEvents = new LinkedHashMap<>();
    private final Map<String, EventCatalogProperties.SectionTypeEntry> sectionTypes = new LinkedHashMap<>();
    private final List<String> validationErrors = new ArrayList<>();

    @PostConstruct
    public void load() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("sdui-event-catalog.yml")) {
            if (in == null) {
                throw new IllegalStateException("sdui-event-catalog.yml not found on classpath");
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            properties = mapper.readValue(in, EventCatalogProperties.class);
            build();
            if (!validationErrors.isEmpty()) {
                throw new IllegalStateException("Invalid sdui-event-catalog.yml: " + validationErrors);
            }
            log.info("SDUI event catalog loaded: {} command events, {} section events, {} section types",
                    commandEvents.size(), sectionEvents.size(), sectionTypes.size());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load SDUI event catalog", e);
        }
    }

    public Collection<EventDefinition> commandEvents() {
        return commandEvents.values();
    }

    public Collection<EventDefinition> sectionEvents() {
        return sectionEvents.values();
    }

    public Map<String, EventCatalogProperties.SectionTypeEntry> sectionTypes() {
        return Map.copyOf(sectionTypes);
    }

    public List<String> validationErrors() {
        return List.copyOf(validationErrors);
    }

    private void build() {
        commandEvents.clear();
        sectionEvents.clear();
        sectionTypes.clear();
        validationErrors.clear();

        for (EventCatalogProperties.CommandGroup group : properties.getCommands().getGroups()) {
            if (blank(group.getId())) {
                validationErrors.add("command group id is required");
                continue;
            }
            for (EventCatalogProperties.CommandEntry command : group.getCommands()) {
                EventDefinition def = buildCommandEvent(group, command);
                register(commandEvents, def);
            }
        }

        for (EventCatalogProperties.EventEntry lifecycle : properties.getCommands().getLifecycle()) {
            EventDefinition def = buildLifecycleEvent(lifecycle);
            register(commandEvents, def);
        }

        for (EventCatalogProperties.EventEntry sysEvent : properties.getCommands().getSystem()) {
            EventDefinition def = buildSystemEvent(sysEvent);
            register(commandEvents, def);
        }

        for (EventCatalogProperties.SectionTypeEntry section : properties.getSections().getTypes()) {
            validateSectionType(section);
            if (!blank(section.getType())) {
                sectionTypes.put(section.getType(), section);
            }
            for (EventCatalogProperties.EventEntry event : section.getEvents()) {
                EventDefinition def = buildSectionEvent(section, event);
                register(sectionEvents, def);
            }
        }
    }

    private EventDefinition buildCommandEvent(EventCatalogProperties.CommandGroup group,
                                              EventCatalogProperties.CommandEntry command) {
        validateEventEntry(command, "command " + group.getId());
        EventDefinition.EventCategory cat = resolveCategory(command,
                "platform".equals(command.getSubtype())
                        ? EventDefinition.EventCategory.COMMAND_PLATFORM
                        : EventDefinition.EventCategory.COMMAND_DISPATCH);
        return new EventDefinition(
                command.getId(),
                EventDefinition.EventKind.COMMAND,
                direction(command.getDirection(), EventDefinition.Direction.OUTBOUND),
                cat,
                defaultString(command.getSubtype(), "dispatch"),
                defaultString(command.getDisplayName(), command.getId()),
                command.getDescription(),
                defaultString(command.getSource(), group.getId()),
                transport(command.getTransport()),
                fields(command.getPayloadSchema()),
                command.getConstraints(),
                null
        );
    }

    private EventDefinition buildLifecycleEvent(EventCatalogProperties.EventEntry lifecycle) {
        validateEventEntry(lifecycle, "command lifecycle");
        return new EventDefinition(
                lifecycle.getId(),
                EventDefinition.EventKind.COMMAND,
                EventDefinition.Direction.INBOUND,
                resolveCategory(lifecycle, EventDefinition.EventCategory.COMMAND_LIFECYCLE),
                defaultString(lifecycle.getSubtype(), "lifecycle"),
                defaultString(lifecycle.getDisplayName(), lifecycle.getId()),
                lifecycle.getDescription(),
                defaultString(lifecycle.getSource(), "command.lifecycle"),
                transport(lifecycle.getTransport()),
                fields(lifecycle.getPayloadSchema()),
                Map.of(),
                null
        );
    }

    private EventDefinition buildSystemEvent(EventCatalogProperties.EventEntry sysEvent) {
        validateEventEntry(sysEvent, "system event");
        return new EventDefinition(
                sysEvent.getId(),
                EventDefinition.EventKind.COMMAND,
                EventDefinition.Direction.INBOUND,
                resolveCategory(sysEvent, EventDefinition.EventCategory.SYSTEM_EVENT),
                defaultString(sysEvent.getSubtype(), "system"),
                defaultString(sysEvent.getDisplayName(), sysEvent.getId()),
                sysEvent.getDescription(),
                defaultString(sysEvent.getSource(), "system"),
                transport(sysEvent.getTransport()),
                fields(sysEvent.getPayloadSchema()),
                Map.of(),
                null
        );
    }

    /** Resolve category from YAML override or fall back to default. */
    private EventDefinition.EventCategory resolveCategory(EventCatalogProperties.EventEntry entry,
                                                           EventDefinition.EventCategory defaultCategory) {
        String catStr = entry.getCategory();
        if (catStr != null && !catStr.isBlank()) {
            try {
                return EventDefinition.EventCategory.valueOf(catStr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown event category '{}' for event '{}' — falling back to {}",
                        catStr, entry.getId(), defaultCategory);
            }
        }
        return defaultCategory;
    }

    private EventDefinition buildSectionEvent(EventCatalogProperties.SectionTypeEntry section,
                                             EventCatalogProperties.EventEntry event) {
        validateEventEntry(event, "section " + section.getType());
        return new EventDefinition(
                event.getId(),
                EventDefinition.EventKind.SECTION,
                direction(event.getDirection(), EventDefinition.Direction.INBOUND),
                EventDefinition.EventCategory.USER_INTERACTION,
                defaultString(event.getSubtype(), "interaction"),
                defaultString(event.getDisplayName(), event.getId()),
                event.getDescription(),
                section.getType(),
                transport(event.getTransport()),
                fields(event.getPayloadSchema()),
                section.getConstraints(),
                null
        );
    }

    private void register(Map<String, EventDefinition> target, EventDefinition def) {
        if (def == null || blank(def.eventId())) {
            validationErrors.add("event id is required");
            return;
        }
        if (commandEvents.containsKey(def.eventId()) || sectionEvents.containsKey(def.eventId()) || target.containsKey(def.eventId())) {
            validationErrors.add("duplicate event id: " + def.eventId());
            return;
        }
        target.put(def.eventId(), def);
    }

    private void validateSectionType(EventCatalogProperties.SectionTypeEntry section) {
        if (blank(section.getType())) {
            validationErrors.add("section type is required");
            return;
        }
        if (sectionTypes.containsKey(section.getType())) {
            validationErrors.add("duplicate section type: " + section.getType());
        }
        validateFields(section.getFields(), "section " + section.getType());
    }

    private void validateEventEntry(EventCatalogProperties.EventEntry event, String scope) {
        if (blank(event.getId())) {
            validationErrors.add(scope + " event id is required");
        }
        validateFields(event.getPayloadSchema(), scope + " " + event.getId());
    }

    private void validateFields(List<EventCatalogProperties.FieldEntry> fields, String scope) {
        Set<String> names = new LinkedHashSet<>();
        for (EventCatalogProperties.FieldEntry field : fields) {
            if (blank(field.getName())) {
                validationErrors.add(scope + " field name is required");
                continue;
            }
            if (!names.add(field.getName())) {
                validationErrors.add(scope + " duplicate field: " + field.getName());
            }
            String type = normalizeType(field.getType());
            if (!FIELD_TYPES.contains(type)) {
                validationErrors.add(scope + " unsupported field type " + field.getType() + " on " + field.getName());
            }
            if ((field.getMin() != null || field.getMax() != null) && !Set.of("int", "float").contains(type)) {
                validationErrors.add(scope + " min/max only supports numeric field: " + field.getName());
            }
            if ("enum".equals(type) && (field.getValues() == null || field.getValues().isEmpty())) {
                validationErrors.add(scope + " enum values are required on " + field.getName());
            }
            validateFields(field.getChildren(), scope + "." + field.getName());
        }
    }

    private List<EventDefinition.ParamDef> fields(List<EventCatalogProperties.FieldEntry> fields) {
        return fields.stream()
                .map(field -> new EventDefinition.ParamDef(
                        field.getName(),
                        normalizeType(field.getType()),
                        field.isRequired(),
                        field.getMin(),
                        field.getMax(),
                        field.getValues(),
                        field.getDescription()))
                .toList();
    }

    private EventDefinition.TransportInfo transport(EventCatalogProperties.Transport transport) {
        if (transport == null) {
            return new EventDefinition.TransportInfo("internal", null, null, null, null, null, null);
        }
        return new EventDefinition.TransportInfo(
                defaultString(transport.getProtocol(), "internal"),
                transport.getTopic(),
                transport.getAction(),
                transport.getMsgType(),
                transport.getEventKind(),
                transport.getEventName(),
                transport.getNodeId()
        );
    }

    private EventDefinition.Direction direction(String raw, EventDefinition.Direction fallback) {
        if (blank(raw)) {
            return fallback;
        }
        return EventDefinition.Direction.valueOf(raw.trim().toUpperCase());
    }

    private String normalizeType(String type) {
        if (blank(type)) {
            return "string";
        }
        return switch (type) {
            case "integer", "number" -> "int";
            case "bool" -> "boolean";
            default -> type;
        };
    }

    private String defaultString(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
