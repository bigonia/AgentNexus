package com.zwbd.agentnexus.datasource.service;

import com.zwbd.agentnexus.datasource.dialect.DatabaseDialect;
import com.zwbd.agentnexus.datasource.dialect.DatabaseSession;
import com.zwbd.agentnexus.datasource.dialect.DialectFactory;
import com.zwbd.agentnexus.datasource.dto.database.DataBaseInfoDTO;
import com.zwbd.agentnexus.datasource.entity.DataBaseInfo;
import com.zwbd.agentnexus.common.exception.CommonException;
import com.zwbd.agentnexus.datasource.repository.DataBaseInfoRepository;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for managing database information
 * Provides CRUD operations and connection testing functionality
 * 
 * @Author: wnli
 * @Date: 2025/9/18 17:44
 * @Desc:
 */
@Service
@AllArgsConstructor
public class DataBaseInfoService {

    private final DataBaseInfoRepository repository;
    private final DialectFactory dialectFactory;

    /**
     * Create a new database info record
     * 
     * @param dto the database info DTO
     * @return the created database info DTO
     */
    @Transactional
    public DataBaseInfoDTO create(DataBaseInfoDTO dto) {
        // Validate unique name
        if (repository.existsByName(dto.name())) {
            throw new CommonException("Database info with name '" + dto.name() + "' already exists");
        }
        
        DataBaseInfo entity = dto.toEntity();
        DataBaseInfo saved = repository.save(entity);
        return DataBaseInfoDTO.fromEntity(saved);
    }

    /**
     * Update an existing database info record
     * 
     * @param id the database info ID
     * @param dto the updated database info DTO
     * @return the updated database info DTO
     */
    @Transactional
    public DataBaseInfoDTO update(Long id, DataBaseInfoDTO dto) {
        DataBaseInfo existing = repository.findById(id)
                .orElseThrow(() -> new CommonException("Database info not found with id: " + id));
        
        // Check name uniqueness if name is being changed
        if (!existing.getName().equals(dto.name()) && repository.existsByName(dto.name())) {
            throw new CommonException("Database info with name '" + dto.name() + "' already exists");
        }
        
        // Update fields
        existing.setName(dto.name());
        existing.setType(dto.type());
        existing.setDescription(dto.description());
        existing.setHost(dto.host());
        existing.setPort(dto.port());
        existing.setDatabaseName(dto.databaseName());
        existing.setUsername(dto.username());
        existing.setPassword(dto.password());
        existing.setExtraProperties(dto.extraProperties());
        
        DataBaseInfo saved = repository.save(existing);
        return DataBaseInfoDTO.fromEntity(saved);
    }

    /**
     * Find database info by ID
     * 
     * @param id the database info ID
     * @return the database info DTO if found
     */
    @Transactional(readOnly = true)
    public Optional<DataBaseInfoDTO> findById(Long id) {
        return repository.findById(id)
                .map(DataBaseInfoDTO::fromEntity);
    }

    /**
     * Find database info by name
     * 
     * @param name the database info name
     * @return the database info DTO if found
     */
    @Transactional(readOnly = true)
    public Optional<DataBaseInfoDTO> findByName(String name) {
        return repository.findByName(name)
                .map(DataBaseInfoDTO::fromEntity);
    }

    /**
     * Get all database info records
     * 
     * @return list of all database info DTOs
     */
    @Transactional(readOnly = true)
    public List<DataBaseInfoDTO> findAll() {
        return repository.findAll().stream()
                .map(DataBaseInfoDTO::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Get database info records with pagination
     * 
     * @param pageable pagination information
     * @return page of database info DTOs
     */
    @Transactional(readOnly = true)
    public Page<DataBaseInfoDTO> findAll(Pageable pageable) {
        return repository.findAll(pageable)
                .map(DataBaseInfoDTO::fromEntity);
    }

    /**
     * Delete database info by ID
     * 
     * @param id the database info ID
     */
    @Transactional
    public void deleteById(Long id) {
        if (!repository.existsById(id)) {
            throw new CommonException("Database info not found with id: " + id);
        }
        repository.deleteById(id);
    }

    /**
     * Test database connection
     * 
     * @param id the database info ID
     * @return true if connection is successful
     */
    public boolean testConnection(Long id) {
        DataBaseInfo dataBaseInfo = repository.findById(id)
                .orElseThrow(() -> new CommonException("Database info not found with id: " + id));
        
        return testConnection(dataBaseInfo);
    }

    /**
     * Test database connection using DTO
     * 
     * @param dto the database info DTO
     * @return true if connection is successful
     */
    public boolean testConnection(DataBaseInfoDTO dto) {
        DataBaseInfo dataBaseInfo = dto.toEntity();
        return testConnection(dataBaseInfo);
    }

    /**
     * Test database connection using entity
     * 
     * @param dataBaseInfo the database info entity
     * @return true if connection is successful
     */
    private boolean testConnection(DataBaseInfo dataBaseInfo) {
        DatabaseSession databaseSession = dialectFactory.openSession(dataBaseInfo);
        return databaseSession.execute(DatabaseDialect::testConnection);
    }

    /**
     * Check if database info exists by ID
     * 
     * @param id the database info ID
     * @return true if exists
     */
    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return repository.existsById(id);
    }

    /**
     * Check if database info exists by name
     * 
     * @param name the database info name
     * @return true if exists
     */
    @Transactional(readOnly = true)
    public boolean existsByName(String name) {
        return repository.existsByName(name);
    }

}
