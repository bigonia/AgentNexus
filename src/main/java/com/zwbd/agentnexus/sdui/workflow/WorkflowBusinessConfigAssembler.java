package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.v2.V2ProtocolProperties;
import com.zwbd.agentnexus.sdui.v2.business.BusinessConfig;
import com.zwbd.agentnexus.sdui.v2.business.BusinessConfigService;
import com.zwbd.agentnexus.sdui.v2.business.BusinessConfigValidator;
import com.zwbd.agentnexus.sdui.v2.business.ResponseStep;
import com.zwbd.agentnexus.sdui.v2.business.TriggerBinding;
import com.zwbd.agentnexus.sdui.v2.business.TriggerSource;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilitySchemaV2;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowDefinition;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 把一个工作流部署翻译成每台设备的 {@code BusinessConfig}。
 *
 * <p>对应 10_PLATFORM_UPGRADE.md §8：一个 deployment 对应设备当前的一份 {@code BusinessConfig}，
 * 工作流节点产出配置片段，部署时合并为全量配置。本类就是这个"合并"的实现。</p>
 *
 * <h2>组装规则</h2>
 * <ol>
 *   <li>只处理绑定到目标设备的 slot 上的<b>触发节点</b>（{@code nodeType} 以 {@code .trigger} 结尾）。</li>
 *   <li>{@code triggerId} 取触发节点的 {@code eventId}，并必须出现在能力 Schema 的可配置 Trigger 中。</li>
 *   <li>响应序列按执行计划逐条下沉——遇到第一个<b>同 slot 且不可下沉</b>的节点就停止，
 *       之后的节点全部留给平台。因为这些节点的产物是后续动作的输入，终端拿不到。</li>
 *   <li><b>跨 slot</b> 节点不下沉，但也<b>不阻断</b>本 slot 的静态前缀：它属于别的设备，
 *       不需要本设备提供输入，只是需要平台在收到交互上报后去协调。</li>
 *   <li>只要产生了任何平台步骤，就在序列尾部追加一个 {@code platform.interaction.report} 动作
 *       （token 由 {@code BusinessConfigService.prepare} 注入），让终端在本地序列执行完后
 *       告知平台"该继续云端流程了"。没有平台步骤时不追加，保持纯本地闭环。</li>
 * </ol>
 *
 * <p>同一个 {@code triggerId} 出现多次时只保留先出现的绑定并记 WARN。终端侧一个 triggerId 只能有
 * 一条绑定，而合并会破坏"一条绑定尾部一个上报 token"的对应关系。</p>
 *
 * <h2>为什么组装阶段要跑一次下发校验</h2>
 * <p>下发路径本身有 {@code BusinessConfigValidator} 把关。如果只在 {@code business.update} 时校验，
 * 就会形成"部署接口返回成功、设备实际下发失败"的静默失败——部署记录已落库，配置却没生效。
 * 因此组装结束时用 {@code BusinessConfigService.validate} 做一次干跑（无持久副作用），把校验错误
 * 提前变成部署错误。这样部署成功即等价于"配置一定能被终端接受"。</p>
 */
@Service
public class WorkflowBusinessConfigAssembler {

    /** 组装问题的严重级别。ERROR 会阻止部署，WARN 只提示。 */
    public enum Severity {
        ERROR, WARN
    }

    /**
     * 一条组装问题。
     *
     * @param severity 级别
     * @param slotId   所在 slot；无法定位时为 {@code null}
     * @param nodeId   所在节点；无法定位时为 {@code null}
     * @param message  说明
     */
    public record Issue(Severity severity, String slotId, String nodeId, String message) {

        public Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("severity", severity.name().toLowerCase());
            if (slotId != null) {
                data.put("slotId", slotId);
            }
            if (nodeId != null) {
                data.put("nodeId", nodeId);
            }
            data.put("message", message);
            return data;
        }
    }

    /**
     * 一个必须由平台在收到交互上报后执行的步骤。
     *
     * @param reason 为什么不能下沉
     */
    public record PlatformStep(String slotId, String nodeId, String nodeType,
                               Map<String, Object> params, String reason) {

        public Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("slotId", slotId);
            data.put("nodeId", nodeId);
            data.put("nodeType", nodeType);
            data.put("params", params);
            data.put("reason", reason);
            return data;
        }
    }

    /**
     * 一台设备的组装结果。
     *
     * @param config        草稿配置；{@code configVersion} 为 0，由 {@code BusinessConfigService.prepare} 赋予
     * @param platformSteps 留在平台的步骤
     * @param issues        组装问题
     */
    public record Assembled(String deviceId, BusinessConfig config,
                            List<PlatformStep> platformSteps, List<Issue> issues) {

        public boolean hasErrors() {
            return issues.stream().anyMatch(issue -> issue.severity() == Severity.ERROR);
        }

        public List<String> errorMessages() {
            return issues.stream()
                    .filter(issue -> issue.severity() == Severity.ERROR)
                    .map(Issue::message)
                    .toList();
        }

        /** 下沉到终端的响应动作总数。 */
        public int localResponseCount() {
            return config.triggers().stream().mapToInt(binding -> binding.responses().size()).sum();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deviceId", deviceId);
            data.put("bindings", config.triggers().size());
            data.put("localResponses", localResponseCount());
            data.put("platformSteps", platformSteps.stream().map(PlatformStep::toMap).toList());
            data.put("issues", issues.stream().map(Issue::toMap).toList());
            return data;
        }
    }

    private final WorkflowActionMapper actionMapper;
    private final V2ProtocolProperties properties;
    private final BusinessConfigService businessConfigService;

    public WorkflowBusinessConfigAssembler(WorkflowActionMapper actionMapper,
                                           V2ProtocolProperties properties,
                                           BusinessConfigService businessConfigService) {
        this.actionMapper = actionMapper;
        this.properties = properties;
        this.businessConfigService = businessConfigService;
    }

    /**
     * 组装一台设备的业务配置。
     *
     * @param workflow  工作流定义
     * @param slotBindings slotId → deviceId
     * @param deviceId  目标设备
     * @param schema    该设备当前生效的能力 Schema；为 {@code null} 表示尚未同步
     */
    public Assembled assemble(NodeWorkflowDefinition workflow,
                             Map<String, String> slotBindings,
                             String deviceId,
                             CapabilitySchemaV2 schema) {
        List<Issue> issues = new ArrayList<>();
        List<PlatformStep> platformSteps = new ArrayList<>();

        if (schema == null) {
            issues.add(new Issue(Severity.ERROR, null, null,
                    "设备 " + deviceId + " 的能力 Schema 尚未同步，无法校验动作可用性；"
                            + "请等待能力协商完成后重新部署"));
            return new Assembled(deviceId, BusinessConfig.empty(deviceId, 0L), List.of(), issues);
        }

        List<String> slots = slotsOf(slotBindings, deviceId);
        if (slots.isEmpty()) {
            return new Assembled(deviceId, BusinessConfig.empty(deviceId, 0L), List.of(), issues);
        }

        Map<String, TriggerBinding> byTriggerId = new LinkedHashMap<>();
        for (NodeWorkflowNode trigger : workflow.nodes()) {
            if (!NodeWorkflowSupport.isTriggerNode(trigger.nodeType())) {
                continue;
            }
            if (!slots.contains(trigger.slotId())) {
                continue;
            }
            TriggerBinding binding = assembleBinding(workflow, trigger, schema, issues, platformSteps);
            if (binding == null) {
                continue;
            }
            if (byTriggerId.containsKey(binding.triggerId())) {
                issues.add(new Issue(Severity.WARN, trigger.slotId(), trigger.nodeId(),
                        "triggerId 重复，已保留先出现的绑定: " + binding.triggerId()));
                continue;
            }
            byTriggerId.put(binding.triggerId(), binding);
        }

        if (byTriggerId.size() > properties.getMaxBindingsPerConfig()) {
            issues.add(new Issue(Severity.ERROR, null, null,
                    "绑定数量 " + byTriggerId.size() + " 超过平台上限 "
                            + properties.getMaxBindingsPerConfig()));
        }

        BusinessConfig config = new BusinessConfig(deviceId, 0L, List.copyOf(byTriggerId.values()));
        validateAgainstDownstream(config, issues);
        return new Assembled(deviceId, config, List.copyOf(platformSteps), List.copyOf(issues));
    }

    /**
     * 用下发路径的校验器干跑一次，把"部署成功但下发失败"提前暴露成部署错误。
     *
     * <p>只对非空配置执行：空配置没有绑定也没有响应动作，校验必然通过，跑一次只是浪费。
     * 干跑会为 {@code platform.interaction.report} 步骤临时签发 token，{@code validate} 内部
     * 在 {@code finally} 中全部撤销，不留下副作用（见 12_DESIGN_NOTES.md §4.6）。</p>
     */
    private void validateAgainstDownstream(BusinessConfig config, List<Issue> issues) {
        if (config.isEmpty()) {
            return;
        }
        BusinessConfigValidator.ValidationResult result =
                businessConfigService.validate(config.deviceId(), config);
        if (result.ok()) {
            return;
        }
        for (String error : result.errors()) {
            issues.add(new Issue(Severity.ERROR, null, null, "下发校验不通过: " + error));
        }
    }

    // ── 单条绑定 ────────────────────────────────────────────────────────────

    private TriggerBinding assembleBinding(NodeWorkflowDefinition workflow,
                                           NodeWorkflowNode trigger,
                                           CapabilitySchemaV2 schema,
                                           List<Issue> issues,
                                           List<PlatformStep> platformSteps) {
        String triggerId = NodeWorkflowSupport.string(trigger.params().get("eventId")).trim();
        if (triggerId.isEmpty()) {
            issues.add(new Issue(Severity.ERROR, trigger.slotId(), trigger.nodeId(),
                    "触发节点缺少 eventId，无法生成绑定"));
            return null;
        }

        CapabilitySchemaV2.TriggerSpec triggerSpec = schema.trigger(triggerId);
        if (triggerSpec == null) {
            issues.add(new Issue(Severity.ERROR, trigger.slotId(), trigger.nodeId(),
                    "终端能力 Schema 未声明 Trigger: " + triggerId));
            return null;
        }
        if (!triggerSpec.configurable()) {
            issues.add(new Issue(Severity.ERROR, trigger.slotId(), trigger.nodeId(),
                    "Trigger 未声明允许平台配置: " + triggerId));
            return null;
        }

        TriggerSource source = TriggerSource.infer(triggerId);
        if (source == null) {
            issues.add(new Issue(Severity.ERROR, trigger.slotId(), trigger.nodeId(),
                    "无法从 triggerId 推断 Trigger 来源: " + triggerId));
            return null;
        }

        List<ResponseStep> responses = new ArrayList<>();
        List<PlatformStep> localPlatformSteps = new ArrayList<>();
        boolean platformPhase = false;

        for (NodeWorkflowNode target : NodeWorkflowSupport.executionPlan(workflow, trigger)) {
            if (!target.slotId().equals(trigger.slotId())) {
                localPlatformSteps.add(new PlatformStep(target.slotId(), target.nodeId(), target.nodeType(),
                        target.params(), "跨 slot 节点，由平台在收到交互上报后协调"));
                continue;
            }
            WorkflowActionMapper.Mapped mapped = actionMapper.map(target, schema);
            if (!mapped.sinkable()) {
                platformPhase = true;
                localPlatformSteps.add(new PlatformStep(target.slotId(), target.nodeId(), target.nodeType(),
                        target.params(), mapped.reason()));
                continue;
            }
            if (platformPhase) {
                localPlatformSteps.add(new PlatformStep(target.slotId(), target.nodeId(), target.nodeType(),
                        target.params(), "位于平台步骤之后，需平台在收到交互上报后再驱动"));
                continue;
            }
            responses.add(mapped.toStep());
        }

        if (responses.isEmpty() && localPlatformSteps.isEmpty()) {
            issues.add(new Issue(Severity.WARN, trigger.slotId(), trigger.nodeId(),
                    "触发节点没有任何下游输出节点，未生成绑定: " + triggerId));
            return null;
        }

        if (!localPlatformSteps.isEmpty()) {
            // 尾部上报步骤：token 由 BusinessConfigService.prepare 注入。
            responses.add(ResponseStep.of(V2Names.ACTION_PLATFORM_REPORT, Map.of()));
        }

        int maxResponses = triggerSpec.maxResponses() != null
                ? triggerSpec.maxResponses()
                : properties.getMaxResponsesPerBinding();
        if (responses.size() > maxResponses) {
            issues.add(new Issue(Severity.ERROR, trigger.slotId(), trigger.nodeId(),
                    "Trigger " + triggerId + " 的响应序列长度 " + responses.size()
                            + " 超过上限 " + maxResponses));
            return null;
        }

        platformSteps.addAll(localPlatformSteps);
        return new TriggerBinding(triggerId, source, null, responses);
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /** 某设备被绑定到的全部 slotId，按声明顺序去重。 */
    private static List<String> slotsOf(Map<String, String> slotBindings, String deviceId) {
        LinkedHashSet<String> slots = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : slotBindings.entrySet()) {
            if (deviceId.equals(entry.getValue())) {
                slots.add(entry.getKey());
            }
        }
        return List.copyOf(slots);
    }

    /** 绑定表中出现的全部设备，按声明顺序去重。 */
    public static List<String> devicesOf(Map<String, String> slotBindings) {
        LinkedHashSet<String> devices = new LinkedHashSet<>();
        for (String deviceId : slotBindings.values()) {
            if (deviceId != null && !deviceId.isBlank()) {
                devices.add(deviceId);
            }
        }
        return List.copyOf(devices);
    }
}
