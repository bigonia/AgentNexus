package com.zwbd.agentnexus.sdui.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SduiRegisterAssetRequest(
        @NotNull(message = "fileId must not be null")
        Long fileId,
        @NotBlank(message = "assetType must not be blank")
        String assetType,
        @NotBlank(message = "name must not be blank")
        String name,
        List<String> tags
) {
}

