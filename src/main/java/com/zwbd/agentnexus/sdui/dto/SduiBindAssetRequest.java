package com.zwbd.agentnexus.sdui.dto;

import jakarta.validation.constraints.NotBlank;

public record SduiBindAssetRequest(
        @NotBlank(message = "assetId must not be blank")
        String assetId,
        @NotBlank(message = "usageType must not be blank")
        String usageType
) {
}

