package com.zwbd.agentnexus.sdui.capability;

import com.zwbd.agentnexus.sdui.event.EventDefinition;
import com.zwbd.agentnexus.sdui.event.EventRegistry;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Fully dynamic capability registry — all knowledge comes from device reports.
 * No static preset capabilities. The global catalog is the union of all
 * capabilities ever reported by any connected device.
 *
 * Five layers:
 * 1. Global catalog: union across all devices (for workflow editor dropdowns)
 * 2. Device snapshots: per-device precise capabilities (for validation)
 * 3. Command schemas: parameter definitions per command (from device output params)
 * 4. Event definitions: enriched via {@link EventRegistry}
 * 5. Board types: grouped by hardware board identifier (for workflow targeting)
 */
@Slf4j
@Component
public class CapabilityRegistry {

    private final CapabilityCatalog catalog;
    private EventRegistry eventRegistry;
    private SectionTypeCatalog sectionCatalog;

    public CapabilityRegistry(CapabilityCatalog catalog) {
        this.catalog = catalog;
    }

    /** Setter injection to avoid circular dependency. */
    @Autowired(required = false)
    public void setEventRegistry(EventRegistry eventRegistry) {
        this.eventRegistry = eventRegistry;
    }

    /** Setter injection to avoid circular dependency. */
    @Autowired(required = false)
    public void setSectionTypeCatalog(SectionTypeCatalog sectionCatalog) {
        this.sectionCatalog = sectionCatalog;
    }

    // ── Global catalog (union of all device reports) ──

    private final Set<String> knownEvents = new CopyOnWriteArraySet<>();
    private final Set<String> knownCommands = new CopyOnWriteArraySet<>();
    private final Set<String> knownSectionTypes = new CopyOnWriteArraySet<>();

    // ── Per-device snapshots ──

    private final Map<String, DeviceCapabilities> deviceSnapshots = new ConcurrentHashMap<>();

    // ── Command parameter schemas ──

    private final Map<String, Map<String, CommandParamSchema>> deviceCommandSchemas = new ConcurrentHashMap<>();

    // ── Board type grouping ──

    /** Maps deviceId → board for quick lookup. */
    private final Map<String, String> deviceToBoard = new ConcurrentHashMap<>();

    /** Maps board → BoardInfo. Boards are auto-discovered from device reports. */
    private final Map<String, BoardInfo> boardTypes = new ConcurrentHashMap<>();

    // ── Event listeners ──

    private final List<CapabilityChangeListener> listeners = new CopyOnWriteArrayList<>();

    // ── Data records ──

    /**
     * Auto-discovered board type, derived from capability reports.
     * All devices with the same board are merged into one entry (union of capabilities).
     *
     * @param board           hardware board identifier from capability snapshot
     * @param label           human-readable label
     * @param inputEvents     all supported input event IDs for this board (union)
     * @param outputCommands  all supported output command IDs for this board (union)
     * @param sectionTypes    all supported section types for this board (union)
     * @param deviceCount     total devices of this board ever seen
     * @param onlineCount     devices of this board currently tracked (have snapshots)
     * @param exampleDeviceIds  up to 3 example device IDs for this board
     * @param lastSeen        when a device of this board was last seen
     */
    public record BoardInfo(
            String board,
            String label,
            Set<String> inputEvents,
            Set<String> outputCommands,
            Set<String> sectionTypes,
            int deviceCount,
            int onlineCount,
            List<String> exampleDeviceIds,
            Instant lastSeen
    ) {}

    public record DeviceCapabilities(
            Set<String> inputEvents,
            Set<String> outputCommands,
            Set<String> sectionTypes,
            String deviceProfileShape,
            int screenW,
            int screenH,
            String inputMode,
            String sizeClass,
            Instant reportedAt
    ) {
        public boolean supportsEvent(String eventId) {
            return inputEvents.contains(eventId);
        }

        public boolean supportsCommand(String command) {
            return outputCommands.contains(command);
        }

        public boolean supportsSection(String sectionType) {
            return sectionTypes.contains(sectionType);
        }

        /** Effective size class, defaulting to {@code "large"}. */
        public String effectiveSizeClass() {
            return sizeClass != null && !sizeClass.isBlank() ? sizeClass : "large";
        }
    }

    public record CommandParamSchema(
            String command,
            String description,
            List<FieldDef> fields
    ) {
        public record FieldDef(
                String name,
                String type,
                boolean required,
                Object defaultValue,
                String description,
                Map<String, Object> constraints
        ) {}
    }

    @FunctionalInterface
    public interface CapabilityChangeListener {
        void onCapabilityChange(String deviceId, DeviceCapabilities caps);
    }

    // ── Registration ──

    public void registerListener(CapabilityChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(CapabilityChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Called when a device reports its capabilities.
     * Learns new capabilities and updates the device snapshot.
     */
    public void onDeviceReport(String deviceId, CapabilitySchema.CapabilitySnapshot caps) {
        Set<String> events = new LinkedHashSet<>();
        Set<String> commands = new LinkedHashSet<>();
        Set<String> sectionTypes = new LinkedHashSet<>();

        // Collect input events from catalog for each reported input name
        if (caps.inputs() != null) {
            for (String inputName : caps.inputs()) {
                for (String eventName : catalog.getInputEvents(inputName)) {
                    events.add(normalizeInputEventId(inputName, eventName));
                }
            }
        }

        // Collect output commands from catalog for each reported output name
        if (caps.outputs() != null) {
            commands.addAll(catalog.getAllCommands(new LinkedHashSet<>(caps.outputs())));
        }

        // Collect section types + enrich with interaction events
        if (caps.display() != null && caps.display().sectionTypes() != null) {
            sectionTypes.addAll(caps.display().sectionTypes());

            for (String stype : caps.display().sectionTypes()) {
                List<SectionTypeCatalog.InteractionEvent> interactionEvents =
                        sectionCatalog != null ? sectionCatalog.getInteractionEvents(stype) : List.of();
                for (SectionTypeCatalog.InteractionEvent ievt : interactionEvents) {
                    events.add(ievt.eventId());
                }
            }
        }

        // Update global catalog
        int newEvents = addAllIfNew(knownEvents, events);
        int newCommands = addAllIfNew(knownCommands, commands);
        int newSections = addAllIfNew(knownSectionTypes, sectionTypes);

        // Update device snapshot
        CapabilitySchema.ScreenInfo screen = caps.screen();
        String sizeClass = caps.display() != null ? caps.display().effectiveSizeClass() : "large";
        DeviceCapabilities deviceCaps = new DeviceCapabilities(
                Collections.unmodifiableSet(events),
                Collections.unmodifiableSet(commands),
                Collections.unmodifiableSet(sectionTypes),
                screen != null ? screen.shape() : null,
                screen != null ? screen.w() : 0,
                screen != null ? screen.h() : 0,
                caps.inputMode(),
                sizeClass,
                Instant.now()
        );
        deviceSnapshots.put(deviceId, deviceCaps);

        // Build command param schemas from catalog
        buildCommandSchemas(deviceId, caps);

        if (newEvents > 0 || newCommands > 0 || newSections > 0) {
            log.info("CapabilityRegistry learned new capabilities: +{} events, +{} commands, +{} sections from device {}",
                    newEvents, newCommands, newSections, deviceId);
        }

        // Notify listeners
        for (CapabilityChangeListener listener : listeners) {
            try {
                listener.onCapabilityChange(deviceId, deviceCaps);
            } catch (Exception e) {
                log.error("CapabilityChangeListener error for device {}: {}", deviceId, e.getMessage());
            }
        }

        // Auto-discover / update board type
        discoverBoardType(deviceId, caps, deviceCaps);
    }

    /**
     * Clear a device's snapshot (on disconnect or cache eviction).
     * Does NOT remove from global catalog — learned capabilities persist.
     */
    public void removeDevice(String deviceId) {
        deviceSnapshots.remove(deviceId);
        deviceCommandSchemas.remove(deviceId);
        markDeviceOffline(deviceId);
        log.info("Removed device snapshot for {}", deviceId);
    }

    // ── Queries: Global catalog ──

    public Set<String> getKnownEvents() {
        return Collections.unmodifiableSet(knownEvents);
    }

    public Set<String> getKnownCommands() {
        return Collections.unmodifiableSet(knownCommands);
    }

    public Set<String> getKnownSectionTypes() {
        return Collections.unmodifiableSet(knownSectionTypes);
    }

    /** Global catalog as a structured map (for API responses). */
    public Map<String, Object> getGlobalCatalog() {
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("events", new ArrayList<>(knownEvents));
        catalog.put("commands", new ArrayList<>(knownCommands));
        catalog.put("sectionTypes", new ArrayList<>(knownSectionTypes));
        catalog.put("interactionEvents", new ArrayList<>(sectionCatalog != null ? sectionCatalog.allInteractionEventIds() : List.of()));
        catalog.put("deviceCount", deviceSnapshots.size());
        return catalog;
    }

    // ── Queries: Per-device ──

    public Optional<DeviceCapabilities> getDeviceSnapshot(String deviceId) {
        return Optional.ofNullable(deviceSnapshots.get(deviceId));
    }

    public boolean hasCapability(String deviceId, String capabilityId) {
        DeviceCapabilities caps = deviceSnapshots.get(deviceId);
        if (caps == null) return false;
        return caps.supportsEvent(capabilityId)
                || caps.supportsCommand(capabilityId)
                || caps.supportsSection(capabilityId);
    }

    public boolean supportsEvent(String deviceId, String eventId) {
        DeviceCapabilities caps = deviceSnapshots.get(deviceId);
        return caps != null && caps.supportsEvent(eventId);
    }

    private String normalizeInputEventId(String inputName, String eventName) {
        if (eventName == null || eventName.isBlank()) {
            return eventName;
        }
        if (eventName.contains(":")) {
            return eventName;
        }
        if (inputName != null && inputName.startsWith("buttons.")) {
            return "input:" + inputName + "." + eventName;
        }
        if ("motion".equals(inputName)) {
            return "input:motion." + eventName;
        }
        if (inputName != null && inputName.startsWith("audio.") && eventName.startsWith("audio.")) {
            return "input:" + inputName + "." + eventName;
        }
        return eventName;
    }

    public boolean supportsCommand(String deviceId, String command) {
        DeviceCapabilities caps = deviceSnapshots.get(deviceId);
        return caps != null && caps.supportsCommand(command);
    }

    public boolean supportsSectionType(String deviceId, String sectionType) {
        DeviceCapabilities caps = deviceSnapshots.get(deviceId);
        return caps != null && caps.supportsSection(sectionType);
    }

    /**
     * Get all section interaction events that this device supports
     * (derived from the section types the device supports).
     */
    public Set<String> getDeviceInteractionEvents(String deviceId) {
        DeviceCapabilities caps = deviceSnapshots.get(deviceId);
        if (caps == null) return Set.of();

        Set<String> events = new LinkedHashSet<>();
        for (String stype : caps.sectionTypes()) {
            for (SectionTypeCatalog.InteractionEvent ievt :
                    sectionCatalog != null ? sectionCatalog.getInteractionEvents(stype) : List.<SectionTypeCatalog.InteractionEvent>of()) {
                events.add(ievt.eventId());
            }
        }
        return Collections.unmodifiableSet(events);
    }

    /**
     * Get a device's physical input definitions (buttons, motion sensors, etc.)
     * enriched from {@link CapabilityCatalog} with display names and descriptions.
     *
     * Physical inputs are simple trigger events — the platform only receives them,
     * there are no configurable parameters. Platform-mediated capabilities
     * (like audio.record) are excluded; see {@link #getDeviceMediaCapabilities(String)}.
     *
     * Events are grouped under their parent input to avoid redundant metadata.
     */
    public List<Map<String, Object>> getDevicePhysicalInputs(String deviceId) {
        DeviceCapabilities caps = deviceSnapshots.get(deviceId);
        if (caps == null) return List.of();

        // Group events by input name
        Map<String, List<Map<String, Object>>> groupedByInput = new LinkedHashMap<>();
        Map<String, CapabilityCatalog.InputDef> inputDefsSeen = new LinkedHashMap<>();

        for (String inputEventId : caps.inputEvents()) {
            for (var entry : catalog.getInputsByName().entrySet()) {
                CapabilityCatalog.InputDef inputDef = entry.getValue();
                if (inputDef.platform()) continue; // skip platform-mediated capabilities
                if (inputDef.events() == null) continue;
                for (String eventName : inputDef.events()) {
                    if (inputDef.isInternalEvent(eventName)) continue; // skip internal/tech events
                    String normalizedId = normalizeInputEventId(entry.getKey(), eventName);
                    if (inputEventId.equals(normalizedId)) {
                        inputDefsSeen.putIfAbsent(entry.getKey(), inputDef);
                        groupedByInput.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                                .add(Map.of(
                                        "eventId", normalizedId,
                                        "eventName", eventName,
                                        "displayName", inputDef.eventDisplayName(eventName)
                                ));
                        break;
                    }
                }
            }
        }

        // Build the result — one entry per input, with grouped events
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : groupedByInput.entrySet()) {
            CapabilityCatalog.InputDef def = inputDefsSeen.get(entry.getKey());
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("inputName", entry.getKey());
            input.put("displayName", def != null && def.displayName() != null ? def.displayName() : entry.getKey());
            input.put("description", def != null && def.description() != null ? def.description() : "");
            input.put("events", entry.getValue());
            result.add(input);
        }
        return result;
    }

    /**
     * Get a device's platform-mediated media capabilities (audio recording, etc.).
     * Unlike simple physical inputs, these are command→response pipelines:
     * the platform sends a command, the device performs the action, and responds
     * with lifecycle events + data.
     *
     * Each entry describes the full capability flow: outbound commands,
     * inbound lifecycle events, and any events usable as state-machine triggers.
     */
    public List<Map<String, Object>> getDeviceMediaCapabilities(String deviceId) {
        DeviceCapabilities caps = deviceSnapshots.get(deviceId);
        if (caps == null) return List.of();

        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : catalog.getInputsByName().entrySet()) {
            CapabilityCatalog.InputDef inputDef = entry.getValue();
            if (!inputDef.platform()) continue; // only platform-mediated capabilities

            // Check if this device actually has this capability
            boolean deviceHasCapability = false;
            if (inputDef.events() != null) {
                for (String eventName : inputDef.events()) {
                    String normalizedId = normalizeInputEventId(entry.getKey(), eventName);
                    if (caps.inputEvents().contains(normalizedId)) {
                        deviceHasCapability = true;
                        break;
                    }
                }
            }
            if (!deviceHasCapability) continue;

            Map<String, Object> cap = new LinkedHashMap<>();
            cap.put("capabilityName", entry.getKey());
            cap.put("displayName", inputDef.displayName() != null ? inputDef.displayName() : entry.getKey());
            cap.put("description", inputDef.description() != null ? inputDef.description() : "");
            cap.put("flowType", "command_response");

            // Outbound commands for this capability (from outputs section)
            List<Map<String, Object>> commands = new ArrayList<>();
            CapabilityCatalog.OutputDef outputDef = catalog.getOutput(entry.getKey()).orElse(null);
            if (outputDef != null && outputDef.commands() != null) {
                for (var cmdEntry : outputDef.commands().entrySet()) {
                    CapabilityCatalog.CommandDef cmdDef = cmdEntry.getValue();
                    if (cmdDef.internal()) continue;
                    Map<String, Object> cmd = new LinkedHashMap<>();
                    cmd.put("commandId", cmdEntry.getKey());
                    cmd.put("displayName", cmdDef.displayName() != null ? cmdDef.displayName() : cmdEntry.getKey());
                    cmd.put("description", cmdDef.description() != null ? cmdDef.description() : "");
                    commands.add(cmd);
                }
            }
            cap.put("commands", commands);

            // Inbound lifecycle events (excluding internal/tech events)
            List<Map<String, Object>> events = new ArrayList<>();
            if (inputDef.events() != null) {
                for (String eventName : inputDef.events()) {
                    if (inputDef.isInternalEvent(eventName)) continue; // skip internal/tech events
                    String normalizedId = normalizeInputEventId(entry.getKey(), eventName);
                    if (caps.inputEvents().contains(normalizedId)) {
                        Map<String, Object> evt = new LinkedHashMap<>();
                        evt.put("eventId", normalizedId);
                        evt.put("eventName", eventName);
                        evt.put("displayName", inputDef.eventDisplayName(eventName));
                        events.add(evt);
                    }
                }
            }
            cap.put("events", events);

            // Trigger events: all business-facing events are usable as state-machine triggers.
            cap.put("triggerEvents", new ArrayList<>(events));

            result.add(cap);
        }
        return result;
    }

    /**
     * Build a complete input event catalog for a device, optionally filtered by
     * section types. This is the single source of truth for event listings —
     * consumed by the board-type events API, debug SSE catalog, and workflow
     * trigger configuration.
     *
     * @param deviceId           the device to query
     * @param sectionTypesFilter if non-empty, only include interaction events
     *                           from these section types; if empty, include all
     *                           device-supported interaction events
     * @return structured map with keys: sectionEvents, physicalInputs,
     *         mediaCapabilities, availableTriggers
     */
    public Map<String, Object> buildEventCatalog(String deviceId, Set<String> sectionTypesFilter) {
        Map<String, Object> catalog2 = new LinkedHashMap<>();
        catalog2.put("deviceId", deviceId);

        // Physical input events (buttons, motion) — always included
        List<Map<String, Object>> physicalInputs = getDevicePhysicalInputs(deviceId);
        catalog2.put("physicalInputs", physicalInputs);

        // Platform-mediated media capabilities (audio recording, etc.)
        List<Map<String, Object>> mediaCapabilities = getDeviceMediaCapabilities(deviceId);
        catalog2.put("mediaCapabilities", mediaCapabilities);

        // Section interaction events — optionally filtered, uses EventDefinition for full IDs
        List<Map<String, Object>> sectionEvents = new ArrayList<>();
        Set<String> filterTypes = sectionTypesFilter != null && !sectionTypesFilter.isEmpty()
                ? sectionTypesFilter
                : getBoardInfoForDevice(deviceId)
                        .map(info -> info.sectionTypes())
                        .orElse(Set.of());

        for (EventDefinition def : getDeviceEventDefinitions(deviceId)) {
            if (!def.isPublicTrigger() || def.kind() != com.zwbd.agentnexus.sdui.event.EventDefinition.EventKind.SECTION) continue;
            String source = def.sourceCapability();
            if (!filterTypes.isEmpty() && !filterTypes.contains(source)) continue;
            Map<String, Object> evt = new LinkedHashMap<>();
            evt.put("eventId", def.eventId());
            evt.put("displayName", def.displayName() != null ? def.displayName() : def.eventId());
            evt.put("description", def.description() != null ? def.description() : "");
            evt.put("sectionType", source);
            evt.put("category", def.category().name());
            evt.put("source", source);
            sectionEvents.add(evt);
        }
        catalog2.put("sectionEvents", sectionEvents);

        // Build merged availableTriggers (deduped, ready for dropdown)
        List<Map<String, Object>> availableTriggers = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Section interaction events
        for (Map<String, Object> evt : sectionEvents) {
            String eid = string(evt.get("eventId"));
            if (!eid.isBlank() && seen.add(eid)) {
                availableTriggers.add(Map.of(
                        "eventId", eid,
                        "displayName", string(evt.get("displayName")),
                        "category", string(evt.get("category")),
                        "source", string(evt.get("source"))
                ));
            }
        }
        // Physical input events (flatten grouped structure)
        for (Map<String, Object> input : physicalInputs) {
            String inputName = string(input.get("inputName"));
            String inputLabel = string(input.get("displayName"));
            if (input.get("events") instanceof List<?> evts) {
                for (Object e : evts) {
                    if (e instanceof Map<?, ?> em) {
                        String eid = string(em.get("eventId"));
                        if (!eid.isBlank() && seen.add(eid)) {
                            availableTriggers.add(Map.of(
                                    "eventId", eid,
                                    "displayName", inputLabel + " · " + string(em.get("displayName")),
                                    "category", "USER_INTERACTION",
                                    "source", inputName
                            ));
                        }
                    }
                }
            }
        }
        // Media trigger events
        for (Map<String, Object> mc : mediaCapabilities) {
            String capName = string(mc.get("capabilityName"));
            String capLabel = string(mc.get("displayName"));
            if (mc.get("triggerEvents") instanceof List<?> triggers) {
                for (Object t : triggers) {
                    if (t instanceof Map<?, ?> tm) {
                        String eid = string(tm.get("eventId"));
                        if (!eid.isBlank() && seen.add(eid)) {
                            availableTriggers.add(Map.of(
                                    "eventId", eid,
                                    "displayName", capLabel + " · " + string(tm.get("displayName")),
                                    "category", "SYSTEM_EVENT",
                                    "source", capName
                            ));
                        }
                    }
                }
            }
        }
        // System events from EventRegistry (timer, cron)
        if (eventRegistry != null) {
            for (Map<String, String> opt : eventRegistry.getFlatEventOptions()) {
                String eid = opt.get("value");
                if (eid != null && seen.add(eid)) {
                    availableTriggers.add(Map.of(
                            "eventId", eid,
                            "displayName", opt.getOrDefault("label", eid),
                            "category", opt.getOrDefault("category", ""),
                            "source", opt.getOrDefault("source", "")
                    ));
                }
            }
        }
        catalog2.put("availableTriggers", availableTriggers);

        return catalog2;
    }

    /**
     * Resolve a raw binary input event (from UI3 msgType=9) to its namespaced
     * event ID (e.g. {@code short_press + kind=4 + nodeId=pwr} →
     * {@code input:buttons.pwr.short_press}).
     *
     * This bridges the gap between raw device binary frames and the namespaced
     * event IDs used in state-machine transition configuration.
     *
     * @param deviceId     the device that sent the event (used to verify capability)
     * @param rawEventName the raw event name from TLV 122 (e.g. "short_press")
     * @param eventKind    the event kind from TLV 120 (e.g. 4 for BUTTON)
     * @param nodeId       the node/button ID from TLV 121 (e.g. "pwr")
     * @return the resolved namespaced event ID, or the rawEventName if unresolvable
     */
    public String resolveInputEventId(String deviceId, String rawEventName, int eventKind, String nodeId) {
        if (rawEventName == null || rawEventName.isBlank()) return rawEventName;

        // Check if already a namespaced ID
        if (rawEventName.contains(":")) return rawEventName;

        DeviceCapabilities caps = deviceSnapshots.get(deviceId);
        if (caps == null) return rawEventName;

        // Iterate capability catalog inputs to find a match
        for (var entry : catalog.getInputsByName().entrySet()) {
            CapabilityCatalog.InputDef inputDef = entry.getValue();
            if (inputDef.events() == null) continue;
            // Skip platform-mediated (handled separately)
            if (inputDef.platform()) continue;

            // Match by eventKind if the input has one
            if (inputDef.eventKind() != null && inputDef.eventKind() != eventKind) continue;

            // For button inputs, additionally match by nodeId suffix
            String inputName = entry.getKey();
            if (inputName.startsWith("buttons.")) {
                String buttonId = inputName.substring("buttons.".length());
                if (nodeId != null && !nodeId.isBlank() && !buttonId.equals(nodeId)) continue;
            }

            // Check if the raw event name is in this input's events list
            if (!inputDef.events().contains(rawEventName)) continue;

            // Build the normalized ID
            String normalizedId = normalizeInputEventId(inputName, rawEventName);
            // Verify the device actually supports this event
            if (caps.inputEvents().contains(normalizedId)) {
                return normalizedId;
            }
        }

        return rawEventName;
    }

    // ── Queries: Command schemas ──

    public Optional<CommandParamSchema> getCommandSchema(String deviceId, String command) {
        Map<String, CommandParamSchema> schemas = deviceCommandSchemas.get(deviceId);
        return schemas != null ? Optional.ofNullable(schemas.get(command)) : Optional.empty();
    }

    public Map<String, CommandParamSchema> getCommandSchemas(String deviceId) {
        Map<String, CommandParamSchema> schemas = deviceCommandSchemas.get(deviceId);
        return schemas != null ? Collections.unmodifiableMap(schemas) : Map.of();
    }

    // ── Queries: EventDefinition-based (NEW — delegates to EventRegistry) ──

    /**
     * Get the {@link EventDefinition} for a given event ID (namespaced or legacy).
     */
    public Optional<EventDefinition> getEventDefinition(String eventId) {
        if (eventRegistry == null) return Optional.empty();
        String resolved = eventRegistry.resolveEventId(eventId);
        return eventRegistry.getInboundEvent(resolved);
    }

    /**
     * Get all inbound event definitions for a given device,
     * filtered to events the device actually supports.
     */
    public List<EventDefinition> getDeviceEventDefinitions(String deviceId) {
        if (eventRegistry == null) return List.of();
        DeviceCapabilities caps = deviceSnapshots.get(deviceId);
        if (caps == null) return List.of();

        List<EventDefinition> result = new ArrayList<>();
        for (EventDefinition def : eventRegistry.getAllInboundEvents()) {
            if (caps.supportsEvent(def.eventId())
                    || caps.inputEvents().contains(def.eventId())) {
                result.add(def);
            }
        }
        return result;
    }

    /**
     * Get all outbound event (command) definitions for a given device.
     */
    public List<EventDefinition> getDeviceCommandDefinitions(String deviceId) {
        if (eventRegistry == null) return List.of();
        DeviceCapabilities caps = deviceSnapshots.get(deviceId);
        if (caps == null) return List.of();

        List<EventDefinition> result = new ArrayList<>();
        for (EventDefinition def : eventRegistry.getAllOutboundEvents()) {
            if (caps.supportsCommand(def.eventId())) {
                result.add(def);
            }
        }
        return result;
    }

    // ── Suggestions ──

    /**
     * Find similar capabilities for a given capability ID (fuzzy match).
     */
    public List<String> suggestSimilar(String capabilityId) {
        List<String> suggestions = new ArrayList<>();
        String normalized = capabilityId.replace(".", "").replace("_", "").toLowerCase();

        for (String cmd : knownCommands) {
            String norm = cmd.replace(".", "").replace("_", "").toLowerCase();
            if (editDistance(normalized, norm) <= 3) {
                suggestions.add(cmd);
            }
        }
        for (String evt : knownEvents) {
            String norm = evt.replace(".", "").replace("_", "").toLowerCase();
            if (editDistance(normalized, norm) <= 3) {
                suggestions.add(evt);
            }
        }
        for (String st : knownSectionTypes) {
            String norm = st.replace(".", "").replace("_", "").toLowerCase();
            if (editDistance(normalized, norm) <= 3) {
                suggestions.add(st);
            }
        }
        return suggestions;
    }

    // ── Statistics ──

    public int deviceCount() {
        return deviceSnapshots.size();
    }

    public Map<String, Object> stats() {
        return Map.of(
                "devicesTracked", deviceSnapshots.size(),
                "knownEvents", knownEvents.size(),
                "knownCommands", knownCommands.size(),
                "knownSectionTypes", knownSectionTypes.size(),
                "boardTypes", boardTypes.size()
        );
    }

    // ── Board type queries ──

    /**
     * Get all auto-discovered board types.
     */
    public List<BoardInfo> getBoardTypes() {
        return List.copyOf(boardTypes.values());
    }

    /**
     * Get a specific board type by its board identifier.
     */
    public Optional<BoardInfo> getBoardType(String board) {
        return Optional.ofNullable(boardTypes.get(board));
    }

    /**
     * Get the board identifier for a specific device.
     */
    public Optional<String> getBoardForDevice(String deviceId) {
        return Optional.ofNullable(deviceToBoard.get(deviceId));
    }

    /**
     * Get the BoardInfo for a specific device.
     */
    public Optional<BoardInfo> getBoardInfoForDevice(String deviceId) {
        String board = deviceToBoard.get(deviceId);
        return board != null ? Optional.ofNullable(boardTypes.get(board)) : Optional.empty();
    }

    /**
     * Get all board types as a structured list (for API responses).
     */
    public List<Map<String, Object>> getBoardTypesAsList() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BoardInfo info : boardTypes.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("board", info.board());
            entry.put("label", info.label());
            entry.put("inputEvents", new ArrayList<>(info.inputEvents()));
            entry.put("outputCommands", new ArrayList<>(info.outputCommands()));
            entry.put("sectionTypes", new ArrayList<>(info.sectionTypes()));
            entry.put("deviceCount", info.deviceCount());
            entry.put("onlineCount", info.onlineCount());
            entry.put("exampleDeviceIds", info.exampleDeviceIds());
            entry.put("lastSeen", info.lastSeen().toString());
            result.add(entry);
        }
        return result;
    }

    // ── Board type auto-discovery ──

    /**
     * Discover / update the board type for a newly reported device.
     * All devices with the same board are merged into one entry (union of capabilities).
     */
    private void discoverBoardType(String deviceId, CapabilitySchema.CapabilitySnapshot caps,
                                   DeviceCapabilities deviceCaps) {
        String board = caps.board();
        if (board == null || board.isBlank()) {
            log.warn("Device {} reported no board identifier, skipping board type discovery", deviceId);
            return;
        }

        Set<String> events = deviceCaps.inputEvents();
        Set<String> commands = deviceCaps.outputCommands();
        Set<String> sections = deviceCaps.sectionTypes();

        // Check if this device was previously associated with a different board
        String oldBoard = deviceToBoard.get(deviceId);

        BoardInfo existing = boardTypes.get(board);
        if (existing != null) {
            // Merge capabilities into existing board entry (union)
            Set<String> mergedEvents = new LinkedHashSet<>(existing.inputEvents());
            int beforeEvents = mergedEvents.size();
            mergedEvents.addAll(events);
            Set<String> mergedCommands = new LinkedHashSet<>(existing.outputCommands());
            int beforeCommands = mergedCommands.size();
            mergedCommands.addAll(commands);
            Set<String> mergedSections = new LinkedHashSet<>(existing.sectionTypes());
            int beforeSections = mergedSections.size();
            mergedSections.addAll(sections);

            List<String> exampleIds = new ArrayList<>(existing.exampleDeviceIds());
            if (!exampleIds.contains(deviceId) && exampleIds.size() < 3) {
                exampleIds.add(deviceId);
            }

            BoardInfo updated = new BoardInfo(
                    board, existing.label(),
                    Set.copyOf(mergedEvents), Set.copyOf(mergedCommands), Set.copyOf(mergedSections),
                    existing.deviceCount(), existing.onlineCount(),
                    exampleIds, Instant.now()
            );
            boardTypes.put(board, updated);

            if (mergedEvents.size() > beforeEvents || mergedCommands.size() > beforeCommands || mergedSections.size() > beforeSections) {
                log.info("Board type expanded (capabilities merged): board={} events={}→{} commands={}→{}",
                        board, beforeEvents, mergedEvents.size(), beforeCommands, mergedCommands.size());
            }
        } else {
            // New board type
            String label = board;
            BoardInfo newBoard = new BoardInfo(
                    board, label,
                    Set.copyOf(events), Set.copyOf(commands), Set.copyOf(sections),
                    0, 0, new ArrayList<>(), Instant.now()
            );
            boardTypes.put(board, newBoard);
            log.info("New board type discovered: board={} events={} commands={} sections={}",
                    board, events.size(), commands.size(), sections.size());
        }

        // Update device-to-board mapping
        if (oldBoard != null && !oldBoard.equals(board)) {
            // Device moved to a different board — update old board counts
            updateBoardStats(oldBoard, -1, 0);
            BoardInfo oldInfo = boardTypes.get(oldBoard);
            if (oldInfo != null && oldInfo.deviceCount() <= 1) {
                boardTypes.remove(oldBoard);
                log.info("Removed orphaned board type: board={}", oldBoard);
            }
        }
        deviceToBoard.put(deviceId, board);

        // Update board stats
        BoardInfo info = boardTypes.get(board);
        int deltaCount = (oldBoard == null || !oldBoard.equals(board)) ? 1 : 0;
        updateBoardStats(board, deltaCount, 0);

        // Update example device IDs
        int newDeviceCount = (info != null ? info.deviceCount() : 0) + deltaCount;
        List<String> exampleIds = new ArrayList<>(info != null ? info.exampleDeviceIds() : List.of());
        if (!exampleIds.contains(deviceId) && exampleIds.size() < 3) {
            exampleIds.add(deviceId);
        }

        if (info != null) {
            boardTypes.put(board, new BoardInfo(
                    info.board(), info.label(),
                    info.inputEvents(), info.outputCommands(), info.sectionTypes(),
                    newDeviceCount, info.onlineCount(),
                    exampleIds, Instant.now()
            ));
        }
    }

    /**
     * Called when a device disconnects to update board online counts.
     */
    public void markDeviceOffline(String deviceId) {
        String board = deviceToBoard.get(deviceId);
        if (board != null) {
            updateBoardStats(board, 0, -1);
        }
    }

    private void updateBoardStats(String board, int countDelta, int onlineDelta) {
        BoardInfo info = boardTypes.get(board);
        if (info == null) return;
        BoardInfo updated = new BoardInfo(
                info.board(), info.label(),
                info.inputEvents(), info.outputCommands(), info.sectionTypes(),
                Math.max(0, info.deviceCount() + countDelta),
                Math.max(0, info.onlineCount() + onlineDelta),
                info.exampleDeviceIds(), info.lastSeen()
        );
        boardTypes.put(board, updated);
    }

    // ── Internal ──

    private int addAllIfNew(Set<String> target, Set<String> source) {
        int count = 0;
        for (String item : source) {
            if (target.add(item)) count++;
        }
        return count;
    }

    private void buildCommandSchemas(String deviceId, CapabilitySchema.CapabilitySnapshot caps) {
        Map<String, CommandParamSchema> schemas = new ConcurrentHashMap<>();
        if (caps.outputs() != null) {
            for (String outputName : caps.outputs()) {
                CapabilityCatalog.OutputDef outputDef = catalog.getOutput(outputName).orElse(null);
                if (outputDef == null || outputDef.commands() == null) continue;
                for (CapabilityCatalog.CommandDef cmdDef : outputDef.commands().values()) {
                    if (cmdDef.internal()) continue;
                    List<CommandParamSchema.FieldDef> fields = new ArrayList<>();
                    for (var entry : cmdDef.params().entrySet()) {
                        CapabilityCatalog.FieldSchema fs = entry.getValue();
                        Map<String, Object> constraints = new LinkedHashMap<>();
                        if (fs.values() != null) constraints.put("enum", fs.values());
                        if (fs.min() != null) constraints.put("min", fs.min());
                        if (fs.max() != null) constraints.put("max", fs.max());
                        fields.add(new CommandParamSchema.FieldDef(
                                entry.getKey(), fs.type(), fs.required(),
                                fs.defaultValue(),
                                fs.description() != null ? fs.description() : entry.getKey(),
                                constraints));
                    }
                    schemas.put(cmdDef.command(), new CommandParamSchema(
                            cmdDef.command(), outputName, fields));
                }
            }
        }
        deviceCommandSchemas.put(deviceId, schemas);
        if (!schemas.isEmpty()) {
            log.debug("Built {} command schemas for device {}", schemas.size(), deviceId);
        }
    }

    private int editDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? dp[i - 1][j - 1]
                        : 1 + Math.min(dp[i - 1][j - 1], Math.min(dp[i - 1][j], dp[i][j - 1]));
            }
        }
        return dp[a.length()][b.length()];
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
