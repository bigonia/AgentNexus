package com.zwbd.agentnexus.sdui.workflow.node.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.workflow.VariableResolver;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class FetchNode implements CapabilityNode {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;

    @Override
    public String type() { return "flow.fetch"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "HTTP 请求", "发送HTTP请求并将结果保存到变量",
                "flow_control", "globe",
                List.of(
                        new NodeSchema.ParamDef("url", "string", true, null, "请求地址，支持 $data.xxx / $trigger.xxx"),
                        new NodeSchema.ParamDef("method", "string", false, "GET", "GET / POST"),
                        new NodeSchema.ParamDef("body", "string", false, null, "请求体 (POST 时使用)，支持变量"),
                        new NodeSchema.ParamDef("save", "string", true, null, "结果保存到 $data.<name>")
                ),
                List.of(new NodeSchema.ParamDef("response", "object", false, null, "响应内容")),
                false, 30000);
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String url = resolve(ctx.resolvedInputs().get("url"), ctx);
        String method = ctx.resolvedInputs().get("method") instanceof String s ? s.toUpperCase() : "GET";
        String body = resolve(ctx.resolvedInputs().get("body"), ctx);
        String save = (String) ctx.resolvedInputs().get("save");
        log.info("Fetch: {} {} -> $data.{}", method, url, save);
        try {
            String response;
            if ("POST".equals(method)) {
                response = restTemplate.postForObject(url, body, String.class);
            } else {
                response = restTemplate.getForObject(url, String.class);
            }
            Object parsed = objectMapper.readValue(response, Object.class);
            ctx.instance().putVariable(save, parsed);
            return NodeResult.completed(Map.of("response", parsed), Set.of(save));
        } catch (Exception e) {
            log.error("Fetch failed for {} {}: {}", method, url, e.getMessage());
            return NodeResult.error("Fetch failed: " + e.getMessage());
        }
    }

    private String resolve(Object expr, NodeContext ctx) {
        if (expr == null) return null;
        Object resolved = VariableResolver.resolveExpression(expr.toString(),
                ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env());
        return resolved != null ? resolved.toString() : expr.toString();
    }
}
