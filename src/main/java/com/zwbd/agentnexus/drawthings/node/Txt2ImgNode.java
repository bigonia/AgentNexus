package com.zwbd.agentnexus.drawthings.node;

import com.zwbd.agentnexus.drawthings.ImageGenService;
import com.zwbd.agentnexus.drawthings.dto.Txt2ImgResponse;
import com.zwbd.agentnexus.sdui.workflow.VariableResolver;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Workflow node for text-to-image generation via local DrawThings.
 * <p>
 * Type: {@code platform.txt2img}
 * <br>
 * Inputs: prompt, negative_prompt, width, height, steps, cfg_scale, seed, sampler_name, count, save_to
 * <br>
 * Outputs: image_url (first generated image URL), image_urls (all URLs as JSON array string)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "drawthings", name = "enabled", havingValue = "true", matchIfMissing = false)
public class Txt2ImgNode implements CapabilityNode {

    private final ImageGenService imageGenService;

    @Override
    public String type() {
        return "platform.txt2img";
    }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(
                type(),
                "文生图",
                "使用本地 DrawThings/Stable Diffusion 将文本提示词生成图片",
                "platform",
                "image",
                List.of(
                        new NodeSchema.ParamDef("prompt", "string", true, null,
                                "图片生成提示词（英文为佳），支持 $data.xxx / $trigger.xxx 变量"),
                        new NodeSchema.ParamDef("negative_prompt", "string", false, "",
                                "负向提示词（不想要的内容）"),
                        new NodeSchema.ParamDef("width", "int", false, 768,
                                "图片宽度（64的倍数：512/768/1024）"),
                        new NodeSchema.ParamDef("height", "int", false, 768,
                                "图片高度（64的倍数：512/768/1024）"),
                        new NodeSchema.ParamDef("steps", "int", false, 20,
                                "采样步数（8-12快速预览，20-30高质量）"),
                        new NodeSchema.ParamDef("cfg_scale", "float", false, 7.0,
                                "提示词引导强度（1-20）"),
                        new NodeSchema.ParamDef("seed", "int", false, -1,
                                "随机种子（-1为随机）"),
                        new NodeSchema.ParamDef("sampler_name", "string", false, "",
                                "采样器名称（留空使用DrawThings当前设置）"),
                        new NodeSchema.ParamDef("count", "int", false, 1,
                                "生成数量"),
                        new NodeSchema.ParamDef("save_to", "string", true, "image_url",
                                "保存图片URL的工作流变量名")
                ),
                List.of(
                        new NodeSchema.ParamDef("image_url", "string", false, null,
                                "第一张生成图片的访问URL"),
                        new NodeSchema.ParamDef("image_urls", "string", false, null,
                                "所有生成图片URL的JSON数组")
                ),
                false, 120000, null, "platform", "grpc", "platform.txt2img", Map.of()
        );
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        Map<String, Object> inputs = ctx.resolvedInputs();

        String prompt = str(inputs, "prompt");
        if (prompt == null || prompt.isBlank()) {
            return NodeResult.error("缺少必填参数 'prompt'");
        }

        String resolved = resolve(prompt, ctx);
        String negativePrompt = resolve(str(inputs, "negative_prompt"), ctx);
        int width = parseInt(inputs, "width", 768);
        int height = parseInt(inputs, "height", 768);
        int steps = parseInt(inputs, "steps", 20);
        double cfgScale = parseDouble(inputs, "cfg_scale", 7.0);
        int seed = parseInt(inputs, "seed", -1);
        String samplerName = str(inputs, "sampler_name");
        if (samplerName != null && samplerName.isBlank()) samplerName = null;
        int count = parseInt(inputs, "count", 1);
        String saveTo = str(inputs, "save_to");
        if (saveTo == null || saveTo.isBlank()) saveTo = "image_url";

        log.info("Txt2ImgNode: prompt=\"{}\", size={}x{}, steps={}, saveTo={}",
                truncate(resolved, 60), width, height, steps, saveTo);

        try {
            Txt2ImgResponse result = imageGenService.generate(
                    resolved, negativePrompt, width, height, steps, cfgScale, seed, samplerName, count);

            String firstUrl = (result.getImageUrls() != null && !result.getImageUrls().isEmpty())
                    ? result.getImageUrls().get(0) : null;

            return NodeResult.completed(Map.of(
                    "image_url", firstUrl != null ? firstUrl : "",
                    "image_urls", result.getImageUrls() != null
                            ? result.getImageUrls().toString() : "[]"
            ), java.util.Set.of(saveTo));

        } catch (Exception e) {
            log.error("Txt2ImgNode failed for device={}", ctx.deviceId(), e);
            return NodeResult.error("图片生成失败: " + e.getMessage());
        }
    }

    // ── helpers ──

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private int parseInt(Map<String, Object> m, String key, int defaultVal) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(v.toString()); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private double parseDouble(Map<String, Object> m, String key, double defaultVal) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v != null) {
            try { return Double.parseDouble(v.toString()); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private String resolve(String expr, NodeContext ctx) {
        if (expr == null) return null;
        Object resolved = VariableResolver.resolveExpression(expr,
                ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env());
        return resolved != null ? resolved.toString() : expr;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
