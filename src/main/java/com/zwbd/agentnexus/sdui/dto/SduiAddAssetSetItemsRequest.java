package com.zwbd.agentnexus.sdui.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SduiAddAssetSetItemsRequest(
        @NotEmpty(message = "assetIds must not be empty")
        List<String> assetIds
) {
}

