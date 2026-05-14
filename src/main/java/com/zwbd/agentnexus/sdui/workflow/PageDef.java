package com.zwbd.agentnexus.sdui.workflow;

import java.util.List;

public record PageDef(
        String id,
        String layout,
        boolean autoScroll,
        int autoScrollMs,
        List<SectionBindDef> sections
) {}
