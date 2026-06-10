package com.zwbd.agentnexus.sdui.protocol.catalog;

import com.zwbd.agentnexus.sdui.capability.CapabilityCatalog;
import com.zwbd.agentnexus.sdui.protocol.SduiProtocolConstants;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DeviceProtocolCatalog {

    private final CapabilityCatalog capabilityCatalog;
    private final Map<String, CommandSpec> commands;
    private final Map<String, SectionSpec> sections;
    private final Map<String, EventSpec> events;

    public DeviceProtocolCatalog(CapabilityCatalog capabilityCatalog) {
        this.capabilityCatalog = capabilityCatalog;
        this.commands = Collections.unmodifiableMap(buildCommands());
        this.sections = Collections.unmodifiableMap(buildSections());
        this.events = Collections.unmodifiableMap(buildEvents());
    }

    public Collection<CommandSpec> commands() {
        return commands.values();
    }

    public Optional<CommandSpec> command(String id) {
        return Optional.ofNullable(commands.get(id));
    }

    public Collection<SectionSpec> sections() {
        return sections.values();
    }

    public Optional<SectionSpec> section(String type) {
        return Optional.ofNullable(sections.get(type));
    }

    public Collection<EventSpec> events() {
        return events.values();
    }

    public Optional<EventSpec> event(String id) {
        return Optional.ofNullable(events.get(id));
    }

    private Map<String, CommandSpec> buildCommands() {
        Map<String, CommandSpec> result = new LinkedHashMap<>();
        for (CapabilityCatalog.CommandDef cmd : capabilityCatalog.getCommandsByName().values()) {
            if (cmd.internal()) {
                continue;
            }
            List<FieldSpec> params = new ArrayList<>();
            for (Map.Entry<String, CapabilityCatalog.FieldSchema> entry : cmd.params().entrySet()) {
                params.add(new FieldSpec(entry.getKey(), normalizeType(entry.getValue().type())));
            }
            String group = commandGroup(cmd.command());
            String messageKind = cmd.binaryMsgType() != null ? String.valueOf(cmd.binaryMsgType()) : null;
            result.put(cmd.command(), new CommandSpec(
                    cmd.command(),
                    group,
                    params,
                    new TransportSpec(cmd.topic(), cmd.action(), messageKind)
            ));
        }
        return result;
    }

    private Map<String, SectionSpec> buildSections() {
        Map<String, SectionSpec> result = new LinkedHashMap<>();
        for (SectionTypeCatalog.SectionTypeDef def : SectionTypeCatalog.all().values()) {
            result.put(def.type(), new SectionSpec(
                    def.type(),
                    toFieldSpecs(def.displayFields()),
                    true,
                    List.of("add", "update", "remove"),
                    def.interactionEvents().stream().map(SectionTypeCatalog.InteractionEvent::eventId).toList()
            ));
        }
        return result;
    }

    private Map<String, EventSpec> buildEvents() {
        Map<String, EventSpec> result = new LinkedHashMap<>();
        for (CapabilityCatalog.InputDef input : capabilityCatalog.getInputsByName().values()) {
            List<FieldSpec> payload = buildInputPayload(input);
            TransportSpec transport = new TransportSpec(
                    input.topic(),
                    null,
                    "ui3_binary".equals(input.protocol()) ? "EVENT_INPUT" : null
            );
            for (String event : input.events()) {
                result.put(resolveInputEventId(input.name(), event), new EventSpec(
                        resolveInputEventId(input.name(), event),
                        input.name(),
                        payload,
                        transport
                ));
            }
        }

        for (SectionTypeCatalog.SectionTypeDef def : SectionTypeCatalog.all().values()) {
            if (!def.interactive()) {
                continue;
            }
            for (SectionTypeCatalog.InteractionEvent event : def.interactionEvents()) {
                List<FieldSpec> payload = new ArrayList<>();
                payload.add(new FieldSpec("pageId", "string"));
                payload.add(new FieldSpec("sectionId", "string"));
                payload.add(new FieldSpec("sectionType", "string"));
                payload.add(new FieldSpec("nodeId", "string"));
                payload.add(new FieldSpec("value", "object"));
                payload.add(new FieldSpec("ts", "int"));
                for (SectionTypeCatalog.ParamDef param : event.params()) {
                    if (payload.stream().anyMatch(existing -> existing.name().equals(param.name()))) {
                        continue;
                    }
                    payload.add(new FieldSpec(param.name(), normalizeType(param.type())));
                }
                result.put("ui:" + event.eventId(), new EventSpec(
                        "ui:" + event.eventId(),
                        def.type(),
                        payload,
                        new TransportSpec(null, event.eventId(), "EVENT_INPUT")
                ));
            }
        }

        return result;
    }

    private List<FieldSpec> buildInputPayload(CapabilityCatalog.InputDef input) {
        if (input.payloadSchema() != null && !input.payloadSchema().isEmpty()) {
            return input.payloadSchema().stream()
                    .map(field -> new FieldSpec(field.name(), normalizeType(field.type())))
                    .toList();
        }
        if ("audio.record".equals(input.name())) {
            return List.of(
                    new FieldSpec("state", "string"),
                    new FieldSpec("seq", "int"),
                    new FieldSpec("total", "int"),
                    new FieldSpec("codec", "string"),
                    new FieldSpec("data", "string")
            );
        }
        return List.of(
                new FieldSpec("nodeId", "string"),
                new FieldSpec("eventType", "string"),
                new FieldSpec("ts", "int")
        );
    }

    private List<FieldSpec> toFieldSpecs(List<SectionTypeCatalog.SectionFieldDef> defs) {
        List<FieldSpec> specs = new ArrayList<>();
        for (SectionTypeCatalog.SectionFieldDef def : defs) {
            List<FieldSpec> children = def.children() != null ? toFieldSpecs(def.children()) : List.of();
            specs.add(new FieldSpec(def.name(), normalizeType(def.type()), children));
        }
        return specs;
    }

    private String resolveInputEventId(String inputName, String eventName) {
        if (inputName.startsWith("buttons.")) {
            return "input:" + inputName + "." + eventName;
        }
        if ("motion".equals(inputName)) {
            return "input:motion." + eventName;
        }
        if (inputName.startsWith("audio.")) {
            return "input:" + inputName + "." + eventName;
        }
        return "input:" + inputName + "." + eventName;
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "object";
        }
        return switch (type) {
            case "boolean" -> "bool";
            case "integer", "number" -> "int";
            default -> type;
        };
    }

    private String commandGroup(String command) {
        int split = command.indexOf('.');
        return split > 0 ? command.substring(0, split) : command;
    }
}
