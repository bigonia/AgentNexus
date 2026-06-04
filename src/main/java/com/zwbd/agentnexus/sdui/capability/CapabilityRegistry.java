package com.zwbd.agentnexus.sdui.capability;

import com.zwbd.agentnexus.sdui.event.EventDefinition;
import com.zwbd.agentnexus.sdui.event.EventRegistry;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

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
 * 5. Device types: auto-discovered from capability fingerprints (for workflow targeting)
 */
@Slf4j
@Component
public class CapabilityRegistry {

    private final CapabilityCatalog catalog;
    private EventRegistry eventRegistry;

    public CapabilityRegistry(CapabilityCatalog catalog) {
        this.catalog = catalog;
    }

    /** Setter injection to avoid circular dependency. */
    @Autowired(required = false)
    public void setEventRegistry(EventRegistry eventRegistry) {
        this.eventRegistry = eventRegistry;
    }

    // ── Global catalog (union of all device reports) ──

    private final Set<String> knownEvents = new CopyOnWriteArraySet<>();
    private final Set<String> knownCommands = new CopyOnWriteArraySet<>();
    private final Set<String> knownSectionTypes = new CopyOnWriteArraySet<>();

    // ── Per-device snapshots ──

    private final Map<String, DeviceCapabilities> deviceSnapshots = new ConcurrentHashMap<>();

    // ── Command parameter schemas ──

    private final Map<String, Map<String, CommandParamSchema>> deviceCommandSchemas = new ConcurrentHashMap<>();

    // ── Device type auto-discovery ──

    /** Maps deviceId → typeKey for quick lookup. */
    private final Map<String, String> deviceToType = new ConcurrentHashMap<>();

    /** Maps typeKey → DeviceTypeInfo. Types are auto-discovered from device reports. */
    private final Map<String, DeviceTypeInfo> deviceTypes = new ConcurrentHashMap<>();

    // ── Event listeners ──

    private final List<CapabilityChangeListener> listeners = new CopyOnWriteArrayList<>();

    // ── Data records ──

    /**
     * Auto-discovered device type, derived from capability reports.
     * Types naturally emerge as devices connect — no manual configuration needed.
     *
     * @param key             unique type key: "board:{board}:{variant}" or "fp:{fingerprint}"
     * @param board           hardware board identifier from capability snapshot (may be null)
     * @param label           human-readable label with auto-generated suffixes
     * @param labelSource     "board" if derived from board field, "fingerprint" otherwise
     * @param hasVariants     true if this board has multiple capability variants
     * @param inputEvents     all supported input event IDs for this type
     * @param outputCommands  all supported output command IDs for this type
     * @param sectionTypes    all supported section types for this type
     * @param deviceCount     total devices of this type ever seen
     * @param onlineCount     devices of this type currently tracked (have snapshots)
     * @param exampleDeviceIds  up to 3 example device IDs for this type
     * @param lastSeen        when a device of this type was last seen
     */
    public record DeviceTypeInfo(
            String key,
            String board,
            String label,
            String labelSource,
            boolean hasVariants,
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
                events.addAll(catalog.getInputEvents(inputName));
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
                        SectionTypeCatalog.getInteractionEvents(stype);
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

        // Auto-discover / update device type
        discoverDeviceType(deviceId, caps, deviceCaps);
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
        catalog.put("interactionEvents", new ArrayList<>(SectionTypeCatalog.allInteractionEventIds()));
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
                    SectionTypeCatalog.getInteractionEvents(stype)) {
                events.add(ievt.eventId());
            }
        }
        return Collections.unmodifiableSet(events);
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
                "deviceTypes", deviceTypes.size()
        );
    }

    // ── Device type queries ──

    /**
     * Get all auto-discovered device types.
     */
    public List<DeviceTypeInfo> getDeviceTypes() {
        return List.copyOf(deviceTypes.values());
    }

    /**
     * Get a specific device type by its key.
     */
    public Optional<DeviceTypeInfo> getDeviceType(String key) {
        return Optional.ofNullable(deviceTypes.get(key));
    }

    /**
     * Get the device type for a specific device.
     */
    public Optional<DeviceTypeInfo> getDeviceTypeForDevice(String deviceId) {
        String typeKey = deviceToType.get(deviceId);
        return typeKey != null ? Optional.ofNullable(deviceTypes.get(typeKey)) : Optional.empty();
    }

    /**
     * Get all device types as a structured list (for API responses).
     */
    public List<Map<String, Object>> getDeviceTypesAsList() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DeviceTypeInfo info : deviceTypes.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", info.key());
            entry.put("board", info.board());
            entry.put("label", info.label());
            entry.put("labelSource", info.labelSource());
            entry.put("hasVariants", info.hasVariants());
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

    // ── Device type auto-discovery ──

    /**
     * Discover / update the device type for a newly reported device.
     * Types are auto-generated from capability fingerprints and board identifiers.
     */
    private void discoverDeviceType(String deviceId, CapabilitySchema.CapabilitySnapshot caps,
                                    DeviceCapabilities deviceCaps) {
        String board = caps.board();
        Set<String> events = deviceCaps.inputEvents();
        Set<String> commands = deviceCaps.outputCommands();
        Set<String> sections = deviceCaps.sectionTypes();

        String fingerprint = computeFingerprint(events, commands, sections);

        // Check if this device was previously associated with a different type
        String oldTypeKey = deviceToType.get(deviceId);

        // Find existing type by fingerprint (exact capability match)
        String existingKey = null;
        for (var entry : deviceTypes.entrySet()) {
            String fp = computeFingerprint(entry.getValue().inputEvents(),
                    entry.getValue().outputCommands(), entry.getValue().sectionTypes());
            if (fingerprint.equals(fp)) {
                existingKey = entry.getKey();
                break;
            }
        }

        String typeKey;
        if (existingKey != null) {
            typeKey = existingKey;
        } else {
            // New capability fingerprint — create a new type
            typeKey = generateTypeKey(board, fingerprint);
            String label = generateTypeLabel(board, fingerprint);
            DeviceTypeInfo newType = new DeviceTypeInfo(
                    typeKey, board, label, board != null ? "board" : "fingerprint",
                    false, Set.copyOf(events), Set.copyOf(commands), Set.copyOf(sections),
                    0, 0, new ArrayList<>(), Instant.now()
            );
            deviceTypes.put(typeKey, newType);
            log.info("New device type discovered: key={} label={} board={} events={} commands={}",
                    typeKey, label, board, events.size(), commands.size());
        }

        // Update device-to-type mapping
        if (oldTypeKey != null && !oldTypeKey.equals(typeKey)) {
            // Device changed types (firmware upgrade?) — update old type counts
            updateTypeStats(oldTypeKey, -1, 0);
        }
        deviceToType.put(deviceId, typeKey);

        // Update type stats
        DeviceTypeInfo info = deviceTypes.get(typeKey);
        int deltaCount = (oldTypeKey == null || !oldTypeKey.equals(typeKey)) ? 1 : 0;
        updateTypeStats(typeKey, deltaCount, 0);

        // Update example device IDs
        int newDeviceCount = info.deviceCount() + deltaCount;
        List<String> exampleIds = new ArrayList<>(info.exampleDeviceIds());
        if (!exampleIds.contains(deviceId) && exampleIds.size() < 3) {
            exampleIds.add(deviceId);
        }

        // If board-based, check for variants and regenerate labels
        boolean hasVariants = false;
        if (board != null && !board.isBlank()) {
            long boardVariantCount = deviceTypes.values().stream()
                    .filter(t -> board.equals(t.board())).count();
            hasVariants = boardVariantCount > 1;
        }

        deviceTypes.put(typeKey, new DeviceTypeInfo(
                info.key(), info.board(), info.label(), info.labelSource(),
                hasVariants, info.inputEvents(), info.outputCommands(), info.sectionTypes(),
                newDeviceCount, info.onlineCount(), exampleIds, Instant.now()
        ));

        // Regenerate labels for all variants of this board (to add suffixes)
        if (board != null && !board.isBlank() && hasVariants) {
            regenerateBoardLabels(board);
        }
    }

    /**
     * Called when a device disconnects to update type online counts.
     */
    public void markDeviceOffline(String deviceId) {
        String typeKey = deviceToType.get(deviceId);
        if (typeKey != null) {
            updateTypeStats(typeKey, 0, -1);
        }
    }

    private void updateTypeStats(String typeKey, int countDelta, int onlineDelta) {
        DeviceTypeInfo info = deviceTypes.get(typeKey);
        if (info == null) return;
        deviceTypes.put(typeKey, new DeviceTypeInfo(
                info.key(), info.board(), info.label(), info.labelSource(),
                info.hasVariants(), info.inputEvents(), info.outputCommands(), info.sectionTypes(),
                Math.max(0, info.deviceCount() + countDelta),
                Math.max(0, info.onlineCount() + onlineDelta),
                info.exampleDeviceIds(), info.lastSeen()
        ));
    }

    /**
     * Compute a stable capability fingerprint from sorted event/command/section sets.
     */
    private String computeFingerprint(Set<String> events, Set<String> commands, Set<String> sections) {
        String canonical = events.stream().sorted().collect(Collectors.joining(","))
                + "|" + commands.stream().sorted().collect(Collectors.joining(","))
                + "|" + sections.stream().sorted().collect(Collectors.joining(","));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(canonical.hashCode());
        }
    }

    /**
     * Generate a stable type key from board + fingerprint.
     */
    private String generateTypeKey(String board, String fingerprint) {
        if (board != null && !board.isBlank()) {
            // Count existing variants for this board to assign variant index
            long existing = deviceTypes.values().stream()
                    .filter(t -> board.equals(t.board())).count();
            return "board:" + board + ":" + existing;
        }
        return "fp:" + fingerprint;
    }

    /**
     * Generate a human-readable label for a device type.
     */
    private String generateTypeLabel(String board, String fingerprint) {
        if (board != null && !board.isBlank()) {
            return board; // base label, suffix added later if variants exist
        }
        // Fallback: describe by features
        return "未知设备";
    }

    /**
     * Regenerate labels for all variants of a board after a new variant is discovered.
     * The variant with the most capabilities gets a special suffix; others describe
     * what they're missing or what's unique.
     */
    private void regenerateBoardLabels(String board) {
        List<DeviceTypeInfo> variants = deviceTypes.values().stream()
                .filter(t -> board.equals(t.board()))
                .sorted(Comparator.comparingInt((DeviceTypeInfo t) ->
                        t.inputEvents().size() + t.outputCommands().size() + t.sectionTypes().size()).reversed())
                .toList();

        if (variants.size() <= 1) return;

        // First (most capable) is the reference
        DeviceTypeInfo reference = variants.get(0);
        for (int i = 0; i < variants.size(); i++) {
            DeviceTypeInfo info = variants.get(i);
            String newLabel;
            if (i == 0) {
                newLabel = board + " (全功能版)";
            } else {
                String feature = describeFeatureDiff(info, reference);
                newLabel = board + " (" + feature + ")";
            }
            deviceTypes.put(info.key(), new DeviceTypeInfo(
                    info.key(), info.board(), newLabel, info.labelSource(),
                    true, info.inputEvents(), info.outputCommands(), info.sectionTypes(),
                    info.deviceCount(), info.onlineCount(), info.exampleDeviceIds(), info.lastSeen()
            ));
        }
    }

    /**
     * Describe the most notable capability difference between a variant and reference.
     */
    private String describeFeatureDiff(DeviceTypeInfo variant, DeviceTypeInfo reference) {
        // Check for major capability group differences
        boolean refBoot = hasInput(reference, "buttons.boot");
        boolean varBoot = hasInput(variant, "buttons.boot");
        boolean refPlus = hasInput(reference, "buttons.plus");
        boolean varPlus = hasInput(variant, "buttons.plus");
        boolean refMotion = hasInput(reference, "motion");
        boolean varMotion = hasInput(variant, "motion");
        boolean refAudio = hasInput(reference, "audio.record");
        boolean varAudio = hasInput(variant, "audio.record");
        boolean refRgb = hasOutput(reference, "rgb.effect");
        boolean varRgb = hasOutput(variant, "rgb.effect");

        List<String> missing = new ArrayList<>();
        if (refPlus && !varPlus) missing.add("单按钮");
        if (refMotion && !varMotion) missing.add("无运动");
        if (refAudio && !varAudio) missing.add("无语音");
        if (refRgb && !varRgb) missing.add("无灯光");

        if (!missing.isEmpty()) {
            return String.join("/", missing.stream().limit(2).toList());
        }

        // Fewer events overall
        int totalRef = reference.inputEvents().size() + reference.outputCommands().size();
        int totalVar = variant.inputEvents().size() + variant.outputCommands().size();
        if (totalVar < totalRef) {
            return "精简版 (" + totalVar + " 能力)";
        }
        return "变体";
    }

    private boolean hasInput(DeviceTypeInfo info, String inputName) {
        return info.inputEvents().stream().anyMatch(e -> e.contains(inputName));
    }

    private boolean hasOutput(DeviceTypeInfo info, String outputName) {
        return info.outputCommands().stream().anyMatch(c -> c.startsWith(outputName));
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
}
