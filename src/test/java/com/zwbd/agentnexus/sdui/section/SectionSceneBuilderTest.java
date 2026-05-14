package com.zwbd.agentnexus.sdui.section;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SectionSceneBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SectionSceneBuilder builder = new SectionSceneBuilder(mapper);

    @Test
    void buildHeroScene() {
        SectionScene scene = new SectionScene("test_page", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.HERO, "hero_1",
                        new SectionData.HeroData("85%", "CPU", "Running", "primary", "cpu", 85))
        ));

        String json = builder.buildSceneJson(scene);
        assertTrue(json.contains("\"page_id\":\"test_page\""));
        assertTrue(json.contains("\"layout\":\"vertical_scroll\""));
        assertTrue(json.contains("\"type\":\"hero_section\""));
        assertTrue(json.contains("\"value\":\"85%\""));
        assertTrue(json.contains("\"progress\":85"));
    }

    @Test
    void buildMultiSectionSceneJson() {
        SectionScene scene = SectionPresets.fullDashboard();
        String json = builder.buildSceneJson(scene);

        assertTrue(json.contains("\"page_id\":\"full_dashboard_v1\""));
        assertTrue(json.contains("\"layout\":\"vertical_scroll\""));
        assertTrue(json.contains("\"hero_section\""));
        assertTrue(json.contains("\"metric_section\""));
        assertTrue(json.contains("\"chart_section\""));
        assertTrue(json.contains("\"action_section\""));
        assertTrue(json.contains("\"auto_scroll\":true"));
        assertTrue(json.contains("\"auto_scroll_ms\":3000"));
    }

    @Test
    void buildPatchJson() {
        SectionPatch patch = new SectionPatch("test_page", List.of(
                new SectionPatch.PatchEntry("hero_1", "update",
                        new SectionData.HeroData("92%", "CPU", "High Load", "warning", "cpu", 92))
        ));

        String json = builder.buildPatchJson(patch);
        assertTrue(json.contains("\"page_id\":\"test_page\""));
        assertTrue(json.contains("\"section_id\":\"hero_1\""));
        assertTrue(json.contains("\"op\":\"update\""));
        assertTrue(json.contains("\"value\":\"92%\""));
        assertTrue(json.contains("\"tone\":\"warning\""));
    }

    @Test
    void buildSceneWithAllSectionTypes() {
        SectionScene scene = new SectionScene("all_types", SectionLayout.HORIZONTAL_PAGES, true, 2500, List.of(
                new SectionEntry(SectionType.HERO, "h1",
                        new SectionData.HeroData("72%", "Status", "OK", "primary", "cpu", 72)),
                new SectionEntry(SectionType.METRIC, "m1",
                        new SectionData.MetricData(List.of(
                                new SectionData.MetricData.MetricEntry("CPU", "45%"),
                                new SectionData.MetricData.MetricEntry("RAM", "58%")
                        ))),
                new SectionEntry(SectionType.CHART, "c1",
                        new SectionData.ChartData("Trend", List.of(30, 45, 38, 55), 68)),
                new SectionEntry(SectionType.TIMER, "t1",
                        new SectionData.TimerData("Timer", 50,
                                new SectionData.TimerData.Timer(120000, true))),
                new SectionEntry(SectionType.IMAGE, "i1",
                        new SectionData.ImageData("start", "Status", "Online")),
                new SectionEntry(SectionType.ACTION, "a1",
                        new SectionData.ActionData(List.of(
                                new SectionData.ActionData.ActionButton("ok", "OK", "primary", true),
                                new SectionData.ActionData.ActionButton("cancel", "Cancel", "danger", true)
                        ))),
                new SectionEntry(SectionType.PROGRESS, "p1",
                        new SectionData.ProgressData("Sync", 67, "67%")),
                new SectionEntry(SectionType.TEXT, "tx1",
                        new SectionData.TextData("Notice", "Maintenance at 02:00 UTC.")),
                new SectionEntry(SectionType.OVERLAY, "o1",
                        new SectionData.OverlayData("Alert", "Disk at 90%", "warning", 1, 5000, true)),
                new SectionEntry(SectionType.LIST, "l1",
                        new SectionData.ListData(List.of(
                                new SectionData.ListData.ListItem("l1", "Server OK", "2m ago", "success"),
                                new SectionData.ListData.ListItem("l2", "CPU 92%", "5m ago", "danger")
                        ))),
                new SectionEntry(SectionType.TOGGLE, "tg1",
                        new SectionData.ToggleData(List.of(
                                new SectionData.ToggleData.ToggleOption("wifi", "Wi-Fi", true),
                                new SectionData.ToggleData.ToggleOption("bt", "BT", false)
                        ))),
                new SectionEntry(SectionType.NAV, "n1",
                        new SectionData.NavData(List.of(
                                new SectionData.NavData.NavTab("home", "Home"),
                                new SectionData.NavData.NavTab("stats", "Stats"),
                                new SectionData.NavData.NavTab("settings", "Settings")
                        ), 0))
        ));

        String json = builder.buildSceneJson(scene);
        assertTrue(json.contains("\"hero_section\""));
        assertTrue(json.contains("\"metric_section\""));
        assertTrue(json.contains("\"chart_section\""));
        assertTrue(json.contains("\"timer_section\""));
        assertTrue(json.contains("\"image_section\""));
        assertTrue(json.contains("\"toggle_section\""));
        assertTrue(json.contains("\"nav_section\""));
        assertTrue(json.contains("\"overlay_section\""));
        assertTrue(json.contains("\"list_section\""));
        assertTrue(json.contains("\"text_section\""));
        assertTrue(json.contains("\"progress_section\""));
        assertTrue(json.contains("\"action_section\""));
        assertTrue(json.contains("\"elapsed_ms\":120000"));
        assertTrue(json.contains("\"active_tab\":0"));
    }
}
