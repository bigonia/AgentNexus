package com.zwbd.agentnexus.sdui.section;

import java.util.*;

public enum SectionLayout {
    VERTICAL_SCROLL("vertical_scroll", "垂直滚动列表，所有 section 排在一个可滚动容器中，适合信息流/仪表盘"),
    HORIZONTAL_PAGES("horizontal_pages", "水平翻页，带圆点指示器，适合引导页/分步表单，最多 12 页"),
    FIXED_SINGLE("fixed_single", "固定单页无滚动，超出屏幕内容被裁剪，适合简单卡片/确认页"),
    OVERLAY("overlay", "浮层弹窗，overlay_section 渲染到全屏层，适合通知/告警");

    private final String wireName;
    private final String description;

    SectionLayout(String wireName, String description) {
        this.wireName = wireName;
        this.description = description;
    }

    public String wireName() { return wireName; }

    public String description() { return description; }

    public static SectionLayout fromWireName(String name) {
        for (SectionLayout l : values()) {
            if (l.wireName.equals(name)) return l;
        }
        return VERTICAL_SCROLL;
    }

    /** All layouts as a list of {name, description} maps for API serialization. */
    public static List<Map<String, String>> availableLayouts() {
        List<Map<String, String>> list = new ArrayList<>();
        for (SectionLayout l : values()) {
            list.add(Map.of("name", l.wireName, "description", l.description));
        }
        return list;
    }
}
