package com.zwbd.agentnexus.datasource.controller;

import com.zwbd.agentnexus.document.etl.loader.impl.DatabaseMetadataDocumentLoader;
import com.zwbd.agentnexus.ai.dto.document.metadata.DatabaseRecordMetadata;
import com.zwbd.agentnexus.ai.dto.document.metadata.DocumentType;
import com.zwbd.agentnexus.ai.service.DocumentManagementService;
import com.zwbd.agentnexus.datasource.dto.database.DataBaseInfoDTO;
import com.zwbd.agentnexus.datasource.dto.metadata.DatabaseMetadata;
import com.zwbd.agentnexus.datasource.service.DataBaseInfoService;
import com.zwbd.agentnexus.datasource.service.DatabaseMetadataStorageService;
import com.zwbd.agentnexus.datasource.service.MetadataCollectorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * @Author: wnli
 * @Date: 2025/9/19 11:38
 * @Desc:
 */
@Slf4j
@RestController
@RequestMapping("/api/metadata")
@AllArgsConstructor
@Tag(name = "Database metadata", description = "Database metadata management APIs")
public class MetadataController {

    private final MetadataCollectorService metadataCollectorService;

    private final DataBaseInfoService dataBaseInfoService;

    private final DocumentManagementService documentIngestService;

    private final DatabaseMetadataStorageService databaseMetadataStorageService;

    @PostMapping("/check/{id}")
    @Operation(summary = "check database metadata", description = "check database metadata")
    public ResponseEntity<DatabaseMetadata> check(@Parameter(description = "Database info ID") @PathVariable Long id) {
        Optional<DataBaseInfoDTO> dto = dataBaseInfoService.findById(id);
        CompletableFuture<DatabaseMetadata> future = metadataCollectorService.collectMetadata(dto.get().toEntity());
        DatabaseMetadata databaseMetadata = null;
        try {
            databaseMetadata = future.get();
        } catch (Exception e) {
            log.error("Error creating database metadata", e);
        }
        log.info("============================================================");
        log.info("Check database metadata: {}", databaseMetadata.databaseProductName());
        return ResponseEntity.ok(databaseMetadata);
    }

    @PostMapping("/save/{id}")
    @Operation(summary = "save database metadata", description = "check database metadata")
    public ResponseEntity<DatabaseMetadata> save(@Parameter(description = "Database info ID") @PathVariable Long id) {
        Optional<DataBaseInfoDTO> dto = dataBaseInfoService.findById(id);
        CompletableFuture<DatabaseMetadata> future = metadataCollectorService.collectMetadata(dto.get().toEntity());
        DatabaseMetadata databaseMetadata = null;
        try {
            databaseMetadata = future.get();
        } catch (Exception e) {
            log.error("Error creating database metadata", e);
        }
        log.info("============================================================");
        log.info("Check database metadata: {}", databaseMetadata.databaseProductName());
        databaseMetadataStorageService.save(String.valueOf(id), databaseMetadata);
        return ResponseEntity.ok(databaseMetadata);
    }


    @PostMapping("/analyse/{id}")
    @Operation(summary = "analyse database metadata to RAG sys", description = "analyse database")
    public void analyse(@Parameter(description = "Database info ID") @PathVariable String id) {
        DatabaseRecordMetadata metadata = new DatabaseRecordMetadata();
        metadata.setDocumentType(DocumentType.DATABASE);
        metadata.setSourceId(id);
        documentIngestService.ingest(metadata, false);
    }

    @Autowired
    private DatabaseMetadataDocumentLoader loader;

    @PostMapping("/preview/{id}")
    @Operation(summary = "analyse database metadata to RAG sys", description = "analyse database")
    public ResponseEntity<List<Document>> preview(@Parameter(description = "Database info ID") @PathVariable String id) {
        DatabaseRecordMetadata metadata = new DatabaseRecordMetadata();
        metadata.setDocumentType(DocumentType.DATABASE);
        metadata.setSourceId(id);
        metadata.setSourceSystem("db");
        List<Document> documents = loader.load(metadata);
        return ResponseEntity.ok(documents);
    }

}
