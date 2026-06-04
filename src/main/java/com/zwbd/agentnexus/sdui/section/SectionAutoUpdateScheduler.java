package com.zwbd.agentnexus.sdui.section;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class SectionAutoUpdateScheduler {

    private final SectionOrchestrationService orchestrationService;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
    private final Map<String, AutoUpdateTask> tasks = new ConcurrentHashMap<>();
    private final Random rng = new Random();

    private static final List<String> PRESET_NAMES = List.of(
            "hero_dashboard", "metrics_grid", "chart_trend", "full_dashboard", "system_overview"
    );

    /** Preset metadata for the frontend: name, label, description, layout, section types, section count. */
    public record PresetMeta(String name, String label, String description,
                             String layout, int sectionCount, List<String> sectionTypes) {}

    private static final Map<String, PresetMeta> PRESET_METAS = new LinkedHashMap<>();
    static {
        PRESET_METAS.put("hero_dashboard", new PresetMeta(
                "hero_dashboard", "Hero 仪表盘", "单 Hero 卡片展示 CPU 使用率",
                "vertical_scroll", 1, List.of("hero_section")));
        PRESET_METAS.put("metrics_grid", new PresetMeta(
                "metrics_grid", "指标网格", "4 格指标展示内存/磁盘/网络/负载",
                "vertical_scroll", 1, List.of("metric_section")));
        PRESET_METAS.put("chart_trend", new PresetMeta(
                "chart_trend", "趋势图表", "16 点折线图展示 CPU 10 分钟趋势",
                "vertical_scroll", 1, List.of("chart_section")));
        PRESET_METAS.put("full_dashboard", new PresetMeta(
                "full_dashboard", "完整仪表盘", "Hero + 指标网格 + 图表 + 操作按钮，自动轮播",
                "vertical_scroll", 4, List.of("hero_section", "metric_section", "chart_section", "action_section")));
        PRESET_METAS.put("system_overview", new PresetMeta(
                "system_overview", "系统概览", "健康度 + 资源指标 + 备份进度 + 负载图表",
                "vertical_scroll", 4, List.of("hero_section", "metric_section", "progress_section", "chart_section")));
    }

    public record AutoUpdateStatus(String deviceId, String activePreset, long intervalMs, long startedAt,
                                   boolean running) {}

    private static class AutoUpdateTask {
        final String deviceId;
        final String activePreset;
        final long intervalMs;
        final long startedAt;
        final ScheduledFuture<?> future;

        AutoUpdateTask(String deviceId, String activePreset, long intervalMs, long startedAt,
                       ScheduledFuture<?> future) {
            this.deviceId = deviceId;
            this.activePreset = activePreset;
            this.intervalMs = intervalMs;
            this.startedAt = startedAt;
            this.future = future;
        }
    }

    public boolean start(String deviceId, String presetName, long intervalMs) {
        if (tasks.containsKey(deviceId)) {
            log.warn("Auto-update already running for device {}", deviceId);
            return false;
        }

        String preset = presetName != null ? presetName : "full_dashboard";
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                () -> tick(deviceId), 0, intervalMs, TimeUnit.MILLISECONDS);
        tasks.put(deviceId, new AutoUpdateTask(deviceId, preset, intervalMs,
                System.currentTimeMillis(), future));
        log.info("Auto-update started for device {}: preset={} interval={}ms", deviceId, preset, intervalMs);
        return true;
    }

    public boolean stop(String deviceId) {
        AutoUpdateTask task = tasks.remove(deviceId);
        if (task == null) {
            return false;
        }
        task.future.cancel(false);
        log.info("Auto-update stopped for device {}", deviceId);
        return true;
    }

    public List<AutoUpdateStatus> listStatus() {
        List<AutoUpdateStatus> result = new ArrayList<>();
        for (AutoUpdateTask t : tasks.values()) {
            result.add(new AutoUpdateStatus(t.deviceId, t.activePreset, t.intervalMs,
                    t.startedAt, true));
        }
        return result;
    }

    public Set<String> getPresetNames() {
        return new LinkedHashSet<>(PRESET_NAMES);
    }

    public List<PresetMeta> getPresetDetails() {
        List<PresetMeta> details = new ArrayList<>();
        for (String name : PRESET_NAMES) {
            PresetMeta meta = PRESET_METAS.get(name);
            if (meta != null) {
                details.add(meta);
            }
        }
        return details;
    }

    public Optional<PresetMeta> getPresetMeta(String name) {
        return Optional.ofNullable(PRESET_METAS.get(name));
    }

    private void tick(String deviceId) {
        AutoUpdateTask task = tasks.get(deviceId);
        if (task == null) return;

        String presetName = task.activePreset;
        if (!PRESET_NAMES.contains(presetName)) {
            presetName = "full_dashboard";
        }

        SectionScene scene = buildRandomizedScene(presetName);
        orchestrationService.sendScene(deviceId, scene);
    }

    private SectionScene buildRandomizedScene(String presetName) {
        return switch (presetName) {
            case "hero_dashboard" -> randomizedHero();
            case "metrics_grid" -> randomizedMetrics();
            case "chart_trend" -> randomizedChart();
            case "full_dashboard" -> randomizedFullDashboard();
            case "system_overview" -> randomizedSystemOverview();
            default -> randomizedFullDashboard();
        };
    }

    private SectionScene randomizedHero() {
        int val = 60 + rng.nextInt(39);
        String[] tones = {"primary", "success", "warning", "danger"};
        return new SectionScene("hero_dashboard", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.HERO, "cpu_hero",
                        new SectionData.HeroData(val + "%", "CPU Usage", "Running",
                                tones[rng.nextInt(tones.length)], "cpu", null, val))
        ));
    }

    private SectionScene randomizedMetrics() {
        return new SectionScene("metrics_grid", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.METRIC, "sys_metrics",
                        new SectionData.MetricData(List.of(
                                new SectionData.MetricData.MetricEntry("Memory", (40 + rng.nextInt(40)) + "%"),
                                new SectionData.MetricData.MetricEntry("Disk", (30 + rng.nextInt(50)) + "%"),
                                new SectionData.MetricData.MetricEntry("Network", String.format("%.1fG", 0.5 + rng.nextDouble() * 2)),
                                new SectionData.MetricData.MetricEntry("Load", String.format("%.1f", 0.5 + rng.nextDouble() * 3))
                        )))
        ));
    }

    private SectionScene randomizedChart() {
        List<Integer> points = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            points.add(20 + rng.nextInt(55));
        }
        int progress = 30 + rng.nextInt(50);
        return new SectionScene("chart_trend", SectionLayout.VERTICAL_SCROLL, false, 0, List.of(
                new SectionEntry(SectionType.CHART, "trend_chart",
                        new SectionData.ChartData("CPU 10min Trend", points, progress))
        ));
    }

    private SectionScene randomizedFullDashboard() {
        int cpuVal = 60 + rng.nextInt(35);
        return new SectionScene("full_dashboard_v1", SectionLayout.VERTICAL_SCROLL, true, 3000, List.of(
                new SectionEntry(SectionType.HERO, "cpu_hero",
                        new SectionData.HeroData(cpuVal + "%", "CPU Usage", "Running Normal",
                                cpuVal > 85 ? "warning" : "primary", "cpu", null, cpuVal)),
                new SectionEntry(SectionType.METRIC, "sys_metrics",
                        new SectionData.MetricData(List.of(
                                new SectionData.MetricData.MetricEntry("Memory", (40 + rng.nextInt(40)) + "%"),
                                new SectionData.MetricData.MetricEntry("Disk", (30 + rng.nextInt(50)) + "%"),
                                new SectionData.MetricData.MetricEntry("Network", String.format("%.1fG", 0.5 + rng.nextDouble() * 2)),
                                new SectionData.MetricData.MetricEntry("Load", String.format("%.1f", 0.5 + rng.nextDouble() * 3))
                        ))),
                new SectionEntry(SectionType.CHART, "trend_chart",
                        new SectionData.ChartData("CPU 10min", randomizedPoints(16), cpuVal)),
                new SectionEntry(SectionType.ACTION, "page_actions",
                        new SectionData.ActionData(List.of(
                                new SectionData.ActionData.ActionButton("refresh", "Refresh", "primary", true),
                                new SectionData.ActionData.ActionButton("detail", "Detail", "secondary", true)
                        )))
        ));
    }

    private SectionScene randomizedSystemOverview() {
        int health = 85 + rng.nextInt(15);
        int backupProgress = 30 + rng.nextInt(70);
        return new SectionScene("system_overview", SectionLayout.VERTICAL_SCROLL, true, 3000, List.of(
                new SectionEntry(SectionType.HERO, "health_hero",
                        new SectionData.HeroData(health + "%", "System Health", "All systems nominal",
                                health >= 95 ? "success" : "primary", "start", null, health)),
                new SectionEntry(SectionType.METRIC, "res_metrics",
                        new SectionData.MetricData(List.of(
                                new SectionData.MetricData.MetricEntry("CPU", (15 + rng.nextInt(40)) + "%"),
                                new SectionData.MetricData.MetricEntry("RAM", String.format("%.1fG", 3.0 + rng.nextDouble() * 5)),
                                new SectionData.MetricData.MetricEntry("IOPS", String.format("%.1fK", 0.5 + rng.nextDouble() * 3)),
                                new SectionData.MetricData.MetricEntry("Temp", (35 + rng.nextInt(25)) + "C")
                        ))),
                new SectionEntry(SectionType.PROGRESS, "backup_progress",
                        new SectionData.ProgressData("Backup Progress", backupProgress,
                                backupProgress + "% — ETA " + (1 + rng.nextInt(5)) + " min")),
                new SectionEntry(SectionType.CHART, "load_chart",
                        new SectionData.ChartData("Load 1h", randomizedPoints(16),
                                20 + rng.nextInt(50)))
        ));
    }

    private List<Integer> randomizedPoints(int count) {
        List<Integer> pts = new ArrayList<>();
        int v = 20 + rng.nextInt(30);
        for (int i = 0; i < count; i++) {
            v = Math.max(5, Math.min(75, v + (rng.nextInt(15) - 7)));
            pts.add(v);
        }
        return pts;
    }
}
