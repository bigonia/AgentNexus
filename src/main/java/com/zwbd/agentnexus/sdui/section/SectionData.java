package com.zwbd.agentnexus.sdui.section;

import java.util.List;

public sealed interface SectionData {

    SectionType type();

    record HeroData(
            String value,
            String label,
            String subtitle,
            String tone,
            String iconSrc,
            int progress
    ) implements SectionData {
        @Override public SectionType type() { return SectionType.HERO; }
    }

    record MetricData(List<MetricEntry> metrics) implements SectionData {
        @Override public SectionType type() { return SectionType.METRIC; }
        public record MetricEntry(String label, String value) {}
    }

    record ChartData(String title, List<Integer> points, int progress) implements SectionData {
        @Override public SectionType type() { return SectionType.CHART; }
    }

    record TimerData(String title, int progress, Timer timer) implements SectionData {
        @Override public SectionType type() { return SectionType.TIMER; }
        public record Timer(long elapsedMs, boolean running) {}
    }

    record ImageData(String iconSrc, String title, String subtitle) implements SectionData {
        @Override public SectionType type() { return SectionType.IMAGE; }
    }

    record ActionData(List<ActionButton> actions) implements SectionData {
        @Override public SectionType type() { return SectionType.ACTION; }
        public record ActionButton(String id, String label, String tone, boolean enabled) {}
    }

    record ProgressData(String title, int progress, String progressText) implements SectionData {
        @Override public SectionType type() { return SectionType.PROGRESS; }
    }

    record TextData(String title, String body) implements SectionData {
        @Override public SectionType type() { return SectionType.TEXT; }
    }

    record OverlayData(
            String title, String body, String tone,
            int unreadCount, int autoHideMs, boolean visible
    ) implements SectionData {
        @Override public SectionType type() { return SectionType.OVERLAY; }
    }

    record ListData(List<ListItem> items) implements SectionData {
        @Override public SectionType type() { return SectionType.LIST; }
        public record ListItem(String id, String title, String subtitle, String tone) {}
    }

    record ToggleData(List<ToggleOption> options) implements SectionData {
        @Override public SectionType type() { return SectionType.TOGGLE; }
        public record ToggleOption(String id, String label, boolean active) {}
    }

    record NavData(List<NavTab> tabs, int activeTab) implements SectionData {
        @Override public SectionType type() { return SectionType.NAV; }
        public record NavTab(String id, String label) {}
    }
}
