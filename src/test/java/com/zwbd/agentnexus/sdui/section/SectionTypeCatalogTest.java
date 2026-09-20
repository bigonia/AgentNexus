package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.event.EventCatalogLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 类型目录。
 *
 * <p>固定 T17 解耦的结论：目录内容来自 {@code sdui-event-catalog.yml}，由配置加载器
 * {@link EventCatalogLoader} 提供，**不经运行时事件模型**（{@code EventRegistry}）。
 * 这不是风格问题——v2 的 {@code SectionDataCodec} 依赖本类，本类若再依赖 {@code EventRegistry}，
 * 整个旧事件模型就会经 v2 进入保留闭包、在 P5c 中删不掉。若有人把依赖改回去，
 * 本测试的构造方式会立刻不可编译，这是有意的。</p>
 */
class SectionTypeCatalogTest {

    private SectionTypeCatalog catalog;

    @BeforeEach
    void setUp() {
        EventCatalogLoader loader = new EventCatalogLoader();
        loader.load();
        catalog = new SectionTypeCatalog(loader);
        catalog.init();
    }

    @Test
    @DisplayName("类型目录由 YAML 填充")
    void loadsTypesFromYaml() {
        assertTrue(catalog.isValidType("text_section"));
        assertTrue(catalog.isValidType("action_section"));
        assertFalse(catalog.isValidType("no_such_section"));
        assertFalse(catalog.isValidType(null), "null 不是合法类型名");
    }

    @Test
    @DisplayName("未注册类型原样返回 wire name，已知类型按声明返回")
    void toWireNameFallsBackToInput() {
        assertEquals("text_section", catalog.toWireName("text_section"));
        assertEquals("unknown_section", catalog.toWireName("unknown_section"));
    }

    @Test
    @DisplayName("字段默认值覆盖声明的每个字段")
    void defaultFieldValuesCoverAllFields() {
        Map<String, Object> defaults = catalog.defaultFieldValues("text_section");
        SectionTypeCatalog.SectionTypeDef text = catalog.getOrThrow("text_section");

        assertFalse(text.displayFields().isEmpty());
        assertTrue(text.displayFields().stream().allMatch(f -> defaults.containsKey(f.name())));
    }

    @Test
    @DisplayName("交互事件由类型的 events 声明派生，命名空间前缀被剥离")
    void derivesInteractionEventsFromDeclaration() {
        List<SectionTypeCatalog.InteractionEvent> events = catalog.getInteractionEvents("action_section");

        assertFalse(events.isEmpty());
        // YAML 里声明的是 ui:action.click，目录对外暴露短名
        assertTrue(catalog.allInteractionEventIds().contains("action.click"));
        assertTrue(catalog.getSectionTypesForEvent("action.click").contains("action_section"));
        assertTrue(catalog.interactiveTypes().stream()
                .anyMatch(def -> "action_section".equals(def.type())));
    }

    @Test
    @DisplayName("未知类型没有交互事件，也不参与 interactive 列表")
    void unknownTypeHasNoInteractionEvents() {
        assertTrue(catalog.getInteractionEvents("no_such_section").isEmpty());
        assertTrue(catalog.getSectionTypesForEvent("no_such_event").isEmpty());
    }
}
