package com.zwbd.agentnexus.sdui.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.file.KnowledgeFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SduiAssetProcessorService {

    private final ObjectMapper objectMapper;

    @Value("${sdui.asset.processed-dir:./upload-dir/sdui-processed}")
    private String processedDir;

    @Value("${sdui.asset.ffmpeg-command:ffmpeg}")
    private String ffmpegCommand;

    @Value("${sdui.asset.image-max-width:200}")
    private int imageMaxWidth;

    @Value("${sdui.asset.image-max-height:200}")
    private int imageMaxHeight;

    public ProcessingResult process(KnowledgeFile file, String assetType) {
        try {
            Path outputRoot = Paths.get(processedDir).toAbsolutePath().normalize();
            Files.createDirectories(outputRoot);
            String ext = extension(file.getOriginalFilename());

            if (isImage(assetType, ext)) {
                return processImage(file, outputRoot);
            }
            if (isAudio(assetType, ext)) {
                return processAudio(file, outputRoot);
            }
            Map<String, Object> passthrough = new HashMap<>();
            passthrough.put("mode", "passthrough");
            passthrough.put("originalPath", file.getFilePath());
            passthrough.put("originalFilename", file.getOriginalFilename());
            return new ProcessingResult("READY", objectMapper.writeValueAsString(passthrough));
        } catch (Exception e) {
            log.warn("Asset process failed. fileId={}, name={}", file.getId(), file.getOriginalFilename(), e);
            return new ProcessingResult("FAILED", "{\"error\":\"" + safe(e.getMessage()) + "\"}");
        }
    }

    private ProcessingResult processImage(KnowledgeFile file, Path outputRoot) throws Exception {
        BufferedImage src = ImageIO.read(new File(file.getFilePath()));
        if (src == null) {
            throw new IllegalArgumentException("invalid image file");
        }
        int ow = src.getWidth();
        int oh = src.getHeight();
        double scale = Math.min((double) imageMaxWidth / ow, (double) imageMaxHeight / oh);
        scale = Math.min(scale, 1.0d);
        int nw = Math.max(1, (int) Math.round(ow * scale));
        int nh = Math.max(1, (int) Math.round(oh * scale));

        Image scaled = src.getScaledInstance(nw, nh, Image.SCALE_SMOOTH);
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = dst.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.drawImage(scaled, 0, 0, null);
        g2d.dispose();

        String filename = UUID.randomUUID() + ".jpg";
        Path out = outputRoot.resolve(filename);
        ImageIO.write(dst, "jpg", out.toFile());

        Map<String, Object> payload = new HashMap<>();
        payload.put("mode", "image_resized");
        payload.put("processedPath", out.toString());
        payload.put("width", nw);
        payload.put("height", nh);
        payload.put("originalPath", file.getFilePath());
        return new ProcessingResult("READY", objectMapper.writeValueAsString(payload));
    }

    private ProcessingResult processAudio(KnowledgeFile file, Path outputRoot) throws Exception {
        String filename = UUID.randomUUID() + ".wav";
        Path out = outputRoot.resolve(filename);

        ProcessBuilder pb = new ProcessBuilder(
                ffmpegCommand,
                "-y",
                "-i", file.getFilePath(),
                "-ac", "1",
                "-ar", "16000",
                out.toString()
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String logs = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("ffmpeg exit=" + code + ", logs=" + logs);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("mode", "audio_transcoded");
        payload.put("processedPath", out.toString());
        payload.put("format", "wav");
        payload.put("sampleRate", 16000);
        payload.put("channels", 1);
        payload.put("originalPath", file.getFilePath());
        return new ProcessingResult("READY", objectMapper.writeValueAsString(payload));
    }

    private boolean isImage(String assetType, String ext) {
        String t = assetType == null ? "" : assetType.toUpperCase();
        return t.contains("IMAGE") || t.contains("COVER") || ext.matches("jpg|jpeg|png|bmp|webp");
    }

    private boolean isAudio(String assetType, String ext) {
        String t = assetType == null ? "" : assetType.toUpperCase();
        return t.contains("AUDIO") || t.contains("SOUND") || ext.matches("mp3|wav|m4a|aac|ogg|flac");
    }

    private String extension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    private String safe(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\"", "'");
    }

    public record ProcessingResult(String status, String payloadJson) {
    }
}

