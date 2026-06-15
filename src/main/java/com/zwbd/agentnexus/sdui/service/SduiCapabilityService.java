package com.zwbd.agentnexus.sdui.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.capability.CapabilityCatalog;
import com.zwbd.agentnexus.sdui.capability.CapabilityRegistry;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySnapshotParser;
import com.zwbd.agentnexus.sdui.protocol.SduiProtocolConstants;
import com.zwbd.agentnexus.sdui.protocol.catalog.CommandSpec;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceProtocolCatalog;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SduiCapabilityService {

    private final SduiDeviceRepository deviceRepository;
    private final ObjectMapper objectMapper;
    private final CommandSchemaRegistry schemaRegistry;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityCatalog catalog;
    private final DeviceProtocolCatalog protocolCatalog;
    private final Map<String, CapabilitySchema.CapabilitySnapshot> cache = new ConcurrentHashMap<>();

    public SduiCapabilityService(SduiDeviceRepository deviceRepository,
                                  ObjectMapper objectMapper,
                                  CommandSchemaRegistry schemaRegistry,
                                  CapabilityRegistry capabilityRegistry,
                                  CapabilityCatalog catalog,
                                  DeviceProtocolCatalog protocolCatalog) {
        this.deviceRepository = deviceRepository;
        this.objectMapper = objectMapper;
        this.schemaRegistry = schemaRegistry;
        this.capabilityRegistry = capabilityRegistry;
        this.catalog = catalog;
        this.protocolCatalog = protocolCatalog;
    }

    @Transactional
    public void onCapabilitiesReport(String deviceId, CapabilitySchema.CapabilitySnapshot caps, String rawJson) {
        cache.put(deviceId, caps);
        schemaRegistry.loadFromCapabilities(deviceId, caps);
        SduiDevice device = deviceRepository.findById(deviceId).orElse(null);
        if (device != null) {
            device.setCapabilitiesSnapshot(rawJson);
            device.setCapabilitiesSchemaVersion(caps.schemaVersion());
            device.setCapabilitiesReportedAt(LocalDateTime.now());
            deviceRepository.save(device);
        }
        capabilityRegistry.onDeviceReport(deviceId, caps);
        log.info("Capabilities stored for device {}, hasDisplay={}", deviceId, caps.display() != null);
    }

    public Optional<CapabilitySchema.CapabilitySnapshot> getCapabilities(String deviceId) {
        CapabilitySchema.CapabilitySnapshot cached = cache.get(deviceId);
        if (cached != null) {
            ensureRegistrySnapshot(deviceId, cached);
            return Optional.of(cached);
        }

        SduiDevice device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null || device.getCapabilitiesSnapshot() == null) return Optional.empty();
        try {
            CapabilitySchema.CapabilitySnapshot caps = CapabilitySnapshotParser.parse(
                    device.getCapabilitiesSnapshot(), objectMapper);
            cache.put(deviceId, caps);
            schemaRegistry.loadFromCapabilities(deviceId, caps);
            ensureRegistrySnapshot(deviceId, caps);
            return Optional.of(caps);
        } catch (Exception e) {
            log.error("Failed to parse capabilities for device {}", deviceId, e);
            return Optional.empty();
        }
    }

    public boolean supportsSectionType(String deviceId, String sectionType) {
        return getCapabilities(deviceId)
                .map(CapabilitySchema.CapabilitySnapshot::display)
                .map(d -> d.supportsType(sectionType))
                .orElse(false);
    }

    public boolean supportsSectionLayout(String deviceId, String layout) {
        return getCapabilities(deviceId)
                .map(CapabilitySchema.CapabilitySnapshot::display)
                .map(d -> d.supportsLayout(layout))
                .orElse(false);
    }

    public int getSectionLimit(String deviceId, String limitKey) {
        return getCapabilities(deviceId)
                .map(caps -> {
                    String sizeClass = caps.display() != null ? caps.display().effectiveSizeClass() : "large";
                    return catalog.getDisplayLimits(sizeClass).getOrDefault(limitKey, 0);
                })
                .orElse(0);
    }

    public Optional<CapabilitySchema.DisplayInfo> getSectionCapability(String deviceId) {
        return getCapabilities(deviceId)
                .map(CapabilitySchema.CapabilitySnapshot::display);
    }

    public Set<String> getAvailableCommands(String deviceId) {
        Optional<CapabilitySchema.CapabilitySnapshot> capsOpt = getCapabilities(deviceId);
        if (capsOpt.isEmpty()) {
            return Set.of();
        }
        return catalog.getAllCommands(new LinkedHashSet<>(capsOpt.get().outputs()));
    }

    public String getCommandDisplayName(String command) {
        return protocolCatalog.command(command)
                .map(CommandSpec::id)
                .orElse(command);
    }

    public void clearCapabilitiesCache(String deviceId) {
        cache.remove(deviceId);
        schemaRegistry.clearDeviceSchemas(deviceId);
        capabilityRegistry.removeDevice(deviceId);
        log.info("Capabilities cache cleared for device {}", deviceId);
    }

    public record CommandRoute(String topic, String action, boolean bareTopic) {
        public boolean isActionTopic() { return !bareTopic; }
    }

    public CommandRoute resolveRoute(String deviceId, String command) {
        var spec = protocolCatalog.command(command).orElse(null);
        if (spec != null && spec.transport() != null) {
            return new CommandRoute(spec.transport().topic(), spec.transport().action(),
                    spec.transport().action() == null);
        }
        return new CommandRoute(SduiProtocolConstants.Topics.COMMAND_CONTROL, command, false);
    }

    /**
     * Debug-only capability view.
     * <p>
     * This method is kept for troubleshooting and metadata inspection only.
     * Public capability APIs should use the resolved contract from CapabilityContractService.
     */
    public Map<String, Object> buildCapabilityDebugView(String deviceId) {
        Optional<CapabilitySchema.CapabilitySnapshot> capsOpt = getCapabilities(deviceId);
        Optional<SduiDevice> deviceOpt = deviceRepository.findById(deviceId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("view", "debug");
        result.put("status", capsOpt.isPresent() ? "ok" : "no_capability_data");
        result.put("metadata", buildCapabilityMetadata(deviceId));

        if (capsOpt.isEmpty()) {
            result.put("deviceProfile", Map.of());
            result.put("hardwareInputs", List.of());
            result.put("deviceOutputs", List.of());
            result.put("sectionSupport", Map.of("layouts", List.of(), "sections", List.of()));
            return result;
        }

        CapabilitySchema.CapabilitySnapshot caps = capsOpt.get();
        String sizeClass = caps.display() != null ? caps.display().effectiveSizeClass() : "large";
        Map<String, Integer> limits = catalog.getDisplayLimits(sizeClass);

        result.put("deviceProfile", buildDeviceProfile(caps));
        result.put("hardwareInputs", buildHardwareInputs(caps));
        result.put("deviceOutputs", buildDeviceOutputs(caps));
        result.put("sectionSupport", buildSectionSupport(caps, sizeClass, limits));
        deviceOpt.ifPresent(device -> result.put("reportedAt", device.getCapabilitiesReportedAt()));
        return result;
    }

    /**
     * @deprecated Use {@link #buildCapabilityDebugView(String)} for troubleshooting only.
     * Main capability APIs should consume CapabilityContractService instead.
     */
    @Deprecated
    public Map<String, Object> buildNormalizedCapabilityTree(String deviceId) {
        return buildCapabilityDebugView(deviceId);
    }

    public Map<String, Object> buildCapabilityMetadata(String deviceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);

        Optional<SduiDevice> deviceOpt = deviceRepository.findById(deviceId);
        if (deviceOpt.isEmpty()) {
            result.put("status", "device_not_found");
            return result;
        }

        SduiDevice device = deviceOpt.get();
        String rawJson = device.getCapabilitiesSnapshot();
        if (rawJson == null || rawJson.isBlank()) {
            result.put("status", "no_capability_data");
            result.put("reportedAt", device.getCapabilitiesReportedAt());
            result.put("schema", device.getCapabilitiesSchemaVersion());
            return result;
        }

        result.put("status", "ok");
        result.put("reportedAt", device.getCapabilitiesReportedAt());
        result.put("schema", device.getCapabilitiesSchemaVersion());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> rawPayload = objectMapper.readValue(rawJson, Map.class);
            result.put("rawPayload", rawPayload);

            List<Map<String, Object>> unresolved = new ArrayList<>();
            Optional<CapabilitySchema.CapabilitySnapshot> capsOpt = getCapabilities(deviceId);
            if (capsOpt.isPresent()) {
                CapabilitySchema.CapabilitySnapshot caps = capsOpt.get();
                for (String input : caps.inputs()) {
                    if (catalog.getInput(input).isEmpty()) {
                        unresolved.add(unresolvedEntry("inputs", input, "input capability not found in catalog"));
                    }
                }
                for (String output : caps.outputs()) {
                    if (catalog.getOutput(output).isEmpty()) {
                        unresolved.add(unresolvedEntry("outputs", output, "output capability not found in catalog"));
                    }
                }
                if (caps.display() != null) {
                    for (String sectionType : caps.display().sectionTypes()) {
                        if (com.zwbd.agentnexus.sdui.section.SectionTypeCatalog.get(sectionType).isEmpty()) {
                            unresolved.add(unresolvedEntry("display.section_types", sectionType,
                                    "section type not found in platform catalog"));
                        }
                    }
                }
            }

            result.put("normalizedStatus", unresolved.isEmpty() ? "OK" : "PARTIAL");
            result.put("unresolved", unresolved);
        } catch (Exception e) {
            result.put("normalizedStatus", "ERROR");
            result.put("parseError", e.getMessage());
        }

        return result;
    }

    private Map<String, Object> buildDeviceProfile(CapabilitySchema.CapabilitySnapshot caps) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("board", caps.board());
        profile.put("schema", caps.schemaVersion());
        profile.put("protocolVersion", caps.protocolVersion());
        profile.put("inputMode", caps.inputMode());
        profile.put("screen", caps.screen() != null ? Map.of(
                "w", caps.screen().w(),
                "h", caps.screen().h(),
                "shape", caps.screen().shape()) : Map.of());
        profile.put("sizeClass", caps.display() != null ? caps.display().effectiveSizeClass() : "large");
        return profile;
    }

    private List<Map<String, Object>> buildHardwareInputs(CapabilitySchema.CapabilitySnapshot caps) {
        List<Map<String, Object>> items = new ArrayList<>();

        List<String> buttonInputs = caps.inputs().stream()
                .filter(input -> input.startsWith("buttons."))
                .toList();
        if (!buttonInputs.isEmpty()) {
            Map<String, Object> buttons = new LinkedHashMap<>();
            buttons.put("groupId", "buttons");
            buttons.put("label", "实体按钮");
            buttons.put("transport", "ui3_binary:EVENT_INPUT");

            List<Map<String, Object>> instances = new ArrayList<>();
            for (String input : buttonInputs) {
                String buttonId = input.substring("buttons.".length());
                CapabilityCatalog.InputDef inputDef = catalog.getInput(input).orElse(null);

                Map<String, Object> instance = new LinkedHashMap<>();
                instance.put("instanceId", buttonId);
                instance.put("capability", input);
                instance.put("label", inputDef != null && inputDef.displayName() != null
                        ? inputDef.displayName() : buttonId.toUpperCase(Locale.ROOT));
                instance.put("public", true);
                instance.put("supportedEvents", inputDef != null ? normalizeInputEvents(input, inputDef.events()) : List.of());
                instances.add(instance);
            }
            buttons.put("instances", instances);
            items.add(buttons);
        }

        for (String input : caps.inputs()) {
            if (input.startsWith("buttons.")) {
                continue;
            }
            CapabilityCatalog.InputDef inputDef = catalog.getInput(input).orElse(null);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("groupId", input);
            entry.put("label", inputDef != null && inputDef.displayName() != null ? inputDef.displayName() : input);
            entry.put("description", inputDef != null ? inputDef.description() : null);
            entry.put("transport", inputDef != null ? resolveInputTransport(inputDef) : null);
            entry.put("supportedEvents", inputDef != null ? normalizeInputEvents(input, inputDef.events()) : List.of());
            items.add(entry);
        }

        return items;
    }

    private List<Map<String, Object>> buildDeviceOutputs(CapabilitySchema.CapabilitySnapshot caps) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String output : caps.outputs()) {
            CapabilityCatalog.OutputDef outputDef = catalog.getOutput(output).orElse(null);
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("groupId", output);
            group.put("label", outputDef != null && outputDef.displayName() != null ? outputDef.displayName() : output);
            group.put("description", outputDef != null ? outputDef.description() : null);

            List<Map<String, Object>> commands = new ArrayList<>();
            if (outputDef != null && outputDef.commands() != null) {
                for (CapabilityCatalog.CommandDef commandDef : outputDef.commands().values()) {
                    if (commandDef.internal()) {
                        continue;
                    }
                    Map<String, Object> command = new LinkedHashMap<>();
                    command.put("commandId", commandDef.command());
                    command.put("label", commandDef.displayName() != null ? commandDef.displayName() : commandDef.command());
                    command.put("description", commandDef.description());
                    command.put("transport", resolveCommandTransport(commandDef));
                    command.put("action", commandDef.action());
                    command.put("parameterSchema", buildParamSchema(commandDef));
                    commands.add(command);
                }
            }
            group.put("commands", commands);
            items.add(group);
        }
        return items;
    }

    private Map<String, Object> buildSectionSupport(CapabilitySchema.CapabilitySnapshot caps,
                                                    String sizeClass,
                                                    Map<String, Integer> limits) {
        Map<String, Object> sectionSupport = new LinkedHashMap<>();
        sectionSupport.put("layouts", caps.display() != null ? caps.display().layouts() : List.of());
        sectionSupport.put("sizeClass", sizeClass);
        sectionSupport.put("constraints", limits);

        List<Map<String, Object>> sections = new ArrayList<>();
        if (caps.display() != null) {
            for (String sectionType : caps.display().sectionTypes()) {
                com.zwbd.agentnexus.sdui.section.SectionTypeCatalog.SectionTypeDef def =
                        com.zwbd.agentnexus.sdui.section.SectionTypeCatalog.get(sectionType).orElse(null);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", sectionType);
                entry.put("displayName", def != null ? def.displayName() : sectionType);
                entry.put("interactive", def != null && def.interactive());
                entry.put("events", def != null
                        ? def.interactionEvents().stream()
                                .map(com.zwbd.agentnexus.sdui.section.SectionTypeCatalog.InteractionEvent::eventId)
                                .toList()
                        : List.of());
                entry.put("constraints", def != null ? def.defaultConstraints() : Map.of());
                entry.put("renderModes", "small".equalsIgnoreCase(sizeClass)
                        ? List.of("compact")
                        : List.of("rich", "compact"));
                sections.add(entry);
            }
        }
        sectionSupport.put("sections", sections);
        return sectionSupport;
    }

    private List<Map<String, Object>> buildParamSchema(CapabilityCatalog.CommandDef commandDef) {
        if (commandDef.params() == null || commandDef.params().isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> schema = new ArrayList<>();
        for (Map.Entry<String, CapabilityCatalog.FieldSchema> entry : commandDef.params().entrySet()) {
            CapabilityCatalog.FieldSchema field = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.getKey());
            item.put("type", field.type());
            item.put("required", field.required());
            item.put("min", field.min());
            item.put("max", field.max());
            item.put("defaultValue", field.defaultValue());
            item.put("options", field.values());
            item.put("label", field.label());
            item.put("description", field.description());
            schema.add(item);
        }
        return schema;
    }

    private List<String> normalizeInputEvents(String inputName, List<String> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream()
                .map(eventName -> normalizeInputEventId(inputName, eventName))
                .toList();
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

    private String resolveInputTransport(CapabilityCatalog.InputDef inputDef) {
        if (inputDef.topic() != null && !inputDef.topic().isBlank()) {
            return "topic:" + inputDef.topic();
        }
        if (inputDef.protocol() != null && !inputDef.protocol().isBlank()) {
            return inputDef.protocol();
        }
        return null;
    }

    private String resolveCommandTransport(CapabilityCatalog.CommandDef commandDef) {
        if (commandDef.binaryMsgType() != null) {
            return "binary:" + commandDef.binaryMsgType();
        }
        if (commandDef.topic() != null && !commandDef.topic().isBlank()) {
            return "topic:" + commandDef.topic();
        }
        return null;
    }

    private Map<String, Object> unresolvedEntry(String path, String value, String reason) {
        return Map.of("path", path, "value", value, "reason", reason);
    }

    private void ensureRegistrySnapshot(String deviceId, CapabilitySchema.CapabilitySnapshot caps) {
        if (capabilityRegistry.getDeviceSnapshot(deviceId).isPresent()) {
            return;
        }
        capabilityRegistry.onDeviceReport(deviceId, caps);
        log.info("Rehydrated capability registry snapshot for device {} from stored capability report", deviceId);
    }
}
