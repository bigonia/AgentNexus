package com.zwbd.agentnexus.sdui.protocol.catalog;

import java.util.List;
import java.util.Map;

public record FieldSpec(
        String name,
        String type,
        List<FieldSpec> children
) {
    public FieldSpec(String name, String type) {
        this(name, type, List.of());
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "name", name,
                "type", type,
                "children", children.stream().map(FieldSpec::toMap).toList()
        );
    }
}
