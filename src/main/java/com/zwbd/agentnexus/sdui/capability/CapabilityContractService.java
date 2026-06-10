package com.zwbd.agentnexus.sdui.capability;

import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.protocol.SduiRuntimeHandlers;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CapabilityContractService {

    private final SduiCapabilityService capabilityService;
    private final CapabilityCatalog capabilityCatalog;
    private final PlatformCapabilityRegistry platformCapabilityRegistry;
    private final DeviceCapabilityProjection capabilityProjection;

    public CapabilityContractService(SduiCapabilityService capabilityService,
                                     CapabilityCatalog capabilityCatalog,
                                     PlatformCapabilityRegistry platformCapabilityRegistry,
                                     DeviceCapabilityProjection capabilityProjection) {
        this.capabilityService = capabilityService;
        this.capabilityCatalog = capabilityCatalog;
        this.platformCapabilityRegistry = platformCapabilityRegistry;
        this.capabilityProjection = capabilityProjection;
    }

    public CapabilityContract buildContract(String deviceId) {
        Optional<CapabilitySchema.CapabilitySnapshot> capsOpt = capabilityService.getCapabilities(deviceId);
        List<CapabilityContract.UnresolvedCapability> unresolved = new ArrayList<>();

        if (capsOpt.isEmpty()) {
            return new CapabilityContract(
                    deviceId,
                    "no_capability_data",
                    List.of(),
                    List.of(),
                    new CapabilityContract.DisplayContract("device", false, "large", List.of(), List.of(),
                            Map.of(), Map.of(), SduiRuntimeHandlers.DEVICE_UI),
                    buildPlatformCapabilities(),
                    List.of()
            );
        }

        CapabilitySchema.CapabilitySnapshot caps = capsOpt.get();
        List<CapabilityContract.ContractCapability> inputs = buildInputs(caps, unresolved);
        List<CapabilityContract.ContractCapability> outputs = buildOutputs(caps, unresolved);
        CapabilityContract.DisplayContract display = buildDisplay(caps, unresolved);

        return new CapabilityContract(
                deviceId,
                unresolved.isEmpty() ? "ok" : "partial",
                inputs,
                outputs,
                display,
                buildPlatformCapabilities(),
                unresolved
        );
    }

    public List<Map<String, Object>> buildResolvedEvents(String deviceId) {
        return capabilityProjection.events(deviceId).stream()
                .map(event -> {
                    Map<String, Object> entry = new LinkedHashMap<>(event.toMap());
                    entry.put("eventId", event.id());
                    entry.put("capability", event.source());
                    entry.put("category", event.id().startsWith("ui:") ? "display" : "input");
                    return entry;
                })
                .toList();
    }

    private List<CapabilityContract.ContractCapability> buildInputs(
            CapabilitySchema.CapabilitySnapshot caps,
            List<CapabilityContract.UnresolvedCapability> unresolved) {
        List<CapabilityContract.ContractCapability> inputs = new ArrayList<>();
        for (String inputName : caps.inputs()) {
            CapabilityCatalog.InputDef inputDef = capabilityCatalog.getInput(inputName).orElse(null);
            if (inputDef == null) {
                unresolved.add(new CapabilityContract.UnresolvedCapability(
                        "inputs", inputName, "input capability not found in catalog"));
                inputs.add(new CapabilityContract.ContractCapability(
                        inputName, "device", false, "input", inputName, inputName,
                        Map.of(), Map.of(), Map.of(), "device.input"));
                continue;
            }
            List<Map<String, Object>> events = new ArrayList<>();
            for (String eventId : capabilityCatalog.getInputEvents(inputName)) {
                events.add(Map.of(
                        "eventId", eventId,
                        "payloadSchema", inputDef.payloadSchema().stream().map(p -> Map.of(
                                "name", p.name(),
                                "type", p.type(),
                                "required", p.required(),
                                "description", p.description() != null ? p.description() : ""))
                                .toList()
                ));
            }
            inputs.add(new CapabilityContract.ContractCapability(
                    inputName,
                    "device",
                    true,
                    "input",
                    inputDef.displayName() != null ? inputDef.displayName() : inputName,
                    inputDef.description(),
                    Map.of("events", events),
                    inputProtocol(inputDef.protocol(), inputDef.topic(), inputDef.eventKind()),
                    Map.of(),
                    "device.input"
            ));
        }
        return inputs;
    }

    private List<CapabilityContract.ContractCapability> buildOutputs(
            CapabilitySchema.CapabilitySnapshot caps,
            List<CapabilityContract.UnresolvedCapability> unresolved) {
        List<CapabilityContract.ContractCapability> outputs = new ArrayList<>();
        for (String outputName : caps.outputs()) {
            CapabilityCatalog.OutputDef outputDef = capabilityCatalog.getOutput(outputName).orElse(null);
            if (outputDef == null) {
                unresolved.add(new CapabilityContract.UnresolvedCapability(
                        "outputs", outputName, "output capability not found in catalog"));
                outputs.add(new CapabilityContract.ContractCapability(
                    outputName, "device", false, "output", outputName, outputName,
                        Map.of("commands", List.of()), Map.of(), Map.of(), SduiRuntimeHandlers.DEVICE_COMMAND));
                continue;
            }

            List<Map<String, Object>> commands = new ArrayList<>();
            for (String commandName : capabilityCatalog.getOutputCommands(outputName)) {
                CapabilityCatalog.CommandDef cmdDef = capabilityCatalog.getCommand(commandName).orElse(null);
                if (cmdDef == null) {
                    continue;
                }
                List<Map<String, Object>> params = new ArrayList<>();
                for (var fieldEntry : cmdDef.params().entrySet()) {
                    CapabilityCatalog.FieldSchema field = fieldEntry.getValue();
                    Map<String, Object> param = new LinkedHashMap<>();
                    param.put("name", fieldEntry.getKey());
                    param.put("type", field.type());
                    param.put("required", field.required());
                    if (field.defaultValue() != null) {
                        param.put("default", field.defaultValue());
                    }
                    param.put("description", field.description() != null ? field.description() : field.label());
                    param.put("constraints", fieldConstraints(field));
                    params.add(param);
                }
                Map<String, Object> command = new LinkedHashMap<>();
                command.put("id", commandName);
                command.put("displayName", cmdDef.displayName() != null ? cmdDef.displayName() : commandName);
                command.put("description", cmdDef.description());
                command.put("params", params);
                command.put("protocol", protocol(cmdDef.topic(), cmdDef.action(), cmdDef.binaryMsgType()));
                command.put("runtimeHandler", SduiRuntimeHandlers.DEVICE_COMMAND);
                commands.add(command);
            }

            outputs.add(new CapabilityContract.ContractCapability(
                    outputName,
                    "device",
                    true,
                    "output",
                    outputDef.displayName() != null ? outputDef.displayName() : outputName,
                    outputDef.description(),
                    Map.of("commands", commands),
                    Map.of("transport", "device_command"),
                    Map.of(),
                    SduiRuntimeHandlers.DEVICE_COMMAND
            ));
        }
        return outputs;
    }

    private CapabilityContract.DisplayContract buildDisplay(
            CapabilitySchema.CapabilitySnapshot caps,
            List<CapabilityContract.UnresolvedCapability> unresolved) {
        if (caps.display() == null) {
            return new CapabilityContract.DisplayContract("device", false, "large", List.of(), List.of(),
                    Map.of(), Map.of(), SduiRuntimeHandlers.DEVICE_UI);
        }

        List<CapabilityContract.ContractCapability> sectionTypes = new ArrayList<>();
        for (String sectionType : caps.display().sectionTypes()) {
            SectionTypeCatalog.SectionTypeDef def = SectionTypeCatalog.get(sectionType).orElse(null);
            if (def == null) {
                unresolved.add(new CapabilityContract.UnresolvedCapability(
                        "display.section_types", sectionType, "section type not found in platform catalog"));
                sectionTypes.add(new CapabilityContract.ContractCapability(
                        sectionType, "device", false, "display", sectionType, sectionType,
                        Map.of(), Map.of(), Map.of(), SduiRuntimeHandlers.DEVICE_UI_SECTION));
                continue;
            }

            sectionTypes.add(new CapabilityContract.ContractCapability(
                    sectionType,
                    "device",
                    true,
                    "display",
                    def.displayName(),
                    def.interactive() ? "可交互 Section" : "展示型 Section",
                    Map.of(
                            "displayFields", SectionTypeCatalog.fieldsToMaps(def.displayFields()),
                            "interactionEvents", def.interactionEvents().stream().map(event -> Map.of(
                                    "eventId", event.eventId(),
                                    "description", event.description(),
                                    "params", event.params().stream().map(param -> Map.of(
                                            "name", param.name(),
                                            "type", param.type(),
                                            "description", param.description()
                                    )).toList()
                            )).toList()
                    ),
                    Map.of("transport", caps.display().transport()),
                    Map.of("defaultConstraints", def.defaultConstraints()),
                    SduiRuntimeHandlers.DEVICE_UI_SECTION
            ));
        }

        return new CapabilityContract.DisplayContract(
                "device",
                true,
                caps.display().effectiveSizeClass(),
                caps.display().layouts(),
                sectionTypes,
                new LinkedHashMap<>(capabilityCatalog.getDisplayLimits(caps.display().effectiveSizeClass())),
                Map.of("transport", caps.display().transport()),
                SduiRuntimeHandlers.DEVICE_UI
        );
    }

    private List<CapabilityContract.ContractCapability> buildPlatformCapabilities() {
        List<CapabilityContract.ContractCapability> capabilities = new ArrayList<>();
        for (PlatformCapabilityRegistry.PlatformCapabilityDef def : platformCapabilityRegistry.listCapabilities()) {
            capabilities.add(new CapabilityContract.ContractCapability(
                    def.id(),
                    "platform",
                    def.available(),
                    def.category(),
                    def.displayName(),
                    def.description(),
                    def.schema(),
                    def.protocol(),
                    def.constraints(),
                    def.runtimeHandler()
            ));
        }
        return capabilities;
    }

    private Map<String, Object> inputProtocol(String protocol, String topic, Integer eventKind) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (protocol != null) {
            map.put("transport", protocol);
        }
        if (topic != null) {
            map.put("topic", topic);
        }
        if (eventKind != null) {
            map.put("eventKind", eventKind);
        }
        return map;
    }

    private Map<String, Object> protocol(String topic, String action, Integer binaryMsgType) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (topic != null) {
            map.put("topic", topic);
        }
        if (action != null) {
            map.put("action", action);
        }
        if (binaryMsgType != null) {
            map.put("binaryMsgType", binaryMsgType);
        }
        return map;
    }

    private Map<String, Object> fieldConstraints(CapabilityCatalog.FieldSchema field) {
        Map<String, Object> constraints = new LinkedHashMap<>();
        if (field.min() != null) {
            constraints.put("min", field.min());
        }
        if (field.max() != null) {
            constraints.put("max", field.max());
        }
        if (field.values() != null && !field.values().isEmpty()) {
            constraints.put("options", field.values());
        }
        return constraints;
    }
}
