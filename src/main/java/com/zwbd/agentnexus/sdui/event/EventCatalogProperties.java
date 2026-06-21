package com.zwbd.agentnexus.sdui.event;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Raw YAML shape for sdui-event-catalog.yml.
 */
public class EventCatalogProperties {

    private Commands commands = new Commands();
    private Sections sections = new Sections();

    public Commands getCommands() {
        return commands;
    }

    public void setCommands(Commands commands) {
        this.commands = commands != null ? commands : new Commands();
    }

    public Sections getSections() {
        return sections;
    }

    public void setSections(Sections sections) {
        this.sections = sections != null ? sections : new Sections();
    }

    public static class Commands {
        private List<CommandGroup> groups = List.of();
        private List<EventEntry> lifecycle = List.of();
        /** System-level events: timer, cron, heartbeat, etc. — public triggers. */
        private List<EventEntry> system = List.of();

        public List<CommandGroup> getGroups() {
            return groups;
        }

        public void setGroups(List<CommandGroup> groups) {
            this.groups = groups != null ? groups : List.of();
        }

        public List<EventEntry> getLifecycle() {
            return lifecycle;
        }

        public void setLifecycle(List<EventEntry> lifecycle) {
            this.lifecycle = lifecycle != null ? lifecycle : List.of();
        }

        public List<EventEntry> getSystem() {
            return system;
        }

        public void setSystem(List<EventEntry> system) {
            this.system = system != null ? system : List.of();
        }
    }

    public static class CommandGroup {
        private String id;
        private String displayName;
        private String description;
        private List<CommandEntry> commands = List.of();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<CommandEntry> getCommands() {
            return commands;
        }

        public void setCommands(List<CommandEntry> commands) {
            this.commands = commands != null ? commands : List.of();
        }
    }

    public static class Sections {
        private List<SectionTypeEntry> types = List.of();

        public List<SectionTypeEntry> getTypes() {
            return types;
        }

        public void setTypes(List<SectionTypeEntry> types) {
            this.types = types != null ? types : List.of();
        }
    }

    public static class SectionTypeEntry {
        private String type;
        private String displayName;
        private List<String> operations = List.of("add", "update", "remove");
        private List<FieldEntry> fields = List.of();
        private List<EventEntry> events = List.of();
        private Map<String, Object> constraints = new LinkedHashMap<>();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public List<String> getOperations() {
            return operations;
        }

        public void setOperations(List<String> operations) {
            this.operations = operations != null ? operations : List.of();
        }

        public List<FieldEntry> getFields() {
            return fields;
        }

        public void setFields(List<FieldEntry> fields) {
            this.fields = fields != null ? fields : List.of();
        }

        public List<EventEntry> getEvents() {
            return events;
        }

        public void setEvents(List<EventEntry> events) {
            this.events = events != null ? events : List.of();
        }

        public Map<String, Object> getConstraints() {
            return constraints;
        }

        public void setConstraints(Map<String, Object> constraints) {
            this.constraints = constraints != null ? constraints : new LinkedHashMap<>();
        }
    }

    public static class CommandEntry extends EventEntry {
        private Map<String, Object> constraints = new LinkedHashMap<>();

        public Map<String, Object> getConstraints() {
            return constraints;
        }

        public void setConstraints(Map<String, Object> constraints) {
            this.constraints = constraints != null ? constraints : new LinkedHashMap<>();
        }
    }

    public static class EventEntry {
        private String id;
        private String subtype;
        private String displayName;
        private String description;
        private String direction;
        private String source;
        /** Override category (defaults to position-based: lifecycle→COMMAND_LIFECYCLE, system→SYSTEM_EVENT). */
        private String category;
        private Transport transport;
        private List<FieldEntry> payloadSchema = List.of();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getSubtype() {
            return subtype;
        }

        public void setSubtype(String subtype) {
            this.subtype = subtype;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getDirection() {
            return direction;
        }

        public void setDirection(String direction) {
            this.direction = direction;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public Transport getTransport() {
            return transport;
        }

        public void setTransport(Transport transport) {
            this.transport = transport;
        }

        public List<FieldEntry> getPayloadSchema() {
            return payloadSchema;
        }

        public void setPayloadSchema(List<FieldEntry> payloadSchema) {
            this.payloadSchema = payloadSchema != null ? payloadSchema : List.of();
        }
    }

    public static class FieldEntry {
        private String name;
        private String type = "string";
        private boolean required;
        private Object min;
        private Object max;
        private List<String> values = List.of();
        private String description;
        private List<FieldEntry> children = List.of();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public Object getMin() {
            return min;
        }

        public void setMin(Object min) {
            this.min = min;
        }

        public Object getMax() {
            return max;
        }

        public void setMax(Object max) {
            this.max = max;
        }

        public List<String> getValues() {
            return values;
        }

        public void setValues(List<String> values) {
            this.values = values != null ? values : List.of();
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<FieldEntry> getChildren() {
            return children;
        }

        public void setChildren(List<FieldEntry> children) {
            this.children = children != null ? children : List.of();
        }
    }

    public static class Transport {
        private String protocol;
        private String topic;
        private String action;
        private Integer msgType;
        private Integer eventKind;
        private String eventName;
        private String nodeId;

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public Integer getMsgType() {
            return msgType;
        }

        public void setMsgType(Integer msgType) {
            this.msgType = msgType;
        }

        public Integer getEventKind() {
            return eventKind;
        }

        public void setEventKind(Integer eventKind) {
            this.eventKind = eventKind;
        }

        public String getEventName() {
            return eventName;
        }

        public void setEventName(String eventName) {
            this.eventName = eventName;
        }

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }
    }
}
