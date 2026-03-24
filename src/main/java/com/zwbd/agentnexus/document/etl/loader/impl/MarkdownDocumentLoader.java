package com.zwbd.agentnexus.document.etl.loader.impl;

import com.zwbd.agentnexus.document.etl.loader.DocumentLoader;
import com.zwbd.agentnexus.ai.dto.document.metadata.BaseMetadata;
import com.zwbd.agentnexus.ai.dto.document.metadata.DocumentType;
import com.zwbd.agentnexus.ai.dto.document.metadata.FileUploadMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * @Author: wnli
 * @Date: 2025/9/18 9:07
 * @Desc:
 */
@Slf4j
@Component
public class MarkdownDocumentLoader implements DocumentLoader {

    @Autowired
    private ResourceLoader resourceLoader;

    @Override
    public List<Document> load(BaseMetadata metadata) {
        FileUploadMetadata fileUploadMetadata = (FileUploadMetadata) metadata;
        String filePathStr = fileUploadMetadata.getFilePath();
        String fileUri = Paths.get(filePathStr).toUri().toString();
        Resource resource = this.resourceLoader.getResource(fileUri);
        // 配置并使用 Spring AI 的原生 Reader
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                // 附加所有元数据
                .withAdditionalMetadata(fileUploadMetadata.toMap())
                //设为 true 时，Markdown 中的水平分隔符将生成新 Document 对象。
                .withHorizontalRuleCreateDocument(false)
                //设为 true 时，代码块将与周边文本合并到同一 Document；设为 false 时，代码块生成独立 Document 对象。
                .withIncludeCodeBlock(false)
                //设为 true 时，引用块将与周边文本合并到同一 Document；设为 false 时，引用块生成独立 Document 对象。
                .withIncludeBlockquote(true)
                .build();

        MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
        return reader.get();
    }

    @Override
    public Set<DocumentType> getSourceType() {
        return Set.of(DocumentType.MD, DocumentType.TXT);
    }
}
