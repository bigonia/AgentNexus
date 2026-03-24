package com.zwbd.agentnexus.ai.dto.document;

/**
 * @Author: wnli
 * @Date: 2025/9/18 10:56
 * @Desc:
 */
public record DocumentInfoDTO(
        String sourceId,
        String sourceName,
        String documentType,
        String sourceSystem,
        long chunkCount
) {
}
