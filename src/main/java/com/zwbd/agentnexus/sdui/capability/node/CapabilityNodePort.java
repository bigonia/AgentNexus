package com.zwbd.agentnexus.sdui.capability.node;

import java.util.Map;

public record CapabilityNodePort(
        String id,
        String direction,
        String type,
        String displayName,
        boolean required,
        Map<String, Object> schema
) {}
