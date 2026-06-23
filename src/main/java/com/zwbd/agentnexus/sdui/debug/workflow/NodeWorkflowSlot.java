package com.zwbd.agentnexus.sdui.debug.workflow;

import java.util.List;

public record NodeWorkflowSlot(
        String slotId,
        String typeKey,
        String displayName,
        List<String> requiredCapabilities
) {}
