package com.zwbd.agentnexus.sdui.workflow.node.platform;

import com.zwbd.agentnexus.sdui.workflow.VariableResolver;
import com.zwbd.agentnexus.sdui.workflow.node.CapabilityNode;
import com.zwbd.agentnexus.sdui.workflow.node.NodeContext;
import com.zwbd.agentnexus.sdui.workflow.node.NodeResult;
import com.zwbd.agentnexus.sdui.workflow.node.NodeSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagQueryNode implements CapabilityNode {

    @Qualifier("deepseekChatModel")
    private final org.springframework.ai.chat.model.ChatModel chatModel;
    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;

    @Override
    public String type() { return "platform.rag.query"; }

    @Override
    public NodeSchema schema() {
        return new NodeSchema(type(), "RAG 检索问答", "基于知识库的检索增强生成问答",
                "platform", "database-search",
                List.of(
                        new NodeSchema.ParamDef("query", "string", true, null, "查询问题，支持 $data.xxx / $trigger.xxx"),
                        new NodeSchema.ParamDef("similarityThreshold", "number", false, "0.5", "相似度阈值"),
                        new NodeSchema.ParamDef("topK", "number", false, "4", "返回文档片段数量"),
                        new NodeSchema.ParamDef("systemPrompt", "string", false, null, "自定义系统提示词")
                ),
                List.of(
                        new NodeSchema.ParamDef("answer", "string", false, null, "生成的回答"),
                        new NodeSchema.ParamDef("sources", "object[]", false, null, "检索到的文档来源")
                ),
                false, 30000);
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String query = (String) ctx.resolvedInputs().get("query");
        if (query == null || query.isEmpty()) {
            return NodeResult.error("'query' is required");
        }
        query = resolve(query, ctx);

        double threshold = getDouble(ctx.resolvedInputs(), "similarityThreshold", 0.5);
        int topK = getInt(ctx.resolvedInputs(), "topK", 4);

        try {
            RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                    .queryExpander(MultiQueryExpander.builder()
                            .chatClientBuilder(chatClientBuilder.build().mutate())
                            .numberOfQueries(2)
                            .includeOriginal(true)
                            .build())
                    .queryTransformers(RewriteQueryTransformer.builder()
                            .chatClientBuilder(chatClientBuilder.build().mutate())
                            .build())
                    .documentRetriever(VectorStoreDocumentRetriever.builder()
                            .similarityThreshold(threshold)
                            .topK(topK)
                            .vectorStore(vectorStore)
                            .build())
                    .queryAugmenter(ContextualQueryAugmenter.builder()
                            .allowEmptyContext(true)
                            .build())
                    .build();

            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultAdvisors(ragAdvisor)
                    .build();

            String systemPrompt = (String) ctx.resolvedInputs().get("systemPrompt");
            String response = chatClient.prompt()
                    .system(s -> s.text(systemPrompt != null ? resolve(systemPrompt, ctx)
                            : "你是一个知识库助手，请根据提供的文档内容回答问题。"))
                    .user(query)
                    .call()
                    .content();

            log.info("RAG query response: {} chars", response != null ? response.length() : 0);
            return NodeResult.completed(Map.of(
                    "answer", response != null ? response : "",
                    "sources", List.of()
            ));
        } catch (Exception e) {
            log.error("RAG query failed: {}", e.getMessage());
            return NodeResult.error("RAG query failed: " + e.getMessage());
        }
    }

    private String resolve(String expr, NodeContext ctx) {
        if (expr == null) return null;
        Object resolved = VariableResolver.resolveExpression(expr,
                ctx.instance().variablesAsMap(), ctx.triggerPayload(), ctx.env());
        return resolved != null ? resolved.toString() : expr;
    }

    private double getDouble(Map<String, Object> map, String key, double def) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private int getInt(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }
}
