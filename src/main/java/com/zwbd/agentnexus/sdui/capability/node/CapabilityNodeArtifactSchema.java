package com.zwbd.agentnexus.sdui.capability.node;

import java.util.Map;

public record CapabilityNodeArtifactSchema(
        String name,
        String type,
        String displayName,
        boolean required,
        Map<String, Object> schema
) {}
