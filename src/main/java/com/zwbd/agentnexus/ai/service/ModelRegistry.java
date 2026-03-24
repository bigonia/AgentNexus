package com.zwbd.agentnexus.ai.service;

import com.zwbd.agentnexus.ai.dto.model.OpenAiModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Author: wnli
 * @Date: 2026/1/7 15:13
 * @Desc: 管理全部model实现，包括预定义和外部接入
 * <p>
 * 等待本地模型加载完毕
 */
@Slf4j
@DependsOn("modelConfig")
@Component
public class ModelRegistry {

    private final Map<String, ChatModel> modelMap = new ConcurrentHashMap<>();

    @Autowired
    public void DefaultModelRegistry(Map<String, ChatModel> initialModels) {
        if (initialModels != null) {
            modelMap.putAll(initialModels);
        }
    }

    /**
     * 动态注册基于 OpenAI 协议的外部模型
     *
     * @param config 模型配置 DTO
     */
    public void registerOpenAiModel(OpenAiModelConfig config) {

        String registrationName = config.getRegistrationName();

        try {
            // 1. 构建 API 客户端
            // 使用 Spring AI 的 OpenAiApi，传入 baseURL 和 apiKey
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .apiKey(config.getApiKey())
                    .baseUrl(config.getBaseUrl())
                    .build();

            // 2. 构建配置选项
            OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                    .model(config.getModelName());

            if (config.getTemperature() != null) {
                optionsBuilder.temperature(config.getTemperature());
            }
            if (config.getTopP() != null) {
                optionsBuilder.topP(config.getTopP());
            }

            // 3. 构建模型实例
            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(optionsBuilder.build())
                    .build();

            // 4. 注册到内存 Map
            modelMap.put(registrationName, chatModel);
            log.info("Dynamically registered OpenAI model: [{}] mapped to [{}]", registrationName, config.getModelName());

        } catch (Exception e) {
            log.error("Failed to register OpenAI model: {}", registrationName, e);
            throw new RuntimeException("Failed to register model", e);
        }
    }

    public void deleteModel(String registrationName) {
        modelMap.remove(registrationName);
    }

    public void registerModel(String name, ChatModel model) {
        modelMap.put(name, model);
        log.info("Registered Model: {}", name);
    }

    public Set<String> getAllModels() {
        return modelMap.keySet();
    }

    public ChatModel getModel(String name) {
        return modelMap.get(name);
    }

    public boolean containsModel(String name) {
        return modelMap.containsKey(name);
    }
}
