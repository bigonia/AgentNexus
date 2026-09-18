package com.zwbd.agentnexus.sdui.capability.node;

import com.zwbd.agentnexus.sdui.v2.capability.CapabilityQueryService;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilitySchemaV2;
import com.zwbd.agentnexus.sdui.workflow.NodeTypeRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 节点目录的内容完全由「节点类型登记表 + 设备声明的能力 Schema」推导，因此这里的用例只构造这两处输入。
 */
class CapabilityNodeCatalogServiceTest {

    private static final String DEVICE = "dev-node";

    private CapabilityQueryService capabilities;
    private CapabilityNodeCatalogService service;

    @BeforeEach
    void setUp() {
        capabilities = mock(CapabilityQueryService.class);
        service = new CapabilityNodeCatalogService(new NodeTypeRegistry(), capabilities);
        when(capabilities.online(DEVICE)).thenReturn(true);
        when(capabilities.sync(DEVICE)).thenReturn(Map.of("state", "SYNCED"));
    }

    @Test
    @DisplayName("schema 声明的动作与触发源都成为节点")
    void buildsNodesFromSchema() {
        givenSchema(schema(
                List.of(
                        trigger("button.ok", "physical", true, 3),
                        trigger("button.pwr", "physical", true, 3),
                        trigger("platform.trigger.say", "platform", true, 5)),
                List.of(
                        action("rgb.effect.set", List.of(), List.of("binding")),
                        action("audio.record.toggle", List.of(), List.of("binding")),
                        action("prompt.play", List.of(param("preset", "enum", false,
                                List.of("beep", "success"))), List.of("binding")),
                        action("display.section.show", List.of(), List.of("binding")),
                        action("display.section", List.of(), List.of("request")))));

        CapabilityNodeCatalog catalog = service.buildForDevice(DEVICE);

        assertTrue(catalog.online());
        assertEquals("SYNCED", catalog.status());
        assertTrue(catalog.unresolvedNodes().isEmpty(), "全部节点都应可用");

        // 触发节点按来源族分组，具体触发源进入 eventId 取值域
        assertTrue(hasNode(catalog, "button.trigger"));
        assertTrue(hasNode(catalog, "platform.trigger"));
        Map<String, Object> eventIdParam = node(catalog, "button.trigger").parameters().stream()
                .filter(p -> "eventId".equals(p.get("name")))
                .findFirst().orElseThrow();
        assertEquals(2, ((List<?>) eventIdParam.get("values")).size());

        assertTrue(hasNode(catalog, "rgb.effect"));
        assertTrue(hasNode(catalog, "audio.record"));
        assertTrue(hasNode(catalog, "audio.play"));
        assertTrue(hasNode(catalog, "display.section"));
        // 无终端动作的节点仍然可用——平台自己渲染下发
        assertTrue(hasNode(catalog, "ui.update"));
    }

    @Test
    @DisplayName("参数取值域来自设备 Schema，而非平台侧硬编码")
    void parameterValuesComeFromSchema() {
        givenSchema(schema(List.of(), List.of(
                action("prompt.play", List.of(param("preset", "enum", false, List.of("beep", "alarm"))),
                        List.of("binding")))));

        CapabilityNodeDefinition play = node(service.buildForDevice(DEVICE), "audio.play");

        Map<String, Object> preset = play.parameters().stream()
                .filter(p -> "preset".equals(p.get("name")))
                .findFirst().orElseThrow();
        assertEquals(List.of("beep", "alarm"), preset.get("values"));
    }

    @Test
    @DisplayName("schema 未声明的动作让节点进入 unresolved 并给出原因")
    void undeclaredActionsBecomeUnresolved() {
        givenSchema(schema(List.of(), List.of(
                action("audio.record.toggle", List.of(), List.of("binding")))));

        CapabilityNodeCatalog catalog = service.buildForDevice(DEVICE);

        assertFalse(hasNode(catalog, "rgb.effect"));
        assertTrue(unresolvedIds(catalog).contains("rgb.effect"));
        assertTrue(unresolvedReason(catalog, "rgb.effect").contains("rgb.effect.set"));
        assertTrue(unresolvedReason(catalog, "rgb.effect").contains("未声明"));
    }

    @Test
    @DisplayName("不可配置的触发源不进入目录，但记录原因")
    void nonConfigurableTriggersAreReported() {
        givenSchema(schema(List.of(
                trigger("button.ok", "physical", true, 3),
                trigger("button.secret", "physical", false, null)),
                List.of()));

        CapabilityNodeCatalog catalog = service.buildForDevice(DEVICE);

        Map<String, Object> eventIdParam = node(catalog, "button.trigger").parameters().stream()
                .filter(p -> "eventId".equals(p.get("name")))
                .findFirst().orElseThrow();
        assertEquals(1, ((List<?>) eventIdParam.get("values")).size());
        assertTrue(unresolvedReason(catalog, "button.secret").contains("configurable"));
    }

    @Test
    @DisplayName("能力未同步时不产出任何节点，并指明原因")
    void withoutSchemaNothingIsAvailable() {
        when(capabilities.schemaOf(DEVICE)).thenReturn(Optional.empty());
        when(capabilities.sync(DEVICE)).thenReturn(Map.of("state", "PENDING"));

        CapabilityNodeCatalog catalog = service.buildForDevice(DEVICE);

        assertEquals("PENDING", catalog.status());
        assertTrue(catalog.nodes().isEmpty());
        assertEquals(1, catalog.unresolvedNodes().size());
        assertEquals("device", catalog.unresolvedNodes().get(0).get("kind"));
    }

    // ── 构造 ───────────────────────────────────────────────────────────────

    private void givenSchema(CapabilitySchemaV2 schema) {
        when(capabilities.schemaOf(DEVICE)).thenReturn(Optional.of(schema));
    }

    private static CapabilitySchemaV2 schema(List<CapabilitySchemaV2.TriggerSpec> triggers,
                                             List<CapabilitySchemaV2.ActionSpec> actions) {
        return new CapabilitySchemaV2("2.0", "1", "ESP32-S3-LCD-0.85", triggers, actions, null);
    }

    private static CapabilitySchemaV2.TriggerSpec trigger(String id, String source,
                                                          boolean configurable, Integer maxResponses) {
        return new CapabilitySchemaV2.TriggerSpec(id, source, configurable, maxResponses);
    }

    private static CapabilitySchemaV2.ActionSpec action(String name,
                                                        List<CapabilitySchemaV2.ParamSpec> params,
                                                        List<String> usableIn) {
        return new CapabilitySchemaV2.ActionSpec(name, params, usableIn);
    }

    private static CapabilitySchemaV2.ParamSpec param(String name, String type, boolean required,
                                                      List<String> values) {
        return new CapabilitySchemaV2.ParamSpec(name, type, required, null, null, values);
    }

    private static boolean hasNode(CapabilityNodeCatalog catalog, String nodeType) {
        return catalog.nodes().stream().anyMatch(node -> nodeType.equals(node.nodeType()));
    }

    private static CapabilityNodeDefinition node(CapabilityNodeCatalog catalog, String nodeType) {
        return catalog.nodes().stream()
                .filter(n -> nodeType.equals(n.nodeType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("节点缺失: " + nodeType));
    }

    private static List<String> unresolvedIds(CapabilityNodeCatalog catalog) {
        return catalog.unresolvedNodes().stream()
                .map(item -> String.valueOf(item.get("id")))
                .toList();
    }

    private static String unresolvedReason(CapabilityNodeCatalog catalog, String id) {
        return catalog.unresolvedNodes().stream()
                .filter(item -> id.equals(item.get("id")))
                .map(item -> String.valueOf(item.get("reason")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 unresolved 条目: " + id));
    }
}
