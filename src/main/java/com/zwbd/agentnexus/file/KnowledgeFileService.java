package com.zwbd.agentnexus.file;

import com.zwbd.agentnexus.ai.dto.document.metadata.DocumentType;
import com.zwbd.agentnexus.ai.dto.document.metadata.FileUploadMetadata;
import com.zwbd.agentnexus.ai.service.DocumentManagementService;
import com.zwbd.agentnexus.document.service.DomainDocumentService;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * @Author: wnli
 * @Date: 2025/11/13 15:08
 * @Desc:
 */
@Service
@AllArgsConstructor
public class KnowledgeFileService {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeFileService.class);

    private final KnowledgeFileRepository knowledgeFileRepository;
    private final FileStorageService fileStorageService;
    private final DocumentManagementService documentManagementService;

    private final DomainDocumentService domainDocumentService;

    /**
     * 1. （您的API 1）上传文件
     * 仅保存文件到磁盘，并在数据库中创建记录。
     *
     * @param file         上传的文件
     * @param sourceSystem 文档来源
     * @return 保存后的 KnowledgeFile 实体
     */
    @Transactional
    public KnowledgeFile uploadFile(MultipartFile file, String sourceSystem) {
        // 1. 存储到本地磁盘
        String storedFilename = fileStorageService.store(file);

        // 2. 创建数据库实体
        KnowledgeFile newFile = new KnowledgeFile();
        newFile.setOriginalFilename(file.getOriginalFilename());
        newFile.setStoredFilename(storedFilename);
        newFile.setFilePath(fileStorageService.getPath(storedFilename).toString());
        newFile.setSourceSystem(sourceSystem);
        newFile.setCreatedAt(Instant.now());

        // 3. 保存到数据库并返回
        return knowledgeFileRepository.save(newFile);
    }

    public void toDomainDocument(Long fileId) {
        KnowledgeFile file = knowledgeFileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("File not found with id: " + fileId));
        try {
            logger.info("toDomainDocument {} (ID: {})", file.getOriginalFilename(), fileId);
            // 只需要文件地址用于加载资源 ,但是为了保持一致仍然生成全部元数据
            FileUploadMetadata metaData = new FileUploadMetadata();
            metaData.setSourceId(String.valueOf(fileId));
            metaData.setSourceName(file.getOriginalFilename());
            // 根据文件类型确定 sourceType
            DocumentType sourceType = getSourceTypeFromFile(file.getOriginalFilename());
            metaData.setDocumentType(sourceType);
            metaData.setSourceSystem(file.getSourceSystem());
            metaData.setFilePath(file.getFilePath());
            // 转换为领域文档
            domainDocumentService.initDomainDocument(metaData);
        } catch (Exception e) {
            logger.error("toDomainDocument fail (ID: {})", fileId, e);
            throw new RuntimeException("toDomainDocument fail: " + e.getMessage(), e);
        }
    }

    /**
     * 预览文件向量化结果 (不保存到向量库，不更新状态)
     *
     * @param fileId 文件ID
     * @return Document 列表
     */
//    public List<Document> previewVectorization(Long fileId) {
//        KnowledgeFile file = knowledgeFileRepository.findById(fileId)
//                .orElseThrow(() -> new RuntimeException("File not found with id: " + fileId));
//        try {
//            logger.info("文件预览 {} (ID: {})", file.getOriginalFilename(), fileId);
//            // 只需要文件地址用于加载资源 ,但是为了保持一致仍然生成全部元数据
//            FileUploadMetadata metaData = new FileUploadMetadata();
//            metaData.setFileId(fileId);
//            metaData.setSourceSystem(file.getSourceSystem());
//            metaData.setFilePath(file.getFilePath());
//            metaData.setOriginalFilename(file.getOriginalFilename());
//            // 2. 根据文件类型确定 sourceType
//            DocumentType sourceType = getSourceTypeFromFile(file.getOriginalFilename());
//            metaData.setDocumentType(sourceType);
//            return documentManagementService.ingest(metaData, true);
//        } catch (Exception e) {
//            logger.error("预览文件失败 (ID: {})", fileId, e);
//            throw new RuntimeException("预览失败: " + e.getMessage(), e);
//        }
//    }

    /**
     * 2. （您的API 2）触发文件向量化
     *
     * @param fileId 要处理的文件ID
     */
//    @Transactional
//    public void triggerVectorization(Long fileId) {
//        KnowledgeFile file = knowledgeFileRepository.findById(fileId)
//                .orElseThrow(() -> new RuntimeException("File not found with id: " + fileId));
//
//        // 0. (可选) 清理旧的向量，以支持“重新处理”
//        if (file.getProcessingStatus() == ProcessingStatus.VECTORIZATION || file.getProcessingStatus() == ProcessingStatus.FAILED) {
//            try {
//                logger.info("正在清理文件 {} 的旧向量...", fileId);
////                documentManagementService.deleteDocument(String.valueOf(fileId));
//                documentManagementService.deleteOnCondition(Map.of("fileId", String.valueOf(fileId)));
//            } catch (Exception e) {
//                logger.warn("清理旧向量失败 (文件ID: {}), 将继续处理: {}", fileId, e.getMessage());
//            }
//        }
//
//        // 1. 更新状态为“处理中”
//        file.setProcessingStatus(ProcessingStatus.PROCESSING);
//        file.setErrorMessage(null);
//        knowledgeFileRepository.save(file);
//
//        try {
//            // 2. 调用向量化服务
//            logger.info("开始处理文件 {} (ID: {})", file.getOriginalFilename(), fileId);
//            // 设置元数据
//            FileUploadMetadata metaData = new FileUploadMetadata();
//            metaData.setFileId(fileId);
//            metaData.setSourceSystem(file.getSourceSystem());
//            metaData.setFilePath(file.getFilePath());
//            metaData.setOriginalFilename(file.getOriginalFilename());
//
//            // 2. 根据文件类型确定 sourceType
//            DocumentType sourceType = getSourceTypeFromFile(file.getOriginalFilename());
//            metaData.setDocumentType(sourceType);
//
//            documentManagementService.ingest(metaData, false); // 核心操作
//
//            // 3. 成功：更新状态
//            file.setProcessingStatus(ProcessingStatus.VECTORIZATION);
//            knowledgeFileRepository.save(file);
//            logger.info("文件 {} (ID: {}) 处理成功", file.getOriginalFilename(), fileId);
//
//        } catch (Exception e) {
//            // 4. 失败：记录错误并更新状态
//            logger.error("文件 {} (ID: {}) 处理失败", file.getOriginalFilename(), fileId, e);
//            file.setProcessingStatus(ProcessingStatus.FAILED);
//            file.setErrorMessage(e.getMessage());
//            knowledgeFileRepository.save(file);
//        }
//    }

    /**
     * 仅删除向量数据，保留文件实体和物理文件。
     * 状态将被重置为 UPLOADED。
     *
     * @param fileId 文件ID
     */
//    @Transactional
//    public void removeVectorsOnly(Long fileId) {
//        KnowledgeFile file = knowledgeFileRepository.findById(fileId)
//                .orElseThrow(() -> new RuntimeException("File not found with id: " + fileId));
//
//        try {
//            logger.info("正在从向量库中移除文件 {} (ID: {}) 的数据...", file.getOriginalFilename(), fileId);
//            documentManagementService.deleteOnCondition(Map.of("fileId", String.valueOf(fileId)));
//        } catch (Exception e) {
//            logger.error("移除向量数据失败 (文件ID: {})", fileId, e);
//            throw new RuntimeException("无法移除向量数据: " + e.getMessage(), e);
//        }
//
//        // 重置状态为 UPLOADED (就像刚上传一样)
//        file.setProcessingStatus(ProcessingStatus.UN_VECTORIZATION);
//        file.setErrorMessage(null);
//        knowledgeFileRepository.save(file);
//
//        logger.info("文件 {} (ID: {}) 向量已清除，状态已重置为 UPLOADED", file.getOriginalFilename(), fileId);
//    }

    /**
     * 根据文件名后缀判断数据源类型。
     */
    private DocumentType getSourceTypeFromFile(String filename) {
        if (filename == null || filename.lastIndexOf('.') == -1) {
            return DocumentType.UNKNOWN;
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1);
        return DocumentType.valueOf(extension.toUpperCase());
    }

    /**
     * 3. 删除文件（从所有地方）
     *
     * @param fileId 要删除的文件ID
     */
    @Transactional
    public void deleteFile(Long fileId) {
        KnowledgeFile file = knowledgeFileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("File not found with id: " + fileId));

        // 1. 从向量库删除
        try {
            documentManagementService.deleteSourceId(String.valueOf(fileId));
        } catch (Exception e) {
            // 记录错误但继续执行，确保文件总能被删除
            logger.warn("从向量库删除文件 {} (ID: {}) 失败，但将继续删除文件: {}",
                    file.getOriginalFilename(), fileId, e.getMessage());
        }

        // 2. 从本地磁盘删除
        try {
            fileStorageService.delete(file.getStoredFilename());
        } catch (Exception e) {
            logger.warn("从本地删除文件 {} (ID: {}) 失败，但将继续删除数据库记录: {}",
                    file.getOriginalFilename(), fileId, e.getMessage());
        }

        // 3. 从数据库删除元数据
        knowledgeFileRepository.delete(file);
        logger.info("文件 {} (ID: {}) 已被完全删除", file.getOriginalFilename(), fileId);
    }


    public Optional<KnowledgeFile> getFileById(Long fileId) {
        return knowledgeFileRepository.findById(fileId);
    }

    public List<KnowledgeFile> getAllFiles() {
        return knowledgeFileRepository.findAll();
    }
}