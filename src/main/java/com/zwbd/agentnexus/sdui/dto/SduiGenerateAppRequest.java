package com.zwbd.agentnexus.sdui.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record SduiGenerateAppRequest(
        @NotBlank(message = "requirement must not be blank")
        String requirement,
        List<String> sceneTags,
        List<String> assetSetIds,
        List<String> targetDeviceIds
) {
}
