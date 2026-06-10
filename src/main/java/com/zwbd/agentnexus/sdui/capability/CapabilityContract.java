package com.zwbd.agentnexus.sdui.capability;

import java.util.List;
import java.util.Map;

public record CapabilityContract(
        String deviceId,
        String status,
        List<ContractCapability> inputs,
        List<ContractCapability> outputs,
        DisplayContract display,
        List<ContractCapability> platformCapabilities,
        List<UnresolvedCapability> unresolved
) {

    public record ContractCapability(
            String id,
            String source,
            boolean supported,
            String category,
            String displayName,
            String description,
            Map<String, Object> schema,
            Map<String, Object> protocol,
            Map<String, Object> constraints,
            String runtimeHandler
    ) {}

    public record DisplayContract(
            String source,
            boolean supported,
            String sizeClass,
            List<String> layouts,
            List<ContractCapability> sectionTypes,
            Map<String, Object> constraints,
            Map<String, Object> protocol,
            String runtimeHandler
    ) {}

    public record UnresolvedCapability(
            String domain,
            String id,
            String reason
    ) {}
}
