package com.zwbd.agentnexus.sdui.protocol.catalog;

import java.util.List;
import java.util.Map;

public record SectionSpec(
        String type,
        List<FieldSpec> fields,
        boolean sceneSupported,
        List<String> patchOps,
        List<String> events
) {
    public Map<String, Object> toMap() {
        return Map.of(
                "type", type,
                "fields", fields.stream().map(FieldSpec::toMap).toList(),
                "sceneSupported", sceneSupported,
                "patchOps", patchOps,
                "events", events
        );
    }
}
