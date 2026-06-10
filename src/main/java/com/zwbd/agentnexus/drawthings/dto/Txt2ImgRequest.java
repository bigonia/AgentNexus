package com.zwbd.agentnexus.drawthings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DrawThings / Automatic1111 compatible txt2img request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Txt2ImgRequest {

    private String prompt;

    @JsonProperty("negative_prompt")
    @Builder.Default
    private String negativePrompt = "";

    @Builder.Default
    private int seed = -1;

    @Builder.Default
    private int steps = 20;

    @JsonProperty("guidance_scale")
    @Builder.Default
    private double guidanceScale = 7.0;

    @Builder.Default
    private int width = 768;

    @Builder.Default
    private int height = 768;

    @JsonProperty("batch_count")
    @Builder.Default
    private int batchCount = 1;

    @JsonProperty("batch_size")
    @Builder.Default
    private int batchSize = 1;

    @JsonProperty("sampler_name")
    private String samplerName;
}
