package com.zwbd.agentnexus.sdui.workflow;

import java.util.Map;
import java.util.UUID;

public record DeviceMessage(
        String messageId,
        String messageType,
        String sourceType,
        String sourceId,
        String targetId,
        Map<String, Object> payload,
        String replyTo,
        long timestamp
) {
    public DeviceMessage {
        if (messageId == null || messageId.isEmpty()) {
            messageId = UUID.randomUUID().toString();
        }
    }

    public DeviceMessage(String messageType, String sourceType, String sourceId,
                         String targetId, Map<String, Object> payload) {
        this(UUID.randomUUID().toString(), messageType, sourceType, sourceId,
                targetId, payload, null, System.currentTimeMillis());
    }
}
