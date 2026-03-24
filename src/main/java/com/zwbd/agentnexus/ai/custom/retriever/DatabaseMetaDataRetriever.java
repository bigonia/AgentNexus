package com.zwbd.agentnexus.ai.custom.retriever;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.ai.dto.ChatRequest;
import com.zwbd.agentnexus.ai.custom.GeneratedQueries;
import com.zwbd.agentnexus.ai.repository.RAGDocumentRepository;
import com.zwbd.agentnexus.datasource.service.DatabaseMetadataStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @Author: wnli
 * @Date: 2025/9/28 17:43
 * @Desc: 文档查询增强
 */
@Slf4j
@Service
public class DatabaseMetaDataRetriever implements DocumentRetriever, InitializingBean {

    private final ChatModel chatModel;
    private final RAGDocumentRepository vectorRepository;

    private final ObjectMapper objectMapper;

    public DatabaseMetaDataRetriever(ChatModel chatModel, RAGDocumentRepository vectorRepository, DatabaseMetadataStorageService databaseMetadataStorageService, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.vectorRepository = vectorRepository;
        this.objectMapper = objectMapper;
    }

    // 用于生成多查询的提示词模板
    @Value("classpath:/prompts/multi-query-prompt.st")
    private Resource multiQueryPromptResource;

    @Value("${app.rag.retrieval.top-k:4}")
    private int topK;

    private String templateString;

    @Override
    public List<Document> retrieveDocuments(ChatRequest chatRequest) {
        log.info("Starting multi-query retrieval for: '{}'", chatRequest.query());

        // 1. 生成多个查询变体
        List<String> queries = generateQueryVariations(chatRequest.query());

        // 2. 为每个查询变体执行向量搜索，并合并结果
        // 使用 flatMap + stream 来处理并行化（在实际生产中可以用 TaskExecutor 优化）
        List<Document> retrievedDocs = queries.parallelStream()
                .peek(query -> log.info("Executing retrieval for generated query: '{}'", query))
                .flatMap(query -> {
                    try {
                        return vectorRepository.search(query, topK,0.3, chatRequest.RAGFilters()).stream();
                    } catch (Exception e) {
                        log.error("Error retrieving documents for query: {}", query, e);
                        return Stream.empty();
                    }
                })
                .toList();
        log.info("retrieved {} documents", retrievedDocs.size());
        // 3. 去重：根据文档ID去重，确保唯一性
        List<Document> distinctDocs = retrievedDocs.stream()
                .filter(doc -> !doc.getId().isEmpty())
                .collect(Collectors.toMap(
                        Document::getId, // 使用 document.getId() 作为去重的key
                        doc -> doc,
                        (existing, replacement) -> existing // 如果key重复，保留第一个
                ))
                .values().stream()
                .limit(topK * 2L)
                .toList();
        log.info("Retrieved {} distinct documents from {} generated queries.", distinctDocs.size(), queries.size());
        return distinctDocs;
    }


    /**
     * 使用 LLM 生成查询变体
     */
    private List<String> generateQueryVariations(String userQuery) {
        var outputConverter = new BeanOutputConverter<>(GeneratedQueries.class);
        String format = outputConverter.getFormat();

        SystemPromptTemplate promptTemplate = new SystemPromptTemplate(templateString);
        Message message = promptTemplate.createMessage(Map.of("question", userQuery, "format", format));

        Prompt prompt = new Prompt(message);

        try {
            log.info("Generating query variations for: '{}'", userQuery);
            org.springframework.ai.chat.model.ChatResponse response = chatModel.call(prompt);
            GeneratedQueries generatedQueries = outputConverter.convert(response.getResult().getOutput().getText());
            if (generatedQueries != null && generatedQueries.queries() != null && !generatedQueries.queries().isEmpty()) {
                // 将原始查询也包含进去，确保原始意图不丢失
                List<String> allQueries = Stream.concat(
                        Stream.of(userQuery),
                        generatedQueries.queries().stream()
                ).distinct().toList();

                log.info("Generated queries: {}", allQueries);
                return allQueries;
            }
        } catch (Exception e) {
            log.error("Failed to generate query variations, falling back to original query.", e);
        }

        // 如果生成失败，则只返回原始查询
        return List.of(userQuery);
    }


    @Override
    public void afterPropertiesSet() throws Exception {
        templateString = multiQueryPromptResource.getContentAsString(StandardCharsets.UTF_8);
    }

}
