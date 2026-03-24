package com.zwbd.agentnexus.ai.dto.document;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/9/18 10:56
 * @Desc:
 */
@Schema(description = "单个文档分片的详细信息")
public record DocumentChunkDTO(
        @Schema(description = "分片的唯一ID (UUID)")
        String id,
        @Schema(description = "分片的文本内容")
        String content,
        @Schema(description = "分片的元数据")
        Map<String, Object> metadata
) {
}

