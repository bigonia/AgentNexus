package com.zwbd.agentnexus.ai.custom;

import com.zwbd.agentnexus.ai.dto.ChatRequest;
import com.zwbd.agentnexus.ai.dto.StreamEvent;
import com.zwbd.agentnexus.ai.custom.service.ConversationService2;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * @Author: wnli
 * @Date: 2025/9/16 11:10
 * @Desc: RAG controller providing REST API endpoints for question answering
 * <p>
 * This controller exposes HTTP endpoints for RAG functionality,
 * including both blocking and streaming chat responses.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
@Tag(name = "RAG API", description = "Retrieval-Augmented Generation endpoints for intelligent question answering")
public class RAGChatController {

    private final ConversationService2 conversationService2;


    @Operation(summary = "标准问答接口", description = "一次性返回完整的回答和上下文，支持多轮对话和 RAG 开关。")
    @PostMapping("/chat")
    public ResponseEntity<LocalChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        LocalChatResponse response = conversationService2.ask(
                request
        );
        return ResponseEntity.ok(response);
    }

     @Operation(summary = "流式问答接口 (推荐)", description = "通过 Server-Sent Events (SSE) 返回结构化的事件流，支持多轮对话和 RAG 开关。"
            , requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ChatRequest.class),
                    examples = @ExampleObject(
                            name = "Streaming Question", value = """
                            {
                              "query": "Explain the architecture of this RAG system",
                              "ragFilters": [],
                              "sessionId": ""
                            }
                            """
                    )
            )
    ))
    @PostMapping(value = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamEvent> chatStream(@Valid @RequestBody ChatRequest request) {
        return conversationService2.stream(request);
    }


}