package com.zwbd.agentnexus.datasource.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.datasource.dto.metadata.DatabaseMetadata;
import com.zwbd.agentnexus.datasource.dto.metadata.TableMetadata;
import com.zwbd.agentnexus.datasource.entity.DatabaseMetadataEntity;
import com.zwbd.agentnexus.datasource.repository.DatabaseMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * @Author: wnli
 * @Date: 2025/9/26 14:46
 * @Desc:
 */
@Service
public class DatabaseMetadataStorageService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMetadataStorageService.class);

    private final DatabaseMetadataRepository repository;
    private final ObjectMapper objectMapper;

    public DatabaseMetadataStorageService(DatabaseMetadataRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void save(String databaseInfoId, DatabaseMetadata metadata) {
        try {
            String jsonContent = objectMapper.writeValueAsString(metadata);
            DatabaseMetadataEntity entity = repository.findByDatabaseInfoId(databaseInfoId)
                    .orElse(new DatabaseMetadataEntity());
            entity.setDatabaseInfoId(databaseInfoId);
            entity.setMetadataContent(jsonContent);
            repository.save(entity);
            logger.info("Successfully saved DatabaseMetadata for ID: {}", databaseInfoId);
        } catch (Exception e) {
            logger.error("Failed to serialize DatabaseMetadata for ID: {}", databaseInfoId, e);
            throw new RuntimeException("Serialization failed for DatabaseMetadata", e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<DatabaseMetadata> findById(String databaseInfoId) {
        return repository.findByDatabaseInfoId(databaseInfoId)
                .flatMap(this::deserialize);
    }

    /**
     * 【新增】根據表名查找特定的表元數據。
     * 該方法在內存中執行過濾。
     */
    @Transactional(readOnly = true)
    public Optional<TableMetadata> findTable(String databaseInfoId, String schemaName, String tableName) {
        return findById(databaseInfoId)
                .flatMap(dbMeta -> dbMeta.catalogs().stream()
                        .filter(catalog -> catalog.schemaName().equalsIgnoreCase(schemaName))
                        .flatMap(catalog -> catalog.tables().stream())
                        .filter(table -> table.tableName().equalsIgnoreCase(tableName))
                        .findFirst()
                );
    }

    @Transactional
    public void deleteById(String databaseInfoId) {
        repository.deleteByDatabaseInfoId(databaseInfoId);
        logger.info("Deleted DatabaseMetadata for ID: {}", databaseInfoId);
    }

    private Optional<DatabaseMetadata> deserialize(DatabaseMetadataEntity entity) {
        try {
            return Optional.of(objectMapper.readValue(entity.getMetadataContent(), DatabaseMetadata.class));
        } catch (Exception e) {
            logger.error("Failed to deserialize DatabaseMetadata for ID: {}", entity.getDatabaseInfoId(), e);
            return Optional.empty();
        }
    }
}
