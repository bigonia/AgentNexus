package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.v2.business.ResponseStep;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilitySchemaV2;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把工作流输出节点翻译成终端的本地响应动作，并判定能否下沉到 {@code BusinessConfig}。
 *
 * <h2>为什么需要这一层</h2>
 * <p>旧模型下工作流的每个输出节点都由平台逐条下发设备命令（{@link CapabilityNodeExecutorService}）。
 * 新模型下终端自己按序执行静态响应序列，平台只下发配置。但工作流节点类型与终端的能力动作不是同一套
 * 命名空间，而且并非所有节点都能独立执行——{@code audio.play} 要先有 TTS 产物，{@code ui.update}
 * 要先做模板渲染。所以需要一处显式声明"哪些节点可以变成静态动作、哪些必须留给平台"。</p>
 *
 * <h2>下沉判据</h2>
 * <p>一个节点只有同时满足以下三条才会成为 {@link ResponseStep}：</p>
 * <ol>
 *   <li><b>参数全静态</b>：不含 {@code {"$ref": "nodeId"}} 之类的运行时取值。</li>
 *   <li><b>不依赖平台产物</b>：不需要 TTS 文本、音频 artifact、Section / UI 模板渲染。</li>
 *   <li><b>终端声明支持</b>：目标动作出现在设备能力 Schema 的 {@code actions[]} 中，且
 *       {@code usableIn} 包含 {@code binding}。</li>
 * </ol>
 * <p>第 3 条来自 04_PROTOCOL_MODEL.md §4：动作要显式声明"可用于本地绑定还是平台请求"。因此能力
 * Schema 是下沉判定的最终依据，Schema 未同步时一律不下沉（见
 * {@link WorkflowBusinessConfigAssembler}）。</p>
 *
 * <h2>业务动态行为不在这里</h2>
 * <p>响应序列刻意保持"全静态"。业务上的动态差异由平台通过<b>不同 token 指向同一动作</b>来表达，
 * 例如同一段提示音用两个 token 区分"开始"与"结束"两种业务语义。因此本类不做任何条件、分支或
 * 参数求值。</p>
 */
@Service
public class WorkflowActionMapper {

    /**
     * 一个节点的映射结果。
     *
     * @param sinkable 能否进入终端本地响应序列
     * @param action   目标终端动作名；{@code sinkable=false} 时为 {@code null}
     * @param params   动作参数；{@code sinkable=false} 时为空表
     * @param reason   不可下沉的原因；{@code sinkable=true} 时为 {@code null}
     */
    public record Mapped(boolean sinkable, String action, Map<String, Object> params, String reason) {

        static Mapped sink(String action, Map<String, Object> params) {
            return new Mapped(true, action, new LinkedHashMap<>(params), null);
        }

        static Mapped hold(String reason) {
            return new Mapped(false, null, Map.of(), reason);
        }

        public ResponseStep toStep() {
            return new ResponseStep(action, params);
        }
    }

    /** 是否需要平台产出的参数：出现任一即表示该节点无法静态执行。 */
    private static final List<String> PLATFORM_PRODUCED_PARAMS =
            List.of("artifact_id", "audio_file", "text");

    /** 需要平台渲染的 UI 类参数：出现任一即表示该节点无法静态执行。 */
    private static final List<String> PLATFORM_RENDERED_PARAMS =
            List.of("scene", "patch", "pageId", "templateKey", "variableKey", "value");

    public Mapped map(NodeWorkflowNode node, CapabilitySchemaV2 schema) {
        if (node == null) {
            return Mapped.hold("节点为空");
        }
        Map<String, Object> params = node.params() == null ? Map.of() : node.params();
        if (containsRef(params)) {
            return Mapped.hold("参数包含 $ref 运行时绑定，不是静态动作");
        }
        return switch (node.nodeType()) {
            case "rgb.effect" -> mapRgbEffect(params, schema);
            case "audio.record" -> mapAudioRecord(params, schema);
            case "audio.play" -> mapAudioPlay(params, schema);
            case "display.section" -> mapDisplaySection(params, schema);
            case "ui.update" -> Mapped.hold("ui.update 需要平台按模板渲染内容");
            default -> Mapped.hold("未登记的输出节点类型: " + node.nodeType());
        };
    }

    // ── 各节点类型的映射规则 ────────────────────────────────────────────────

    private Mapped mapRgbEffect(Map<String, Object> params, CapabilitySchemaV2 schema) {
        if (Boolean.TRUE.equals(params.get("off"))) {
            return verify("rgb.effect.set", Map.of("r", 0, "g", 0, "b", 0), schema);
        }
        Integer r = asInt(params.get("r"));
        Integer g = asInt(params.get("g"));
        Integer b = asInt(params.get("b"));
        if (r != null && g != null && b != null) {
            return verify("rgb.effect.set", Map.of("r", r, "g", g, "b", b), schema);
        }
        return Mapped.hold("rgb.effect 的 mode 需要平台解析为具体通道值");
    }

    private Mapped mapAudioRecord(Map<String, Object> params, CapabilitySchemaV2 schema) {
        String control = NodeWorkflowSupport.string(params.get("control")).trim();
        return switch (control) {
            case "start" -> verify("audio.record.start", Map.of(), schema);
            case "stop" -> verify("audio.record.stop", Map.of(), schema);
            case "toggle" -> Mapped.hold("audio.record 的 toggle 依赖运行时录音状态，非静态动作");
            case "" -> Mapped.hold("audio.record 缺少 control 参数");
            default -> Mapped.hold("audio.record 不支持的 control 取值: " + control);
        };
    }

    private Mapped mapAudioPlay(Map<String, Object> params, CapabilitySchemaV2 schema) {
        for (String produced : PLATFORM_PRODUCED_PARAMS) {
            String value = NodeWorkflowSupport.string(params.get(produced)).trim();
            if (!value.isEmpty()) {
                return Mapped.hold("audio.play 需要平台产出音频内容（参数 " + produced + "）");
            }
        }
        String preset = NodeWorkflowSupport.string(params.get("preset")).trim();
        if (preset.isEmpty()) {
            return Mapped.hold("audio.play 缺少静态 preset 参数");
        }
        return verify("prompt.play", Map.of("preset", preset), schema);
    }

    private Mapped mapDisplaySection(Map<String, Object> params, CapabilitySchemaV2 schema) {
        for (String rendered : PLATFORM_RENDERED_PARAMS) {
            if (params.containsKey(rendered)) {
                return Mapped.hold("display.section 需要平台渲染 Section 内容（参数 " + rendered + "）");
            }
        }
        String sectionId = NodeWorkflowSupport.string(params.get("sectionId")).trim();
        if (sectionId.isEmpty()) {
            return Mapped.hold("display.section 缺少静态 sectionId 参数");
        }
        return verify("display.section.show", Map.of("sectionId", sectionId), schema);
    }

    // ── 能力 Schema 门禁 ────────────────────────────────────────────────────

    /** 动作必须在能力 Schema 中声明为可用于本地绑定，否则不下沉。 */
    private Mapped verify(String action, Map<String, Object> params, CapabilitySchemaV2 schema) {
        if (schema == null) {
            return Mapped.hold("设备能力 Schema 未同步，无法确认动作 " + action + " 是否可用");
        }
        CapabilitySchemaV2.ActionSpec spec = schema.action(action);
        if (spec == null) {
            return Mapped.hold("终端能力 Schema 未声明动作 " + action);
        }
        if (!spec.usableInBinding()) {
            return Mapped.hold("动作 " + action + " 未声明可用于本地响应序列（usableIn 不含 binding）");
        }
        return Mapped.sink(action, params);
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /** 递归判断参数中是否存在 {@code {"$ref": ...}} 形态的运行时绑定。 */
    static boolean containsRef(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.containsKey("$ref")) {
                return true;
            }
            for (Object item : map.values()) {
                if (containsRef(item)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (containsRef(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
