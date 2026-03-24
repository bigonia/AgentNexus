package com.zwbd.agentnexus.ai.service;

import com.zwbd.agentnexus.ai.dto.ChatRequest;
import com.zwbd.agentnexus.ai.dto.StreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * @Author: wnli
 * @Date: 2025/10/15 14:25
 * @Desc:
 */
@Slf4j
@Service
public class ChatClientService {

    @Qualifier("ragClient")
    @Autowired
    private ChatClient chatClient;

    @Autowired
    private PromptTemplate promptTemplate;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private ToolCallingManager toolCallingManager;

    @Autowired
    private AgentFactory agentFactory;

    @Autowired
    JdbcChatMemoryRepository chatMemoryRepository;

    public List<Message> getHistoryChat(String conversationId) {
        List<Message> messageList = chatMemoryRepository.findByConversationId(conversationId);
        return messageList;
    }

    public Flux<StreamEvent> agentChat(String agentId, ChatRequest chatRequest) {

        // 插入事件，用于接收工具的日志
        // unicast: 单播，onBackpressureBuffer: 防止消息积压
        Sinks.Many<StreamEvent> toolLogSink = Sinks.many().unicast().onBackpressureBuffer();

        // 定义一个变量或通过回调处理 Token
        AtomicReference<Usage> finalUsage = new AtomicReference<>();

        // AI正文事件
        Flux<ChatClientResponse> responseFlux = agentFactory.getAgentClient(agentId)
                .prompt()
                .user(chatRequest.query())
                .advisors(a -> a.params(Map.of(ChatMemory.CONVERSATION_ID, chatRequest.sessionId())))
                .advisors(a -> a.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, chatRequest.toExpression()))
                .toolContext(Map.of(ChatMemory.CONVERSATION_ID, chatRequest.sessionId()))
                .toolContext(Map.of("TOOL_LOG_CONSUMER", (Consumer<StreamEvent>) toolLogSink::tryEmitNext))
                .stream()
                .chatClientResponse()
                .doOnNext(response -> {
                    // 1. 获取当前 Chunk 的元数据
                    Usage usage = response.chatResponse().getMetadata().getUsage();

                    // 2. 只有最后一个数据包通常才包含 Usage 信息
                    if (usage != null && usage.getTotalTokens() > 0) {
                        finalUsage.set(usage);
                        // 在这里执行你的业务逻辑，例如：
                        System.out.println("检测到 Token 消耗: " + usage.getTotalTokens());
                        // saveToDatabase(usage, chatRequest.sessionId());
                    }
                })
                .doFinally(signalType -> {
                    // 3. 流结束后的收尾动作
                    if (finalUsage.get() != null) {
                        log.info("会话结束，最终 Token 统计：{}", finalUsage.get());
                    }
                });

        // SESSION_INFO 事件
        Flux<StreamEvent> sessionInfoStream = Flux.just(
                new StreamEvent(StreamEvent.EventType.SESSION_INFO, Map.of("sessionId", chatRequest.sessionId()))
        );

        // END 事件
        Flux<StreamEvent> endStream = Flux.just(
                new StreamEvent(StreamEvent.EventType.END, "Stream finished")
        );


        // 转换响应流：从 context 提取文档（仅首次发送 CONTEXT），从 ChatResponse 提取文本
        AtomicBoolean contextSent = new AtomicBoolean(false);
        Flux<StreamEvent> responseEvents = responseFlux.flatMap(chatClientResponse -> {

            Usage usage = chatClientResponse.chatResponse().getMetadata().getUsage();
            System.out.println("提示词 Token: " + usage.getPromptTokens());
            System.out.println("生成 Token: " + usage.getCompletionTokens()); // 或 getGenerationTokens()
            System.out.println("总消耗 Token: " + usage.getTotalTokens());

            List<StreamEvent> eventsInThisChunk = new ArrayList<>();

            ChatResponse chatResponse = chatClientResponse.chatResponse();

            // 提取文档，仅一次
            @SuppressWarnings("unchecked")
            List<Document> documents = (List<Document>) chatClientResponse.context()
                    .getOrDefault(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of());
            if (!contextSent.get() && !documents.isEmpty()) {
                eventsInThisChunk.add(new StreamEvent(StreamEvent.EventType.CONTEXT, documents));
                contextSent.set(true);
                log.info("从 context 捕获 {} 个文档", documents.size());
            }
            // 提取响应内容
            String content = chatResponse.getResult() != null
                    ? chatResponse.getResult().getOutput().getText()
                    : null;
            if (content != null && !content.isEmpty()) {
                eventsInThisChunk.add(new StreamEvent(StreamEvent.EventType.TEXT, content));
            }

            return Flux.fromIterable(eventsInThisChunk);
        }).doFinally(signalType -> {
            // 显示关闭流
            toolLogSink.tryEmitComplete();
        });

        // 构建中间的混合流 (Body)
        // 关键点：这里使用 merge，允许 AI 文本和工具日志“谁先到谁先出”，互不阻塞
        Flux<StreamEvent> aiBodyStream = Flux.merge(
                responseEvents,          // AI 的文本响应流
                toolLogSink.asFlux()     // 工具的日志插队流
        );

        // 最终拼接：(头) -> (混合的中间体) -> (尾)
        return Flux.concat(sessionInfoStream, aiBodyStream, endStream);
    }


    public Flux<StreamEvent> chat(ChatRequest chatRequest) {

        // 插入事件，用于接收工具的日志
        // unicast: 单播，onBackpressureBuffer: 防止消息积压
        Sinks.Many<StreamEvent> toolLogSink = Sinks.many().unicast().onBackpressureBuffer();

        // AI正文事件
        Flux<ChatClientResponse> responseFlux = chatClient
                .prompt()
                .user(chatRequest.query())
                .advisors(a -> a.params(Map.of(ChatMemory.CONVERSATION_ID, chatRequest.sessionId())))
                .advisors(a -> a.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, chatRequest.toExpression()))
                .toolContext(Map.of(ChatMemory.CONVERSATION_ID, chatRequest.sessionId()))
                .toolContext(Map.of("TOOL_LOG_CONSUMER", (Consumer<StreamEvent>) toolLogSink::tryEmitNext))
                .stream()
                .chatClientResponse();

        // SESSION_INFO 事件
        Flux<StreamEvent> sessionInfoStream = Flux.just(
                new StreamEvent(StreamEvent.EventType.SESSION_INFO, Map.of("sessionId", chatRequest.sessionId()))
        );

        // END 事件
        Flux<StreamEvent> endStream = Flux.just(
                new StreamEvent(StreamEvent.EventType.END, "Stream finished")
        );


        // 转换响应流：从 context 提取文档（仅首次发送 CONTEXT），从 ChatResponse 提取文本
        AtomicBoolean contextSent = new AtomicBoolean(false);
        Flux<StreamEvent> responseEvents = responseFlux.flatMap(chatClientResponse -> {

            List<StreamEvent> eventsInThisChunk = new ArrayList<>();

            ChatResponse chatResponse = chatClientResponse.chatResponse();

            // 提取文档，仅一次
            @SuppressWarnings("unchecked")
            List<Document> documents = (List<Document>) chatClientResponse.context()
                    .getOrDefault(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of());
            if (!contextSent.get() && !documents.isEmpty()) {
                eventsInThisChunk.add(new StreamEvent(StreamEvent.EventType.CONTEXT, documents));
                contextSent.set(true);
                log.info("从 context 捕获 {} 个文档", documents.size());
            }
            // 提取响应内容
            String content = chatResponse.getResult() != null
                    ? chatResponse.getResult().getOutput().getText()
                    : null;
            if (content != null && !content.isEmpty()) {
                eventsInThisChunk.add(new StreamEvent(StreamEvent.EventType.TEXT, content));
            }

            return Flux.fromIterable(eventsInThisChunk);
        }).doFinally(signalType -> {
            // 显示关闭流
            toolLogSink.tryEmitComplete();
        });

        // 构建中间的混合流 (Body)
        // 关键点：这里使用 merge，允许 AI 文本和工具日志“谁先到谁先出”，互不阻塞
        Flux<StreamEvent> aiBodyStream = Flux.merge(
                responseEvents,          // AI 的文本响应流
                toolLogSink.asFlux()     // 工具的日志插队流
        );

        // 最终拼接：(头) -> (混合的中间体) -> (尾)
        return Flux.concat(sessionInfoStream, aiBodyStream, endStream);
    }

}
