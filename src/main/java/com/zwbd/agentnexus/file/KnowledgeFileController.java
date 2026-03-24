package com.zwbd.agentnexus.file;

import com.zwbd.agentnexus.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 知识库文件管理的 REST API Controller
 */
@Slf4j
@Tag(name = "File Manager API", description = "文件管理")
@AllArgsConstructor
@RestController
@RequestMapping("/api/files")
public class KnowledgeFileController {

    private final KnowledgeFileService knowledgeFileService;
    private final FileStorageService fileStorageService;

    /**
     * 1. 上传文件（仅保存文件和元数据）
     *
     * @param file         上传的文件
     * @param sourceSystem 文档来源
     * @return 包含新创建文件元数据（包括ID）的 ResponseEntity
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "upload file")
    public ApiResponse<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sourceSystem", required = false) String sourceSystem) {

        if (file.isEmpty()) {
            return ApiResponse.error(50000, "上传的文件不能为空");
        }

        try {
            KnowledgeFile savedFile = knowledgeFileService.uploadFile(file, sourceSystem);
            return ApiResponse.success(savedFile);
        } catch (Exception e) {
            log.error("文件上传失败: {}", file.getOriginalFilename(), e);
            return ApiResponse.error(50000, "文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 生成领域文档
     *
     * @param fileId 文件的数据库ID
     * @return 状态响应
     */
    @PostMapping("/{id}/toDoc")
    @Operation(summary = "file 2 doc")
    public ApiResponse<String> file2Doc(@PathVariable("id") Long fileId) {
        try {
            // 正式模式：解析、存储、更新状态
            knowledgeFileService.toDomainDocument(fileId);
            return ApiResponse.ok("文件 (ID: " + fileId + ") 转换完成。");
        } catch (Exception e) {
            // 捕获其他意外错误
            log.error("处理文件 (ID: {}) 时发生意外错误", fileId, e);
            return ApiResponse.error("处理失败: " + e.getMessage());
        }
    }

//    @PostMapping("/{id}/preview")
//    @Operation(summary = "preview file Document")
//    public ApiResponse<List<Document>> previewDoc(@PathVariable("id") Long fileId) {
//        try {
//            List<Document> documents = knowledgeFileService.previewVectorization(fileId);
//            return ApiResponse.success(documents);
//        } catch (Exception e) {
//            // 捕获其他意外错误
//            log.error("处理文件 (ID: {}) 时发生意外错误", fileId, e);
//            return ApiResponse.error(50000, e.getMessage());
//        }
//    }
//
//    /**
//     * 2. 触发指定文件的向量化处理
//     *
//     * @param fileId 文件的数据库ID
//     * @return 状态响应
//     */
//    @PostMapping("/{id}/vectorize")
//    @Operation(summary = "vectorize file")
//    public ResponseEntity<String> vectorizeFile(@PathVariable("id") Long fileId) {
//        try {
//            // 正式模式：解析、存储、更新状态
//            knowledgeFileService.triggerVectorization(fileId);
//            return ResponseEntity.ok("文件 (ID: " + fileId + ") 向量化处理已触发并完成。");
//        } catch (RuntimeException e) {
//            log.warn("触发向量化失败 (ID: {}): {}", fileId, e.getMessage());
//            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
//        } catch (Exception e) {
//            // 捕获其他意外错误
//            log.error("处理文件 (ID: {}) 时发生意外错误", fileId, e);
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body("处理失败: " + e.getMessage());
//        }
//    }


    /**
     * 3. 删除一个文件（从向量库、磁盘和数据库中）
     *
     * @param fileId 文件的数据库ID
     * @return 状态响应
     */
    @DeleteMapping("/{id}")
    public ApiResponse<String> deleteFile(@PathVariable("id") Long fileId) {
        try {
            knowledgeFileService.deleteFile(fileId);
            return ApiResponse.ok("文件 (ID: " + fileId + ") 已被成功删除。");
        } catch (RuntimeException e) {
            // 捕获 "File not found"
            log.warn("删除文件失败 (ID: {}): {}", fileId, e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 4. 获取所有文件的元数据列表
     *
     * @return KnowledgeFile 的列表
     */
    @GetMapping
    public ApiResponse<List<KnowledgeFile>> getAllFiles() {
        List<KnowledgeFile> files = knowledgeFileService.getAllFiles();
        return ApiResponse.success(files);
    }

    /**
     * 5. 获取单个文件的元数据
     *
     * @param fileId 文件的数据库ID
     * @return KnowledgeFile 实体或 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeFile> getFileById(@PathVariable("id") Long fileId) {
        return knowledgeFileService.getFileById(fileId)
                .map(ResponseEntity::ok) // 如果找到，返回 200 OK 和文件
                .orElse(ResponseEntity.notFound().build()); // 如果没找到，返回 404 Not Found
    }

    /**
     * 6. 获取文件内容流（用于前端浏览器预览或下载）
     *
     * @param fileId  文件ID
     * @param request HttpServletRequest 用于探测MIME类型
     * @return 文件流 Resource
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> getFileContent(@PathVariable("id") Long fileId, HttpServletRequest request) {
        // 1. 查询文件元数据
        KnowledgeFile knowledgeFile = knowledgeFileService.getFileById(fileId)
                .orElseThrow(() -> new RuntimeException("File not found with id " + fileId));

        // 2. 加载文件资源
        Resource resource = fileStorageService.loadFileAsResource(knowledgeFile.getStoredFilename());

        // 3. 探测文件类型
        String contentType = null;
        try {
            // 方法 A: 使用 Java NIO (更准确)
            contentType = Files.probeContentType(resource.getFile().toPath());
        } catch (IOException ex) {
            log.info("Could not determine file type.");
        }

        // 方法 B: 如果 NIO 也没探测出来，手动兜底常见类型 (尤其是 PDF 和 图片)
        if (contentType == null) {
            String filenameLower = knowledgeFile.getOriginalFilename().toLowerCase();
            if (filenameLower.endsWith(".pdf")) {
                contentType = "application/pdf";
            } else if (filenameLower.endsWith(".jpg") || filenameLower.endsWith(".jpeg")) {
                contentType = "image/jpeg";
            } else if (filenameLower.endsWith(".png")) {
                contentType = "image/png";
            } else if (filenameLower.endsWith(".txt")) {
                contentType = "text/plain";
            } else {
                contentType = "application/octet-stream";
            }
        }

        // 4. 【修复】处理文件名编码，防止中文乱码或丢失
        String originalFilename = knowledgeFile.getOriginalFilename();
        // 使用 UTF-8 编码，并将 '+' 替换为 '%20' (空格)
        String encodedFilename = URLEncoder.encode(originalFilename, StandardCharsets.UTF_8).replace("+", "%20");

        // 同时设置 filename 和 filename* 以兼容不同浏览器
        // inline 表示尝试在浏览器内打开（如PDF），如果无法打开则下载
        String contentDisposition = "inline; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename;

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
//                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(resource);
    }

    /**
     * 7. 仅删除文件的向量数据（保留文件和元数据）
     * 状态重置为 UPLOADED
     */
//    @DeleteMapping("/{id}/vectors")
//    public ResponseEntity<String> deleteFileVectors(@PathVariable("id") Long fileId) {
//        try {
//            knowledgeFileService.removeVectorsOnly(fileId);
//            return ResponseEntity.ok("文件 (ID: " + fileId + ") 的向量数据已清除，状态重置为 UPLOADED。");
//        } catch (RuntimeException e) {
//            // 可能是找不到文件，或者删除过程中出错
//            log.warn("清除向量数据失败 (ID: {}): {}", fileId, e.getMessage());
//            if (e.getMessage().contains("File not found")) {
//                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
//            }
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
//        }
//    }

}
