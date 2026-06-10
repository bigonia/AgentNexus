package com.zwbd.agentnexus.ai.config;

import com.zwbd.agentnexus.ai.tools.CommonTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author: wnli
 * @Date: 2025/9/29 17:00
 * @Desc:
 */
@Slf4j
@Configuration
public class ChatClientConfig {

    @Autowired
    VectorStore vectorStore;
    @Autowired
    ChatClient.Builder builder;
    @Autowired
    JdbcChatMemoryRepository chatMemoryRepository;

    @Autowired
    private CommonTools commonTools;

    @Autowired(required = false)
    private SyncMcpToolCallbackProvider toolCallbackProvider;

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    @Bean("ragAdvisor-nullable")
    public Advisor initRagAdvisor() {
        Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()

                .queryExpander(MultiQueryExpander.builder()
                        .chatClientBuilder(builder.build().mutate())
                        .numberOfQueries(2)
                        .includeOriginal(true)
                        .build())
                .queryTransformers(RewriteQueryTransformer.builder()
                        .chatClientBuilder(builder.build().mutate())
                        .build())
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .similarityThreshold(0.50)
                        .vectorStore(vectorStore)
                        .build())
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(true)
                        .build())
                .build();
        retrievalAugmentationAdvisor.getOrder();
        return retrievalAugmentationAdvisor;
    }

    @Bean("memoryAdvisor")
    public Advisor initChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }


    @Bean
    public ChatClient ragClient(ChatMemory chatMemory) {

        Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()

                .queryExpander(MultiQueryExpander.builder()
                        .chatClientBuilder(builder.build().mutate())
                        .numberOfQueries(2)
                        .includeOriginal(true)
                        .build())
                .queryTransformers(RewriteQueryTransformer.builder()
                        .chatClientBuilder(builder.build().mutate())
                        .build())
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .similarityThreshold(0.50)
                        .vectorStore(vectorStore)
                        .build())
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(true)
                        .build())
//                .documentPostProcessors(databaseMetaDataProcessor)

                .build();

        ChatClient.Builder clientBuilder = builder.defaultAdvisors(
                        // chat-memory advisor
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                        , retrievalAugmentationAdvisor
                )
//                .defaultOptions(
//                        ToolCallingChatOptions.builder()
//                                .toolCallbacks(toolCallbackProvider.getToolCallbacks())
//                                .internalToolExecutionEnabled(false)
//                                .build()
//                )
//                .defaultTools(commonTools)
                .defaultTools(new CommonTools());

        if (toolCallbackProvider != null) {
            clientBuilder.defaultToolCallbacks(toolCallbackProvider);
        }

        return clientBuilder.build();
    }

//    /**
//     * spring的默认实现，用于包装类增强
//     * @param observationRegistry
//     * @param toolCallbackResolver
//     * @param toolExecutionExceptionProcessor
//     * @return
//     */
//    @Bean
//    public ToolCallingManager defaultToolCallingManager(ObservationRegistry observationRegistry, ToolCallbackResolver toolCallbackResolver,
//                                                        ToolExecutionExceptionProcessor toolExecutionExceptionProcessor) {
//        return new DefaultToolCallingManager(observationRegistry, toolCallbackResolver, toolExecutionExceptionProcessor);
//    }

//    @Bean
//    @Primary
//    public ToolCallingManager toolCallingManagerWrap(ToolCallingManager defaultToolCallingManager) {
//        return new ToolCallingManagerWrap(defaultToolCallingManager);
//    }

//    @Bean
//    @Primary
//    ToolCallingManager toolCallingManager(ToolCallbackResolver toolCallbackResolver,
//                                          ToolExecutionExceptionProcessor toolExecutionExceptionProcessor,
//                                          ObjectProvider<ObservationRegistry> observationRegistry,
//                                          ObjectProvider<ToolCallingObservationConvention> observationConvention) {
//        var toolCallingManager = ToolCallingManager.builder()
//                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
//                .toolCallbackResolver(toolCallbackResolver)
//                .toolExecutionExceptionProcessor(toolExecutionExceptionProcessor)
//                .build();
//
//        observationConvention.ifAvailable(toolCallingManager::setObservationConvention);
//
//        return new ToolCallingManagerWrap(toolCallingManager);
//    }

    /**
     * 覆盖官方的 OpenAiChatModel 定义。
     */
//    @Bean
//    @Primary
//    public OpenAiChatModel openAiChatModel(OpenAiApi openAiApi, OpenAiChatProperties chatProperties,
//                                           ToolCallingManager toolCallingManager, RetryTemplate retryTemplate,
//                                           ObjectProvider<ObservationRegistry> observationRegistry,
//                                           ObjectProvider<ChatModelObservationConvention> observationConvention,
//                                           ObjectProvider<ToolExecutionEligibilityPredicate> openAiToolExecutionEligibilityPredicate) {
//
//        log.info("Creating OpenAiChatModel , ToolCallingManager is {}", toolCallingManager instanceof ToolCallingManagerWrap);
//
//        // 1. 显式构建 Model，传入你的 Wrapper
//        var chatModel = OpenAiChatModel.builder()
//                .openAiApi(openAiApi)
//                .defaultOptions(chatProperties.getOptions())
//                .toolCallingManager(toolCallingManager)
//                .toolExecutionEligibilityPredicate(
//                        openAiToolExecutionEligibilityPredicate.getIfUnique(DefaultToolExecutionEligibilityPredicate::new))
//                .retryTemplate(retryTemplate)
//                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
//                .build();
//
//        observationConvention.ifAvailable(chatModel::setObservationConvention);
//
//        return chatModel;
//    }
}
