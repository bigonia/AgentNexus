package com.zwbd.agentnexus.sdui.workflow;

import java.util.Map;

public record SectionBindDef(
        String id,
        String type,
        Map<String, String> bind
) {}
