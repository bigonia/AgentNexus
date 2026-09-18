package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.v2.V2ProtocolProperties;
import com.zwbd.agentnexus.sdui.v2.business.BusinessConfigService;
import com.zwbd.agentnexus.sdui.v2.business.BusinessConfigValidator;
import com.zwbd.agentnexus.sdui.v2.business.InteractionTokenService;
import com.zwbd.agentnexus.sdui.v2.business.TriggerSource;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilityRegistryV2;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilitySchemaV2;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;
import com.zwbd.agentnexus.sdui.v2.sim.SimulatedLcd085Device;
import com.zwbd.agentnexus.sdui.v2.transport.PlatformRequestService;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowDefinition;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowEdge;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowNode;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 工作流部署 → 每设备业务配置的组装。
 *
 * <p>这些用例固定 P5a 的几条关键约定：静态前缀在首个同 slot 平台步骤处截断、跨 slot 步骤不截断、
 * 只要存在平台步骤就追加一个上报动作、triggerId 冲突只保留先出现的。</p>
 *
 * <p>另外固定一条容易退化的不变量：<b>组装通过 == 下发一定被接受</b>。组装阶段会调用
 * {@link BusinessConfigService#validate} 干跑，所以在部署接口返回成功之后，不会出现
 * "配置写进了部署记录却没生效"的静默失败。</p>
 */
class WorkflowBusinessConfigAssemblerTest {

    private static final String DEVICE = "dev-1";
    private static final String OTHER_DEVICE = "dev-2";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final V2ProtocolProperties properties = new V2ProtocolProperties();
    private final WorkflowActionMapper actionMapper = new WorkflowActionMapper();
    private final CapabilitySchemaV2 schema = SimulatedLcd085Device.SCHEMA;

    private BusinessConfigService businessConfigService;
    private WorkflowBusinessConfigAssembler assembler;

    @BeforeEach
    void setUp() {
        CapabilityRegistryV2 capabilities = new CapabilityRegistryV2(objectMapper);
        capabilities.onHandshake(DEVICE, capabilities.cache(schema));
        businessConfigService = new BusinessConfigService(
                capabilities,
                new BusinessConfigValidator(properties),
                new InteractionTokenService(),
                mock(PlatformRequestService.class),
                properties,
                mock(ApplicationEventPublisher.class));
        assembler = new WorkflowBusinessConfigAssembler(actionMapper, properties, businessConfigService);
    }

    // ── 基本组装 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("物理触发 + 静态动作组装出一条可下发的绑定")
    void assemblesPhysicalBindingFromStaticChain() {
        NodeWorkflowDefinition workflow = workflow(
                List.of(slot("slot-1")),
                List.of(trigger("t1", "slot-1", "button.ok"), rgbOff("n1", "slot-1")),
                List.of(edge("t1", "n1")));

        WorkflowBusinessConfigAssembler.Assembled assembled = assemble(workflow, Map.of("slot-1", DEVICE));

        assertFalse(assembled.hasErrors(), () -> String.valueOf(assembled.errorMessages()));
        assertEquals(1, assembled.config().triggers().size());
        assertEquals(List.of("rgb.effect.set"), actions(assembled));
        assertEquals(TriggerSource.PHYSICAL, assembled.config().triggers().get(0).source());
        assertTrue(assembled.platformSteps().isEmpty());
    }

    @Test
    @DisplayName("同 slot 出现平台步骤时，静态前缀截断且尾部追加一次交互上报")
    void truncatesAtPlatformStepAndAppendsReport() {
        // t1 → n1(静态) → n2(需 TTS) → n3(静态，位于平台步骤之后)
        NodeWorkflowDefinition workflow = workflow(
                List.of(slot("slot-1")),
                List.of(
                        trigger("t1", "slot-1", "button.ok"),
                        rgbOff("n1", "slot-1"),
                        node("n2", "slot-1", "audio.play", params("text", "欢迎")),
                        rgbOff("n3", "slot-1")),
                List.of(edge("t1", "n1"), edge("n1", "n2"), edge("n2", "n3")));

        WorkflowBusinessConfigAssembler.Assembled assembled = assemble(workflow, Map.of("slot-1", DEVICE));

        assertFalse(assembled.hasErrors());
        assertEquals(List.of("rgb.effect.set", V2Names.ACTION_PLATFORM_REPORT), actions(assembled));
        assertEquals(List.of("n2", "n3"), assembled.platformSteps().stream()
                .map(WorkflowBusinessConfigAssembler.PlatformStep::nodeId).toList());
    }

    @Test
    @DisplayName("跨 slot 节点不下沉，但不截断本 slot 的静态前缀")
    void crossSlotNodeDoesNotBreakLocalPrefix() {
        NodeWorkflowDefinition workflow = workflow(
                List.of(slot("slot-1"), slot("slot-2")),
                List.of(
                        trigger("t1", "slot-1", "button.ok"),
                        rgbOff("n1", "slot-1"),
                        rgbOff("n2", "slot-2")),
                List.of(edge("t1", "n1"), edge("n1", "n2")));

        WorkflowBusinessConfigAssembler.Assembled assembled = assemble(workflow,
                Map.of("slot-1", DEVICE, "slot-2", OTHER_DEVICE));

        assertFalse(assembled.hasErrors());
        assertEquals(List.of("rgb.effect.set", V2Names.ACTION_PLATFORM_REPORT), actions(assembled));
        assertEquals(List.of("n2"), assembled.platformSteps().stream()
                .map(WorkflowBusinessConfigAssembler.PlatformStep::nodeId).toList());
        assertTrue(assembled.platformSteps().get(0).reason().contains("跨 slot"));
    }

    @Test
    @DisplayName("设备未被任何 slot 绑定时得到空配置且无报错")
    void unboundDeviceYieldsEmptyConfig() {
        NodeWorkflowDefinition workflow = workflow(
                List.of(slot("slot-1")),
                List.of(trigger("t1", "slot-1", "button.ok"), rgbOff("n1", "slot-1")),
                List.of(edge("t1", "n1")));

        WorkflowBusinessConfigAssembler.Assembled assembled = assemble(workflow, Map.of("slot-1", OTHER_DEVICE));

        assertFalse(assembled.hasErrors());
        assertTrue(assembled.config().isEmpty());
    }

    // ── 错误路径 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("能力 Schema 未同步时拒绝组装")
    void missingSchemaIsRejected() {
        NodeWorkflowDefinition workflow = workflow(
                List.of(slot("slot-1")),
                List.of(trigger("t1", "slot-1", "button.ok"), rgbOff("n1", "slot-1")),
                List.of(edge("t1", "n1")));

        WorkflowBusinessConfigAssembler.Assembled assembled =
                assembler.assemble(workflow, Map.of("slot-1", DEVICE), DEVICE, null);

        assertTrue(assembled.hasErrors());
        assertTrue(assembled.errorMessages().get(0).contains("Schema"));
    }

    @Test
    @DisplayName("Trigger 未在能力 Schema 中声明时拒绝组装")
    void unknownTriggerIsRejected() {
        NodeWorkflowDefinition workflow = workflow(
                List.of(slot("slot-1")),
                List.of(trigger("t1", "slot-1", "button.power"), rgbOff("n1", "slot-1")),
                List.of(edge("t1", "n1")));

        WorkflowBusinessConfigAssembler.Assembled assembled = assemble(workflow, Map.of("slot-1", DEVICE));

        assertTrue(assembled.hasErrors());
        assertTrue(assembled.errorMessages().get(0).contains("button.power"));
    }

    @Test
    @DisplayName("Trigger 未声明允许配置时拒绝组装")
    void nonConfigurableTriggerIsRejected() {
        CapabilitySchemaV2 locked = new CapabilitySchemaV2("2", "1", "LCD_085",
                List.of(new CapabilitySchemaV2.TriggerSpec("button.ok", "physical", false, 4)),
                schema.actions(),
                schema.surface());

        NodeWorkflowDefinition workflow = workflow(
                List.of(slot("slot-1")),
                List.of(trigger("t1", "slot-1", "button.ok"), rgbOff("n1", "slot-1")),
                List.of(edge("t1", "n1")));

        WorkflowBusinessConfigAssembler.Assembled assembled =
                assembler.assemble(workflow, Map.of("slot-1", DEVICE), DEVICE, locked);

        assertTrue(assembled.hasErrors());
        assertTrue(assembled.errorMessages().get(0).contains("允许平台配置"));
    }

    @Test
    @DisplayName("触发节点缺少 eventId 时拒绝组装")
    void missingEventIdIsRejected() {
        NodeWorkflowDefinition workflow = workflow(
                List.of(slot("slot-1")),
                List.of(trigger("t1", "slot-1", ""), rgbOff("n1", "slot-1")),
                List.of(edge("t1", "n1")));

        WorkflowBusinessConfigAssembler.Assembled assembled = assemble(workflow, Map.of("slot-1", DEVICE));

        assertTrue(assembled.hasErrors());
        assertTrue(assembled.errorMessages().get(0).contains("eventId"));
    }

    @Test
    @DisplayName("响应序列超过 Trigger 声明上限时拒绝组装")
    void tooManyResponsesIsRejected() {
        // 模拟终端的 button.ok 声明 maxResponses=4，这里挂 5 个静态动作。
        List<NodeWorkflowNode> nodes = new ArrayList<>();
        List<NodeWorkflowEdge> edges = new ArrayList<>();
        nodes.add(trigger("t1", "slot-1", "button.ok"));
        for (int i = 1; i <= 5; i++) {
            nodes.add(rgbOff("n" + i, "slot-1"));
            edges.add(edge(i == 1 ? "t1" : "n" + (i - 1), "n" + i));
        }

        WorkflowBusinessConfigAssembler.Assembled assembled =
                assemble(workflow(List.of(slot("slot-1")), nodes, edges), Map.of("slot-1", DEVICE));

        assertTrue(assembled.hasErrors());
        assertTrue(assembled.errorMessages().get(0).contains("响应序列长度"));
    }

    @Test
    @DisplayName("绑定数量超过平台上限时拒绝组装")
    void tooManyBindingsIsRejected() {
        V2ProtocolProperties tight = new V2ProtocolProperties();
        tight.setMaxBindingsPerConfig(1);
        WorkflowBusinessConfigAssembler limited =
                new WorkflowBusinessConfigAssembler(actionMapper, tight, businessConfigService);

        NodeWorkflowDefinition workflow = workflow(
                List.of(slot("slot-1")),
                List.of(
                        trigger("t1", "slot-1", "button.ok"),
                        trigger("t2", "slot-1", "button.up"),
                        rgbOff("n1", "slot-1"),
                        rgbOff("n2", "slot-1")),
                List.of(edge("t1", "n1"), edge("t2", "n2")));

        WorkflowBusinessConfigAssembler.Assembled assembled =
                limited.assemble(workflow, Map.of("slot-1", DEVICE), DEVICE, schema);

        assertTrue(assembled.hasErrors());
        assertTrue(assembled.errorMessages().get(0).contains("绑定数量"));
    }

    @Test
    @DisplayName("组装通过但下发校验不通过时，在部署阶段就报错而不是静默失败")
    void downstreamValidationFailureBecomesAssemblyError() {
        // 模拟终端的 prompt.play 只接受 preset ∈ {start, stop, error}，这里给一个域外值。
        // 动作名合法、参数静态，所以下沉判定会放行；只有下发校验器能拦住它。
        NodeWorkflowDefinition workflow = workflow(
                List.of(slot("slot-1")),
                List.of(
                        trigger("t1", "slot-1", "button.ok"),
                        node("n1", "slot-1", "audio.play", params("preset", "ding"))),
                List.of(edge("t1", "n1")));

        WorkflowBusinessConfigAssembler.Assembled assembled = assemble(workflow, Map.of("slot-1", DEVICE));

        assertTrue(assembled.hasErrors());
        assertTrue(assembled.errorMessages().get(0).contains("下发校验不通过"));
        assertTrue(assembled.errorMessages().get(0).contains("preset"));
    }

    // ── 告警路径 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("同一个 triggerId 出现多次时保留先出现的绑定并告警")
    void duplicateTriggerIdKeepsFirstBinding() {
        NodeWorkflowDefinition workflow = workflow(
                List.of(slot("slot-1")),
                List.of(
                        trigger("t1", "slot-1", "button.ok"),
                        trigger("t2", "slot-1", "button.ok"),
                        rgbOff("n1", "slot-1"),
                        node("n2", "slot-1", "audio.record", params("control", "start"))),
                List.of(edge("t1", "n1"), edge("t2", "n2")));

        WorkflowBusinessConfigAssembler.Assembled assembled = assemble(workflow, Map.of("slot-1", DEVICE));

        assertFalse(assembled.hasErrors());
        assertEquals(1, assembled.config().triggers().size());
        assertEquals(List.of("rgb.effect.set"), actions(assembled));
        assertTrue(assembled.issues().stream().anyMatch(
                issue -> issue.severity() == WorkflowBusinessConfigAssembler.Severity.WARN));
    }

    @Test
    @DisplayName("触发节点没有下游输出时不生成绑定，只记告警")
    void triggerWithoutDownstreamYieldsWarningOnly() {
        NodeWorkflowDefinition workflow = workflow(
                List.of(slot("slot-1")),
                List.of(trigger("t1", "slot-1", "button.ok")),
                List.of());

        WorkflowBusinessConfigAssembler.Assembled assembled = assemble(workflow, Map.of("slot-1", DEVICE));

        assertFalse(assembled.hasErrors());
        assertTrue(assembled.config().isEmpty());
        assertNotNull(assembled.issues().get(0));
        assertEquals(WorkflowBusinessConfigAssembler.Severity.WARN, assembled.issues().get(0).severity());
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private WorkflowBusinessConfigAssembler.Assembled assemble(NodeWorkflowDefinition workflow,
                                                              Map<String, String> bindings) {
        return assembler.assemble(workflow, bindings, DEVICE, schema);
    }

    private static List<String> actions(WorkflowBusinessConfigAssembler.Assembled assembled) {
        return assembled.config().triggers().stream()
                .flatMap(binding -> binding.responses().stream())
                .map(step -> step.action())
                .toList();
    }

    private static NodeWorkflowDefinition workflow(List<NodeWorkflowSlot> slots,
                                                   List<NodeWorkflowNode> nodes,
                                                   List<NodeWorkflowEdge> edges) {
        return new NodeWorkflowDefinition("wf-1", "测试工作流", slots, nodes, edges);
    }

    private static NodeWorkflowSlot slot(String slotId) {
        return new NodeWorkflowSlot(slotId, "LCD_085", slotId, List.of());
    }

    private static NodeWorkflowNode trigger(String nodeId, String slotId, String eventId) {
        return new NodeWorkflowNode(nodeId, slotId, "button.trigger", params("eventId", eventId));
    }

    private static NodeWorkflowNode rgbOff(String nodeId, String slotId) {
        return node(nodeId, slotId, "rgb.effect", params("off", true));
    }

    private static NodeWorkflowNode node(String nodeId, String slotId, String nodeType,
                                        Map<String, Object> params) {
        return new NodeWorkflowNode(nodeId, slotId, nodeType, params);
    }

    private static NodeWorkflowEdge edge(String from, String to) {
        return new NodeWorkflowEdge(from, to);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
