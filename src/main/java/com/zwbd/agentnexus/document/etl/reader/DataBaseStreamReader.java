package com.zwbd.agentnexus.document.etl.reader;

import com.zwbd.agentnexus.ai.dto.document.metadata.DocumentType;
import com.zwbd.agentnexus.datasource.dialect.DataStreamContext;
import com.zwbd.agentnexus.datasource.entity.DataBaseInfo;
import com.zwbd.agentnexus.datasource.service.DataBaseInfoService;
import com.zwbd.agentnexus.datasource.service.MetadataCollectorService;
import com.zwbd.agentnexus.document.entity.DocumentContext;
import com.zwbd.agentnexus.document.entity.DomainDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Stream;

/**
 * @Author: wnli
 * @Date: 2025/12/1 15:58
 * @Desc:
 */
@Component
public class DataBaseStreamReader implements DomainDocumentReader {

    @Autowired
    private DataBaseInfoService dataBaseInfoService;

    @Autowired
    private MetadataCollectorService metadataCollectorService;

    @Override
    public Stream<DocumentContext> openContentStream(DomainDocument domainDoc) {
        DataBaseInfo info = dataBaseInfoService.findById(Long.parseLong(domainDoc.getSourceId())).get().toEntityWithId();
        try (DataStreamContext<String> context =
                     metadataCollectorService.openDataStream(
                             info, domainDoc.getMetadata().get("schema").toString(),
                             domainDoc.getMetadata().get("table").toString(),
                             domainDoc.getMetadata().get("template").toString()
                     );
        ) {
            return context.getStream().map(doc -> new DocumentContext(doc, domainDoc.getMetadata()));
        }
    }

    @Override
    public Set<DocumentType> getSourceType() {
        return Set.of(DocumentType.DATABASE_STREAM);
    }

}
