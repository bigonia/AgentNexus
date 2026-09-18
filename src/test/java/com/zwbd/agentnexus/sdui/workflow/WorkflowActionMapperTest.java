package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.v2.capability.CapabilitySchemaV2;
import com.zwbd.agentnexus.sdui.v2.sim.SimulatedLcd085Device;
import com.zwbd.agentnexus.sdui.workflow.model.NodeWorkflowNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节点 → 终端静态动作的映射与下沉判定。
 *
 * <p>这里固定的是"响应动作必须全静态"这一条约定（用户 2026-09-18 明确）：任何依赖运行时取值或
 * 平台产物的节点都不下沉。测试用例里刻意放了几个反面例子（{@code audio.record} 的 toggle、
 * 带 {@code $ref} 的参数、需要 TTS 的 audio.play），防止以后有人为了"少绕一圈"把它们放进去。</p>
 */
class WorkflowActionMapperTest {

    private final WorkflowActionMapper mapper = new WorkflowActionMapper();
    private final CapabilitySchemaV2 schema = SimulatedLcd085Device.SCHEMA;

    private NodeWorkflowNode node(String nodeType, Map<String, Object> params) {
        return new NodeWorkflowNode("n1", "slot-1", nodeType, params);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    // ── rgb.effect ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("rgb.effect 的 off 参数翻译为三通道归零")
    void rgbEffectOff() {
        WorkflowActionMapper.Mapped mapped = mapper.map(node("rgb.effect", params("off", true)), schema);
        assertTrue(mapped.sinkable());
        assertEquals("rgb.effect.set", mapped.action());
        assertEquals(Map.of("r", 0, "g", 0, "b", 0), mapped.params());
    }

    @Test
    @DisplayName("rgb.effect 显式给出三通道时按字面量下沉")
    void rgbEffectExplicitChannels() {
        WorkflowActionMapper.Mapped mapped = mapper.map(
                node("rgb.effect", params("r", 255, "g", 128, "b", "0")), schema);
        assertTrue(mapped.sinkable());
        assertEquals(Map.of("r", 255, "g", 128, "b", 0), mapped.params());
    }

    @Test
    @DisplayName("rgb.effect 只给 mode 时不下沉：需要平台解析为具体通道值")
    void rgbEffectModeOnlyIsHeld() {
        WorkflowActionMapper.Mapped mapped = mapper.map(node("rgb.effect", params("mode", "breath")), schema);
        assertFalse(mapped.sinkable());
        assertNotNull(mapped.reason());
        assertTrue(mapped.reason().contains("mode"));
    }

    // ── audio.record ────────────────────────────────────────────────────────

    @Test
    @DisplayName("audio.record 的 start / stop 是静态动作，可下沉")
    void audioRecordStartStop() {
        WorkflowActionMapper.Mapped start = mapper.map(node("audio.record", params("control", "start")), schema);
        assertTrue(start.sinkable());
        assertEquals("audio.record.start", start.action());

        WorkflowActionMapper.Mapped stop = mapper.map(node("audio.record", params("control", "stop")), schema);
        assertTrue(stop.sinkable());
        assertEquals("audio.record.stop", stop.action());
    }

    @Test
    @DisplayName("audio.record 的 toggle 依赖运行时状态，不下沉")
    void audioRecordToggleIsHeld() {
        WorkflowActionMapper.Mapped mapped = mapper.map(node("audio.record", params("control", "toggle")), schema);
        assertFalse(mapped.sinkable());
        assertTrue(mapped.reason().contains("toggle"));
    }

    @Test
    @DisplayName("audio.record 缺少 control 时不下沉")
    void audioRecordWithoutControlIsHeld() {
        assertFalse(mapper.map(node("audio.record", Map.of()), schema).sinkable());
    }

    // ── audio.play ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("audio.play 的静态 preset 可下沉")
    void audioPlayPresetIsSinkable() {
        WorkflowActionMapper.Mapped mapped = mapper.map(node("audio.play", params("preset", "start")), schema);
        assertTrue(mapped.sinkable());
        assertEquals("prompt.play", mapped.action());
        assertEquals(Map.of("preset", "start"), mapped.params());
    }

    @Test
    @DisplayName("audio.play 依赖 TTS 文本或音频产物时不下沉")
    void audioPlayWithPlatformProducedContentIsHeld() {
        assertFalse(mapper.map(node("audio.play", params("text", "你好")), schema).sinkable());
        assertFalse(mapper.map(node("audio.play", params("artifact_id", "art-1")), schema).sinkable());
        assertFalse(mapper.map(node("audio.play", params("audio_file", "a.wav")), schema).sinkable());
    }

    @Test
    @DisplayName("audio.play 没有任何可用参数时不下沉")
    void audioPlayWithoutContentIsHeld() {
        WorkflowActionMapper.Mapped mapped = mapper.map(node("audio.play", Map.of()), schema);
        assertFalse(mapped.sinkable());
        assertTrue(mapped.reason().contains("preset"));
    }

    // ── UI 类 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("display.section 只有静态 sectionId 时可下沉")
    void displaySectionWithStaticSectionId() {
        WorkflowActionMapper.Mapped mapped = mapper.map(
                node("display.section", params("sectionId", "sec-status")), schema);
        assertTrue(mapped.sinkable());
        assertEquals("display.section.show", mapped.action());
        assertEquals(Map.of("sectionId", "sec-status"), mapped.params());
    }

    @Test
    @DisplayName("display.section 带 scene / pageId 时需要平台渲染，不下沉")
    void displaySectionRequiringRenderingIsHeld() {
        assertFalse(mapper.map(node("display.section", params("scene", Map.of())), schema).sinkable());
        assertFalse(mapper.map(node("display.section", params("pageId", "p-1")), schema).sinkable());
    }

    @Test
    @DisplayName("ui.update 始终不下沉")
    void uiUpdateIsAlwaysHeld() {
        WorkflowActionMapper.Mapped mapped = mapper.map(
                node("ui.update", params("templateKey", "t", "value", "v")), schema);
        assertFalse(mapped.sinkable());
        assertTrue(mapped.reason().contains("模板渲染"));
    }

    // ── 静态性与能力门禁 ────────────────────────────────────────────────────

    @Test
    @DisplayName("参数含 $ref 运行时绑定时一律不下沉")
    void refBoundParamsAreHeld() {
        WorkflowActionMapper.Mapped mapped = mapper.map(
                node("rgb.effect", params("r", Map.of("$ref", "upstream-node"), "g", 0, "b", 0)), schema);
        assertFalse(mapped.sinkable());
        assertTrue(mapped.reason().contains("$ref"));
    }

    @Test
    @DisplayName("嵌套在集合里的 $ref 同样被识别")
    void nestedRefIsDetected() {
        WorkflowActionMapper.Mapped mapped = mapper.map(
                node("display.section", params("sectionId", java.util.List.of(Map.of("$ref", "n0")))), schema);
        assertFalse(mapped.sinkable());
    }

    @Test
    @DisplayName("终端能力 Schema 未声明该动作时不下沉")
    void actionMissingFromSchemaIsHeld() {
        WorkflowActionMapper.Mapped mapped = mapper.map(node("audio.play", params("preset", "start")), schemaOrNothing());
        assertFalse(mapped.sinkable());
        assertTrue(mapped.reason().contains("prompt.play"));
    }

    @Test
    @DisplayName("能力 Schema 未同步时不下沉")
    void nullSchemaIsHeld() {
        WorkflowActionMapper.Mapped mapped = mapper.map(node("rgb.effect", params("off", true)), null);
        assertFalse(mapped.sinkable());
        assertTrue(mapped.reason().contains("Schema"));
    }

    @Test
    @DisplayName("未登记的节点类型不下沉")
    void unknownNodeTypeIsHeld() {
        WorkflowActionMapper.Mapped mapped = mapper.map(node("magic.effect", Map.of()), schema);
        assertFalse(mapped.sinkable());
        assertTrue(mapped.reason().contains("magic.effect"));
    }

    /** 一个声明了 Trigger 但没有任何动作的最小 Schema。 */
    private CapabilitySchemaV2 schemaOrNothing() {
        return new CapabilitySchemaV2("2", "1", "EMPTY",
                java.util.List.of(new CapabilitySchemaV2.TriggerSpec("button.ok", "physical", true, 4)),
                java.util.List.of(),
                null);
    }
}
