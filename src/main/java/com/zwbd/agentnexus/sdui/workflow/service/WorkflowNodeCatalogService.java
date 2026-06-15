package com.zwbd.agentnexus.sdui.workflow.service;

import com.zwbd.agentnexus.sdui.capability.CapabilityCatalog;
import com.zwbd.agentnexus.sdui.capability.PlatformCapabilityRegistry;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.protocol.catalog.FieldSpec;
import com.zwbd.agentnexus.sdui.section.SectionEditorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkflowNodeCatalogService {

    private final DeviceCapabilityProjection capabilityProjection;
    private final CapabilityCatalog capabilityCatalog;
    private final PlatformCapabilityRegistry platformCapabilityRegistry;
    private final SectionEditorService sectionEditorService;

    public Map<String, Object> buildCatalog(String deviceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("triggers", triggers(deviceId));
        result.put("input", capabilityProjection.events(deviceId).stream().map(event -> Map.of(
                "id", "input." + event.id(),
                "type", "input",
                "kind", "device.ui.event",
                "source", event.source(),
                "eventId", event.id(),
                "displayName", displayName(event.id(), event.id()),
                "description", "Device input event from " + event.source(),
                "fields", event.payload().stream().map(this::fieldSpecToEditorField).toList(),
                "ports", ports(List.of(), List.of("event"))
        )).toList());
        result.put("output", outputNodes(deviceId));
        result.put("control", controlNodes());
        return result;
    }

    private List<Map<String, Object>> triggers(String deviceId) {
        return List.of(
                node("trigger.manual", "trigger", "manual", "手动触发", "由用户在平台手动启动工作流", List.of()),
                node("trigger.device.ui.event", "trigger", "device.ui.event", "设备输入事件", "由按钮、触摸、Section 点击等终端输入触发", List.of(
                        field("eventId", "string", true, null, "event id"),
                        field("nodeId", "string", false, null, "interactive node id"),
                        field("sectionId", "string", false, null, "section id")
                ), Map.of("eventOptions", deviceEventOptions(deviceId))),
                node("trigger.command.lifecycle", "trigger", "command.lifecycle", "命令生命周期", "由命令下发、ACK、失败、超时等状态变化触发", List.of(
                        field("eventId", "string", true, "command.ack", "command lifecycle event id"),
                        field("command", "string", false, null, "optional command id filter"),
                        field("status", "string", false, null, "optional command status filter")
                ), Map.of("eventOptions", commandLifecycleEventOptions(), "commandOptions", commandOptions(deviceId))),
                node("trigger.webhook", "trigger", "webhook", "Webhook 触发", "外部 HTTP 请求触发，运行时待接入", List.of(field("path", "string", false, null, "webhook path"))),
                node("trigger.cron", "trigger", "cron", "定时触发", "Cron 调度触发，运行时待接入", List.of(field("cron", "string", true, null, "cron expression")))
        );
    }

    private List<Map<String, Object>> outputNodes(String deviceId) {
        var commands = capabilityProjection.commands(deviceId).stream().map(command -> {
            CapabilityCatalog.CommandDef def = capabilityCatalog.getCommand(command.id()).orElse(null);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "output.command." + command.id());
            node.put("type", "output");
            node.put("kind", "device.command");
            node.put("command", command.id());
            node.put("group", command.group());
            node.put("displayName", def != null && def.displayName() != null ? def.displayName() : command.id());
            node.put("description", def != null ? def.description() : null);
            node.put("fields", commandFields(command.id(), command.params()));
            node.put("configTemplate", Map.of("command", command.id(), "params", defaultsForCommand(command.id())));
            node.put("ui", Map.of("widget", "commandForm", "category", command.group(), "order", 10));
            node.put("ports", ports(List.of("in"), List.of("result")));
            return node;
        }).toList();

        var platform = platformCapabilityRegistry.listCapabilities().stream().map(def -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "output.platform." + def.debugRouteId());
            node.put("type", "output");
            node.put("kind", "platform.capability");
            node.put("command", def.debugRouteId());
            node.put("displayName", def.displayName());
            node.put("description", def.description());
            node.put("category", def.category());
            node.put("available", def.available());
            node.put("fields", normalizePlatformFields(def.schema().getOrDefault("params", List.of())));
            node.put("configTemplate", Map.of("command", def.debugRouteId(), "params", Map.of()));
            node.put("ui", Map.of("widget", "platformCapabilityForm", "category", def.category(), "order", 20));
            node.put("ports", ports(List.of("in"), List.of("result")));
            return node;
        }).toList();

        Map<String, Object> sectionEditor = sectionEditorService.buildSectionEditor(deviceId);
        return List.of(
                Map.of("group", "deviceCommands", "nodes", commands),
                Map.of("group", "platformCapabilities", "nodes", platform),
                Map.of("group", "sections", "nodes", List.of(
                        Map.of("id", "output.section.scene", "type", "output", "kind", "section.scene",
                                "displayName", "渲染页面",
                                "description", "向终端下发完整 Section 页面场景",
                                "fields", List.of(field("pageId", "string", true, null, "page id"),
                                        field("layout", "string", false, "vertical_scroll", "layout"),
                                        field("sections", "array", true, null, "section entries")),
                                "ui", Map.of("widget", "sectionSceneEditor", "order", 30),
                                "sectionEditor", sectionEditor, "ports", ports(List.of("in"), List.of("result"))),
                        Map.of("id", "output.section.patch", "type", "output", "kind", "section.patch",
                                "displayName", "更新页面局部",
                                "description", "向终端下发 Section Patch 更新",
                                "fields", List.of(field("pageId", "string", true, null, "page id"),
                                        field("patches", "array", true, null, "patch entries")),
                                "ui", Map.of("widget", "sectionPatchEditor", "order", 31),
                                "sectionEditor", sectionEditor, "ports", ports(List.of("in"), List.of("result")))
                ))
        );
    }

    private List<Map<String, Object>> controlNodes() {
        return List.of(
                node("control.condition", "control", "condition", "条件判断", "计算布尔表达式，可配合 edge condition 使用", List.of(field("expression", "string", true, null, "boolean expression"))),
                node("control.set_variable", "control", "set_variable", "设置变量", "写入工作流运行变量", List.of(field("name", "string", true, null, "variable name"), field("value", "any", true, null, "value"))),
                node("control.terminate", "control", "terminate", "终止", "结束本次工作流执行", List.of())
        );
    }

    private Map<String, Object> node(String id, String type, String kind, String displayName,
                                     String description, List<Map<String, Object>> fields) {
        return node(id, type, kind, displayName, description, fields, Map.of());
    }

    private Map<String, Object> node(String id, String type, String kind, String displayName,
                                     String description, List<Map<String, Object>> fields,
                                     Map<String, Object> extras) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("kind", kind);
        node.put("label", displayName);
        node.put("displayName", displayName);
        node.put("description", description);
        node.put("fields", fields);
        node.put("ports", ports(List.of("in"), List.of("out")));
        node.put("ui", Map.of("widget", type + "." + kind));
        node.putAll(extras);
        return node;
    }

    private Map<String, Object> field(String name, String type, boolean required, Object defaultValue, String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("type", type);
        field.put("displayName", displayName(name, name));
        field.put("required", required);
        if (defaultValue != null) field.put("default", defaultValue);
        field.put("description", description);
        field.put("ui", Map.of("widget", widgetFor(type)));
        return field;
    }

    private Map<String, Object> fieldSpecToEditorField(FieldSpec spec) {
        Map<String, Object> field = field(spec.name(), spec.type(), false, null, spec.name());
        if (spec.children() != null && !spec.children().isEmpty()) {
            field.put("children", spec.children().stream().map(this::fieldSpecToEditorField).toList());
        }
        return field;
    }

    private List<Map<String, Object>> commandFields(String commandId, List<FieldSpec> fallback) {
        return capabilityCatalog.getCommand(commandId)
                .map(command -> command.params().entrySet().stream()
                        .map(entry -> commandField(entry.getKey(), entry.getValue()))
                        .toList())
                .orElseGet(() -> fallback.stream().map(this::fieldSpecToEditorField).toList());
    }

    private Map<String, Object> commandField(String name, CapabilityCatalog.FieldSchema schema) {
        Map<String, Object> field = field(name, normalizeType(schema.type()), schema.required(), schema.defaultValue(), schema.description());
        field.put("displayName", schema.label() != null ? schema.label() : displayName(name, name));
        if (schema.min() != null) field.put("min", schema.min());
        if (schema.max() != null) field.put("max", schema.max());
        if (schema.values() != null && !schema.values().isEmpty()) {
            field.put("options", schema.values().stream()
                    .map(value -> Map.of("label", displayName(value, value), "value", value))
                    .toList());
        }
        field.put("ui", Map.of("widget", widgetFor(schema.type()), "order", 10));
        return field;
    }

    private Map<String, Object> defaultsForCommand(String commandId) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        capabilityCatalog.getCommand(commandId).ifPresent(command -> command.params().forEach((name, schema) -> {
            if (schema.defaultValue() != null) defaults.put(name, schema.defaultValue());
        }));
        return defaults;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizePlatformFields(Object fields) {
        if (!(fields instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance).map(item -> {
            Map<String, Object> raw = WorkflowDag.normalize((Map<?, ?>) item);
            Map<String, Object> field = field(
                    WorkflowDag.string(raw.get("name")),
                    normalizeType(WorkflowDag.string(raw.getOrDefault("type", "string"))),
                    Boolean.TRUE.equals(raw.get("required")),
                    raw.get("default"),
                    WorkflowDag.string(raw.get("description"))
            );
            if (!WorkflowDag.string(raw.get("label")).isBlank()) {
                field.put("displayName", WorkflowDag.string(raw.get("label")));
            }
            return field;
        }).toList();
    }

    private List<Map<String, Object>> deviceEventOptions(String deviceId) {
        return capabilityProjection.events(deviceId).stream().map(event -> {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("label", event.id());
            option.put("value", event.id());
            option.put("source", event.source());
            return option;
        }).toList();
    }

    private List<Map<String, Object>> commandOptions(String deviceId) {
        return capabilityProjection.commands(deviceId).stream().map(command -> {
            CapabilityCatalog.CommandDef def = capabilityCatalog.getCommand(command.id()).orElse(null);
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("label", def != null && def.displayName() != null ? def.displayName() : command.id());
            option.put("value", command.id());
            option.put("group", command.group());
            option.put("description", def != null && def.description() != null ? def.description() : "");
            return option;
        }).toList();
    }

    private List<Map<String, Object>> commandLifecycleEventOptions() {
        return List.of(
                eventOption("命令已下发", "command.dispatch", "命令已被平台下发或处理"),
                eventOption("命令 ACK", "command.ack", "终端确认命令执行成功"),
                eventOption("命令拒绝", "command.rejected", "终端拒绝执行命令"),
                eventOption("命令失败", "command.failed", "命令发送或执行失败"),
                eventOption("命令超时", "command.timeout", "命令等待 ACK 超时"),
                eventOption("命令结果", "command.result", "其他命令结果事件")
        );
    }

    private Map<String, Object> eventOption(String label, String value, String description) {
        return Map.of("label", label, "value", value, "description", description);
    }

    private String normalizeType(String type) {
        return switch (type) {
            case "integer", "number" -> "int";
            case "boolean" -> "bool";
            case "enum" -> "string";
            default -> type == null || type.isBlank() ? "object" : type;
        };
    }

    private String widgetFor(String type) {
        return switch (type) {
            case "int", "float", "number" -> "number";
            case "bool", "boolean" -> "switch";
            case "enum" -> "select";
            case "array" -> "arrayEditor";
            case "object" -> "objectEditor";
            default -> "text";
        };
    }

    private String displayName(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        return raw;
    }

    private Map<String, Object> ports(List<String> inputs, List<String> outputs) {
        return Map.of("inputs", inputs, "outputs", outputs);
    }
}
