package com.zwbd.agentnexus.sdui.capability.node;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.CapabilityCatalog;
import com.zwbd.agentnexus.sdui.capability.CapabilityContract;
import com.zwbd.agentnexus.sdui.capability.CapabilityContractService;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CapabilityNodeCatalogService {

    private final SduiCapabilityService capabilityService;
    private final CapabilityContractService contractService;
    private final CapabilityCatalog catalog;
    private final DeviceSessionManager sessionManager;
    private final SectionTypeCatalog sectionTypeCatalog;

    public CapabilityNodeCatalogService(SduiCapabilityService capabilityService,
                                        CapabilityContractService contractService,
                                        CapabilityCatalog catalog,
                                        DeviceSessionManager sessionManager,
                                        SectionTypeCatalog sectionTypeCatalog) {
        this.capabilityService = capabilityService;
        this.contractService = contractService;
        this.catalog = catalog;
        this.sessionManager = sessionManager;
        this.sectionTypeCatalog = sectionTypeCatalog;
    }

    public CapabilityNodeCatalog buildForDevice(String deviceId) {
        CapabilityContract contract = contractService.buildContract(deviceId);
        Optional<CapabilitySchema.CapabilitySnapshot> capsOpt = capabilityService.getCapabilities(deviceId);
        if (capsOpt.isEmpty()) {
            return new CapabilityNodeCatalog(
                    deviceId,
                    sessionManager.isDeviceOnline(deviceId),
                    contract.status(),
                    List.of(),
                    unresolvedFromContract(contract)
            );
        }

        CapabilitySchema.CapabilitySnapshot caps = capsOpt.get();
        Set<String> inputs = new LinkedHashSet<>(safeList(caps.inputs()));
        Set<String> outputs = new LinkedHashSet<>(safeList(caps.outputs()));
        List<CapabilityNodeDefinition> nodes = new ArrayList<>();

        for (String inputName : inputs) {
            if (inputName.startsWith("buttons.")) {
                buildButtonNode(inputName).ifPresent(nodes::add);
            }
        }

        if (inputs.contains("audio.record") || outputs.contains("audio.record")) {
            nodes.add(audioRecordNode(inputs.contains("audio.record"), outputs.contains("audio.record")));
        }

        if (outputs.contains("rgb.effect")) {
            buildRgbNode().ifPresent(nodes::add);
        }

        if (outputs.contains("audio.stream")) {
            nodes.add(audioPlayNode());
        }

        if (caps.display() != null) {
            nodes.add(uiUpdateNode(caps.display()));
            nodes.add(displaySectionNode(caps.display()));
            buildSectionTriggerNodes(caps.display()).forEach(nodes::add);
        }

        return new CapabilityNodeCatalog(
                deviceId,
                sessionManager.isDeviceOnline(deviceId),
                contract.status(),
                nodes,
                unresolvedFromContract(contract)
        );
    }

    private Optional<CapabilityNodeDefinition> buildButtonNode(String inputName) {
        CapabilityCatalog.InputDef inputDef = catalog.getInput(inputName).orElse(null);
        if (inputDef == null) {
            return Optional.empty();
        }

        String instanceId = inputName.substring("buttons.".length());
        List<Map<String, Object>> events = new ArrayList<>();
        for (String eventName : safeList(inputDef.events())) {
            events.add(Map.of(
                    "eventId", normalizeInputEventId(inputName, eventName),
                    "eventName", eventName,
                    "displayName", inputDef.eventDisplayName(eventName)
            ));
        }

        return Optional.of(new CapabilityNodeDefinition(
                "button.trigger",
                inputName,
                instanceId,
                inputDef.displayName() != null ? inputDef.displayName() : instanceId.toUpperCase(Locale.ROOT),
                inputDef.description(),
                CapabilityNodeRuntimeMode.TRIGGER,
                List.of(),
                List.of(new CapabilityNodePort("event", "output", "event",
                        "输入事件", true, Map.of("events", events))),
                List.of(),
                List.of(new CapabilityNodeArtifactSchema("event", "object", "事件负载", true,
                        Map.of("fields", payloadFields(inputDef.payloadSchema())))),
                Map.of("kind", "device_input", "inputName", inputName, "events", events),
                Map.of("triggerOnly", true)
        ));
    }

    private CapabilityNodeDefinition audioRecordNode(boolean hasInput, boolean hasOutput) {
        CapabilityCatalog.InputDef inputDef = catalog.getInput("audio.record").orElse(null);
        CapabilityCatalog.OutputDef outputDef = catalog.getOutput("audio.record").orElse(null);
        List<Map<String, Object>> controls = List.of(
                Map.of("value", "start", "displayName", "开始录制"),
                Map.of("value", "stop", "displayName", "停止录制"),
                Map.of("value", "toggle", "displayName", "切换录制")
        );

        List<Map<String, Object>> events = new ArrayList<>();
        if (inputDef != null) {
            for (String eventName : safeList(inputDef.events())) {
                events.add(Map.of(
                        "eventId", eventName,
                        "eventName", eventName,
                        "displayName", inputDef.eventDisplayName(eventName),
                        "namespacedEventId", normalizeInputEventId("audio.record", eventName)
                ));
            }
        }

        return new CapabilityNodeDefinition(
                "audio.record",
                "audio.record",
                "audio-record",
                inputDef != null && inputDef.displayName() != null ? inputDef.displayName() : "音频采集",
                inputDef != null ? inputDef.description() : "设备麦克风音频采集",
                CapabilityNodeRuntimeMode.SESSION,
                List.of(new CapabilityNodePort("control", "input", "enum",
                        "控制", true, Map.of("values", controls))),
                List.of(
                        new CapabilityNodePort("completed", "output", "event", "录音完成", false,
                                Map.of("events", events)),
                        new CapabilityNodePort("audio_file", "output", "audio_file", "音频文件", false, Map.of()),
                        new CapabilityNodePort("text", "output", "string", "转写文本", false, Map.of())
                ),
                List.of(Map.of("name", "control", "type", "enum", "required", true,
                        "values", controls, "default", "toggle")),
                List.of(
                        new CapabilityNodeArtifactSchema("artifact_id", "string", "产物 ID", false, Map.of()),
                        new CapabilityNodeArtifactSchema("audio_file", "audio/wav", "WAV 音频文件", false, Map.of()),
                        new CapabilityNodeArtifactSchema("text", "string", "STT 文本", false, Map.of()),
                        new CapabilityNodeArtifactSchema("duration_ms", "int", "音频时长", false, Map.of()),
                        new CapabilityNodeArtifactSchema("sample_rate", "int", "采样率", false, Map.of())
                ),
                Map.of("kind", "device_session",
                        "inputName", "audio.record",
                        "outputName", "audio.record",
                        "hasInput", hasInput,
                        "hasOutput", hasOutput,
                        "commands", outputDef != null ? safeList(new ArrayList<>(outputDef.commands().keySet())) : List.of(),
                        "events", events),
                Map.of("builtInStt", true, "artifactId", "audio-record-latest")
        );
    }

    private Optional<CapabilityNodeDefinition> buildRgbNode() {
        CapabilityCatalog.CommandDef commandDef = catalog.getCommand("rgb.effect.set").orElse(null);
        if (commandDef == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> parameters = new ArrayList<>();
        parameters.add(Map.of("name", "off", "type", "boolean", "required", false,
                "default", false, "label", "关闭"));
        parameters.addAll(params(commandDef));
        return Optional.of(new CapabilityNodeDefinition(
                "rgb.effect",
                "rgb.effect",
                "rgb-effect",
                "RGB 灯光",
                "设备 RGB LED 灯光效果控制",
                CapabilityNodeRuntimeMode.ACTION,
                List.of(new CapabilityNodePort("params", "input", "object",
                        "灯光参数", true, Map.of("fields", parameters))),
                List.of(new CapabilityNodePort("command", "output", "command_result",
                        "命令结果", false, Map.of())),
                parameters,
                List.of(new CapabilityNodeArtifactSchema("cmdId", "string", "命令 ID", false, Map.of()),
                        new CapabilityNodeArtifactSchema("ackStatus", "string", "ACK 状态", false, Map.of())),
                Map.of("kind", "device_command", "outputName", "rgb.effect",
                        "commands", List.of("rgb.effect.set", "rgb.off")),
                Map.of()
        ));
    }

    private CapabilityNodeDefinition audioPlayNode() {
        CapabilityCatalog.CommandDef tts = catalog.getCommand("audio.tts.speak").orElse(null);
        CapabilityCatalog.CommandDef prompt = catalog.getCommand("audio.prompt.play").orElse(null);
        List<Map<String, Object>> parameters = new ArrayList<>();
        parameters.add(Map.of("name", "text", "type", "string", "required", false, "description", "TTS 文本"));
        parameters.add(Map.of("name", "preset", "type", "enum", "required", false,
                "values", List.of("notification", "success", "error", "warning", "click", "beep"),
                "description", "预设提示音"));
        parameters.add(Map.of("name", "artifact_id", "type", "string", "required", false,
                "description", "音频产物 ID，v1 暂返回 unsupported"));
        parameters.add(Map.of("name", "audio_file", "type", "audio_file", "required", false,
                "description", "音频文件引用，v1 暂返回 unsupported"));

        return new CapabilityNodeDefinition(
                "audio.play",
                "audio.stream",
                "audio-play",
                "音频播放",
                "播放提示音或 TTS 文本",
                CapabilityNodeRuntimeMode.ACTION,
                List.of(new CapabilityNodePort("source", "input", "object",
                        "播放内容", true, Map.of("fields", parameters))),
                List.of(new CapabilityNodePort("command", "output", "command_result",
                        "播放结果", false, Map.of())),
                parameters,
                List.of(new CapabilityNodeArtifactSchema("cmdId", "string", "命令 ID", false, Map.of()),
                        new CapabilityNodeArtifactSchema("sent", "boolean", "是否已发送", false, Map.of())),
                Map.of("kind", "device_audio_output",
                        "outputName", "audio.stream",
                        "commands", List.of(
                                tts != null ? "audio.tts.speak" : "",
                                prompt != null ? "audio.prompt.play" : ""
                        ).stream().filter(s -> !s.isBlank()).toList()),
                Map.of("artifactPlayback", "unsupported_in_v1")
        );
    }

    private CapabilityNodeDefinition uiUpdateNode(CapabilitySchema.DisplayInfo display) {
        return new CapabilityNodeDefinition(
                "ui.update",
                "display",
                "ui-update",
                "UI 更新",
                "修改终端 UI 上下文，由平台转换为 Section 更新",
                CapabilityNodeRuntimeMode.UI_PATCH,
                List.of(new CapabilityNodePort("update", "input", "object", "UI 更新", true,
                        Map.of("fields", List.of(
                                Map.of("name", "sectionId", "type", "string", "required", true),
                                Map.of("name", "field", "type", "string", "required", true),
                                Map.of("name", "value", "type", "any", "required", true)
                        )))),
                List.of(new CapabilityNodePort("patch", "output", "ui_patch", "UI Patch", false, Map.of())),
                List.of(),
                List.of(),
                Map.of("kind", "device_ui", "transport", display.transport(), "layouts", safeList(display.layouts())),
                Map.of("sectionTypes", safeList(display.sectionTypes()))
        );
    }

    private CapabilityNodeDefinition displaySectionNode(CapabilitySchema.DisplayInfo display) {
        return new CapabilityNodeDefinition(
                "display.section",
                "display",
                "display-section",
                "Section 显示",
                "创建或替换终端 Section 视图",
                CapabilityNodeRuntimeMode.UI_PATCH,
                List.of(new CapabilityNodePort("scene", "input", "object", "Section 场景", true,
                        Map.of("sectionTypes", safeList(display.sectionTypes())))),
                List.of(new CapabilityNodePort("scene", "output", "ui_scene", "UI Scene", false, Map.of())),
                List.of(),
                List.of(),
                Map.of("kind", "device_ui_section", "transport", display.transport()),
                Map.of("sectionTypes", safeList(display.sectionTypes()), "layouts", safeList(display.layouts()))
        );
    }

    private List<CapabilityNodeDefinition> buildSectionTriggerNodes(CapabilitySchema.DisplayInfo display) {
        List<CapabilityNodeDefinition> nodes = new ArrayList<>();
        Set<String> sectionTypes = new LinkedHashSet<>(safeList(display.sectionTypes()));
        for (String sectionType : sectionTypes) {
            SectionTypeCatalog.SectionTypeDef def = sectionTypeCatalog.get(sectionType).orElse(null);
            if (def == null || !def.interactive()) continue;
            for (SectionTypeCatalog.InteractionEvent evt : def.interactionEvents()) {
                String nodeId = "section-trigger-" + sectionType + "-" + evt.eventId();
                nodes.add(new CapabilityNodeDefinition(
                        "section.trigger",
                        sectionType,
                        nodeId,
                        def.displayName() + " - " + (evt.description() != null ? evt.description() : evt.eventId()),
                        "Section 交互事件触发器: " + evt.eventId(),
                        CapabilityNodeRuntimeMode.TRIGGER,
                        List.of(),
                        List.of(new CapabilityNodePort("event", "output", "event",
                                "触发事件", true, Map.of("events", List.of(Map.of(
                                "eventId", evt.eventId(),
                                "eventName", evt.eventId(),
                                "displayName", evt.description()
                        ))))),
                        List.of(),
                        List.of(new CapabilityNodeArtifactSchema("event", "object", "事件负载", true,
                                Map.of("fields", List.of(
                                        Map.of("name", "sectionId", "type", "string", "required", true,
                                                "description", "触发事件的 Section 实例 ID"),
                                        Map.of("name", "nodeId", "type", "string", "required", false,
                                                "description", "交互元素 ID"),
                                        Map.of("name", "value", "type", "any", "required", false,
                                                "description", "事件附带的值")
                                )))),
                        Map.of("kind", "section_interaction",
                                "sectionType", sectionType,
                                "events", List.of(Map.of(
                                        "eventId", evt.eventId(),
                                        "eventName", evt.eventId(),
                                        "displayName", evt.description()
                                ))),
                        Map.of("triggerOnly", true)
                ));
            }
        }
        return nodes;
    }

    private List<Map<String, Object>> unresolvedFromContract(CapabilityContract contract) {
        List<Map<String, Object>> unresolved = new ArrayList<>();
        for (CapabilityContract.UnresolvedCapability item : contract.unresolved()) {
            unresolved.add(Map.of(
                    "domain", item.domain(),
                    "id", item.id(),
                    "reason", item.reason()
            ));
        }
        return unresolved;
    }

    private List<Map<String, Object>> params(CapabilityCatalog.CommandDef commandDef) {
        if (commandDef == null || commandDef.params() == null) {
            return List.of();
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (var entry : commandDef.params().entrySet()) {
            CapabilityCatalog.FieldSchema field = entry.getValue();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", entry.getKey());
            map.put("type", field.type());
            map.put("required", field.required());
            if (field.defaultValue() != null) {
                map.put("default", field.defaultValue());
            }
            if (field.values() != null && !field.values().isEmpty()) {
                map.put("values", field.values());
            }
            if (field.label() != null) {
                map.put("label", field.label());
            }
            if (field.description() != null) {
                map.put("description", field.description());
            }
            Map<String, Object> constraints = new LinkedHashMap<>();
            if (field.min() != null) constraints.put("min", field.min());
            if (field.max() != null) constraints.put("max", field.max());
            if (!constraints.isEmpty()) map.put("constraints", constraints);
            fields.add(map);
        }
        return fields;
    }

    private List<Map<String, Object>> payloadFields(List<CapabilityCatalog.PayloadField> fields) {
        if (fields == null || fields.isEmpty()) return List.of();
        return fields.stream()
                .map(field -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", field.name());
                    map.put("type", field.type());
                    map.put("required", field.required());
                    if (field.description() != null) map.put("description", field.description());
                    return map;
                })
                .toList();
    }

    private String normalizeInputEventId(String inputName, String eventName) {
        if (eventName == null || eventName.isBlank() || eventName.contains(":")) {
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

    private <T> List<T> safeList(Collection<T> values) {
        if (values == null) {
            return List.of();
        }
        return new ArrayList<>(values);
    }
}
