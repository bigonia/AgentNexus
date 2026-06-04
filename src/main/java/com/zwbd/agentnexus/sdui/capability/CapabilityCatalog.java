package com.zwbd.agentnexus.sdui.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

/**
 * Preset capability catalog loaded from capability-catalog.yml.
 * Maps capability name strings (as reported by devices) to their full API contracts:
 * events, commands, parameter schemas, and display limits per size_class.
 */
@Slf4j
@Service
public class CapabilityCatalog {

    private final Map<String, InputDef> inputsByName = new LinkedHashMap<>();
    private final Map<String, OutputDef> outputsByName = new LinkedHashMap<>();
    private final Map<String, CommandDef> commandsByName = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> limitsBySizeClass = new LinkedHashMap<>();
    private final Map<String, Integer> commonLimits = new LinkedHashMap<>();

    // ── Public records ──

    public record InputDef(String name, String protocol, Integer eventKind, String topic,
                           List<String> events,
                           String displayName, String description,
                           List<PayloadField> payloadSchema) {
        /** Backward-compat constructor for code that only provides events list. */
        public InputDef(String name, String protocol, Integer eventKind, String topic, List<String> events) {
            this(name, protocol, eventKind, topic, events, null, null, List.of());
        }
    }

    public record OutputDef(String name, Map<String, CommandDef> commands,
                            String displayName, String description) {
        /** Backward-compat constructor. */
        public OutputDef(String name, Map<String, CommandDef> commands) {
            this(name, commands, null, null);
        }
    }

    public record CommandDef(String command, String topic, String action, Integer binaryMsgType,
                             Map<String, FieldSchema> params, boolean internal,
                             String displayName, String description) {
        /** Backward-compat constructor. */
        public CommandDef(String command, String topic, String action, Integer binaryMsgType,
                          Map<String, FieldSchema> params, boolean internal) {
            this(command, topic, action, binaryMsgType, params, internal, null, null);
        }
    }

    public record FieldSchema(String type, boolean required, Integer min, Integer max, Object defaultValue,
                              List<String> values, String label, String description) {}

    /**
     * Payload field definition for event payload schemas (new in catalog v2).
     */
    public record PayloadField(String name, String type, boolean required, String description) {}

    // ── Lifecycle ──

    @PostConstruct
    void load() {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            InputStream in = getClass().getClassLoader().getResourceAsStream("capability-catalog.yml");
            if (in == null) {
                log.error("capability-catalog.yml not found on classpath");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = mapper.readValue(in, Map.class);
            parseInputs(root);
            parseOutputs(root);
            parseLimits(root);
            log.info("CapabilityCatalog loaded: {} inputs, {} outputs, {} commands, {} size classes",
                    inputsByName.size(), outputsByName.size(), commandsByName.size(), limitsBySizeClass.size());
        } catch (Exception e) {
            log.error("Failed to load capability-catalog.yml", e);
        }
    }

    // ── Lookup methods ──

    public Optional<InputDef> getInput(String name) {
        return Optional.ofNullable(inputsByName.get(name));
    }

    public Optional<OutputDef> getOutput(String name) {
        return Optional.ofNullable(outputsByName.get(name));
    }

    public Optional<CommandDef> getCommand(String commandName) {
        return Optional.ofNullable(commandsByName.get(commandName));
    }

    /** @return unmodifiable view of all input definitions. */
    public Map<String, InputDef> getInputsByName() {
        return Collections.unmodifiableMap(inputsByName);
    }

    /** @return unmodifiable view of all output definitions. */
    public Map<String, OutputDef> getOutputsByName() {
        return Collections.unmodifiableMap(outputsByName);
    }

    /** @return unmodifiable view of all command definitions. */
    public Map<String, CommandDef> getCommandsByName() {
        return Collections.unmodifiableMap(commandsByName);
    }

    /**
     * Get all event IDs that an input capability name produces.
     */
    public Set<String> getInputEvents(String inputName) {
        InputDef def = inputsByName.get(inputName);
        if (def == null || def.events() == null) return Set.of();
        return new LinkedHashSet<>(def.events());
    }

    /**
     * Get external command names that an output capability name supports.
     * Internal commands (internal: true) are excluded.
     */
    public Set<String> getOutputCommands(String outputName) {
        OutputDef def = outputsByName.get(outputName);
        if (def == null || def.commands() == null) return Set.of();
        Set<String> cmds = new LinkedHashSet<>();
        def.commands().forEach((name, cmd) -> {
            if (!cmd.internal()) cmds.add(name);
        });
        return cmds;
    }

    /**
     * Get all command names across a set of output capability names.
     */
    public Set<String> getAllCommands(Set<String> outputNames) {
        Set<String> all = new LinkedHashSet<>();
        for (String name : outputNames) {
            all.addAll(getOutputCommands(name));
        }
        return all;
    }

    /**
     * Get display limits for a size_class (merges common + size-specific).
     */
    public Map<String, Integer> getDisplayLimits(String sizeClass) {
        Map<String, Integer> merged = new LinkedHashMap<>(commonLimits);
        String key = sizeClass != null ? sizeClass.toLowerCase() : "large";
        Map<String, Integer> sizeLimits = limitsBySizeClass.getOrDefault(
                key, limitsBySizeClass.get("large"));
        if (sizeLimits != null) {
            merged.putAll(sizeLimits);
        }
        return merged;
    }

    // ── YAML parsing ──

    @SuppressWarnings("unchecked")
    private void parseInputs(Map<String, Object> root) {
        Map<String, Object> inputsNode = (Map<String, Object>) root.get("inputs");
        if (inputsNode == null) return;
        for (var entry : inputsNode.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> def = (Map<String, Object>) entry.getValue();
            String protocol = (String) def.get("protocol");
            Integer eventKind = def.get("eventKind") instanceof Number n ? n.intValue() : null;
            String topic = (String) def.get("topic");
            String displayName = (String) def.get("displayName");
            String description = (String) def.get("description");

            // Parse events — supports both old format (List<String>) and new format (Map<String, {displayName}>)
            List<String> events = parseEventList(def.get("events"));

            // Parse payloadSchema if present (new in catalog v2)
            List<PayloadField> payloadSchema = parsePayloadSchema(
                    (List<Map<String, Object>>) def.get("payloadSchema"));

            inputsByName.put(name, new InputDef(name, protocol, eventKind, topic, events,
                    displayName, description, payloadSchema));
        }
    }

    /**
     * Parse events field — backward compatible with both:
     * <ul>
     *   <li>Old format: {@code events: [press_down, press_up]} (List of strings)</li>
     *   <li>New format: {@code events: {press_down: {displayName: "按下"}}} (Map)</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private List<String> parseEventList(Object eventsNode) {
        if (eventsNode == null) return List.of();
        if (eventsNode instanceof List<?> list) {
            // Old format: list of strings
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s) {
                    result.add(s);
                }
            }
            return result;
        }
        if (eventsNode instanceof Map<?, ?> map) {
            // New format: map of eventName → {displayName, ...}
            return new ArrayList<>((Set<String>) map.keySet());
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<PayloadField> parsePayloadSchema(List<Map<String, Object>> schemaList) {
        if (schemaList == null || schemaList.isEmpty()) return List.of();
        List<PayloadField> result = new ArrayList<>();
        for (Map<String, Object> field : schemaList) {
            String fieldName = (String) field.get("name");
            String type = (String) field.getOrDefault("type", "string");
            boolean required = field.get("required") instanceof Boolean b ? b : false;
            String desc = (String) field.get("description");
            result.add(new PayloadField(fieldName, type, required, desc));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void parseOutputs(Map<String, Object> root) {
        Map<String, Object> outputsNode = (Map<String, Object>) root.get("outputs");
        if (outputsNode == null) return;
        for (var entry : outputsNode.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> def = (Map<String, Object>) entry.getValue();
            String outputDisplayName = (String) def.get("displayName");
            String outputDescription = (String) def.get("description");
            Map<String, Object> commandsRaw = (Map<String, Object>) def.get("commands");
            if (commandsRaw == null) continue;

            Map<String, CommandDef> commands = new LinkedHashMap<>();
            for (var cmdEntry : commandsRaw.entrySet()) {
                String cmdName = cmdEntry.getKey();
                Map<String, Object> cmdDef = (Map<String, Object>) cmdEntry.getValue();
                String topic = (String) cmdDef.get("topic");
                String action = (String) cmdDef.get("action");
                Integer binaryMsgType = cmdDef.get("binaryMsgType") instanceof Number n ? n.intValue() : null;
                boolean internal = cmdDef.get("internal") instanceof Boolean b ? b : false;
                String cmdDisplayName = (String) cmdDef.get("displayName");
                String cmdDescription = (String) cmdDef.get("description");
                Map<String, FieldSchema> params = parseParams((Map<String, Object>) cmdDef.get("params"));

                CommandDef commandDef = new CommandDef(cmdName, topic, action, binaryMsgType, params, internal,
                        cmdDisplayName, cmdDescription);
                commands.put(cmdName, commandDef);
                commandsByName.put(cmdName, commandDef);
            }
            outputsByName.put(name, new OutputDef(name, commands, outputDisplayName, outputDescription));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, FieldSchema> parseParams(Map<String, Object> paramsNode) {
        Map<String, FieldSchema> result = new LinkedHashMap<>();
        if (paramsNode == null) return result;
        for (var entry : paramsNode.entrySet()) {
            String paramName = entry.getKey();
            Map<String, Object> field = (Map<String, Object>) entry.getValue();
            String type = (String) field.getOrDefault("type", "string");
            boolean required = field.get("required") instanceof Boolean b ? b : false;
            Integer min = field.get("min") instanceof Number n ? n.intValue() : null;
            Integer max = field.get("max") instanceof Number n ? n.intValue() : null;
            Object defaultValue = field.get("default");
            List<String> values = (List<String>) field.get("values");
            String label = (String) field.get("label");
            String description = (String) field.get("description");
            result.put(paramName, new FieldSchema(type, required, min, max, defaultValue, values, label, description));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void parseLimits(Map<String, Object> root) {
        Map<String, Object> limitsNode = (Map<String, Object>) root.get("display_limits");
        if (limitsNode == null) return;

        Map<String, Object> common = (Map<String, Object>) limitsNode.get("common");
        if (common != null) {
            for (var entry : common.entrySet()) {
                if (entry.getValue() instanceof Number n) {
                    commonLimits.put(entry.getKey(), n.intValue());
                }
            }
        }

        Map<String, Object> sizeClasses = (Map<String, Object>) limitsNode.get("size_classes");
        if (sizeClasses != null) {
            for (var entry : sizeClasses.entrySet()) {
                Map<String, Integer> limits = new LinkedHashMap<>();
                Map<String, Object> sizeMap = (Map<String, Object>) entry.getValue();
                for (var limitEntry : sizeMap.entrySet()) {
                    if (limitEntry.getValue() instanceof Number n) {
                        limits.put(limitEntry.getKey(), n.intValue());
                    }
                }
                limitsBySizeClass.put(entry.getKey().toLowerCase(), limits);
            }
        }
    }
}
