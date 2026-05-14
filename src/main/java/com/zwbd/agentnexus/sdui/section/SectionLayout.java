package com.zwbd.agentnexus.sdui.section;

public enum SectionLayout {
    VERTICAL_SCROLL("vertical_scroll"),
    HORIZONTAL_PAGES("horizontal_pages"),
    FIXED_SINGLE("fixed_single");

    private final String wireName;

    SectionLayout(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }

    public static SectionLayout fromWireName(String name) {
        for (SectionLayout l : values()) {
            if (l.wireName.equals(name)) return l;
        }
        return VERTICAL_SCROLL;
    }
}
