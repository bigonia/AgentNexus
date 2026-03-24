package com.zwbd.agentnexus.datasource.repository;

import com.zwbd.agentnexus.datasource.entity.DataBaseInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository interface for DataBaseInfo entity
 * Provides CRUD operations and custom query methods
 * 
 * @Author: wnli
 * @Date: 2025/9/18 17:21
 * @Desc:
 */
@Repository
public interface DataBaseInfoRepository extends JpaRepository<DataBaseInfo, Long> {
    
    /**
     * Find database info by name
     * 
     * @param name the database info name
     * @return optional database info
     */
    Optional<DataBaseInfo> findByName(String name);
    
    /**
     * Check if database info exists by name
     * 
     * @param name the database info name
     * @return true if exists
     */
    boolean existsByName(String name);
}
