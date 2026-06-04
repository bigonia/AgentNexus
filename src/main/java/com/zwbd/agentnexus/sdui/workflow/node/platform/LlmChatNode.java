package com.zwbd.agentnexus.sdui.workflow.node.platform;

import com.zwbd.agentnexus.sdui.workflow.VariableResolver;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmChatNode implements CapabilityNode {

    @Qualifier("deepseekChatModel")
    private final ChatModel chatModel;

    @Override
    public String type() { return "platform.llm.chat"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "LLM 对话", "调用大语言模型进行对话",
                "platform", "message-square",
                List.of(
                        new NodeSchema.ParamDef("model", "string", false, null, "模型 ID"),
                        new NodeSchema.ParamDef("systemPrompt", "string", false, null, "系统提示词，支持变量"),
                        new NodeSchema.ParamDef("userPrompt", "string", true, null, "用户消息，支持 $data.xxx / $trigger.xxx"),
                        new NodeSchema.ParamDef("temperature", "number", false, "0.7", "温度参数 0-2"),
                        new NodeSchema.ParamDef("maxTokens", "number", false, "1024", "最大输出 token 数")
                ),
                List.of(
                        new NodeSchema.ParamDef("response", "string", false, null, "模型文本回复"),
                        new NodeSchema.ParamDef("model", "string", false, null, "使用的模型"),
                        new NodeSchema.ParamDef("tokenUsage", "object", false, null, "token 使用统计")
                ),
                false, 30000);
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String systemPrompt = (String) ctx.resolvedInputs().get("systemPrompt");
        String userPrompt = (String) ctx.resolvedInputs().get("userPrompt");

        if (userPrompt == null || userPrompt.isEmpty()) {
            return NodeResult.error("'userPrompt' is required");
        }

        // Resolve variables
        userPrompt = resolve(userPrompt, ctx);
        if (systemPrompt != null) {
            systemPrompt = resolve(systemPrompt, ctx);
        }

        Double temperature = getDouble(ctx.resolvedInputs(), "temperature", 0.7);
        Integer maxTokens = getInt(ctx.resolvedInputs(), "maxTokens", 1024);

        try {
            ChatClient client = ChatClient.builder(chatModel).build();
            var prompt = client.prompt().user(userPrompt);

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                prompt = prompt.system(systemPrompt);
            }

            if (temperature != null || maxTokens != null) {
                var opts = org.springframework.ai.chat.prompt.ChatOptions.builder();
                if (temperature != null) opts.temperature(temperature);
                if (maxTokens != null) opts.maxTokens(maxTokens);
                prompt = prompt.options(opts.build());
            }

            String response = prompt.call().content();

            log.info("LLM Chat response: {} chars", response != null ? response.length() : 0);
            return NodeResult.completed(Map.of(
                    "response", response != null ? response : "",
                    "model", "deepseek"
            ));
        } catch (Exception e) {
            log.error("LLM Chat failed: {}", e.getMessage());
            return NodeResult.error("LLM Chat failed: " + e.getMessage());
        }
    }

    private String resolve(String expr, NodeContext ctx) {
        if (expr == null) return null;
        Object resolved = VariableResolver.resolveExpression(expr,
                ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env());
        return resolved != null ? resolved.toString() : expr;
    }

    private Double getDouble(Map<String, Object> map, String key, Double def) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private Integer getInt(Map<String, Object> map, String key, Integer def) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }
}
