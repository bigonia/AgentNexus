package com.zwbd.agentnexus.datasource.controller;

import com.zwbd.agentnexus.ai.dto.document.metadata.DatabaseRecordMetadata;
import com.zwbd.agentnexus.ai.dto.document.metadata.DocumentType;
import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.datasource.dto.database.DataBaseInfoDTO;
import com.zwbd.agentnexus.datasource.service.DataBaseInfoService;
import com.zwbd.agentnexus.datasource.service.MetadataCollectorService;
import com.zwbd.agentnexus.document.entity.DocMode;
import com.zwbd.agentnexus.document.entity.DomainDocument;
import com.zwbd.agentnexus.document.service.DomainDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for managing database information
 * Provides CRUD operations and connection testing endpoints
 */
@RestController
@RequestMapping("/api/database-info")
@Tag(name = "Database Info", description = "Database information management APIs")
public class DataBaseInfoController {

    @Autowired
    private DataBaseInfoService dataBaseInfoService;

    @Autowired
    private DomainDocumentService domainDocumentService;

    @Autowired
    private MetadataCollectorService metadataCollectorService;

    /**
     * Create a new database info record
     */
    @PostMapping
    @Operation(summary = "Create database info", description = "Create a new database information record")
    public ApiResponse<DataBaseInfoDTO> create(@Valid @RequestBody DataBaseInfoDTO dto) {
        DataBaseInfoDTO created = dataBaseInfoService.create(dto);
        return ApiResponse.success(created);
    }

    /**
     * Update an existing database info record
     */
    @PutMapping("/{id}")
    @Operation(summary = "Update database info", description = "Update an existing database information record")
    public ApiResponse<DataBaseInfoDTO> update(
            @Parameter(description = "Database info ID") @PathVariable Long id,
            @Valid @RequestBody DataBaseInfoDTO dto) {
        DataBaseInfoDTO updated = dataBaseInfoService.update(id, dto);
        return ApiResponse.ok(updated);
    }

    /**
     * Get database info by ID
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get database info by ID", description = "Retrieve database information by ID")
    public ApiResponse<DataBaseInfoDTO> getById(
            @Parameter(description = "Database info ID") @PathVariable Long id) {
        return dataBaseInfoService.findById(id)
                .map(ApiResponse::ok)
                .orElse(ApiResponse.success());
    }

    /**
     * Get database info by name
     */
    @GetMapping("/name/{name}")
    @Operation(summary = "Get database info by name", description = "Retrieve database information by name")
    public ApiResponse<DataBaseInfoDTO> getByName(
            @Parameter(description = "Database info name") @PathVariable String name) {
        return dataBaseInfoService.findByName(name)
                .map(ApiResponse::ok)
                .orElse(ApiResponse.success());
    }

    /**
     * Get all database info records
     */
    @GetMapping
    @Operation(summary = "Get all database info", description = "Retrieve all database information records")
    public ApiResponse<List<DataBaseInfoDTO>> getAll() {
        List<DataBaseInfoDTO> dtos = dataBaseInfoService.findAll();
        return ApiResponse.ok(dtos);
    }

    /**
     * Get database info records with pagination
     */
    @GetMapping("/page")
    @Operation(summary = "Get database info with pagination", description = "Retrieve database information records with pagination")
    public ApiResponse<Page<DataBaseInfoDTO>> getAllPaged(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<DataBaseInfoDTO> page = dataBaseInfoService.findAll(pageable);
        return ApiResponse.ok(page);
    }

    /**
     * Delete database info by ID
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete database info", description = "Delete database information record by ID")
    public ApiResponse delete(
            @Parameter(description = "Database info ID") @PathVariable Long id) {
        dataBaseInfoService.deleteById(id);
        return ApiResponse.success();
    }

    /**
     * Test database connection by ID
     */
    @PostMapping("/{id}/test-connection")
    @Operation(summary = "Test database connection", description = "Test database connection by ID")
    public ApiResponse testConnection(
            @Parameter(description = "Database info ID") @PathVariable Long id) {
        try {
            boolean isConnected = dataBaseInfoService.testConnection(id);
            if (isConnected) {
                return ApiResponse.success();
            } else {
                return ApiResponse.error(50000, "connect fail");
            }

        } catch (Exception e) {
            return ApiResponse.error(50000, e.getMessage());
        }
    }

    /**
     * Test database connection using DTO (without saving)
     */
    @PostMapping("/test-connection")
    @Operation(summary = "Test database connection", description = "Test database connection using provided configuration")
    public ApiResponse testConnection(@Valid @RequestBody DataBaseInfoDTO dto) {
        try {
            boolean isConnected = dataBaseInfoService.testConnection(dto);
            if (isConnected) {
                return ApiResponse.success();
            } else {
                return ApiResponse.error(50000, "connect fail");
            }
        } catch (Exception e) {
            return ApiResponse.error(50000, e.getMessage());
        }
    }

    @GetMapping("/schemas/{id}")
    public ApiResponse<List<String>> getSchemas(@PathVariable Long id) {
        DataBaseInfoDTO dataBaseInfoDTO = dataBaseInfoService.findById(id).get();
        List<String> schemas = metadataCollectorService.getSchemas(dataBaseInfoDTO.toEntityWithId());
        return ApiResponse.ok(schemas);
    }

    @GetMapping("/{id}/{schema}")
    public ApiResponse<List<String>> getTables(@PathVariable Long id, @PathVariable String schema) {
        DataBaseInfoDTO dataBaseInfoDTO = dataBaseInfoService.findById(id).get();
        List<String> schemas = metadataCollectorService.getTables(dataBaseInfoDTO.toEntityWithId(), schema);
        return ApiResponse.ok(schemas);
    }

    @GetMapping("/{id}/{schema}/{table}")
    public ApiResponse<List<String>> getColumns(@PathVariable Long id, @PathVariable String schema, @PathVariable String table) {
        DataBaseInfoDTO dataBaseInfoDTO = dataBaseInfoService.findById(id).get();
        List<String> schemas = metadataCollectorService.getColumns(dataBaseInfoDTO.toEntityWithId(), schema, table);
        return ApiResponse.ok(schemas);
    }

    @PostMapping("/{id}/{schema}/{table}/stream")
    @Operation(summary = "stream tables")
    public ApiResponse<List<String>> streamTable(@PathVariable Long id, @PathVariable String schema, @PathVariable String table, @RequestBody String template) {
        DatabaseRecordMetadata metadata = new DatabaseRecordMetadata();
        metadata.setSourceId(String.valueOf(id));
        metadata.setSourceSystem("DB");
        metadata.setSourceName(schema + "-" + table);
        metadata.setDocumentType(DocumentType.DATABASE_STREAM);
        metadata.setDocMode(DocMode.VIRTUAL);
        metadata.setSchema(schema);
        metadata.setTable(table);
        metadata.setTemplate(template);
        domainDocumentService.initDomainDocument(metadata);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/metadata")
    @Operation(summary = "generate metadata")
    public ApiResponse<DomainDocument> toMetaData(@Parameter(description = "Database info ID") @PathVariable String id) {
        DatabaseRecordMetadata metadata = new DatabaseRecordMetadata();
        metadata.setDocumentType(DocumentType.DATABASE);
        metadata.setSourceId(id);
        metadata.setSourceSystem("DB");
        metadata.setSourceName("testName");
        DomainDocument domainDocument = domainDocumentService.initDomainDocument(metadata);
        return ApiResponse.ok(domainDocument);
    }


    /**
     * Check if database info exists by ID
     */
    @GetMapping("/{id}/exists")
    @Operation(summary = "Check if database info exists", description = "Check if database information exists by ID")
    public ResponseEntity<Map<String, Boolean>> existsById(
            @Parameter(description = "Database info ID") @PathVariable Long id) {
        boolean exists = dataBaseInfoService.existsById(id);
        return ResponseEntity.ok(Map.of("exists", exists));
    }

    /**
     * Check if database info exists by name
     */
    @GetMapping("/name/{name}/exists")
    @Operation(summary = "Check if database info exists by name", description = "Check if database information exists by name")
    public ResponseEntity<Map<String, Boolean>> existsByName(
            @Parameter(description = "Database info name") @PathVariable String name) {
        boolean exists = dataBaseInfoService.existsByName(name);
        return ResponseEntity.ok(Map.of("exists", exists));
    }
}