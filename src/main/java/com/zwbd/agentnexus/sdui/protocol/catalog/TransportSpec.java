package com.zwbd.agentnexus.sdui.protocol.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

public record TransportSpec(
        String topic,
        String action,
        String messageKind
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (topic != null && !topic.isBlank()) {
            map.put("topic", topic);
        }
        if (action != null && !action.isBlank()) {
            map.put("action", action);
        }
        if (messageKind != null && !messageKind.isBlank()) {
            map.put("messageKind", messageKind);
        }
        return map;
    }
}
