package com.zwbd.agentnexus.ai.dto.document;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

/**
 * @Author: wnli
 * @Date: 2025/9/18 11:06
 * @Desc:
 */
@Schema(description = "更新文档分片内容的请求体")
public record UpdateChunkRequest(
        @Schema(description = "分片的新文本内容", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "Content must not be empty")
        String content
) {
}
