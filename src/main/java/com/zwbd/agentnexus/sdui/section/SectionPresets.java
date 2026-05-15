package com.zwbd.agentnexus.sdui.section;

import java.util.List;

/**
 * Ready-to-use section scene presets matching the firmware test server catalog.
 */
public final class SectionPresets {

    private SectionPresets() {}

    // ---- Single-section presets ----

    public static SectionScene heroDashboard() {
        return new SectionScene("hero_dashboard", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.HERO, "cpu_hero",
                        new SectionData.HeroData("85%", "CPU Usage", "Running Normal",
                                "primary", "cpu", 85))
        ));
    }

    public static SectionScene metricsGrid() {
        return new SectionScene("metrics_grid", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.METRIC, "sys_metrics",
                        new SectionData.MetricData(List.of(
                                new SectionData.MetricData.MetricEntry("Memory", "62%"),
                                new SectionData.MetricData.MetricEntry("Disk", "41%"),
                                new SectionData.MetricData.MetricEntry("Network", "1.2G"),
                                new SectionData.MetricData.MetricEntry("Load", "1.8")
                        )))
        ));
    }

    public static SectionScene chartTrend() {
        return new SectionScene("chart_trend", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.CHART, "trend_chart",
                        new SectionData.ChartData("CPU 10min Trend",
                                List.of(30, 45, 38, 55, 50, 62, 58, 64, 59, 68, 52, 47, 54, 60, 49, 57), 68))
        ));
    }

    public static SectionScene fullDashboard() {
        return new SectionScene("full_dashboard_v1", SectionLayout.VERTICAL_SCROLL, true, 3000, List.of(
                new SectionEntry(SectionType.HERO, "cpu_hero",
                        new SectionData.HeroData("85%", "CPU Usage", "Running Normal",
                                "primary", "cpu", 85)),
                new SectionEntry(SectionType.METRIC, "sys_metrics",
                        new SectionData.MetricData(List.of(
                                new SectionData.MetricData.MetricEntry("Memory", "62%"),
                                new SectionData.MetricData.MetricEntry("Disk", "41%"),
                                new SectionData.MetricData.MetricEntry("Network", "1.2G"),
                                new SectionData.MetricData.MetricEntry("Load", "1.8")
                        ))),
                new SectionEntry(SectionType.CHART, "trend_chart",
                        new SectionData.ChartData("CPU 10min",
                                List.of(30, 45, 38, 55, 50, 62, 58, 64, 59, 68, 52, 47, 54, 60, 49, 57), 68)),
                new SectionEntry(SectionType.ACTION, "page_actions",
                        new SectionData.ActionData(List.of(
                                new SectionData.ActionData.ActionButton("refresh", "Refresh", "primary", true),
                                new SectionData.ActionData.ActionButton("detail", "Detail", "secondary", true)
                        )))
        ));
    }

    public static SectionScene claimCodeScene(String claimCode) {
        return new SectionScene("claim_code_screen", SectionLayout.FIXED_SINGLE, false, 0, List.of(
                new SectionEntry(SectionType.HERO, "claim_code_hero",
                        new SectionData.HeroData(formatClaimCode(claimCode), "认领码",
                                "请在管理平台输入此码完成设备认领",
                                "primary", "", -1))
        ));
    }

    public static SectionScene claimedSuccessScene() {
        return new SectionScene("claimed_success", SectionLayout.FIXED_SINGLE, false, 0, List.of(
                new SectionEntry(SectionType.HERO, "claimed_hero",
                        new SectionData.HeroData("✓", "认领成功",
                                "设备已绑定，正在加载业务界面...",
                                "success", "", -1))
        ));
    }

    private static String formatClaimCode(String code) {
        if (code == null || code.isBlank()) return "------";
        String upper = code.trim().toUpperCase();
        if (upper.length() <= 3) return upper;
        return upper.substring(0, 3) + " " + upper.substring(3);
    }

    public static SectionScene systemOverview() {
        return new SectionScene("system_overview", SectionLayout.VERTICAL_SCROLL, true, 3000, List.of(
                new SectionEntry(SectionType.HERO, "health_hero",
                        new SectionData.HeroData("98%", "System Health", "All systems nominal",
                                "success", "start", 98)),
                new SectionEntry(SectionType.METRIC, "res_metrics",
                        new SectionData.MetricData(List.of(
                                new SectionData.MetricData.MetricEntry("CPU", "23%"),
                                new SectionData.MetricData.MetricEntry("RAM", "5.2G"),
                                new SectionData.MetricData.MetricEntry("IOPS", "1.4K"),
                                new SectionData.MetricData.MetricEntry("Temp", "47C")
                        ))),
                new SectionEntry(SectionType.PROGRESS, "backup_progress",
                        new SectionData.ProgressData("Backup Progress", 72, "72% — ETA 3 min")),
                new SectionEntry(SectionType.CHART, "load_chart",
                        new SectionData.ChartData("Load 1h",
                                List.of(20, 25, 22, 35, 30, 42, 38, 44, 40, 50, 35, 28, 32, 38, 26, 30), 50))
        ));
    }
}
