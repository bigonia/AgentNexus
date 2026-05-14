package com.zwbd.agentnexus.sdui.section;

import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SectionCapabilityAdapterTest {

    private final SectionCapabilityAdapter adapter = new SectionCapabilityAdapter();

    private static CapabilitySchema.CapabilitySnapshot capsWithSection(
            List<String> supportedTypes, List<String> supportedLayouts, Map<String, Integer> limits) {
        CapabilitySchema.DeviceProfile profile = new CapabilitySchema.DeviceProfile(
                "round", 466, 466, "touch", false);
        CapabilitySchema.SectionCapability sec = new CapabilitySchema.SectionCapability(
                true, List.of("render", "patch"), "ui3_binary:SECTION_SCENE",
                supportedTypes, supportedLayouts, limits);
        return new CapabilitySchema.CapabilitySnapshot(profile, List.of(), List.of(), sec);
    }

    @Test
    void filtersUnsupportedSectionTypes() {
        CapabilitySchema.CapabilitySnapshot caps = capsWithSection(
                List.of("hero_section", "metric_section"),
                List.of("vertical_scroll"),
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
        CapabilitySchema.CapabilitySnapshot caps = capsWithSection(
                List.of("metric_section"), List.of("vertical_scroll"),
                Map.of("max_metrics", 2)
        );

        SectionScene scene = new SectionScene("test", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.METRIC, "m1",
                        new SectionData.MetricData(List.of(
                                new SectionData.MetricData.MetricEntry("A", "1"),
                                new SectionData.MetricData.MetricEntry("B", "2"),
                                new SectionData.MetricData.MetricEntry("C", "3"),
                                new SectionData.MetricData.MetricEntry("D", "4")
                        )))
        ));

        SectionScene adapted = adapter.adapt(scene, caps);
        SectionData.MetricData m = (SectionData.MetricData) adapted.sections().get(0).data();
        assertEquals(2, m.metrics().size());
    }

    @Test
    void truncatesListItems() {
        CapabilitySchema.CapabilitySnapshot caps = capsWithSection(
                List.of("list_section"), List.of("vertical_scroll"),
                Map.of("max_list_items", 2)
        );

        SectionScene scene = new SectionScene("test", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.LIST, "l1",
                        new SectionData.ListData(List.of(
                                new SectionData.ListData.ListItem("1", "A", "subA", "primary"),
                                new SectionData.ListData.ListItem("2", "B", "subB", "warning"),
                                new SectionData.ListData.ListItem("3", "C", "subC", "danger"),
                                new SectionData.ListData.ListItem("4", "D", "subD", "success")
                        )))
        ));

        SectionScene adapted = adapter.adapt(scene, caps);
        SectionData.ListData l = (SectionData.ListData) adapted.sections().get(0).data();
        assertEquals(2, l.items().size());
    }

    @Test
    void layoutFallback() {
        CapabilitySchema.CapabilitySnapshot caps = capsWithSection(
                List.of("hero_section"), List.of("horizontal_pages"), Map.of()
        );

        SectionScene scene = new SectionScene("test", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.HERO, "h1",
                        new SectionData.HeroData("85%", "CPU", "OK", "primary", "cpu", 85))
        ));

        SectionScene adapted = adapter.adapt(scene, caps);
        assertEquals(SectionLayout.HORIZONTAL_PAGES, adapted.layout());
    }

    @Test
    void textTruncatedByScreenSize() {
        CapabilitySchema.CapabilitySnapshot caps = capsWithSection(
                List.of("text_section"), List.of("vertical_scroll"),
                Map.of("max_text_chars", 20)
        );

        String longBody = "This is a very long text body that should be truncated to fit the limit";
        SectionScene scene = new SectionScene("test", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.TEXT, "tx1",
                        new SectionData.TextData("Title", longBody))
        ));

        SectionScene adapted = adapter.adapt(scene, caps);
        SectionData.TextData t = (SectionData.TextData) adapted.sections().get(0).data();
        assertTrue(t.body().length() <= 23); // 20 + "..."
        assertTrue(t.body().endsWith("..."));
    }

    @Test
    void sizeLabelSmoke() {
        assertEquals("LARGE", SectionCapabilityAdapter.sizeLabel(
                new CapabilitySchema.DeviceProfile("round", 466, 466, "touch", false)));
        assertEquals("SMALL", SectionCapabilityAdapter.sizeLabel(
                new CapabilitySchema.DeviceProfile("square", 128, 128, "buttons", false)));
        assertEquals("MEDIUM", SectionCapabilityAdapter.sizeLabel(
                new CapabilitySchema.DeviceProfile("square", 240, 240, "touch", false)));
    }
}
