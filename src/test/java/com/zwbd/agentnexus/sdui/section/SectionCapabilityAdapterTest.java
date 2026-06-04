package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.capability.CapabilityCatalog;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SectionCapabilityAdapterTest {

    private CapabilityCatalog catalog;
    private SectionCapabilityAdapter adapter;

    @BeforeEach
    void setUp() {
        catalog = mock(CapabilityCatalog.class);
        adapter = new SectionCapabilityAdapter(catalog);
    }

    private static CapabilitySchema.CapabilitySnapshot capsWithDisplay(
            List<String> sectionTypes, List<String> layouts, String sizeClass,
            Map<String, Integer> limits) {
        CapabilitySchema.ScreenInfo screen = new CapabilitySchema.ScreenInfo(466, 466, "round");
        CapabilitySchema.DisplayInfo display = new CapabilitySchema.DisplayInfo(
                "ui3_binary:SECTION_SCENE", sizeClass, sectionTypes, layouts);
        return new CapabilitySchema.CapabilitySnapshot(
                "capability.v2", null, "TEST-BOARD", screen, "touch",
                List.of(), List.of(), display);
    }

    @Test
    void filtersUnsupportedSectionTypes() {
        CapabilitySchema.CapabilitySnapshot caps = capsWithDisplay(
                List.of("hero_section", "metric_section"),
                List.of("vertical_scroll"),
                "large",
                Map.of()
        );

        SectionScene scene = SectionPresets.fullDashboard(); // hero + metric + chart + action
        SectionScene adapted = adapter.adapt(scene, caps);

        assertEquals(2, adapted.sections().size());
        assertEquals(SectionType.HERO, adapted.sections().get(0).type());
        assertEquals(SectionType.METRIC, adapted.sections().get(1).type());
    }

    @Test
    void truncatesMetricsToLimit() {
        when(catalog.getDisplayLimits(anyString())).thenReturn(Map.of("max_metrics", 2));

        SectionScene scene = new SectionScene("test", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.METRIC, "m1",
                        new SectionData.MetricData(List.of(
                                new SectionData.MetricData.MetricEntry("A", "1"),
                                new SectionData.MetricData.MetricEntry("B", "2"),
                                new SectionData.MetricData.MetricEntry("C", "3"),
                                new SectionData.MetricData.MetricEntry("D", "4")
                        )))
        ));

        CapabilitySchema.CapabilitySnapshot caps = capsWithDisplay(
                List.of("metric_section"), List.of("vertical_scroll"),
                "large", Map.of("max_metrics", 2));
        SectionScene adapted = adapter.adapt(scene, caps);
        SectionData.MetricData m = (SectionData.MetricData) adapted.sections().get(0).data();
        assertEquals(2, m.metrics().size());
    }

    @Test
    void truncatesListItems() {
        when(catalog.getDisplayLimits(anyString())).thenReturn(Map.of("max_list_items", 2));

        SectionScene scene = new SectionScene("test", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.LIST, "l1",
                        new SectionData.ListData(List.of(
                                new SectionData.ListData.ListItem("1", "A", "subA", "primary", null),
                                new SectionData.ListData.ListItem("2", "B", "subB", "warning", null),
                                new SectionData.ListData.ListItem("3", "C", "subC", "danger", null),
                                new SectionData.ListData.ListItem("4", "D", "subD", "success", null)
                        )))
        ));

        CapabilitySchema.CapabilitySnapshot caps = capsWithDisplay(
                List.of("list_section"), List.of("vertical_scroll"),
                "large", Map.of("max_list_items", 2));
        SectionScene adapted = adapter.adapt(scene, caps);
        SectionData.ListData l = (SectionData.ListData) adapted.sections().get(0).data();
        assertEquals(2, l.items().size());
    }

    @Test
    void layoutFallback() {
        CapabilitySchema.CapabilitySnapshot caps = capsWithDisplay(
                List.of("hero_section"), List.of("horizontal_pages"),
                "large", Map.of()
        );

        SectionScene scene = new SectionScene("test", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.HERO, "h1",
                        new SectionData.HeroData("85%", "CPU", "OK", "primary", "cpu", null, 85))
        ));

        SectionScene adapted = adapter.adapt(scene, caps);
        assertEquals(SectionLayout.HORIZONTAL_PAGES, adapted.layout());
    }

    @Test
    void textTruncatedByLimit() {
        when(catalog.getDisplayLimits(anyString())).thenReturn(Map.of("max_text_chars", 20));

        String longBody = "This is a very long text body that should be truncated to fit the limit";
        SectionScene scene = new SectionScene("test", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.TEXT, "tx1",
                        new SectionData.TextData("Title", longBody))
        ));

        CapabilitySchema.CapabilitySnapshot caps = capsWithDisplay(
                List.of("text_section"), List.of("vertical_scroll"),
                "small", Map.of("max_text_chars", 20));
        SectionScene adapted = adapter.adapt(scene, caps);
        SectionData.TextData t = (SectionData.TextData) adapted.sections().get(0).data();
        assertTrue(t.body().length() <= 23); // 20 + "..."
        assertTrue(t.body().endsWith("..."));
    }

    @Test
    void noDisplayReturnsOriginalScene() {
        CapabilitySchema.CapabilitySnapshot caps = new CapabilitySchema.CapabilitySnapshot(
                "capability.v2", null, "TEST", null, null,
                List.of(), List.of(), null);

        SectionScene scene = SectionPresets.fullDashboard();
        SectionScene adapted = adapter.adapt(scene, caps);
        assertEquals(scene.sections().size(), adapted.sections().size());
    }

    // ── Render mode tests ──

    @Test
    void largeSizeClassResolvesToRichMode() {
        CapabilitySchema.CapabilitySnapshot caps = capsWithDisplay(
                List.of("hero_section"), List.of("vertical_scroll"),
                "large", Map.of());

        SectionScene scene = new SectionScene("test", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.HERO, "h1",
                        new SectionData.HeroData("72%", "Status", "OK", "primary", "cpu", null, 72))
        ));

        SectionScene adapted = adapter.adapt(scene, caps);
        assertEquals(SectionRenderMode.RICH, adapted.renderMode());
    }

    @Test
    void smallSizeClassResolvesToCompactMode() {
        CapabilitySchema.CapabilitySnapshot caps = capsWithDisplay(
                List.of("hero_section"), List.of("vertical_scroll"),
                "small", Map.of());

        SectionScene scene = new SectionScene("test", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.HERO, "h1",
                        new SectionData.HeroData("72%", "Status", "OK", "primary", "cpu", null, 72))
        ));

        SectionScene adapted = adapter.adapt(scene, caps);
        assertEquals(SectionRenderMode.COMPACT, adapted.renderMode());
    }

    @Test
    void nullSizeClassDefaultsToRichMode() {
        CapabilitySchema.CapabilitySnapshot caps = capsWithDisplay(
                List.of("hero_section"), List.of("vertical_scroll"),
                null, Map.of());

        SectionScene scene = new SectionScene("test", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.HERO, "h1",
                        new SectionData.HeroData("72%", "Status", "OK", "primary", "cpu", null, 72))
        ));

        SectionScene adapted = adapter.adapt(scene, caps);
        assertEquals(SectionRenderMode.RICH, adapted.renderMode());
    }

    @Test
    void resolveModeSmall() {
        CapabilitySchema.CapabilitySnapshot caps = capsWithDisplay(
                List.of(), List.of(), "small", Map.of());
        assertEquals(SectionRenderMode.COMPACT, adapter.resolveMode(caps));
    }

    @Test
    void resolveModeLarge() {
        CapabilitySchema.CapabilitySnapshot caps = capsWithDisplay(
                List.of(), List.of(), "large", Map.of());
        assertEquals(SectionRenderMode.RICH, adapter.resolveMode(caps));
    }

    @Test
    void resolveModeNullDisplay() {
        CapabilitySchema.CapabilitySnapshot caps = new CapabilitySchema.CapabilitySnapshot(
                "v2", null, "TEST", null, null, List.of(), List.of(), null);
        assertEquals(SectionRenderMode.RICH, adapter.resolveMode(caps));
    }

    @Test
    void overlayBodyTruncated() {
        when(catalog.getDisplayLimits(anyString())).thenReturn(Map.of("max_overlay_body", 20));

        String longBody = "This is a long overlay body that needs truncation for small screens";
        SectionScene scene = new SectionScene("test", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.OVERLAY, "o1",
                        new SectionData.OverlayData("Alert", longBody, "warning", 0, 5000))
        ));

        CapabilitySchema.CapabilitySnapshot caps = capsWithDisplay(
                List.of("overlay_section"), List.of("vertical_scroll"),
                "large", Map.of("max_overlay_body", 20));
        SectionScene adapted = adapter.adapt(scene, caps);
        SectionData.OverlayData o = (SectionData.OverlayData) adapted.sections().get(0).data();
        assertTrue(o.body().length() <= 23);
        assertTrue(o.body().endsWith("..."));
    }
}
