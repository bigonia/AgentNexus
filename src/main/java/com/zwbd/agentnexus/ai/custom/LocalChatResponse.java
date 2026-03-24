package com.zwbd.agentnexus.ai.custom;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/9/16 10:52
 * @Desc: Chat response DTO for RAG question answering
 * 
 * This DTO represents the response payload for the RAG chat endpoint.
 * It contains the generated answer and optionally the retrieved context documents.
 */
@Schema(description = "标准问答的响应体")
public record LocalChatResponse(
        @Schema(description = "由 LLM 生成的最终答案")
        String answer,

        @Schema(description = "本次回答引用的上下文文档列表")
        @JsonProperty("retrieved_documents")
        List<Document> retrievedDocuments,

        @Schema(description = "当前会话的唯一ID，客户端应在后续请求中携带此ID以保持对话连续性")
        String sessionId
) {
}