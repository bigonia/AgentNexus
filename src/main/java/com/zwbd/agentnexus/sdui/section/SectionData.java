package com.zwbd.agentnexus.sdui.section;

import java.util.List;

public sealed interface SectionData {

    String type();

    record HeroData(
            String value,
            String label,
            String subtitle,
            String tone,
            String iconSrc,
            String iconSymbol,
            int progress
    ) implements SectionData {
        @Override public String type() { return "hero_section"; }
    }

    record MetricData(List<MetricEntry> metrics) implements SectionData {
        @Override public String type() { return "metric_section"; }
        public record MetricEntry(String label, String value) {}
    }

    record ChartData(String title, List<Integer> points, int progress) implements SectionData {
        @Override public String type() { return "chart_section"; }
    }

    record TimerData(String title, int progress, Timer timer) implements SectionData {
        @Override public String type() { return "timer_section"; }
        public record Timer(long elapsedMs, boolean running) {}
    }

    record ImageData(String iconSrc, String title, String subtitle) implements SectionData {
        @Override public String type() { return "image_section"; }
    }

    record ActionData(List<ActionButton> actions) implements SectionData {
        @Override public String type() { return "action_section"; }
        public record ActionButton(String id, String label, String tone, boolean enabled) {}
    }

    record ProgressData(String title, int progress, String progressText) implements SectionData {
        @Override public String type() { return "progress_section"; }
    }

    record TextData(String title, String body) implements SectionData {
        @Override public String type() { return "text_section"; }
    }

    record OverlayData(
            String title, String body, String tone,
            int unreadCount, int autoHideMs
    ) implements SectionData {
        @Override public String type() { return "overlay_section"; }
    }

    record ListData(List<ListItem> items) implements SectionData {
        @Override public String type() { return "list_section"; }
        public record ListItem(String id, String title, String subtitle, String tone, String iconSrc) {}
    }

    record ToggleData(List<ToggleOption> options) implements SectionData {
        @Override public String type() { return "toggle_section"; }
        public record ToggleOption(String id, String label, boolean active) {}
    }

    record NavData(List<NavTab> tabs, int activeTab) implements SectionData {
        @Override public String type() { return "nav_section"; }
        public record NavTab(String id, String label) {}
    }
}
