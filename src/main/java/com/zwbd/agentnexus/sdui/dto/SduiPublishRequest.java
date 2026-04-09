package com.zwbd.agentnexus.sdui.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SduiPublishRequest(
        String versionId,
        @NotEmpty(message = "deviceIds must not be empty")
        List<String> deviceIds
) {
}

