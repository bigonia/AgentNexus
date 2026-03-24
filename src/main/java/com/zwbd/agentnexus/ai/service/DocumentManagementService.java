package com.zwbd.agentnexus.ai.service;

import com.zwbd.agentnexus.ai.dto.document.DocumentChunkDTO;
import com.zwbd.agentnexus.ai.dto.document.DocumentInfoDTO;
import com.zwbd.agentnexus.ai.dto.document.metadata.BaseMetadata;
import com.zwbd.agentnexus.ai.dto.document.metadata.DocumentType;
import com.zwbd.agentnexus.ai.repository.RAGDocumentRepository;
import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.document.entity.DocumentContext;
import com.zwbd.agentnexus.document.entity.DomainDocument;
import com.zwbd.agentnexus.document.etl.loader.DocumentLoader;
import com.zwbd.agentnexus.document.service.DocumentContextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Desc: 核心文档管理服务
 * 职责：协调 ETL 流程（Load -> Split -> Store）及处理业务逻辑。
 * 不再包含任何 JDBC 代码。
 */
@Slf4j
@Service
public class DocumentManagementService {

    private final RAGDocumentRepository documentRepository;
    private final Map<DocumentType, DocumentLoader> documentLoaders;
//    private final TokenTextSplitter textSplitter = new  TokenTextSplitter();
    private final TokenTextSplitter textSplitter = new  TokenTextSplitter(800, 350, 1, 10000, true);

    @Autowired
    private DocumentContextService documentContextService;

    public DocumentManagementService(RAGDocumentRepository documentRepository,
                                     List<DocumentLoader> loaderImplementations) {
        this.documentRepository = documentRepository;
        this.documentLoaders = new HashMap<>();

        if (loaderImplementations != null) {
            for (DocumentLoader loader : loaderImplementations) {
                loader.getSourceType().forEach(type -> documentLoaders.put(type, loader));
            }
        }
        log.info("Initialized Service with loaders: {}", documentLoaders.keySet());
    }

    @Transactional
    public List<Document> ingest(DomainDocument domainDocument) {
        log.info("Starting ingestion domainDocument {}", domainDocument.getId());
        List<DocumentContext> documentContent = documentContextService.getDocumentContents(domainDocument.getId());
        List<Document> documents = documentContent.stream().map(documentContext -> {
            return new Document(documentContext.getText(), documentContext.getMetadata());
        }).toList();
        log.info("ingest domainDocument {} size: {}", domainDocument.getId(), documents.size());
//        documents.forEach(item-> System.out.println(item.getText()));
        List<Document> chunks = textSplitter.split(documents);
        log.info("splitting chunks size {}", chunks.size());
//        chunks.forEach(item-> System.out.println(item.getText()));
        documentRepository.save(chunks);
        return chunks;
    }

    /**
     * ETL 核心流程：注入文档
     */
    @Transactional
    public List<Document> ingest(BaseMetadata metadata, boolean preview) {
        DocumentType sourceType = metadata.getDocumentType();
        log.info("Starting ingestion for type: {}", sourceType);

        // 1. 获取加载器
        DocumentLoader loader = documentLoaders.get(sourceType);
        Assert.notNull(loader, "Unsupported source type: " + sourceType);

        // 2. 加载文档
        List<Document> documents = loader.load(metadata);
        if (documents.isEmpty()) return Collections.emptyList();

        // 3. 切分文档
        List<Document> chunks = textSplitter.split(documents);

        // 4. 持久化 (仅非预览模式)
        if (!preview && !chunks.isEmpty()) {
            log.info("Saving {} chunks to repository.", chunks.size());
            documentRepository.save(chunks);
        }

        return chunks;
    }

    public List<Document> search(String query, int topK, double threshold) {
        List<Document> documents = documentRepository.search(query, topK, threshold, null);
        return documents;
    }

    /**
     * 查询逻辑文档列表
     */
    public List<DocumentInfoDTO> listAllDocuments() {
        return documentRepository.findAllDocumentSummaries();
    }

    /**
     * 获取文档分片详情
     */
    public List<DocumentChunkDTO> getDocumentChunks(String sourceId) {
        return documentRepository.findChunksByDocumentId(sourceId);
    }

    /**
     * 更新分片内容
     * 业务逻辑：更新内容 -> 重新生成向量 (Implicit in save) -> 更新存储
     */
    @Transactional
    public void updateChunkContent(String chunkId, String newContent) {
        Assert.hasText(chunkId, "Chunk ID required");
        Assert.hasText(newContent, "Content required");

        // 1. 从 Repository 获取旧的元数据
        Map<String, Object> metadata = documentRepository.findMetadataByChunkId(chunkId)
                .orElseThrow(() -> new IllegalArgumentException("Chunk not found: " + chunkId));

        // 2. 构建新对象
        Document updatedDocument = new Document(chunkId, newContent, metadata);

        // 3. 保存 (Upsert)
        documentRepository.save(List.of(updatedDocument));
    }

    /**
     * 删除文档
     */
    @Transactional
    public ApiResponse deleteSourceId(String sourceId) {
        documentRepository.deleteBySourceId(sourceId);
        return ApiResponse.success();
    }

    /**
     * 条件删除
     */
    @Transactional
    public int deleteOnCondition(Map<String, Object> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            log.warn("Empty delete conditions, ignoring.");
            return 0;
        }
        return documentRepository.deleteByMetadataConditions(conditions);
    }
}