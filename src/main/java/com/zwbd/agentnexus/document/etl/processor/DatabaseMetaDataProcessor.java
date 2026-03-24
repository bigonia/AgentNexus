package com.zwbd.agentnexus.document.etl.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.datasource.dto.metadata.TableMetadata;
import com.zwbd.agentnexus.datasource.service.DatabaseMetadataStorageService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @Author: wnli
 * @Date: 2025/9/29 17:41
 * @Desc: 组装表的样例数据
 */
@Slf4j
@Component
@AllArgsConstructor
public class DatabaseMetaDataProcessor implements DocumentPostProcessor {

    private final ObjectMapper objectMapper;

    private final DatabaseMetadataStorageService databaseMetadataStorageService;

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        List<Document> collect = documents.stream()
                .filter(item -> item.getMetadata().get("document_type").equals("table_detail"))
                .map(item -> {
                    try {
                        String schemaName = item.getMetadata().get("schema_name").toString();
                        String table_name = item.getMetadata().get("table_name").toString();
                        Optional<TableMetadata> sampleData = databaseMetadataStorageService.findTable("databaseID", schemaName, table_name);
                        if (sampleData.isPresent()) {
                            String data = objectMapper.writeValueAsString(sampleData.get().trimSampleData(3, 64));
                            return item.mutate().text(item.getText() + data).build();
                        }
                    } catch (Exception e) {
                        log.error("Error while processing table detail", e);
                    }
                    return item;
                }).collect(Collectors.toList());
        return collect;
    }
}
