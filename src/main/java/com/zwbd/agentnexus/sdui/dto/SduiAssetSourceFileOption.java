package com.zwbd.agentnexus.sdui.dto;

import java.time.Instant;

public record SduiAssetSourceFileOption(
        Long fileId,
        String originalFilename,
        String sourceSystem,
        Instant createdAt
) {
}
