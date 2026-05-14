package com.zwbd.agentnexus.sdui.section;

import java.util.List;

public record SectionScene(
        String pageId,
        SectionLayout layout,
        boolean autoScroll,
        int autoScrollMs,
        List<SectionEntry> sections
) {}
