package com.zwbd.agentnexus.sdui.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record SduiCreateAssetSetRequest(
        @NotBlank(message = "name must not be blank")
        String name,
        String description,
        List<String> tags
) {
}

