package com.zwbd.agentnexus.sdui.image;

import com.zwbd.agentnexus.sdui.image.dto.Txt2ImgResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * AI Agent tool for text-to-image generation via local DrawThings.
 * <p>
 * When registered, AI agents can call {@code local:generateImage} to create images
 * from natural language prompts during chat conversations.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "drawthings", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ImageGenTool {

    private final ImageGenService imageGenService;

    @Tool(description = """
            Generate an image from a text prompt using local Stable Diffusion (DrawThings).
            The image is generated locally on the server's GPU and saved to disk.
            Returns a URL that can be used to view or download the generated image.
            Use this when the user asks to create, generate, or draw an image, picture, artwork, or illustration.""")
    public String generateImage(
            @ToolParam(description = "The image generation prompt describing what to draw. Be detailed and descriptive in English for best results. Example: 'a serene mountain lake at sunrise, 4k, highly detailed'")
            String prompt,

            @ToolParam(description = "Things to avoid in the image. Example: 'low quality, blurry, ugly, distorted'")
            String negativePrompt,

            @ToolParam(description = "Image width in pixels, must be a multiple of 64 (e.g. 512, 768, 1024). Default 768.")
            int width,

            @ToolParam(description = "Image height in pixels, must be a multiple of 64 (e.g. 512, 768, 1024). Default 768.")
            int height,

            @ToolParam(description = "Number of sampling steps. 8-12 for fast draft, 20-30 for quality. Default 20.")
            int steps
    ) {
        log.info("AI Agent requested image generation: prompt=\"{}\", size={}x{}",
                prompt != null ? prompt.substring(0, Math.min(80, prompt.length())) : "null",
                width, height);

        if (!imageGenService.isAvailable()) {
            return "❌ DrawThings 服务不可用。请确保 DrawThings 应用已启动并开启了 API 服务器（端口7860）。";
        }

        try {
            Txt2ImgResponse result = imageGenService.generate(
                    prompt,
                    negativePrompt != null ? negativePrompt : "",
                    width > 0 ? width : 768,
                    height > 0 ? height : 768,
                    steps > 0 ? steps : 20,
                    7.0,  // cfgScale
                    -1,    // random seed
                    null,  // use UI sampler
                    1      // single image
            );

            if (result.getImageUrls() != null && !result.getImageUrls().isEmpty()) {
                String url = result.getImageUrls().get(0);
                return "✅ 图片已生成！\n" +
                       "提示词: " + prompt + "\n" +
                       "尺寸: " + width + "x" + height + "\n" +
                       "图片URL: " + url;
            } else {
                return "❌ 图片生成失败：API 返回了空结果。";
            }
        } catch (Exception e) {
            log.error("Image generation failed via AI tool", e);
            return "❌ 图片生成失败: " + e.getMessage();
        }
    }
}
