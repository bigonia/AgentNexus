package com.zwbd.agentnexus.sdui.protocol.catalog;

import java.util.List;
import java.util.Map;

public record EventSpec(
        String id,
        String source,
        List<FieldSpec> payload,
        TransportSpec transport
) {
    public Map<String, Object> toMap() {
        return Map.of(
                "id", id,
                "source", source,
                "payload", payload.stream().map(FieldSpec::toMap).toList(),
                "transport", transport != null ? transport.toMap() : Map.of()
        );
    }
}
