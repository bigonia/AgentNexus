package com.zwbd.agentnexus.drawthings.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DrawThings / Automatic1111 compatible txt2img response.
 */
@Data
@NoArgsConstructor
public class Txt2ImgResponse {

    private List<String> images;

    private Object parameters;

    private String info;

    @JsonProperty("image_urls")
    private List<String> imageUrls;
}
