package com.zwbd.agentnexus.drawthings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.drawthings.dto.Txt2ImgRequest;
import com.zwbd.agentnexus.drawthings.dto.Txt2ImgResponse;
import com.zwbd.agentnexus.file.FileStorageService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Core service for text-to-image generation via local DrawThings API.
 * <p>
 * Calls the Automatic1111-compatible /sdapi/v1/txt2img endpoint,
 * decodes base64-encoded PNG images, persists them via {@link FileStorageService},
 * and returns publicly accessible URLs.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "drawthings", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ImageGenService {

    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    @Value("${drawthings.base-url:http://127.0.0.1:7860}")
    private String baseUrl;

    @Value("${drawthings.timeout:120000}")
    private long timeoutMs;

    @Value("${drawthings.default-width:768}")
    private int defaultWidth;

    @Value("${drawthings.default-height:768}")
    private int defaultHeight;

    @Value("${drawthings.default-steps:20}")
    private int defaultSteps;

    @Value("${drawthings.default-cfg-scale:7.0}")
    private double defaultCfgScale;

    private RestTemplate restTemplate;

    public ImageGenService(FileStorageService fileStorageService, ObjectMapper objectMapper) {
        this.fileStorageService = fileStorageService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        this.restTemplate = new RestTemplate();
        this.restTemplate.setRequestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
            setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
            setReadTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
        }});
        log.info("ImageGenService initialized: baseUrl={}, timeout={}ms, defaultSize={}x{}",
                baseUrl, timeoutMs, defaultWidth, defaultHeight);
    }

    public Txt2ImgResponse generate(String prompt, String negativePrompt,
                                     int width, int height, int steps,
                                     double cfgScale, int seed,
                                     String samplerName, int count) {
        Txt2ImgRequest request = Txt2ImgRequest.builder()
                .prompt(prompt)
                .negativePrompt(negativePrompt != null ? negativePrompt : "")
                .width(width > 0 ? width : defaultWidth)
                .height(height > 0 ? height : defaultHeight)
                .steps(steps > 0 ? steps : defaultSteps)
                .guidanceScale(cfgScale > 0 ? cfgScale : defaultCfgScale)
                .seed(seed)
                .samplerName(samplerName)
                .batchCount(count > 0 ? count : 1)
                .batchSize(1)
                .build();

        return callDrawThingsApi(request);
    }

    public Txt2ImgResponse generate(String prompt) {
        return generate(prompt, "", defaultWidth, defaultHeight,
                defaultSteps, defaultCfgScale, -1, null, 1);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getState() {
        try {
            String url = baseUrl + "/";
            String json = restTemplate.getForObject(url, String.class);
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("Failed to get DrawThings state: {}", e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    public boolean isAvailable() {
        try {
            restTemplate.getForObject(baseUrl + "/", String.class);
            return true;
        } catch (Exception e) {
            log.debug("DrawThings health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ── private helpers ──

    @SuppressWarnings("unchecked")
    private Txt2ImgResponse callDrawThingsApi(Txt2ImgRequest request) {
        String url = baseUrl + "/sdapi/v1/txt2img";
        log.info("Calling DrawThings txt2img: prompt=\"{}\", size={}x{}, steps={}",
                truncate(request.getPrompt(), 80), request.getWidth(), request.getHeight(), request.getSteps());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Txt2ImgRequest> entity = new HttpEntity<>(request, headers);

        long start = System.currentTimeMillis();
        Map<String, Object> responseMap = restTemplate.postForObject(url, entity, Map.class);
        long elapsed = System.currentTimeMillis() - start;

        Txt2ImgResponse response = new Txt2ImgResponse();
        response.setParameters(responseMap.get("parameters"));
        response.setInfo(responseMap.get("info") != null ? responseMap.get("info").toString() : null);

        List<String> base64Images = (List<String>) responseMap.get("images");
        if (base64Images == null || base64Images.isEmpty()) {
            log.error("DrawThings returned no images in {}ms", elapsed);
            throw new RuntimeException("DrawThings returned no images");
        }

        log.info("DrawThings returned {} image(s) in {}ms", base64Images.size(), elapsed);

        List<String> savedUrls = new ArrayList<>();
        List<String> rawBase64 = new ArrayList<>();
        for (int i = 0; i < base64Images.size(); i++) {
            String b64 = base64Images.get(i);
            rawBase64.add(b64);
            try {
                byte[] pngBytes = Base64.getDecoder().decode(b64);
                String filename = fileStorageService.storeBytes(pngBytes, ".png");
                String imageUrl = "/api/v1/drawthings/image/view/" + filename;
                savedUrls.add(imageUrl);
                log.info("Image {} saved as {} ({} bytes)", i + 1, filename, pngBytes.length);
            } catch (Exception e) {
                log.error("Failed to save image {}: {}", i, e.getMessage());
                savedUrls.add(null);
            }
        }

        response.setImages(rawBase64);
        response.setImageUrls(savedUrls);
        return response;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
