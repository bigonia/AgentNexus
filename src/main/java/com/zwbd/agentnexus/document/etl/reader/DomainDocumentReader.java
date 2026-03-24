package com.zwbd.agentnexus.document.etl.reader;

import com.zwbd.agentnexus.ai.dto.document.metadata.DocumentType;
import com.zwbd.agentnexus.document.entity.DocumentContext;
import com.zwbd.agentnexus.document.entity.DomainDocument;

import java.util.Set;
import java.util.stream.Stream;

/**
 * @Author: wnli
 * @Date: 2025/12/1 15:57
 * @Desc:
 */
public interface DomainDocumentReader {

    /**
     * 获取文档的流式读取器。
     * 无论底层是静态文本还是DB数据，都转换为 Stream<Document> 供 AI 消费（如嵌入、问答）。
     */
    Stream<DocumentContext> openContentStream(DomainDocument domainDoc);


    Set<DocumentType> getSourceType();

}
