package com.zwbd.agentnexus.ai.web.controller;

import com.zwbd.agentnexus.ai.dto.document.DocumentChunkDTO;
import com.zwbd.agentnexus.ai.dto.document.DocumentInfoDTO;
import com.zwbd.agentnexus.ai.dto.document.UpdateChunkRequest;
import com.zwbd.agentnexus.ai.service.DocumentManagementService;
import com.zwbd.agentnexus.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/9/18 10:58
 * @Desc:
 */
@Tag(name = "Document Management API", description = "用于管理和预览知识库文档的接口")
@RestController
@RequestMapping("/api/documents")
public class DocumentManagementController {

    private final DocumentManagementService documentManagementService;

    public DocumentManagementController(DocumentManagementService documentManagementService) {
        this.documentManagementService = documentManagementService;
    }

    @Operation(summary = "搜索知识库")
    @PostMapping("/search")
    public ApiResponse<List<Document>> searchDocuments(@RequestBody SearchRequest request) {
        int k = request.topK() > 0 ? request.topK() : 5; // 默认值保护
        double threshold = request.threshold > 0 ? request.threshold : 0.3; // 默认值保护
        return ApiResponse.ok(documentManagementService.search(request.query(), k, threshold));
    }

    @Operation(summary = "获取所有文档列表")
    @GetMapping("/list")
    public ApiResponse<List<DocumentInfoDTO>> getAllDocuments() {
        return ApiResponse.ok(documentManagementService.listAllDocuments());
    }

    @Operation(summary = "预览文档分片")
    @GetMapping("/{sourceId}")
    public ApiResponse<List<DocumentChunkDTO>> getDocumentChunks(
            @Parameter(description = "要预览的文档的唯一ID", required = true)
            @PathVariable String sourceId) {
        List<DocumentChunkDTO> chunks = documentManagementService.getDocumentChunks(sourceId);
        if (chunks.isEmpty()) {
            return ApiResponse.ok(new ArrayList<>());
        }
        return ApiResponse.ok(chunks);
    }

    @Operation(summary = "更新文档分片内容")
    @PutMapping("/chunks/{chunkId}")
    public ResponseEntity<Void> updateChunk(
            @Parameter(description = "要更新的分片的唯一ID (UUID)", required = true)
            @PathVariable String chunkId,
            @Valid @RequestBody UpdateChunkRequest request) {
        documentManagementService.updateChunkContent(chunkId, request.content());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "删除文档", description = "根据指定的 documentId，删除该文档及其对应的所有分片。")
    @DeleteMapping("/{sourceId}")
    public ApiResponse deleteDocument(
            @Parameter(description = "要删除的文档的唯一ID", required = true)
            @PathVariable String sourceId) {
        documentManagementService.deleteSourceId(sourceId);
        return ApiResponse.success();
    }

    public record SearchRequest(
            @Schema(description = "搜索关键词")
            String query,
            @Schema(description = "返回结果数量")
            int topK,
            double threshold
    ) {
    }

}
