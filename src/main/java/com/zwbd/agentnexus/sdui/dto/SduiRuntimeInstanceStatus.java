package com.zwbd.agentnexus.sdui.dto;

public record SduiRuntimeInstanceStatus(
        String appId,
        String deviceId,
        Integer versionNo,
        String state,
        String health,
        Integer restartCount,
        String host,
        Long pid,
        String updatedAt
) {
}
