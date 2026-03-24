package com.zwbd.agentnexus.datasource.repository;

import com.zwbd.agentnexus.datasource.entity.DatabaseMetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * @Author: wnli
 * @Date: 2025/9/26 14:47
 * @Desc:
 */
@Repository
public interface DatabaseMetadataRepository extends JpaRepository<DatabaseMetadataEntity, UUID> {

    /**
     * 根據 database_info_id 查找唯一的元數據實體。
     * @param databaseInfoId 邏輯外鍵 ID
     * @return 包含實體的 Optional
     */
    Optional<DatabaseMetadataEntity> findByDatabaseInfoId(String databaseInfoId);

    /**
     * 根據 database_info_id 刪除記錄。
     * @param databaseInfoId 邏輯外鍵 ID
     */
    void deleteByDatabaseInfoId(String databaseInfoId);
}
