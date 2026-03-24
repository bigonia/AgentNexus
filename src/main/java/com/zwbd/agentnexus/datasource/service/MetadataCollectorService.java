package com.zwbd.agentnexus.datasource.service;

import com.zwbd.agentnexus.common.config.TimeoutConfig;
import com.zwbd.agentnexus.common.exception.CommonException;
import com.zwbd.agentnexus.datasource.dialect.DataStreamContext;
import com.zwbd.agentnexus.datasource.dialect.DatabaseSession;
import com.zwbd.agentnexus.datasource.dialect.DialectFactory;
import com.zwbd.agentnexus.datasource.dto.metadata.ColumnMetadata;
import com.zwbd.agentnexus.datasource.dto.metadata.DatabaseMetadata;
import com.zwbd.agentnexus.datasource.dto.metadata.SchemaMetadata;
import com.zwbd.agentnexus.datasource.dto.metadata.TableMetadata;
import com.zwbd.agentnexus.datasource.entity.DataBaseInfo;
import com.zwbd.agentnexus.datasource.entity.ExecutionMode;
import com.zwbd.agentnexus.utils.TemplateRenderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @Author: wnli
 * @Date: 2025/12/25
 * @Desc: 元数据采集服务 (V2 重构版)
 * 核心变更：
 * 1. 采用 "System Catalog Driven" 模式，依赖数据库系统视图获取统计信息，摒弃 SELECT COUNT(*)。
 * 2. 实现 "Batch Loading"，按 Schema 批量获取表和列，解决 N+1 性能瓶颈。
 * 3. 引入 "Fast Sampling"，使用 Dialect 特定的采样策略进行数据预览。
 */
@Slf4j
@Service
public class MetadataCollectorService {

    @Autowired
    private DialectFactory dialectFactory;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private TimeoutConfig timeoutConfig;
    @Autowired
    private TemplateRenderService templateRenderService;

    /**
     * 异步采集指定数据源的元数据。
     * V2 版本忽略 ExecutionMode，强制使用基于系统视图的高效模式。
     */
    public CompletableFuture<DatabaseMetadata> collectMetadata(DataBaseInfo dataBaseInfo) {
        return collectMetadata(dataBaseInfo, ExecutionMode.AUTO);
    }

    public CompletableFuture<DatabaseMetadata> collectMetadata(DataBaseInfo dataBaseInfo, ExecutionMode mode) {
        CompletableFuture<DatabaseMetadata> future = CompletableFuture.supplyAsync(() -> {
            // 1. 获取会话
            try (DatabaseSession session = dialectFactory.openSession(dataBaseInfo)) {
                // 2. 执行核心采集逻辑
                return session.execute((dialect, connection) -> {
                    long startTime = System.currentTimeMillis();
                    log.info("Starting metadata collection for DB: {}", dataBaseInfo.getDatabaseName());

                    // A. 获取数据库基础信息
                    DatabaseMetaData metaData = connection.getMetaData();
                    String dbProductName = metaData.getDatabaseProductName();
                    String dbProductVersion = metaData.getDatabaseProductVersion();

                    // B. 获取所有 Schema (Catalogs)
                    List<SchemaMetadata> schemas = dialect.getSchemas(connection);
                    log.info("Found {} schemas. Starting batch processing...", schemas.size());

                    // C. 遍历 Schema 进行批量处理
                    // 注意：此处串行处理 Schema 以复用同一 Connection，避免连接池瞬时耗尽。
                    // 单个 Schema 内部已经是极速的 Batch 操作。
                    List<SchemaMetadata> enrichedSchemas = schemas.stream()
                            .map(schema -> {
                                try {
                                    return processSchemaBatch(connection, dialect, schema);
                                } catch (Exception e) {
                                    log.error("Failed to process schema: {}", schema.schemaName(), e);
                                    // 发生错误时返回原始空 Schema，并在备注中标记错误，保证整体流程不中断
                                    return new SchemaMetadata(schema.schemaName(), "Error: " + e.getMessage(), Collections.emptyList());
                                }
                            })
                            .collect(Collectors.toList());

                    log.info("Metadata collection finished in {} ms", System.currentTimeMillis() - startTime);
                    return new DatabaseMetadata(dbProductName, dbProductVersion, enrichedSchemas);
                });
            }
        }, executorService);

        // 超时控制
        return future.orTimeout(timeoutConfig.getTaskTimeoutMinutes(), TimeUnit.MINUTES)
                .exceptionally(throwable -> {
                    if (throwable instanceof TimeoutException) {
                        log.error("Metadata collection timed out for DB: {}", dataBaseInfo.getDatabaseName());
                        throw new RuntimeException("Task timed out", throwable);
                    } else {
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        log.error("Metadata collection failed for DB: {}", dataBaseInfo.getDatabaseName(), cause);
                        throw new RuntimeException("Metadata collection failed: " + cause.getMessage(), cause);
                    }
                });
    }

    /**
     * 【核心方法】批量处理单个 Schema
     * 逻辑：
     * 1. 获取所有表的基础信息（含统计）。
     * 2. 获取所有表的列信息。
     * 3. 在内存中组装。
     */
    private SchemaMetadata processSchemaBatch(Connection connection, com.zwbd.agentnexus.datasource.dialect.DatabaseDialect dialect, SchemaMetadata schema) throws SQLException {
        String schemaName = schema.schemaName();
        log.debug("Processing schema: {}", schemaName);

        // 1. 批量获取表信息 (含 rowCount, dataSize 等统计)
        List<TableMetadata> tables = dialect.getTablesWithStats(connection, schemaName);
        if (tables.isEmpty()) {
            return schema; // 无表直接返回
        }

        // 2. 提取表名列表
        List<String> tableNames = tables.stream()
                .map(TableMetadata::tableName)
                .collect(Collectors.toList());

        // 3. 批量获取列信息 (一次查询搞定所有表)
        Map<String, List<ColumnMetadata>> columnsMap = dialect.getColumnsBatch(connection, schemaName, tableNames);

        // 4. 内存组装 (In-Memory Join)
        List<TableMetadata> enrichedTables = tables.stream()
                .map(table -> {
                    List<ColumnMetadata> columns = columnsMap.getOrDefault(table.tableName(), Collections.emptyList());
                    // 使用 Record 的 @With 特性 (或构造器) 更新列信息
                    // 注意：此处不需要再计算 Metrics，因为 getTablesWithStats 和 getColumnsBatch 已经利用系统视图填充了 System Metrics
                    return table.withColumns(columns);
                })
                .collect(Collectors.toList());

        log.debug("Schema {} processed. Tables: {}", schemaName, enrichedTables.size());

        // 返回填充好数据的 Schema (保留原有的 remarks)
        return new SchemaMetadata(schemaName, schema.remarks(), enrichedTables);
    }

    /**
     * 【数据预览】获取指定表的采样数据
     * 使用 Dialect 特定的高效采样策略 (如 Tablesample, Rand 优化)
     */
    public List<Map<String, Object>> getPreviewData(DataBaseInfo dbInfo, String schema, String tableName) {
        try (DatabaseSession session = dialectFactory.openSession(dbInfo)) {
            return session.execute((dialect, connection) -> {
                // 确保连接只读
                if (!connection.isReadOnly()) {
                    connection.setReadOnly(true);
                }
                return dialect.sampleTableData(connection, schema, tableName);
            });
        }
    }

    // =========================================================================
    // Legacy / Streaming Support
    // =========================================================================

    public List<String> getSchemas(DataBaseInfo dbInfo) {
        try (DatabaseSession session = dialectFactory.openSession(dbInfo)) {
            return session.execute((dialect, connection) ->
                    dialect.getSchemas(connection).stream()
                            .map(SchemaMetadata::schemaName)
                            .collect(Collectors.toList())
            );
        }
    }

    public List<String> getTables(DataBaseInfo dbInfo, String schema) {
        try (DatabaseSession session = dialectFactory.openSession(dbInfo)) {
            return session.execute((dialect, connection) ->
                    dialect.getTablesWithStats(connection, schema).stream().map(TableMetadata::tableName).collect(Collectors.toList()));
        }
    }

    public List<String> getColumns(DataBaseInfo dbInfo, String schema, String tableName) {
        try (DatabaseSession session = dialectFactory.openSession(dbInfo)) {
            return session.execute((dialect, connection) ->
                    dialect.getColumnsBatch(connection, schema, List.of(tableName))
                            .get(tableName).stream().map(ColumnMetadata::columnName).collect(Collectors.toList()));
        }
    }

    /**
     * 数据导出流 (保持原有功能，用于大文件下载)
     */
    public DataStreamContext<String> openDataStream(DataBaseInfo dbInfo, String schema, String tableName, String template) {
        try (DataStreamContext<Map<String, Object>> context = openDataStreamRaw(dbInfo, schema, tableName)) {
            // 将原始 Map 流转换为渲染后的 String 流
            Stream<String> stream = context.getStream()
                    .map(row -> templateRenderService.render(template, row));
            // 传递 Context 以便上层关闭资源
            return new DataStreamContext<>(stream, context);
        }
    }

    private DataStreamContext<Map<String, Object>> openDataStreamRaw(DataBaseInfo dbInfo, String schema, String tableName) {
        DatabaseSession session = dialectFactory.openSession(dbInfo);
        Connection connection = null;
        try {
            connection = session.getDataSource().getConnection();
            // 使用 Dialect 的流式接口
            Stream<Map<String, Object>> stream = session.getDialect().streamTableData(connection, schema, tableName);

            final Connection finalConnection = connection;
            // 返回 Context，包含关闭连接的回调
            return new DataStreamContext<>(stream, () -> {
                try {
                    if (finalConnection != null && !finalConnection.isClosed()) {
                        finalConnection.close();
                    }
                } catch (SQLException e) {
                    log.error("Error closing stream connection", e);
                }
            });
        } catch (Exception e) {
            if (connection != null) {
                try { connection.close(); } catch (Exception ex) { /* ignore */ }
            }
            throw new CommonException("Failed to open data stream", e);
        }
    }
}