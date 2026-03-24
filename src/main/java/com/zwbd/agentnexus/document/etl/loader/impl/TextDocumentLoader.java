package com.zwbd.agentnexus.document.etl.loader.impl;

import com.zwbd.agentnexus.document.etl.loader.DocumentLoader;
import com.zwbd.agentnexus.ai.dto.document.metadata.BaseMetadata;
import com.zwbd.agentnexus.ai.dto.document.metadata.DocumentType;
import com.zwbd.agentnexus.ai.dto.document.metadata.FileUploadMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.*;

/**
 * @Author: wnli
 * @Date: 2025/9/16 11:18
 * @Desc: Text document loader for plain text content
 * 
 * This loader handles plain text content provided directly in the request.
 * It's useful for ingesting text snippets, articles, or any textual content.
 */
@Slf4j
@Component
public class TextDocumentLoader implements DocumentLoader {

    @Autowired
    private ResourceLoader resourceLoader;
    
    @Override
    public List<Document> load(BaseMetadata metadata)  {
        log.debug("Loading text document");
        FileUploadMetadata fileUploadMetadata = (FileUploadMetadata) metadata;
        String filePathStr = fileUploadMetadata.getFilePath();
        String fileUri = Paths.get(filePathStr).toUri().toString();
        Resource resource = this.resourceLoader.getResource(fileUri);
        //文件资源的元数据
        // 文件资源的元数据（基础元数据）
        Map<String, Object> metadataMap = fileUploadMetadata.toMap();
        // 最终返回的Document列表
        List<Document> documents = new ArrayList<>();

        try {
            // 1. 读取文件全部内容（使用默认字符集，也可改为指定编码如StandardCharsets.UTF_8）
            String content = resource.getContentAsString(Charset.defaultCharset());

            // 2. 按换行拆分（兼容Windows(\r\n)、Linux(\n)、Mac(\r)所有换行符）
            String[] lines = content.split("\\r?\\n");

            // 3. 遍历每行，生成Document（可按需过滤空行）
            for (int lineNum = 0; lineNum < lines.length; lineNum++) {
                String lineContent = lines[lineNum];
                // 可选：过滤纯空白行（根据业务需求决定是否保留）
                if (lineContent.trim().isEmpty()) {
                    continue; // 跳过空行，注释此行则保留空行
                }

                // 4. 为每行构建独立元数据（继承基础元数据+补充行号/文件路径等）
                Map<String, Object> lineMetadata = new HashMap<>(metadataMap);
                lineMetadata.put("lineNumber", lineNum + 1); // 行号从1开始
                lineMetadata.put("filePath", filePathStr);    // 关联原始文件路径
                lineMetadata.put("totalLines", lines.length); // 总行数

                // 5. 创建单行Document（根据实际Document类的构造方法调整）
                // 假设Document构造器：new Document(内容, 元数据Map)
                Document lineDoc = new Document(lineContent, lineMetadata);
                documents.add(lineDoc);
            }

            log.debug("Split text file into {} valid lines (documents)", documents.size());

        } catch (IOException e) {
            log.error("Failed to load and split text document", e);
            // 异常时返回空列表，避免返回null导致NPE
            return new ArrayList<>();
        }

        // 返回拆分后的Document列表（非null）
        return documents;
    }

    @Override
    public Set<DocumentType> getSourceType() {
        return Set.of(DocumentType.TXT);
    }

}