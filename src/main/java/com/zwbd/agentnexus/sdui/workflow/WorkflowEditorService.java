package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.capability.CapabilityContractService;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import com.zwbd.agentnexus.sdui.section.SectionEditorService;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class WorkflowEditorService {

    private final ObjectMapper objectMapper;
    private final WorkflowDefinitionRepository definitionRepository;
    private final CapabilityContractService contractService;
    private final SectionEditorService sectionEditorService;
    private final DeviceCapabilityProjection capabilityProjection;
    private final WorkflowDefinitionNormalizer definitionNormalizer;

    public WorkflowEditorService(ObjectMapper objectMapper,
                                 WorkflowDefinitionRepository definitionRepository,
                                 CapabilityContractService contractService,
                                 SectionEditorService sectionEditorService,
                                 DeviceCapabilityProjection capabilityProjection,
                                 WorkflowDefinitionNormalizer definitionNormalizer) {
        this.objectMapper = objectMapper;
        this.definitionRepository = definitionRepository;
        this.contractService = contractService;
        this.sectionEditorService = sectionEditorService;
        this.capabilityProjection = capabilityProjection;
        this.definitionNormalizer = definitionNormalizer;
    }

    public Map<String, Object> buildNodeCatalog(List<NodeSchema> schemas) {
        List<Map<String, Object>> nodes = schemas.stream()
                .map(this::toNodeMap)
                .toList();

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String group = String.valueOf(node.get("category"));
            grouped.computeIfAbsent(group, ignored -> new ArrayList<>()).add(node);
        }

        List<Map<String, Object>> groups = grouped.entrySet().stream()
                .map(entry -> Map.of(
                        "group", entry.getKey(),
                        "nodes", entry.getValue()
                ))
                .toList();

        return Map.of(
                "groups", groups,
                "nodes", nodes
        );
    }

    public Map<String, Object> buildTriggerCatalog(String deviceId) {
        List<Map<String, Object>> triggers = new ArrayList<>();
        triggers.add(Map.of(
                "type", "manual",
                "displayName", "手动触发",
                "description", "由前端或调试端手动触发",
                "params", List.of()
        ));
        triggers.add(Map.of(
                "type", "cron",
                "displayName", "定时触发",
                "description", "按 cron 或固定间隔执行",
                "params", List.of(
                        Map.of("name", "interval", "type", "int", "required", false, "description", "固定间隔秒数"),
                        Map.of("name", "cron", "type", "string", "required", false, "description", "Cron 表达式")
                )
        ));
        triggers.add(Map.of(
                "type", "webhook",
                "displayName", "Webhook",
                "description", "外部 HTTP 请求触发",
                "params", List.of(
                        Map.of("name", "path", "type", "string", "required", true, "description", "Webhook 路径")
                )
        ));
        triggers.add(Map.of(
                "type", "device_message",
                "displayName", "设备消息",
                "description", "来自其他系统或设备的消息",
                "params", List.of(
                        Map.of("name", "messageType", "type", "string", "required", false, "description", "消息类型"),
                        Map.of("name", "sourceType", "type", "string", "required", false, "description", "来源类型"),
                        Map.of("name", "sourceId", "type", "string", "required", false, "description", "来源 ID")
                )
        ));

        if (deviceId != null && !deviceId.isBlank()) {
            List<Map<String, Object>> events = capabilityProjection.events(deviceId).stream()
                    .filter(event -> event.id().startsWith("ui:"))
                    .map(event -> Map.of(
                            "type", "device.ui.event",
                            "displayName", event.id(),
                            "description", "",
                            "eventType", event.id(),
                            "source", event.source(),
                            "capability", event.source(),
                            "params", List.of(
                                    Map.of("name", "eventType", "type", "string", "required", true, "default", event.id(),
                                            "description", "UI 事件类型"),
                                    Map.of("name", "pageId", "type", "string", "required", false, "description", "页面过滤"),
                                    Map.of("name", "sectionId", "type", "string", "required", false, "description", "Section 过滤"),
                                    Map.of("name", "nodeId", "type", "string", "required", false, "description", "Node 过滤")
                            )
                    )).toList();
            triggers.addAll(events);
            contractService.buildContract(deviceId).outputs().stream()
                    .filter(output -> output.supported())
                    .forEach(output -> {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> commands =
                                (List<Map<String, Object>>) output.schema().getOrDefault("commands", List.of());
                        for (Map<String, Object> command : commands) {
                            triggers.add(Map.of(
                                    "type", "device_command",
                                    "displayName", String.valueOf(command.getOrDefault("displayName", command.get("id"))),
                                    "description", String.valueOf(command.getOrDefault("description", "")),
                                    "command", command.get("id"),
                                    "source", "device",
                                    "capability", output.id(),
                                    "params", List.of(
                                            Map.of("name", "command", "type", "string", "required", true,
                                                    "default", command.get("id"), "description", "命令名")
                                    )
                            ));
                        }
                    });
        }

        return Map.of("deviceId", deviceId, "triggers", triggers);
    }

    public Map<String, Object> buildPageEditor(String deviceId) {
        Map<String, Object> sectionEditor = sectionEditorService.buildSectionEditor(deviceId);
        return Map.of(
                "deviceId", deviceId,
                "layouts", sectionEditor.get("layouts"),
                "renderMode", sectionEditor.get("renderMode"),
                "sectionTypes", sectionEditorService.buildWorkflowPageEditor(deviceId)
        );
    }

    public WorkflowDefinitionEntity scaffoldDefinition(Map<String, Object> body) {
        String id = stringValue(body.get("id"));
        String name = stringValue(body.get("name"));
        String icon = stringValue(body.get("icon"));

        @SuppressWarnings("unchecked")
        Map<String, Object> graph = body.get("graph") instanceof Map<?, ?> rawGraph
                ? (Map<String, Object>) rawGraph : Map.of();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = graph.get("nodes") instanceof List<?> rawNodes
                ? (List<Map<String, Object>>) rawNodes : List.of();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = graph.get("edges") instanceof List<?> rawEdges
                ? (List<Map<String, Object>>) rawEdges : List.of();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> triggers = graph.get("triggers") instanceof List<?> rawTriggers
                ? (List<Map<String, Object>>) rawTriggers : List.of();

        Map<String, List<ActionDef>> actions = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String nodeId = stringValue(node.get("id"));
            String nodeType = stringValue(node.get("nodeType"));
            @SuppressWarnings("unchecked")
            Map<String, Object> params = node.get("params") instanceof Map<?, ?> rawParams
                    ? (Map<String, Object>) rawParams : Map.of();
            actions.put(nodeId, List.of(new ActionDef.NodeActionDef(nodeType, params)));
        }

        WorkflowDefinition definition = definitionNormalizer.normalize(new WorkflowDefinition(
                id,
                name,
                icon,
                convert(body.get("pages"), objectMapper.getTypeFactory().constructCollectionType(List.class, PageDef.class)),
                convert(triggers, objectMapper.getTypeFactory().constructCollectionType(List.class, TriggerDef.class)),
                actions,
                convert(edges, objectMapper.getTypeFactory().constructCollectionType(List.class, EdgeDef.class)),
                convert(body.get("virtualInputs"), objectMapper.getTypeFactory().constructCollectionType(List.class, VirtualIO.class)),
                convert(body.get("virtualOutputs"), objectMapper.getTypeFactory().constructCollectionType(List.class, VirtualIO.class))
        ));

        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
        entity.setId(id);
        entity.setName(name != null ? name : id);
        entity.setIcon(icon);
        try {
            entity.setDefinitionJson(objectMapper.writeValueAsString(definition));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize workflow definition", e);
        }
        return entity;
    }

    public Map<String, Object> buildEditorModel(String definitionId) {
        WorkflowDefinitionEntity entity = definitionRepository.findById(definitionId)
                .orElseThrow(() -> new IllegalArgumentException("definition not found"));
        WorkflowDefinition def;
        try {
            def = definitionNormalizer.normalize(objectMapper.readValue(entity.getDefinitionJson(), WorkflowDefinition.class));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to parse definition json", e);
        }

        List<Map<String, Object>> graphNodes = new ArrayList<>();
        List<String> unsupportedActionGroups = new ArrayList<>();
        if (def.actions() != null) {
            for (var entry : def.actions().entrySet()) {
                if (entry.getValue() == null || entry.getValue().size() != 1
                        || !(entry.getValue().get(0) instanceof ActionDef.NodeActionDef nodeAction)) {
                    unsupportedActionGroups.add(entry.getKey());
                    continue;
                }
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", entry.getKey());
                node.put("nodeType", nodeAction.nodeType());
                node.put("params", nodeAction.params() != null ? nodeAction.params() : Map.of());
                graphNodes.add(node);
            }
        }

        return Map.of(
                "id", def.id(),
                "name", def.name(),
                "icon", def.icon(),
                "pages", def.pages() != null ? def.pages() : List.of(),
                "virtualInputs", def.virtualInputs() != null ? def.virtualInputs() : List.of(),
                "virtualOutputs", def.virtualOutputs() != null ? def.virtualOutputs() : List.of(),
                "graph", Map.of(
                        "triggers", def.triggers() != null ? def.triggers() : List.of(),
                        "nodes", graphNodes,
                        "edges", def.edges() != null ? def.edges() : List.of()
                ),
                "unsupportedActionGroups", unsupportedActionGroups
        );
    }

    private Map<String, Object> toNodeMap(NodeSchema schema) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", schema.type());
        node.put("displayName", schema.displayName());
        node.put("description", schema.description());
        node.put("category", schema.category());
        node.put("icon", schema.icon());
        node.put("source", schema.source());
        node.put("deviceSupported", schema.deviceSupported());
        node.put("protocol", schema.protocol());
        node.put("runtimeHandler", schema.runtimeHandler());
        node.put("inputs", schema.inputs());
        node.put("outputs", schema.outputs());
        node.put("constraints", schema.constraints());
        node.put("defaultConfig", defaultConfig(schema.inputs()));
        return node;
    }

    private Map<String, Object> defaultConfig(List<NodeSchema.ParamDef> inputs) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (NodeSchema.ParamDef input : inputs) {
            if (input.defaultValue() != null) {
                defaults.put(input.name(), input.defaultValue());
            }
        }
        return defaults;
    }

    private String stringValue(Object value) {
        return value instanceof String s ? s : null;
    }

    private <T> T convert(Object source, com.fasterxml.jackson.databind.JavaType type) {
        if (source == null) {
            return objectMapper.convertValue(List.of(), type);
        }
        return objectMapper.convertValue(source, type);
    }
}
