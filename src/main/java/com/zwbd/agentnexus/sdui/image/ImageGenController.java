package com.zwbd.agentnexus.sdui.image;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.file.FileStorageService;
import com.zwbd.agentnexus.sdui.image.dto.Txt2ImgResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for text-to-image generation and generated image serving.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sdui/image")
@RequiredArgsConstructor
@Tag(name = "Image Generation API", description = "本地 DrawThings 文生图接口")
@ConditionalOnProperty(prefix = "drawthings", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ImageGenController {

    private final ImageGenService imageGenService;
    private final FileStorageService fileStorageService;

    /**
     * Generate images from a text prompt.
     */
    @PostMapping("/generate")
    @Operation(summary = "文生图", description = "使用本地 DrawThings 将文本提示词生成为图片")
    public ApiResponse<Txt2ImgResponse> generate(
            @RequestParam String prompt,
            @RequestParam(required = false, defaultValue = "") String negativePrompt,
            @RequestParam(required = false, defaultValue = "768") int width,
            @RequestParam(required = false, defaultValue = "768") int height,
            @RequestParam(required = false, defaultValue = "20") int steps,
            @RequestParam(required = false, defaultValue = "7.0") double cfgScale,
            @RequestParam(required = false, defaultValue = "-1") int seed,
            @RequestParam(required = false) String samplerName,
            @RequestParam(required = false, defaultValue = "1") int count) {

        log.info("REST API image generation request: prompt=\"{}\", size={}x{}, count={}",
                prompt != null ? prompt.substring(0, Math.min(80, prompt.length())) : "null",
                width, height, count);

        try {
            Txt2ImgResponse result = imageGenService.generate(
                    prompt, negativePrompt, width, height, steps, cfgScale, seed, samplerName, count);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Image generation failed via REST API", e);
            return ApiResponse.error(50000, "图片生成失败: " + e.getMessage());
        }
    }

    /**
     * Serve a generated image file by filename.
     */
    @GetMapping("/view/{filename:.+}")
    @Operation(summary = "查看生成的图片")
    public ResponseEntity<Resource> viewImage(@PathVariable String filename) {
        try {
            Resource resource = fileStorageService.loadFileAsResource(filename);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(resource);
        } catch (Exception e) {
            log.warn("Image not found: {}", filename);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get DrawThings current state (model, sampler, settings).
     */
    @GetMapping("/state")
    @Operation(summary = "获取 DrawThings 当前状态")
    public ApiResponse<Map<String, Object>> getState() {
        return ApiResponse.success(imageGenService.getState());
    }

    /**
     * Check if DrawThings API is reachable.
     */
    @GetMapping("/health")
    @Operation(summary = "DrawThings 健康检查")
    public ApiResponse<Map<String, Object>> health() {
        boolean available = imageGenService.isAvailable();
        return ApiResponse.success(Map.of(
                "available", available,
                "message", available ? "DrawThings API is reachable" : "DrawThings API is not reachable"
        ));
    }
}
