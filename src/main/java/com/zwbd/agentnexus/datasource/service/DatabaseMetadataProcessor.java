package com.zwbd.agentnexus.datasource.service;

import com.zwbd.agentnexus.datasource.dto.ColumnViewModel;
import com.zwbd.agentnexus.datasource.dto.TableViewModel;
import com.zwbd.agentnexus.datasource.dto.metadata.SchemaMetadata;
import com.zwbd.agentnexus.datasource.dto.metadata.ColumnMetadata;
import com.zwbd.agentnexus.datasource.dto.metadata.DatabaseMetadata;
import com.zwbd.agentnexus.datasource.dto.metadata.TableMetadata;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @Author: wnli
 * @Date: 2025/9/22 18:38
 * @Desc: 元数据处理为非结构化数据，用于AI分析
 */
@Component
public class DatabaseMetadataProcessor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMetadataProcessor.class);
    private final Configuration freemarkerConfig;

    @Autowired
    public DatabaseMetadataProcessor(Configuration freemarkerConfig) {
        this.freemarkerConfig = freemarkerConfig;
    }

    public Stream<Document> process(DatabaseMetadata dbMeta, Map<String, Object> metadata) {
        return Stream.concat(
                generateTableDocumentsStream(dbMeta, metadata),
                generateSummaryDocumentStream(dbMeta, metadata)
        );
    }


    private Stream<Document> generateTableDocumentsStream(DatabaseMetadata dbMeta, Map<String, Object> metadata) {
        if (dbMeta.catalogs() == null) {
            return Stream.empty();
        }
        return dbMeta.catalogs().stream()
                .flatMap(catalog -> catalog.tables().stream()
                        .map(table -> createTableDocument(table, catalog, dbMeta, metadata))
                );
    }

    private Stream<Document> generateSummaryDocumentStream(DatabaseMetadata dbMeta, Map<String, Object> metadata) {
        Map<String, Object> model = Map.of("dbMeta", dbMeta);
        String summaryContent = processTemplate("database-summary.ftl", model);

        metadata.put("document_type", "database_summary");
        metadata.put("database_name", dbMeta.databaseProductName());

        log.info("generate summary document {}", summaryContent.length());
        return Stream.of(new Document(summaryContent, metadata));
    }


    private Document createTableDocument(TableMetadata table, SchemaMetadata catalog, DatabaseMetadata dbMeta, Map<String, Object> metadata) {
        // TableMetadata 转换为 TableViewModel，进行数据预处理
        TableViewModel tableViewModel = toTableViewModel(table);
        // 1. 准备模板需要的数据模型 (Model)
        Map<String, Object> model = new HashMap<>();
        model.put("schemaName", catalog.schemaName());
        model.put("table", tableViewModel);

        // 2. 预处理列信息，创建ViewModel列表
        List<ColumnViewModel> columnViewModels = table.columns().stream()
                .map(this::toColumnViewModel)
                .collect(Collectors.toList());
        model.put("columns", columnViewModels); // 在模板中使用 ${columns}

        // 3. 处理模板并生成内容
        String content = processTemplate("table-detail.ftl", model);

        // 4. 补充元数据
        metadata.put("document_type", "table_detail");
        metadata.put("database_name", dbMeta.databaseProductName());
        metadata.put("schema_name", catalog.schemaName());
        metadata.put("table_name", table.tableName());
        metadata.put("row_count", table.rowCount());

        log.info("generate table_detail document {}", content.length());
        return new Document(content, metadata);
    }

    private TableViewModel toTableViewModel(TableMetadata table) {
        Optional<List<Map<String, String>>> stringifiedSampleData = table.sampleData()
                .map(listOfMaps -> listOfMaps.stream()
                        .map(row -> row.entrySet().stream()
                                .collect(Collectors.toMap(
                                        Map.Entry::getKey,
                                        entry -> String.valueOf(entry.getValue()) // 安全地将任何对象转换为字符串
                                ))
                        )
                        .collect(Collectors.toList())
                );

        return new TableViewModel(
                table.tableName(),
                table.tableType(),
                table.comment(),
                table.rowCount(),
                stringifiedSampleData
        );
    }

    /**
     * 将原始的 ColumnMetadata 转换为更易于模板使用的 ColumnViewModel
     */
    private ColumnViewModel toColumnViewModel(ColumnMetadata column) {
        return new ColumnViewModel(
                column.columnName(),
                column.dataType(),
                column.isPrimaryKey(),
                column.isNullable(),
                formatCommentAndMetrics(column) // 复用之前的格式化逻辑
        );
    }

    private String formatCommentAndMetrics(ColumnMetadata column) {
        StringBuilder metrics = new StringBuilder();
        column.comment().ifPresent(metrics::append);
        Optional.ofNullable(column.metrics()).ifPresent(m -> {
            String metricDetails = Stream.of(
                            m.get().distinctValues().map(v -> "基数（Cardinality）=" + v),
                            m.get().avgLength().map(v -> String.format("平均长度（avgLength）=", v )),
                            m.get().nullValues().map(v -> String.format("空值数量（NullValues）=", v))
                    )
                    .flatMap(Optional::stream)
                    .collect(Collectors.joining(", "));

            if (!metricDetails.isEmpty()) {
                metrics.append(" [指标值（Metrics）: ").append(metricDetails).append("]");
            }
        });
        return metrics.toString();
    }

    /**
     * 统一的模板处理方法
     *
     * @param templateName 模板文件名 (e.g., "table-detail.ftl")
     * @param model        包含所有需要的数据的Map
     * @return 渲染后的字符串
     */
    private String processTemplate(String templateName, Map<String, Object> model) {
        try {
            Template template = freemarkerConfig.getTemplate(templateName);
            return FreeMarkerTemplateUtils.processTemplateIntoString(template, model);
        } catch (IOException | TemplateException e) {
            // 在生产环境中，这里应该抛出一个自定义的运行时异常
            throw new IllegalStateException("Failed to process Freemarker template: " + templateName, e);
        }
    }
}
