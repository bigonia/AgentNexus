package com.zwbd.agentnexus.sdui.protocol.catalog;

import java.util.List;
import java.util.Map;

public record CommandSpec(
        String id,
        String group,
        List<FieldSpec> params,
        TransportSpec transport
) {
    public Map<String, Object> toMap() {
        return Map.of(
                "id", id,
                "group", group,
                "params", params.stream().map(FieldSpec::toMap).toList(),
                "transport", transport != null ? transport.toMap() : Map.of()
        );
    }
}
