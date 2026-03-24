package com.zwbd.agentnexus.ai.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.ai.dto.document.DocumentChunkDTO;
import com.zwbd.agentnexus.ai.dto.document.DocumentInfoDTO;
import com.zwbd.agentnexus.ai.dto.RAGFilter;
import com.zwbd.agentnexus.common.web.GlobalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.*;

/**
 * @Desc: RAG 文档混合存储库
 * 统一管理文档的向量数据（通过 VectorStore）和元数据（通过 JdbcTemplate）。
 * Service 层应只调用此类，而不应直接操作 JdbcTemplate。
 */
@Slf4j
@Repository
public class RAGDocumentRepository {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    // 批量写入大小
    private static final int BATCH_SIZE = 10;
    // 向量表名 (通常 Spring AI 默认为 vector_store，根据您的实际情况调整)
    private static final String VECTOR_TABLE_NAME = "document_chunks";

    public RAGDocumentRepository(VectorStore vectorStore, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ========================================================================
    // 1. 向量操作 (Vector Operations)
    // ========================================================================

    /**
     * 批量保存文档向量
     */
    public void save(List<Document> documents) {
        Assert.notEmpty(documents, "Documents list must not be empty");
        int totalSize = documents.size();
        log.info("save documents size: {}", totalSize);
        // 1. 注入业务空间 ID
        String spaceId = GlobalContext.getSpaceId();
        String spaceKey = GlobalContext.KEY_SPACE_ID;

        for (Document doc : documents) {
            doc.getMetadata().put(spaceKey, spaceId);
        }

        for (int i = 0; i < totalSize; i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, totalSize);
            List<Document> batch = documents.subList(i, end);
            log.debug("Batch saving chunks: {} to {}", i, end - 1);
            vectorStore.add(batch);
            try {
                Thread.sleep(100); // 避免触发数据库或 API 速率限制
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 语义搜索
     */
    public List<Document> search(String query, int topK, double threshold,List<RAGFilter> filters) {
        Assert.hasText(query, "Query must not be empty");
        SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(topK).similarityThreshold(threshold);

        // 1. 构建空间隔离 Filter
        Filter.Expression spaceExpression = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(GlobalContext.KEY_SPACE_ID),
                new Filter.Value(GlobalContext.getSpaceId())
        );

        // 2. 处理用户传入的 Filters
        Filter.Expression finalExpression = spaceExpression;
        if (!CollectionUtils.isEmpty(filters)) {
            Optional<Filter.Expression> userExpressionOpt = buildFilterExpression(filters);
            if (userExpressionOpt.isPresent()) {
                // 将空间 Filter 和 用户 Filter 进行 AND 组合
                finalExpression = new Filter.Expression(
                        Filter.ExpressionType.AND,
                        spaceExpression,
                        userExpressionOpt.get()
                );
            }
        }

        // 3. 应用最终的 Filter
        builder.filterExpression(finalExpression);

        return vectorStore.similaritySearch(builder.build());
    }

    /**
     * 删除指定条件的文档（利用 VectorStore 的 Filter 能力）
     */
    public void deleteByExpression(Filter.Expression expression) {
        // 构建空间隔离 Filter
        Filter.Expression spaceExpression = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(GlobalContext.KEY_SPACE_ID),
                new Filter.Value(GlobalContext.getSpaceId())
        );

        Filter.Expression finalExpression;
        if (expression != null) {
            // 组合：(SpaceID == curr) AND (UserExpression)
            finalExpression = new Filter.Expression(
                    Filter.ExpressionType.AND,
                    spaceExpression,
                    expression
            );
        } else {
            finalExpression = spaceExpression;
        }
        vectorStore.delete(finalExpression);
    }

    // ========================================================================
    // 2. 元数据管理操作 (Metadata Management - Relational View)
    // ========================================================================

    /**
     * 获取所有“逻辑文档”的摘要列表（基于 document_id 分组）
     * 以前在 Service 层的 SQL 逻辑移动至此
     */
    public List<DocumentInfoDTO> findAllDocumentSummaries() {
        final String sql = """
                SELECT
                    metadata ->> 'sourceId' as sourceId,
                    MAX(metadata ->> 'sourceName') as sourceName,
                    MAX(metadata ->> 'documentType') as documentType,
                    MAX(metadata ->> 'sourceSystem') as sourceSystem,
                    COUNT(*) as chunk_count
                FROM %s
                WHERE  metadata ->> ? = ?
                GROUP BY metadata ->> 'sourceId'
                ORDER BY MAX(created_at) DESC
                """.formatted(VECTOR_TABLE_NAME);

        // 参数顺序：SpaceKey, SpaceValue
        Object[] params = new Object[]{GlobalContext.KEY_SPACE_ID, GlobalContext.getSpaceId()};

        log.info("param: {}", Arrays.toString(params));

        return jdbcTemplate.query(sql, (rs, rowNum) -> new DocumentInfoDTO(
                        rs.getString("sourceId"),
                        rs.getString("sourceName"),
                        rs.getString("documentType"),
                        rs.getString("sourceSystem"),
                        rs.getLong("chunk_count")
                )
                , params
        );
    }

    /**
     * 根据 document_id 获取所有分片详情
     */
    public List<DocumentChunkDTO> findChunksByDocumentId(String sourceId) {
        final String sql = """
                SELECT id, content, metadata
                FROM %s
                WHERE metadata ->> 'sourceId' = ?
                  AND metadata ->> ? = ?
                ORDER BY (metadata ->> 'chunk_sequence')::int ASC
                """.formatted(VECTOR_TABLE_NAME);

        Object[] params = new Object[]{
                sourceId,
                GlobalContext.KEY_SPACE_ID,
                GlobalContext.getSpaceId()
        };

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            return new DocumentChunkDTO(
                    rs.getString("id"),
                    rs.getString("content"),
                    parseMetadata(rs.getString("metadata"))
            );
        }, params);
    }

    /**
     * 获取单个分片的元数据
     */
    public Optional<Map<String, Object>> findMetadataByChunkId(String chunkId) {
        final String sql = "SELECT metadata FROM " + VECTOR_TABLE_NAME + " WHERE id = ?::uuid AND metadata ->> ? = ?";
        Object[] params = new Object[]{
                chunkId,
                GlobalContext.KEY_SPACE_ID,
                GlobalContext.getSpaceId()
        };
        try {
            Map<String, Object> metadata = jdbcTemplate.queryForObject(sql, (rs, rowNum) ->
                    parseMetadata(rs.getString("metadata")), params);
            return Optional.ofNullable(metadata);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 基于 sourceId 的物理删除
     */
    public void deleteBySourceId(String sourceId) {
        String sql = "DELETE FROM " + VECTOR_TABLE_NAME + " WHERE metadata ->> 'sourceId' = ? AND metadata ->> ? = ?";
        Object[] params = new Object[]{
                sourceId,
                GlobalContext.KEY_SPACE_ID,
                GlobalContext.getSpaceId()
        };
        jdbcTemplate.update(sql, params);
    }

    /**
     * 动态条件删除 (Metadata 匹配)
     */
    public int deleteByMetadataConditions(Map<String, Object> conditions) {
        // 如果没有条件，也必须限制在当前 Space 下（相当于清空当前 Space 数据，需谨慎，这里假设 conditions 必须传参）
        // 如果允许清空 Space，则不需要 isEmpty 检查。这里为了安全保留非空校验，但加上 Space 限制。
        if (CollectionUtils.isEmpty(conditions)) return 0;

        StringBuilder sql = new StringBuilder("DELETE FROM " + VECTOR_TABLE_NAME + " WHERE 1=1");
        List<Object> params = new ArrayList<>();

        // 1. 强制添加 Space ID 条件
        sql.append(" AND metadata ->> ? = ?");
        params.add(GlobalContext.KEY_SPACE_ID);
        params.add(GlobalContext.getSpaceId());

        // 2. 添加动态条件
        for (Map.Entry<String, Object> entry : conditions.entrySet()) {
            // 避免重复添加 Space ID (如果 conditions 里也传了)
            if (entry.getKey().equals(GlobalContext.KEY_SPACE_ID)) {
                continue;
            }
            sql.append(" AND metadata ->> ? = ?");
            params.add(entry.getKey());
            params.add(String.valueOf(entry.getValue()));
        }
        return jdbcTemplate.update(sql.toString(), params.toArray());
    }

    private Map<String, Object> parseMetadata(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.error("Failed to parse metadata JSON", e);
            return Map.of();
        }
    }

    private Optional<Filter.Expression> buildFilterExpression(List<RAGFilter> filters) {
        return filters.stream()
                .map(this::toExpression)
                .reduce((e1, e2) -> new Filter.Expression(Filter.ExpressionType.AND, e1, e2));
    }

    private Filter.Expression toExpression(RAGFilter filter) {
        // Spring AI PGVector 默认行为：不需要 "metadata." 前缀，直接传 key 即可，
        // 但取决于具体 VectorStore 实现。如果之前你的实现能跑通，保持原样。
        // 假设 filter.key() 返回的是 "document_id" 这种纯 key
        String key = filter.key();
        Filter.Key filterKey = new Filter.Key(key);
        Filter.Value filterValue = new Filter.Value(filter.value());

        return switch (filter.operator()) {
            case EQUALS -> new Filter.Expression(Filter.ExpressionType.EQ, filterKey, filterValue);
            case NOT_EQUALS -> new Filter.Expression(Filter.ExpressionType.NE, filterKey, filterValue);
            case IN -> new Filter.Expression(Filter.ExpressionType.IN, filterKey, filterValue);
            case NOT_IN -> new Filter.Expression(Filter.ExpressionType.NIN, filterKey, filterValue);
            case GREATER_THAN -> new Filter.Expression(Filter.ExpressionType.GT, filterKey, filterValue);
            case GREATER_THAN_EQUALS -> new Filter.Expression(Filter.ExpressionType.GTE, filterKey, filterValue);
            case LESS_THAN -> new Filter.Expression(Filter.ExpressionType.LT, filterKey, filterValue);
            case LESS_THAN_EQUALS -> new Filter.Expression(Filter.ExpressionType.LTE, filterKey, filterValue);
        };
    }
}