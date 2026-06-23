package com.zwbd.agentnexus.sdui.workflow.model;

import java.util.List;

public record NodeWorkflowSlot(
        String slotId,
        String typeKey,
        String displayName,
        List<String> requiredCapabilities
) {}
