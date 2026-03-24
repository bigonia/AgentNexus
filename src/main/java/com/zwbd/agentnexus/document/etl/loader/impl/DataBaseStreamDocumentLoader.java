package com.zwbd.agentnexus.document.etl.loader.impl;

import com.zwbd.agentnexus.document.etl.loader.DocumentLoader;
import com.zwbd.agentnexus.ai.dto.document.metadata.BaseMetadata;
import com.zwbd.agentnexus.ai.dto.document.metadata.DatabaseRecordMetadata;
import com.zwbd.agentnexus.ai.dto.document.metadata.DocumentType;
import com.zwbd.agentnexus.datasource.dialect.DataStreamContext;
import com.zwbd.agentnexus.datasource.entity.DataBaseInfo;
import com.zwbd.agentnexus.datasource.service.DataBaseInfoService;
import com.zwbd.agentnexus.datasource.service.MetadataCollectorService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * @Author: wnli
 * @Date: 2025/12/1 16:58
 * @Desc:
 */
@Component
public class DataBaseStreamDocumentLoader implements DocumentLoader {

    @Autowired
    private DataBaseInfoService dataBaseInfoService;

    @Autowired
    private MetadataCollectorService metadataCollectorService;

    @Override
    public List<Document> load(BaseMetadata metadata) {
        DatabaseRecordMetadata databaseRecordMetadata = (DatabaseRecordMetadata) metadata;
        DataBaseInfo info = dataBaseInfoService.findById(Long.parseLong(databaseRecordMetadata.getSourceId())).get().toEntityWithId();
        try (DataStreamContext<String> context =
                     metadataCollectorService.openDataStream(
                             info, databaseRecordMetadata.getSchema(), databaseRecordMetadata.getTable(), databaseRecordMetadata.getTemplate())
        ) {
//            return context.getStream().limit(10).map(doc -> new Document(doc,metadata.toMap())).toList();
            return context.getStream().map(doc -> new Document(doc,metadata.toMap())).toList();
        }
    }

    @Override
    public Set<DocumentType> getSourceType() {
        return Set.of(DocumentType.DATABASE_STREAM);
    }
}
