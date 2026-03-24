package com.zwbd.agentnexus.ai.dto;


import com.zwbd.agentnexus.ai.enums.Operator;
import com.zwbd.agentnexus.common.web.GlobalContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * @Author: wnli
 * @Date: 2025/9/16 10:50
 * @Desc: Chat request DTO for RAG question answering
 * <p>
 * This DTO represents the request payload for the RAG chat endpoint.
 * It contains the user's query and optional metadata filters.
 */
public record ChatRequest(

        @Schema(description = "用户的查询问题", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "Query must not be empty")
        String query,

        @Schema(description = "是否启用 RAG 功能。true: 结合知识库回答; false: 普通聊天模式。", defaultValue = "true")
        boolean useRag,

        @Schema(description = "应用于上下文检索的动态元数据过滤器列表 (仅在 useRag=true 时有效)")
        List<RAGFilter> RAGFilters,

        @Schema(description = "可选的会话ID。如果提供，将加载此会话的历史记录。如果为空，将创建一个新的会话。")
        String sessionId
) {

    @Override
    public String sessionId() {
        if (sessionId == null) {
            return UUID.randomUUID().toString();
        }
        return sessionId;
    }

    // 转换过滤条件列表为表达式字符串
    public String toExpression() {
//        return RAGFilters == null ? "" : RAGFilters.stream()
//                .map(ChatRequest::toSingleExpr)
//                .collect(Collectors.joining(" and "));
        List<RAGFilter> ragFilters = Optional.ofNullable(RAGFilters).orElse(new ArrayList<>());
        ragFilters.add(new RAGFilter(GlobalContext.KEY_SPACE_ID, Operator.EQUALS, GlobalContext.getSpaceId()));
        return ragFilters.stream()
                .map(ChatRequest::toSingleExpr)
                .collect(Collectors.joining(" and "));
    }

    // 单个过滤条件转换
    private static String toSingleExpr(RAGFilter filter) {
        String op = Operator.getOperatorSymbol(filter.operator());
        String value = formatValue(filter.value(), filter.operator());
        return "%s %s %s".formatted(filter.key(), op, value);
    }

    // 值格式化
    private static String formatValue(Object value, Operator operator) {
        if (value == null) return "null";

        // 处理IN/NOT_IN的列表值
        if ((operator == Operator.IN || operator == Operator.NOT_IN) && value instanceof List<?> list) {
            return "(" + list.stream()
                    .map(v -> formatSingleValue(v))
                    .collect(Collectors.joining(", ")) + ")";
        }

        return formatSingleValue(value);
    }

    // 单个值格式化
    private static String formatSingleValue(Object value) {
        return value instanceof String str
                ? "'" + str.replace("'", "''") + "'"  // 转义单引号
                : value.toString().toLowerCase();     // 布尔值转小写，数字直接转换
    }

    /**
     * 将通用的 Filter 列表构建成一个 Spring AI 的 Filter.Expression 对象。
     * 多个过滤器默认使用 AND 逻辑连接。
     *
     * @return Spring AI Filter.Expression
     */
    public Filter.Expression getFilterExpression() {
        Optional<Filter.Expression> expression = RAGFilters.stream()
                .map(this::toExpression)
                .reduce((expr1, expr2) -> new Filter.Expression(Filter.ExpressionType.AND, expr1, expr2));
        return expression.orElse(null);
    }

    /**
     * 将单个 Filter DTO 翻译成 Spring AI 的 Filter.Expression。
     * 注意：这里的 key 对应的是 document_chunks 表中 metadata 字段 (JSONB) 内的顶级键。
     * Spring AI 的 PgVectorStore 会自动将其翻译成 "metadata ->> 'key'" 这样的SQL语法。
     *
     * @param RAGFilter 单个过滤器
     * @return Spring AI Filter.Expression
     */
    private Filter.Expression toExpression(RAGFilter RAGFilter) {
        // 使用 "metadata." 前缀来明确指示这是对 metadata JSONB 字段的查询
//        String metadataKey = "metadata." + RAGFilter.key();
        String metadataKey = RAGFilter.key();

        return switch (RAGFilter.operator()) {
            case EQUALS ->
                    new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
            case NOT_EQUALS ->
                    new Filter.Expression(Filter.ExpressionType.NE, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
            case IN ->
                    new Filter.Expression(Filter.ExpressionType.IN, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
            case NOT_IN ->
                    new Filter.Expression(Filter.ExpressionType.NIN, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
            case GREATER_THAN ->
                    new Filter.Expression(Filter.ExpressionType.GT, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
            case GREATER_THAN_EQUALS ->
                    new Filter.Expression(Filter.ExpressionType.GTE, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
            case LESS_THAN ->
                    new Filter.Expression(Filter.ExpressionType.LT, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
            case LESS_THAN_EQUALS ->
                    new Filter.Expression(Filter.ExpressionType.LTE, new Filter.Key(metadataKey), new Filter.Value(RAGFilter.value()));
        };
    }

}