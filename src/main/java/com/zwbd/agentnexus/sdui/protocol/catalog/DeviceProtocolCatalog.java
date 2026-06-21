package com.zwbd.agentnexus.sdui.protocol.catalog;

import com.zwbd.agentnexus.sdui.capability.CapabilityCatalog;
import com.zwbd.agentnexus.sdui.event.EventDefinition;
import com.zwbd.agentnexus.sdui.event.EventRegistry;
import com.zwbd.agentnexus.sdui.protocol.SduiProtocolConstants;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DeviceProtocolCatalog {

    private final CapabilityCatalog capabilityCatalog;
    private final EventRegistry eventRegistry;
    private final Map<String, CommandSpec> commands;
    private final Map<String, SectionSpec> sections;
    private final Map<String, EventSpec> events;

    @Autowired
    public DeviceProtocolCatalog(CapabilityCatalog capabilityCatalog,
                                 EventRegistry eventRegistry) {
        this.capabilityCatalog = capabilityCatalog;
        this.eventRegistry = eventRegistry;
        this.commands = Collections.unmodifiableMap(buildCommands());
        this.sections = Collections.unmodifiableMap(buildSections());
        this.events = Collections.unmodifiableMap(buildEvents());
    }

    public DeviceProtocolCatalog(CapabilityCatalog capabilityCatalog) {
        this.capabilityCatalog = capabilityCatalog;
        this.eventRegistry = null;
        this.commands = Collections.unmodifiableMap(buildCommands());
        this.sections = Collections.unmodifiableMap(buildSections());
        this.events = Map.of();
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
                    eventRegistry != null ? eventRegistry.getAllSectionEvents().stream()
                            .filter(event -> def.type().equals(event.sourceCapability()))
                            .map(EventDefinition::eventId)
                            .toList()
                            : List.of()
            ));
        }
        return result;
    }

    private Map<String, EventSpec> buildEvents() {
        Map<String, EventSpec> result = new LinkedHashMap<>();
        if (eventRegistry == null) {
            return result;
        }
        for (EventDefinition event : eventRegistry.getAllInboundEvents()) {
            EventDefinition.TransportInfo transport = event.transport();
            result.put(event.eventId(), new EventSpec(
                    event.eventId(),
                    event.sourceCapability(),
                    event.payloadSchema().stream()
                            .map(field -> new FieldSpec(field.name(), normalizeType(field.type())))
                            .toList(),
                    new TransportSpec(
                            transport != null ? transport.topic() : null,
                            transport != null ? transport.action() : null,
                            transport != null && transport.msgType() != null ? String.valueOf(transport.msgType()) : null
                    )
            ));
        }

        return result;
    }

    private List<FieldSpec> toFieldSpecs(List<SectionTypeCatalog.SectionFieldDef> defs) {
        List<FieldSpec> specs = new ArrayList<>();
        for (SectionTypeCatalog.SectionFieldDef def : defs) {
            List<FieldSpec> children = def.children() != null ? toFieldSpecs(def.children()) : List.of();
            specs.add(new FieldSpec(def.name(), normalizeType(def.type()), children));
        }
        return specs;
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
