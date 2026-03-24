package com.zwbd.agentnexus.ai.custom.service;

import com.zwbd.agentnexus.ai.dto.ChatRequest;
import com.zwbd.agentnexus.ai.dto.document.DocumentChunkDTO;
import com.zwbd.agentnexus.ai.custom.LocalChatResponse;
import com.zwbd.agentnexus.ai.dto.StreamEvent;
import com.zwbd.agentnexus.ai.custom.retriever.DocumentRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @Author: wnli
 * @Date: 2025/9/18 15:14
 * @Desc:
 */
@Slf4j
@Service
public class ConversationService2 {

    private final ChatModel chatModel;
    private final ConversationHistoryService historyService;
    private final DocumentRetriever documentRetriever;

    // 纯聊天模式下的系统提示词
    private final SystemPromptTemplate genericSystemPromptTemplate = new SystemPromptTemplate("您是一个通用的人工智能助手。请友好、简洁地回答用户的问题。");

    @Autowired
    private PromptTemplate promptTemplate;

    public ConversationService2(ChatModel chatModel, ConversationHistoryService historyService, DocumentRetriever documentRetriever) {
        this.chatModel = chatModel;
        this.historyService = historyService;
        this.documentRetriever = documentRetriever;
    }


    public LocalChatResponse ask(ChatRequest chatRequest) {
        final String finalSessionId = (chatRequest.sessionId() == null || chatRequest.sessionId().isBlank()) ? UUID.randomUUID().toString() : chatRequest.sessionId();

        // 1. 根据 useRag 标志决定是否检索文档并构建相应的 System Message
        List<Document> relevantDocuments;
        Message systemMessage;

        if (chatRequest.useRag()) {
            relevantDocuments = documentRetriever.retrieveDocuments(chatRequest);
            String context = formatContext(relevantDocuments);
            systemMessage = promptTemplate.createMessage(Map.of("context", context));
        } else {
            relevantDocuments = Collections.emptyList();
            systemMessage = genericSystemPromptTemplate.createMessage();
        }

        // 2. 获取历史记录
        List<Message> history = historyService.getHistory(finalSessionId);

        // 3. 构建消息列表
        Message userMessage = new UserMessage(chatRequest.query());
        List<Message> allMessages = Stream.concat(
                Stream.of(systemMessage),
                history.stream()
        ).collect(Collectors.toList());
        allMessages.add(userMessage);

        // 4. 调用 LLM
        var chatResponse = chatModel.call(new Prompt(allMessages));
        String answer = chatResponse.getResult().getOutput().getText();

        // 5. 更新历史记录
        historyService.addHistory(finalSessionId, List.of(userMessage, chatResponse.getResult().getOutput()));

        return new LocalChatResponse(answer, relevantDocuments, finalSessionId);
    }


    public Flux<StreamEvent> stream(ChatRequest chatRequest) {
        final String finalSessionId = (chatRequest.sessionId() == null || chatRequest.sessionId().isBlank()) ? UUID.randomUUID().toString() : chatRequest.sessionId();

        // 1. 根据 useRag 标志决定是否检索文档并构建相应的 System Message
        List<Document> relevantDocuments;
        Message systemMessage;

        if (chatRequest.useRag()) {
            relevantDocuments = documentRetriever.retrieveDocuments(chatRequest);
            String context = formatContext(relevantDocuments);
            systemMessage = promptTemplate.createMessage(Map.of("context", context));
        } else {
            relevantDocuments = Collections.emptyList();
            systemMessage = genericSystemPromptTemplate.createMessage();
        }

        // 2. 获取历史记录
        List<Message> history = historyService.getHistory(finalSessionId);

        // 3. 构建消息列表
        Message userMessage = new UserMessage(chatRequest.query());
        List<Message> allMessages = Stream.concat(
                Stream.of(systemMessage),
                history.stream()
        ).collect(Collectors.toList());
        allMessages.add(userMessage);

        // 4. 准备事件流
        StreamEvent sessionEvent = new StreamEvent(StreamEvent.EventType.SESSION_INFO, Map.of("sessionId", finalSessionId));
        List<DocumentChunkDTO> contextDTOs = relevantDocuments.stream()
                .map(doc -> new DocumentChunkDTO(doc.getId(), doc.getText(), doc.getMetadata()))
                .toList();
        StreamEvent contextEvent = new StreamEvent(StreamEvent.EventType.CONTEXT, contextDTOs);

        StringBuilder fullResponse = new StringBuilder();

        // 5. 调用 LLM 并处理流
        return chatModel.stream(new Prompt(allMessages))
                .doOnNext(response -> {
                    String content = response.getResult().getOutput().getText();
                    if (content != null) {
                        fullResponse.append(content);
                    }
                })
                .map(response -> {
                    String token = response.getResult().getOutput().getText();
                    return new StreamEvent(StreamEvent.EventType.TEXT, token);
                })
                .doOnComplete(() -> {
                    Message assistantMessage = new AssistantMessage(fullResponse.toString());
                    historyService.addHistory(finalSessionId, List.of(userMessage, assistantMessage));
                })
                .startWith(Flux.just(sessionEvent, contextEvent))
                .concatWith(Flux.just(new StreamEvent(StreamEvent.EventType.END, null)));
    }


    private String formatContext(List<Document> documents) {
        if (documents.isEmpty()) {
            return "没有可用的上下文信息。";
        }
        return documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));
    }

}

