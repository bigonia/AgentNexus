package com.zwbd.agentnexus.sdui.workflow;

import java.util.List;
import java.util.Map;

public record WorkflowDefinition(
        String id,
        String name,
        String icon,
        List<PageDef> pages,
        List<TriggerDef> triggers,
        Map<String, List<ActionDef>> actions
) {}
