package com.zwbd.agentnexus.document.etl.loader.impl;

import com.zwbd.agentnexus.document.etl.loader.DocumentLoader;
import com.zwbd.agentnexus.ai.dto.document.metadata.BaseMetadata;
import com.zwbd.agentnexus.ai.dto.document.metadata.DocumentType;
import com.zwbd.agentnexus.ai.dto.document.metadata.FileUploadMetadata;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Author: wnli
 * @Date: 2025/10/23 16:19
 * @Desc:
 */
@Component
public class TikaDocumentLoader implements DocumentLoader {

    @Autowired
    private ResourceLoader resourceLoader;

    @Override
    public List<Document> load(BaseMetadata metadata) {
        FileUploadMetadata fileUploadMetadata = (FileUploadMetadata) metadata;
        String filePathStr = fileUploadMetadata.getFilePath();
        String fileUri = Paths.get(filePathStr).toUri().toString();
        Resource resource = this.resourceLoader.getResource(fileUri);
        //文件资源的元数据
        Map<String, Object> metadataMap = fileUploadMetadata.toMap();
        List<Document> documents = getDocuments(resource, metadataMap);
        return documents;
    }

    private static List<Document> getDocuments(Resource resource, Map<String, Object> metadataMap) {
        TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(resource);
        List<Document> documents = tikaDocumentReader.read();
        documents.forEach(doc -> {
            // 获取文档现有的元数据（可变 Map）
            Map<String, Object> docMetadata = doc.getMetadata();
            // 写入你的自定义元数据
            // 如果 key 相同，putAll 会用你的自定义值覆盖 Tika 提取的值
            if (metadataMap != null) {
                docMetadata.putAll(metadataMap);
            }
        });
        return documents;
    }

    @Override
    public Set<DocumentType> getSourceType() {
        return Set.of(DocumentType.DOC, DocumentType.DOCX, DocumentType.PDF
//                ,DocumentType.TXT
//                , DocumentType.PPT, DocumentType.PPTX
        );
    }

}
