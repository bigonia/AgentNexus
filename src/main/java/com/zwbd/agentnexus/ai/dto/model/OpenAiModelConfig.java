package com.zwbd.agentnexus.ai.dto.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * @Author: wnli
 * @Date: 2026/1/8 9:21
 * @Desc:
 */
@Data
public class OpenAiModelConfig {

    @JsonProperty(required = true)
    private String registrationName; // 在本系统中使用的唯一别名 (如 "custom-gpt4")

    @JsonProperty(required = true)
    private String apiKey;

    @JsonProperty(defaultValue = "https://api.openai.com")
    private String baseUrl;

    @JsonProperty(required = true)
    private String modelName; // 厂商的模型名称 (如 "gpt-4-turbo")

    private Double temperature;

    private Double topP;

}
