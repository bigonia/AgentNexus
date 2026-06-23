package com.zwbd.agentnexus.sdui.capability.node;

import java.util.List;
import java.util.Map;

public record CapabilityNodeDefinition(
        String nodeType,
        String capabilityId,
        String instanceId,
        String displayName,
        String description,
        CapabilityNodeRuntimeMode runtimeMode,
        List<CapabilityNodePort> inputPorts,
        List<CapabilityNodePort> outputPorts,
        List<Map<String, Object>> parameters,
        List<CapabilityNodeArtifactSchema> artifacts,
        Map<String, Object> source,
        Map<String, Object> constraints
) {}
