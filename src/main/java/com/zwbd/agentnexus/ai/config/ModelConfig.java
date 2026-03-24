package com.zwbd.agentnexus.ai.config;

import com.zwbd.agentnexus.ai.tools.ToolCallingManagerWrap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * @Author: wnli
 * @Date: 2025/10/20 17:05
 * @Desc:
 */
@Slf4j
@Configuration
public class ModelConfig {


    // ==========================================================
    // 1. Qwen (通义千问) 模块
    // ==========================================================

    /**
     * 只有当配置了 spring.ai.qwen.base-url 和 api-key 时，才初始化 Qwen 的 API 实例
     */
    @Bean("qwenAPI")
    @ConditionalOnProperty(prefix = "spring.ai.qwen", name = {"base-url", "api-key"})
    public OpenAiApi qwenApi(
            @Value("${spring.ai.qwen.base-url}") String baseUrl,
            @Value("${spring.ai.qwen.api-key}") String apiKey) {
        return OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * 只有当 qwenAPI 存在，且配置了 Chat Model 时才加载
     */
    @Bean
    @Qualifier("qwenChatModel")
    @ConditionalOnBean(name = "qwenAPI")
    @ConditionalOnProperty(prefix = "spring.ai.qwen.chat", name = "model")
    public ChatModel qwenChatModel(
            @Qualifier("qwenAPI") OpenAiApi openAiApi,
            @Value("${spring.ai.qwen.chat.model}") String model,
            @Value("${spring.ai.qwen.chat.temperature:0.7}") Double temperature) {

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }

    /**
     * 只有当 qwenAPI 存在，且配置了 Embedding Model 时才加载
     */
    @Primary
    @Bean
    @Qualifier("qwenEmbeddingModel")
    @ConditionalOnBean(name = "qwenAPI")
    @ConditionalOnProperty(prefix = "spring.ai.qwen.embedding", name = "model")
    public EmbeddingModel qwenEmbeddingModel(
            @Qualifier("qwenAPI") OpenAiApi openAiApi,
            @Value("${spring.ai.qwen.embedding.model}") String model) {

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .build();
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, options);
    }

    // ==========================================================
    // 2. DeepSeek (深度求索) 模块
    // ==========================================================

    /**
     * 只有当配置了 DeepSeek 的必要参数时，才初始化 API 实例
     */
    @Primary
    @Bean("deepseekAPI")
    @ConditionalOnProperty(prefix = "spring.ai.deepseek", name = {"base-url", "api-key"})
    public OpenAiApi deepseekApi(
            @Value("${spring.ai.deepseek.base-url}") String baseUrl,
            @Value("${spring.ai.deepseek.api-key}") String apiKey) {
        return OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * DeepSeek ChatModel 初始化
     * 依赖 deepseekAPI Bean，且必须配置了 model 参数
     */
    @Bean
    @Primary // 如果 DeepSeek 存在，优先作为默认注入
    @Qualifier("deepseekChatModel")
    @ConditionalOnBean(name = "deepseekAPI")
    @ConditionalOnProperty(prefix = "spring.ai.deepseek.chat", name = "model")
    public ChatModel deepseekChatModel(
            @Qualifier("deepseekAPI") OpenAiApi openAiApi,
            @Value("${spring.ai.deepseek.chat.model}") String model,
            @Value("${spring.ai.deepseek.chat.temperature:0.7}") Double temperature,
            ToolCallingManager toolCallManager) { // 假设 ToolCallingManager 已有定义

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .toolCallingManager(new ToolCallingManagerWrap(toolCallManager))
                .build();
    }



}
