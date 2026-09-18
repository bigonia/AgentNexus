package com.zwbd.agentnexus.sdui.capability.node;

import com.zwbd.agentnexus.sdui.v2.capability.CapabilityQueryService;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilitySchemaV2;
import com.zwbd.agentnexus.sdui.workflow.NodeTypeRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流编辑器的节点目录。
 *
 * <p>回答编排阶段的唯一问题：<b>在这台设备上，可以往工作流里放哪些节点</b>。内容由两处既有真值
 * 推导，不新引入第三份：</p>
 *
 * <ul>
 *   <li><b>节点类型</b>来自 {@link NodeTypeRegistry}——运行时按同一登记表执行，避免"编辑器里有、
 *       运行时没实现"或反过来的漂移。</li>
 *   <li><b>可用性</b>来自设备声明的 {@link CapabilitySchemaV2}——输出节点要求动作出现在 Schema 的
 *       {@code actions[]} 中；触发节点要求触发源声明 {@code configurable}。</li>
 * </ul>
 *
 * <p>不可用的节点不静默省略，而是进入 {@code unresolvedNodes} 并附原因。配置里没有某个节点时，
 * 必须能查到它为什么不在。</p>
 *
 * <p>本类只描述"怎么配"，不判定"能不能下沉"。下沉由 {@code WorkflowActionMapper} 在组装期判定，
 * 并会再次校验设备声明——两处判据都指向同一个设备 Schema，因此不会得出相反结论。</p>
 */
@Service
@RequiredArgsConstructor
public class CapabilityNodeCatalogService {

    private final NodeTypeRegistry nodeTypes;
    private final CapabilityQueryService capabilities;

    /** 节点类型的展示信息。可执行性不在这里——那由设备 Schema 决定。 */
    private record Presentation(String displayName, String description, CapabilityNodeRuntimeMode mode) {}

    private static final Map<String, Presentation> PRESENTATIONS = Map.of(
            "rgb.effect", new Presentation("RGB 灯光", "设备 RGB LED 灯光效果", CapabilityNodeRuntimeMode.ACTION),
            "audio.record", new Presentation("音频采集", "设备麦克风音频采集", CapabilityNodeRuntimeMode.SESSION),
            "audio.play", new Presentation("音频播放", "播放设备内置提示音，或由平台产出音频", CapabilityNodeRuntimeMode.ACTION),
            "display.section", new Presentation("Section 显示", "创建或替换终端主视图 Section", CapabilityNodeRuntimeMode.UI_PATCH),
            "ui.update", new Presentation("UI 更新", "由平台按模板渲染后下发 Section", CapabilityNodeRuntimeMode.UI_PATCH));

    /** 触发节点的类型名必须以 {@code .trigger} 结尾——工作流引擎据此识别入口节点。 */
    private static final String GENERIC_TRIGGER_TYPE = "trigger";

    public CapabilityNodeCatalog buildForDevice(String deviceId) {
        return buildForDevice(deviceId, null, null);
    }

    /**
     * 构建设备节点目录。
     *
     * <p>{@code pageId} 与 {@code pageJson} 保留以兼容既有调用方，但不再参与节点解析：Section
     * 触发树由 {@code /board-types/{board}/section-triggers} 单独承担。</p>
     */
    public CapabilityNodeCatalog buildForDevice(String deviceId, String pageId, String pageJson) {
        CapabilitySchemaV2 schema = capabilities.schemaOf(deviceId).orElse(null);
        String status = String.valueOf(capabilities.sync(deviceId).get("state"));
        List<CapabilityNodeDefinition> nodes = new ArrayList<>();
        List<Map<String, Object>> unresolved = new ArrayList<>();

        if (schema == null) {
            unresolved.add(unresolved("device", deviceId, "设备能力 Schema 未同步，无法列出节点"));
            return new CapabilityNodeCatalog(deviceId, capabilities.online(deviceId), status, nodes, unresolved);
        }

        appendTriggerNodes(schema, nodes, unresolved);
        appendOutputNodes(schema, nodes, unresolved);

        return new CapabilityNodeCatalog(deviceId, capabilities.online(deviceId), status, nodes, unresolved);
    }

    // ── 触发节点 ───────────────────────────────────────────────────────────

    /**
     * 触发节点按<b>来源族</b>分组：同一族共用一种节点类型，具体触发 id 进入 {@code eventId} 的取值域。
     * 这样节点类型数量保持稳定，新增触发源不必新增节点类型。
     */
    private void appendTriggerNodes(CapabilitySchemaV2 schema,
                                    List<CapabilityNodeDefinition> nodes,
                                    List<Map<String, Object>> unresolved) {
        if (schema.triggers() == null) {
            return;
        }
        Map<String, List<CapabilitySchemaV2.TriggerSpec>> byFamily = new LinkedHashMap<>();
        for (CapabilitySchemaV2.TriggerSpec trigger : schema.triggers()) {
            if (!trigger.configurable()) {
                unresolved.add(unresolved("trigger", trigger.id(),
                        "触发源未声明 configurable，平台不能在绑定表中使用"));
                continue;
            }
            byFamily.computeIfAbsent(triggerNodeType(trigger.id()), key -> new ArrayList<>()).add(trigger);
        }

        for (Map.Entry<String, List<CapabilitySchemaV2.TriggerSpec>> entry : byFamily.entrySet()) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (CapabilitySchemaV2.TriggerSpec trigger : entry.getValue()) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("eventId", trigger.id());
                value.put("eventName", trigger.id());
                value.put("source", trigger.source());
                value.put("maxResponses", trigger.maxResponses());
                values.add(value);
            }
            Map<String, Object> eventIdParam = new LinkedHashMap<>();
            eventIdParam.put("name", "eventId");
            eventIdParam.put("type", "enum");
            eventIdParam.put("required", true);
            eventIdParam.put("label", "触发源");
            eventIdParam.put("values", values);

            nodes.add(new CapabilityNodeDefinition(
                    entry.getKey(),
                    entry.getKey(),
                    entry.getKey(),
                    "触发",
                    "终端本地产生的交互入口，绑定后由平台按 token 续接",
                    CapabilityNodeRuntimeMode.TRIGGER,
                    List.of(),
                    List.of(new CapabilityNodePort("event", "output", "event", "触发事件", true, Map.of())),
                    List.of(eventIdParam),
                    List.of(new CapabilityNodeArtifactSchema("event", "object", "触发负载", true, Map.of())),
                    Map.of("kind", "terminal_trigger", "triggers", values),
                    Map.of("triggerOnly", true)));
        }
    }

    /** 触发源 id → 节点类型。来源族之外的触发源回落到通用类型。 */
    private static String triggerNodeType(String triggerId) {
        if (triggerId == null || triggerId.isBlank()) {
            return GENERIC_TRIGGER_TYPE;
        }
        if (triggerId.startsWith("button.")) {
            return "button.trigger";
        }
        if (triggerId.startsWith("platform.trigger")) {
            return "platform.trigger";
        }
        int dot = triggerId.indexOf('.');
        return dot > 0 ? triggerId.substring(0, dot) + ".trigger" : GENERIC_TRIGGER_TYPE;
    }

    // ── 输出节点 ───────────────────────────────────────────────────────────

    /**
     * 输出节点。没有终端动作的类型（如 {@code ui.update}）仍然可用——平台自己完成渲染与下发，
     * 只是不会进入终端的本地响应序列。
     */
    private void appendOutputNodes(CapabilitySchemaV2 schema,
                                   List<CapabilityNodeDefinition> nodes,
                                   List<Map<String, Object>> unresolved) {
        Set<String> outputTypes = new LinkedHashSet<>(nodeTypes.nodeTypes());
        outputTypes.removeIf(type -> type.endsWith(".trigger"));

        for (String nodeType : outputTypes) {
            Presentation presentation = PRESENTATIONS.get(nodeType);
            if (presentation == null) {
                unresolved.add(unresolved("node", nodeType, "节点类型已登记但缺少展示定义，未列入目录"));
                continue;
            }
            List<String> candidates = nodeTypes.targetActions(nodeType);
            CapabilitySchemaV2.ActionSpec declared = firstDeclared(schema, candidates);
            if (!candidates.isEmpty() && declared == null) {
                unresolved.add(unresolved("node", nodeType,
                        "终端能力 Schema 未声明动作 " + String.join(" / ", candidates)));
                continue;
            }

            List<Map<String, Object>> parameters = new ArrayList<>();
            for (NodeTypeRegistry.ParamDef param : nodeTypes.getParams(nodeType)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", param.name());
                entry.put("type", param.type());
                entry.put("required", param.required());
                entry.put("label", param.displayName());
                entry.put("values", valuesOf(declared, param.name()));
                parameters.add(entry);
            }

            Map<String, Object> source = new LinkedHashMap<>();
            source.put("kind", "platform_node");
            source.put("targetActions", candidates);
            source.put("declaredAction", declared == null ? null : declared.name());
            source.put("sinkable", declared != null && declared.usableInBinding());

            nodes.add(new CapabilityNodeDefinition(
                    nodeType,
                    nodeType,
                    nodeType,
                    presentation.displayName(),
                    presentation.description(),
                    presentation.mode(),
                    List.of(new CapabilityNodePort("params", "input", "object", "参数", true,
                            Map.of("fields", parameters))),
                    List.of(new CapabilityNodePort("result", "output", "object", "执行结果", false, Map.of())),
                    parameters,
                    List.of(),
                    source,
                    Map.of("requiresPlatform", candidates.isEmpty())));
        }
    }

    /** 该节点类型的候选动作中，设备实际声明了的第一个。 */
    private static CapabilitySchemaV2.ActionSpec firstDeclared(CapabilitySchemaV2 schema,
                                                              List<String> candidates) {
        for (String candidate : candidates) {
            CapabilitySchemaV2.ActionSpec spec = schema.action(candidate);
            if (spec != null) {
                return spec;
            }
        }
        return null;
    }

    /** 参数取值域以设备声明为准；设备未声明时返回空表，表示"无枚举约束"。 */
    private static List<String> valuesOf(CapabilitySchemaV2.ActionSpec action, String paramName) {
        if (action == null) {
            return List.of();
        }
        CapabilitySchemaV2.ParamSpec param = action.param(paramName);
        if (param == null || param.values() == null) {
            return List.of();
        }
        return param.values();
    }

    private static Map<String, Object> unresolved(String kind, String id, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", kind);
        entry.put("id", id);
        entry.put("reason", reason);
        return entry;
    }
}
