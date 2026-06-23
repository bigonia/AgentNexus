package com.zwbd.agentnexus.sdui.capability.node;

import java.util.List;
import java.util.Map;

public record CapabilityNodeCatalog(
        String deviceId,
        boolean online,
        String status,
        List<CapabilityNodeDefinition> nodes,
        List<Map<String, Object>> unresolvedNodes
) {}
