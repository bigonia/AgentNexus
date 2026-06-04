package com.zwbd.agentnexus.sdui.event;

import com.zwbd.agentnexus.sdui.capability.CapabilityCatalog;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Central registry for all SDUI event definitions.
 *
 * Aggregates events from:
 * <ol>
 *   <li>{@link CapabilityCatalog} — hardware events (buttons, sensors, audio input)</li>
 *   <li>{@link SectionTypeCatalog} — section interaction events (click, select, toggle, confirm)</li>
 *   <li>Output commands from capability-catalog.yml — outbound commands</li>
 * </ol>
 *
 * Provides structured queries for the workflow editor (event tree),
 * event validation, and legacy name resolution.
 */
@Slf4j
@Component
public class EventRegistry {

    private final CapabilityCatalog catalog;

    public EventRegistry(CapabilityCatalog catalog) {
        this.catalog = catalog;
    }

    // ── Inbound events ──
    private final Map<String, EventDefinition> inboundEvents = new LinkedHashMap<>();

    // ── Outbound events ──
    private final Map<String, EventDefinition> outboundEvents = new LinkedHashMap<>();

    // ── Events by category ──
    private final Map<EventDefinition.EventCategory, List<EventDefinition>> eventsByCategory = new LinkedHashMap<>();

    // ── Legacy name → namespaced ID mapping ──
    private final Map<String, String> legacyNameToId = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        registerHardwareEvents();
        registerSectionInteractionEvents();
        registerOutboundCommands();
        log.info("EventRegistry initialized: {} inbound, {} outbound, {} legacy mappings, {} categories",
                inboundEvents.size(), outboundEvents.size(), legacyNameToId.size(), eventsByCategory.size());
    }

    // ── Registration ──

    private void registerHardwareEvents() {
        for (var inputEntry : catalog.getInputsByName().entrySet()) {
            String inputName = inputEntry.getKey();
            CapabilityCatalog.InputDef input = inputEntry.getValue();
            EventDefinition.EventCategory category = categorizeInput(inputName);

            // Transport info
            EventDefinition.TransportInfo transport = buildTransport(input);

            // Payload schema from catalog (or default)
            List<EventDefinition.ParamDef> payloadSchema = buildPayloadSchema(input);

            // Register each event this input produces
            for (String eventName : input.events()) {
                String eventId = namespacedId(category, inputName, eventName);
                String displayName = eventDisplayName(category, eventName);
                String description = input.description() != null
                        ? input.description() : inputName + " → " + eventName;

                EventDefinition def = EventDefinition.inbound(
                        eventId, category, displayName, description,
                        inputName, transport, payloadSchema);

                register(def);
            }
        }
    }

    private void registerSectionInteractionEvents() {
        for (var typeDef : SectionTypeCatalog.all().values()) {
            if (!typeDef.interactive()) continue;

            String sectionType = typeDef.type(); // e.g. "action_section"
            EventDefinition.EventCategory category = EventDefinition.EventCategory.SECTION_INTERACTION;
            EventDefinition.TransportInfo transport = EventDefinition.TransportInfo.ui3Binary(9, 1);

            for (SectionTypeCatalog.InteractionEvent ievt : typeDef.interactionEvents()) {
                String eventId = "section:" + ievt.eventId(); // e.g. "section:action.click"

                List<EventDefinition.ParamDef> payloadSchema = new ArrayList<>();
                // Always present in section interaction events
                payloadSchema.add(new EventDefinition.ParamDef("sectionId", "string", true,
                        "触发事件的 Section ID"));
                payloadSchema.add(new EventDefinition.ParamDef("pageId", "string", false,
                        "所在页面 ID"));
                // Add interaction-specific params
                for (SectionTypeCatalog.ParamDef p : ievt.params()) {
                    // Skip sectionId since we already added it as a standard field
                    if ("sectionId".equals(p.name())) continue;
                    if ("pageId".equals(p.name())) continue;
                    payloadSchema.add(new EventDefinition.ParamDef(
                            p.name(), p.type(), false, p.description()));
                }
                // Standard runtime fields
                payloadSchema.add(new EventDefinition.ParamDef("nodeId", "string", false,
                        "被操作的控件 ID"));
                payloadSchema.add(new EventDefinition.ParamDef("ts", "int", false,
                        "事件时间戳(ms)"));

                EventDefinition def = EventDefinition.inbound(
                        eventId, category, ievt.description(),
                        sectionType + " 的 " + ievt.description(),
                        sectionType, transport, payloadSchema);

                register(def);
            }
        }
    }

    private void registerOutboundCommands() {
        for (var outputEntry : catalog.getOutputsByName().entrySet()) {
            String outputName = outputEntry.getKey();
            CapabilityCatalog.OutputDef output = outputEntry.getValue();
            EventDefinition.EventCategory category = categorizeOutput(outputName);

            for (var cmdEntry : output.commands().entrySet()) {
                String cmdName = cmdEntry.getKey();
                CapabilityCatalog.CommandDef cmd = cmdEntry.getValue();
                if (cmd.internal()) continue; // skip internal server-side commands

                String eventId = cmdName; // already namespaced: "display.brightness.set"
                String displayName = cmd.displayName() != null ? cmd.displayName() : cmdName;
                String description = cmd.description() != null ? cmd.description()
                        : output.description() != null ? output.description() : cmdName;

                EventDefinition.TransportInfo transport;
                if ("server".equals(cmd.topic())) {
                    transport = EventDefinition.TransportInfo.serverSide();
                } else if (cmd.binaryMsgType() != null) {
                    transport = EventDefinition.TransportInfo.ui3Binary(cmd.binaryMsgType());
                } else if (cmd.topic() != null && cmd.action() != null) {
                    transport = EventDefinition.TransportInfo.jsonTopic(cmd.topic(), cmd.action());
                } else if (cmd.topic() != null) {
                    transport = EventDefinition.TransportInfo.jsonTopic(cmd.topic());
                } else {
                    transport = EventDefinition.TransportInfo.internal();
                }

                // Build param schema from command params
                List<EventDefinition.ParamDef> payloadSchema = new ArrayList<>();
                for (var paramEntry : cmd.params().entrySet()) {
                    CapabilityCatalog.FieldSchema fs = paramEntry.getValue();
                    payloadSchema.add(new EventDefinition.ParamDef(
                            paramEntry.getKey(), fs.type(), fs.required(),
                            fs.description() != null ? fs.description() : fs.label()));
                }

                EventDefinition def = EventDefinition.outbound(
                        eventId, category, displayName, description,
                        outputName, transport, payloadSchema);

                register(def);
            }
        }
    }

    // ── Registration helper ──

    private void register(EventDefinition def) {
        if (def.direction() == EventDefinition.Direction.INBOUND) {
            inboundEvents.put(def.eventId(), def);
        } else {
            outboundEvents.put(def.eventId(), def);
        }

        eventsByCategory.computeIfAbsent(def.category(), k -> new ArrayList<>()).add(def);

        // Legacy name mapping
        String legacy = legacyNameFrom(def);
        if (legacy != null && !legacy.equals(def.eventId())) {
            legacyNameToId.putIfAbsent(legacy, def.eventId());
        }
    }

    // ── Categorization helpers ──

    private EventDefinition.EventCategory categorizeInput(String inputName) {
        if (inputName.startsWith("buttons.")) return EventDefinition.EventCategory.HARDWARE_BUTTON;
        if ("motion".equals(inputName)) return EventDefinition.EventCategory.HARDWARE_SENSOR;
        if (inputName.startsWith("audio.")) return EventDefinition.EventCategory.AUDIO_INPUT;
        return EventDefinition.EventCategory.SYSTEM;
    }

    private EventDefinition.EventCategory categorizeOutput(String outputName) {
        if (outputName.startsWith("display.")) return EventDefinition.EventCategory.DISPLAY;
        if (outputName.startsWith("audio.")) return EventDefinition.EventCategory.AUDIO_OUTPUT;
        if (outputName.startsWith("rgb.")) return EventDefinition.EventCategory.LIGHTING;
        if ("device.reboot".equals(outputName)) return EventDefinition.EventCategory.SYSTEM;
        return EventDefinition.EventCategory.SYSTEM;
    }

    // ── ID helpers ──

    private String namespacedId(EventDefinition.EventCategory category, String inputName, String eventName) {
        return switch (category) {
            case HARDWARE_BUTTON -> "hardware:" + inputName + "." + eventName;
            case HARDWARE_SENSOR -> "sensor:motion." + eventName;
            case AUDIO_INPUT -> "audio:" + eventName;
            default -> eventName; // fallback for unknown categories
        };
    }

    private String eventDisplayName(EventDefinition.EventCategory category, String eventName) {
        return switch (eventName) {
            case "press_down" -> "按下";
            case "press_up" -> "释放";
            case "single_click" -> "单击";
            case "double_click" -> "双击";
            case "long_press_start" -> "长按开始";
            case "long_press_up" -> "长按释放";
            case "imu.shake" -> "摇晃";
            case "imu.wrist_raise" -> "抬腕";
            case "imu.flip" -> "翻转";
            case "audio.record.data" -> "音频数据";
            case "audio.stt.result" -> "语音识别结果";
            default -> eventName;
        };
    }

    private String legacyNameFrom(EventDefinition def) {
        // Map namespaced ID back to the legacy name that firmware sends
        String id = def.eventId();
        if (id.startsWith("hardware:buttons.")) {
            // "hardware:buttons.boot.single_click" → "single_click"
            int lastDot = id.lastIndexOf('.');
            return lastDot >= 0 ? id.substring(lastDot + 1) : id;
        }
        if (id.startsWith("section:")) {
            // "section:action.click" → "action.click"
            return id.substring("section:".length());
        }
        if (id.startsWith("sensor:motion.")) {
            // "sensor:motion.imu.shake" → "imu.shake"
            return id.substring("sensor:motion.".length());
        }
        if (id.startsWith("audio:")) {
            // "audio:record.data" → "audio.record.data"
            return id.substring("audio:".length());
        }
        return id;
    }

    private EventDefinition.TransportInfo buildTransport(CapabilityCatalog.InputDef input) {
        if ("ui3_binary".equals(input.protocol())) {
            return EventDefinition.TransportInfo.ui3Binary(9,
                    input.eventKind() != null ? input.eventKind() : 0);
        } else if ("json_topic".equals(input.protocol()) && input.topic() != null) {
            return EventDefinition.TransportInfo.jsonTopic(input.topic());
        }
        return EventDefinition.TransportInfo.internal();
    }

    private List<EventDefinition.ParamDef> buildPayloadSchema(CapabilityCatalog.InputDef input) {
        if (input.payloadSchema() != null && !input.payloadSchema().isEmpty()) {
            return input.payloadSchema().stream()
                    .map(f -> new EventDefinition.ParamDef(f.name(), f.type(), f.required(), f.description()))
                    .toList();
        }
        // Default payload schema for binary events
        return List.of(
                new EventDefinition.ParamDef("nodeId", "string", "控件/按钮标识"),
                new EventDefinition.ParamDef("eventName", "string", "事件名称"),
                new EventDefinition.ParamDef("kind", "int", "事件类型编码"),
                new EventDefinition.ParamDef("ts", "int", "事件时间戳(ms)")
        );
    }

    // ── Queries ──

    /** Get an inbound event definition by its namespaced ID. */
    public Optional<EventDefinition> getInboundEvent(String eventId) {
        return Optional.ofNullable(inboundEvents.get(eventId));
    }

    /** Get an outbound event (command) definition by its command ID. */
    public Optional<EventDefinition> getOutboundEvent(String commandId) {
        return Optional.ofNullable(outboundEvents.get(commandId));
    }

    /** Get all events in a given category. */
    public List<EventDefinition> getEventsByCategory(EventDefinition.EventCategory category) {
        return eventsByCategory.getOrDefault(category, List.of());
    }

    /** Get all inbound event definitions. */
    public Collection<EventDefinition> getAllInboundEvents() {
        return Collections.unmodifiableCollection(inboundEvents.values());
    }

    /** Get all outbound event definitions. */
    public Collection<EventDefinition> getAllOutboundEvents() {
        return Collections.unmodifiableCollection(outboundEvents.values());
    }

    /**
     * Resolve a legacy event name (as sent by firmware) to its namespaced event ID.
     * If the name is already namespaced, returns it as-is.
     */
    public String resolveEventId(String eventName) {
        if (eventName == null || eventName.isEmpty()) return null;
        if (eventName.contains(":")) return eventName; // already namespaced
        return legacyNameToId.getOrDefault(eventName, eventName);
    }

    /**
     * Check if an event ID is known (either as a namespaced ID or legacy name).
     */
    public boolean isKnownEvent(String eventId) {
        if (eventId == null) return false;
        return inboundEvents.containsKey(eventId)
                || legacyNameToId.containsKey(eventId)
                || outboundEvents.containsKey(eventId);
    }

    /** Get all known inbound event IDs (both namespaced and legacy). */
    public Set<String> getKnownEventIds() {
        Set<String> ids = new LinkedHashSet<>(inboundEvents.keySet());
        ids.addAll(legacyNameToId.keySet());
        return ids;
    }

    /** Get all known command IDs. */
    public Set<String> getKnownCommandIds() {
        return Collections.unmodifiableSet(outboundEvents.keySet());
    }

    // ── Event tree for frontend ──

    /**
     * Build a structured event tree for the workflow editor's event selector.
     *
     * Structure: category → capability → event list
     * <pre>
     * [
     *   {
     *     "category": "HARDWARE_BUTTON",
     *     "label": "物理按钮",
     *     "capabilities": [
     *       {
     *         "capability": "buttons.boot",
     *         "label": "BOOT 按钮",
     *         "events": [
     *           { "eventId": "hardware:buttons.boot.single_click", "displayName": "单击", ... }
     *         ]
     *       }
     *     ]
     *   }
     * ]
     * </pre>
     */
    public List<Map<String, Object>> getInboundEventTree() {
        List<Map<String, Object>> tree = new ArrayList<>();

        // Group inbound events by category → source capability
        Map<EventDefinition.EventCategory, Map<String, List<EventDefinition>>> grouped = new LinkedHashMap<>();

        for (EventDefinition def : inboundEvents.values()) {
            grouped.computeIfAbsent(def.category(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(def.sourceCapability(), k -> new ArrayList<>())
                    .add(def);
        }

        for (var catEntry : grouped.entrySet()) {
            EventDefinition.EventCategory category = catEntry.getKey();
            Map<String, Object> catNode = new LinkedHashMap<>();
            catNode.put("category", category.name());
            catNode.put("label", category.label());

            List<Map<String, Object>> capabilities = new ArrayList<>();
            for (var capEntry : catEntry.getValue().entrySet()) {
                String capName = capEntry.getKey();
                List<EventDefinition> eventDefs = capEntry.getValue();

                Map<String, Object> capNode = new LinkedHashMap<>();
                capNode.put("capability", capName);
                capNode.put("label", eventDefs.isEmpty() ? capName
                        : eventDefs.get(0).sourceCapability());
                capNode.put("events", eventDefs.stream()
                        .map(EventDefinition::toMap)
                        .toList());

                capabilities.add(capNode);
            }
            catNode.put("capabilities", capabilities);
            tree.add(catNode);
        }
        return tree;
    }

    /**
     * Build a structured command tree for the workflow editor's action selector.
     * Same structure as {@link #getInboundEventTree()} but for outbound events.
     */
    public List<Map<String, Object>> getOutboundEventTree() {
        List<Map<String, Object>> tree = new ArrayList<>();

        Map<EventDefinition.EventCategory, Map<String, List<EventDefinition>>> grouped = new LinkedHashMap<>();

        for (EventDefinition def : outboundEvents.values()) {
            grouped.computeIfAbsent(def.category(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(def.sourceCapability(), k -> new ArrayList<>())
                    .add(def);
        }

        for (var catEntry : grouped.entrySet()) {
            EventDefinition.EventCategory category = catEntry.getKey();
            Map<String, Object> catNode = new LinkedHashMap<>();
            catNode.put("category", category.name());
            catNode.put("label", category.label());

            List<Map<String, Object>> capabilities = new ArrayList<>();
            for (var capEntry : catEntry.getValue().entrySet()) {
                List<EventDefinition> eventDefs = capEntry.getValue();
                Map<String, Object> capNode = new LinkedHashMap<>();
                capNode.put("capability", capEntry.getKey());
                capNode.put("label", eventDefs.isEmpty() ? capEntry.getKey()
                        : eventDefs.get(0).displayName());
                capNode.put("commands", eventDefs.stream()
                        .map(EventDefinition::toMap)
                        .toList());
                capabilities.add(capNode);
            }
            catNode.put("capabilities", capabilities);
            tree.add(catNode);
        }
        return tree;
    }

    /**
     * Build a flat list of event options for backward-compatible API responses.
     * Each entry has: value (eventId), label (displayName), category, source.
     */
    public List<Map<String, String>> getFlatEventOptions() {
        List<Map<String, String>> options = new ArrayList<>();
        for (EventDefinition def : inboundEvents.values()) {
            options.add(Map.of(
                    "value", def.eventId(),
                    "label", def.displayName(),
                    "category", def.category().name(),
                    "source", def.sourceCapability()
            ));
        }
        return options;
    }

    // ── Package-private accessor for testing ──

    Map<String, EventDefinition> inboundEventsMap() { return inboundEvents; }
    Map<String, EventDefinition> outboundEventsMap() { return outboundEvents; }
}
