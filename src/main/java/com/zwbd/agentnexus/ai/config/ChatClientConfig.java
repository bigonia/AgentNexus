package com.zwbd.agentnexus.ai.config;

import com.zwbd.agentnexus.ai.tools.CommonTools;
import com.zwbd.agentnexus.document.etl.processor.DatabaseMetaDataProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * @Author: wnli
 * @Date: 2025/9/29 17:00
 * @Desc:
 */
@Slf4j
@Configuration
public class ChatClientConfig implements InitializingBean {

    @Autowired
    VectorStore vectorStore;
    @Autowired
    ChatClient.Builder builder;
    @Autowired
    JdbcChatMemoryRepository chatMemoryRepository;

    @Autowired
    DatabaseMetaDataProcessor databaseMetaDataProcessor;

    @Value("classpath:/prompts/system-prompt.st")
    Resource systemPromptResource;

    @Autowired
    private CommonTools commonTools;

    @Autowired(required = false)
    private SyncMcpToolCallbackProvider toolCallbackProvider;

    @Bean
    public PromptTemplate ragSystemPromptTemplate(@Value("classpath:/prompts/system-prompt.st") Resource systemPromptResource) throws IOException {
        PromptTemplate ragSystemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        return ragSystemPromptTemplate;
    }


    @Bean("pythonCoder")
    public ChatClient initCommonChatClient(@Qualifier("deepseekChatModel") ChatModel model) {
        ChatClient.Builder builder = ChatClient.builder(model);
        ChatClient customChatClient = builder
//                .defaultSystem("> \"为用户编写一个 Python 数据处理脚本，用于 Java GraalVM 环境下的流式文档清洗系统。\n" +
//                        "\n" +
//                        " **代码要求：**\n" +
//                        "\n" +
//                        " 1.  **函数定义**：必须包含函数 `def process(doc):`。\n" +
//                        " 2.  **输入对象**：`doc` 是一个 Java `Document` 对象。\n" +
//                        "       - 使用 `doc.getContent()` 获取文本内容 (String)。\n" +
//                        "       - 使用 `doc.getMetadata()` 获取元数据 (Map)。\n" +
//                        " 3.  **输出要求**：必须返回一个文档列表 `list`。\n" +
//                        "       - `[doc]`: 修改后返回（一对一）。\n" +
//                        "       - `[]`: 返回空列表以过滤掉该文档（过滤）。\n" +
//                        "       - `[doc1, doc2]`: 返回多个文档（拆分）。\n" +
//                        " 4.  **操作方法**：\n" +
//                        "       - 修改内容：`doc.setContent(\"new content\")`。\n" +
//                        "       - 修改元数据：`doc.getMetadata().put(\"key\", \"value\")`。\n" +
//                        "       - 复制对象：如果需要拆分，使用 `doc.clone()` (假设存在) 或创建新对象。\n" +
//                        " 5.  **限制条件**：\n" +
//                        "       - 不要使用任何 I/O 操作（文件读写、网络请求）。\n" +
//                        "       - 不要创建线程。\n" +
//                        "       - 仅使用 Python 标准库（如 `json`, `re`）。")
                .build();
        return customChatClient;
    }

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
    @Override
    public void afterPropertiesSet() throws Exception {

    }
}
