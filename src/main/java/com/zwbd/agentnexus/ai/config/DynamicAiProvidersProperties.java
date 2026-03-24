package com.zwbd.agentnexus.ai.config;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/10/20 15:05
 * @Desc:
 */
@Data
@Deprecated
public class DynamicAiProvidersProperties  {

    private Map<String, ProviderConfig> providers = new HashMap<>();


    // 内部类，代表每个提供商的配置
    @Data
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;
        private ChatConfig chat;
        private EmbeddingConfig embedding;
    }

    // 聊天配置
    @Data
    public static class ChatConfig {
        private String model;
        private ChatOptions options;
        private boolean primary = false;
    }

    // 聊天选项 (如 temperature)
    @Data
    public static class ChatOptions {
        private Double temperature;
    }

    // 向量配置
    @Data
    public static class EmbeddingConfig {
        private String model;
        private boolean primary = false;
    }

}
