package com.zwbd.agentnexus.datasource.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;


/**
 * @Author: wnli
 * @Date: 2025/9/26 14:47
 * @Desc:
 */
@Data
@Entity
@Table(name = "database_metadata_storage")
public class DatabaseMetadataEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "database_info_id", unique = true, nullable = false)
    private String databaseInfoId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_content", columnDefinition = "jsonb")
    private String metadataContent; // 存儲為 JSON 字符串
}
