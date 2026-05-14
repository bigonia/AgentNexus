package com.zwbd.agentnexus.sdui.section;

public enum SectionType {
    HERO("hero_section"),
    METRIC("metric_section"),
    CHART("chart_section"),
    TIMER("timer_section"),
    IMAGE("image_section"),
    ACTION("action_section"),
    PROGRESS("progress_section"),
    TEXT("text_section"),
    OVERLAY("overlay_section"),
    LIST("list_section"),
    TOGGLE("toggle_section"),
    NAV("nav_section");

    private final String wireName;

    SectionType(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }

    public static SectionType fromWireName(String name) {
        for (SectionType t : values()) {
            if (t.wireName.equals(name)) return t;
        }
        return null;
    }
}
