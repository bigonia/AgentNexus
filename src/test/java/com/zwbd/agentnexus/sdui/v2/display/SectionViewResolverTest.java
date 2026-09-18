package com.zwbd.agentnexus.sdui.v2.display;

import com.zwbd.agentnexus.sdui.section.SectionData;
import com.zwbd.agentnexus.sdui.section.SectionDataCodec;
import com.zwbd.agentnexus.sdui.section.SectionEntry;
import com.zwbd.agentnexus.sdui.section.SectionLayout;
import com.zwbd.agentnexus.sdui.section.SectionScene;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 单 Section 主视图的收敛规则。
 *
 * <p>v2 的主视图是"一个完整 Section、完整替换、无 Patch"，而平台侧的 UI 上下文仍按"页面 → 多个
 * Section"组织。这里固定住两者的转换口径，以及"缺少基底时宁可失败也不猜"的态度。</p>
 */
class SectionViewResolverTest {

    private SectionViewResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SectionViewResolver(new SectionDataCodec(mock(SectionTypeCatalog.class)));
    }

    // ── Scene → 主视图 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("场景有多个 Section 时取声明顺序首个，且只保留 v2 认识的三个字段")
    void takesFirstSectionAndDropsLegacyConcepts() {
        Map<String, Object> scene = scene(
                section("status", "status_section", Map.of("title", "hello")),
                section("metrics", "metrics_section", Map.of()));

        Map<String, Object> primary = resolver.primaryOfScene(scene).orElseThrow();

        assertEquals("status", primary.get("sectionId"));
        assertEquals("status_section", primary.get("sectionType"));
        assertEquals(Map.of("title", "hello"), primary.get("fields"));
        assertEquals(3, primary.size());
        assertFalse(primary.containsKey("pageId"));
        assertFalse(primary.containsKey("layout"));
    }

    @Test
    @DisplayName("兼容 UI 上下文把 sections 存成 id → section 映射的形态")
    void acceptsMapShapedSections() {
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("a", section("a", "text_section", Map.of("body", "first")));
        sections.put("b", section("b", "text_section", Map.of("body", "second")));
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("pageId", "main");
        scene.put("sections", sections);

        assertEquals("a", resolver.primaryOfScene(scene).orElseThrow().get("sectionId"));
    }

    @Test
    @DisplayName("空场景返回空，由调用方决定报错还是忽略")
    void emptySceneYieldsNothing() {
        assertTrue(resolver.primaryOfScene(Map.of()).isEmpty());
        assertTrue(resolver.primaryOfScene(null).isEmpty());
        assertTrue(resolver.primaryOf(null).isEmpty());
    }

    @Test
    @DisplayName("类型化 Scene 走同一条收敛规则")
    void typedSceneUsesSameRule() {
        SectionScene scene = new SectionScene("main", SectionLayout.VERTICAL_SCROLL, false, 0,
                List.of(new SectionEntry("text_section", "body", new SectionData.TextData("t", "b"))));

        Map<String, Object> primary = resolver.primaryOf(scene).orElseThrow();
        assertEquals("body", primary.get("sectionId"));
        assertEquals("text_section", primary.get("sectionType"));
        assertEquals("t", ((Map<?, ?>) primary.get("fields")).get("title"));
    }

    // ── Patch → 完整 Section ────────────────────────────────────────────────

    @Test
    @DisplayName("补丁作用于当前主视图时合并字段，而不是替换整份 Section")
    void mergesFieldsWhenPatchTargetsPrimary() {
        resolver.remember("dev-1", section("status", "status_section",
                Map.of("title", "old", "value", "1")));

        Map<String, Object> merged = resolver.applyPatchJson("dev-1", patch(
                patchEntry("status", "update", "status_section", Map.of("value", "2")))).orElseThrow();

        assertEquals("status", merged.get("sectionId"));
        Map<?, ?> fields = (Map<?, ?>) merged.get("fields");
        assertEquals("old", fields.get("title"));
        assertEquals("2", fields.get("value"));
    }

    @Test
    @DisplayName("补丁指向别的 Section 时视为换一份主视图")
    void switchingPrimaryOnForeignPatch() {
        resolver.remember("dev-1", section("status", "status_section", Map.of("title", "old")));

        Map<String, Object> merged = resolver.applyPatchJson("dev-1", patch(
                patchEntry("metrics", "add", "metrics_section", Map.of("metrics", List.of())))).orElseThrow();

        assertEquals("metrics", merged.get("sectionId"));
        assertEquals("metrics_section", merged.get("sectionType"));
    }

    @Test
    @DisplayName("移除当前主视图后没有主视图可下发")
    void removingPrimaryYieldsNothing() {
        resolver.remember("dev-1", section("status", "status_section", Map.of()));

        assertTrue(resolver.applyPatchJson("dev-1", patch(
                patchEntry("status", "remove", null, null))).isEmpty());
    }

    @Test
    @DisplayName("移除别的 Section 被忽略，主视图保持不变")
    void ignoringForeignRemoval() {
        resolver.remember("dev-1", section("status", "status_section", Map.of("title", "old")));

        Map<String, Object> merged = resolver.applyPatchJson("dev-1", patch(
                patchEntry("other", "remove", null, null))).orElseThrow();

        assertEquals("status", merged.get("sectionId"));
        assertEquals("old", ((Map<?, ?>) merged.get("fields")).get("title"));
    }

    @Test
    @DisplayName("没有主视图基底时返回空，而不是猜一个完整 Section")
    void withoutSnapshotPatchCannotBeSynthesized() {
        assertTrue(resolver.applyPatchJson("dev-1", patch(
                patchEntry("status", "update", "status_section", Map.of()))).isEmpty());
    }

    @Test
    @DisplayName("空补丁返回当前主视图")
    void emptyPatchKeepsCurrent() {
        resolver.remember("dev-1", section("status", "status_section", Map.of("title", "old")));

        assertEquals("status", resolver.applyPatchJson("dev-1", Map.of()).orElseThrow().get("sectionId"));
        assertEquals("status", resolver.applyPatchJson("dev-1", null).orElseThrow().get("sectionId"));
    }

    // ── 快照 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("丢弃快照后不再能合成补丁")
    void forgetClearsSnapshot() {
        resolver.remember("dev-1", section("status", "status_section", Map.of()));
        assertTrue(resolver.current("dev-1").isPresent());

        resolver.forget("dev-1");

        assertTrue(resolver.current("dev-1").isEmpty());
        assertTrue(resolver.applyPatchJson("dev-1", patch(
                patchEntry("status", "update", "status_section", Map.of()))).isEmpty());
    }

    @Test
    @DisplayName("记住空 Section 等于清空快照")
    void rememberingEmptyClearsSnapshot() {
        resolver.remember("dev-1", section("status", "status_section", Map.of()));
        resolver.remember("dev-1", Map.of());
        assertTrue(resolver.current("dev-1").isEmpty());
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private static Map<String, Object> scene(Map<String, Object>... sections) {
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("pageId", "main");
        scene.put("layout", "vertical_scroll");
        scene.put("sections", List.of(sections));
        return scene;
    }

    private static Map<String, Object> section(String sectionId, String sectionType, Map<String, Object> fields) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("sectionId", sectionId);
        section.put("sectionType", sectionType);
        section.put("fields", fields);
        return section;
    }

    private static Map<String, Object> patch(Map<String, Object>... entries) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("pageId", "main");
        patch.put("patches", List.of(entries));
        return patch;
    }

    private static Map<String, Object> patchEntry(String sectionId, String op, String sectionType,
                                                  Map<String, Object> fields) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sectionId", sectionId);
        entry.put("op", op);
        entry.put("sectionType", sectionType);
        entry.put("fields", fields == null ? Map.of() : fields);
        return entry;
    }
}
