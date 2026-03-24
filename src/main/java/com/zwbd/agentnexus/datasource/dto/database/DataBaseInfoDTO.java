package com.zwbd.agentnexus.datasource.dto.database;

import com.zwbd.agentnexus.datasource.entity.DataBaseInfo;
import com.zwbd.agentnexus.datasource.entity.DataBaseType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/9/18 17:38
 * @Desc:
 */
public record DataBaseInfoDTO(

        Long id,

        @NotBlank(message = "数据源名称不能为空")
        String name,

        @NotNull(message = "数据源类型不能为空")
        DataBaseType type,

        String description,

        // --- 通用核心属性 ---
        String host,
        @Positive(message = "端口号必须为正数")
        Integer port,
        String databaseName,
        String username,
        String password,

        // --- 额外属性 ---
        Map<String, String> extraProperties,

        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public DataBaseInfo toEntity() {
        DataBaseInfo entity = new DataBaseInfo();
//        entity.setId(this.id);
        entity.setName(this.name);
        entity.setType(this.type);
        entity.setDescription(this.description);
        entity.setHost(this.host);
        entity.setPort(this.port);
        entity.setDatabaseName(this.databaseName);
        entity.setUsername(this.username);
        entity.setPassword(this.password);
        entity.setExtraProperties(this.extraProperties);
        return entity;
    }

    public DataBaseInfo toEntityWithId() {
        DataBaseInfo entity = new DataBaseInfo();
        entity.setId(this.id);
        entity.setName(this.name);
        entity.setType(this.type);
        entity.setDescription(this.description);
        entity.setHost(this.host);
        entity.setPort(this.port);
        entity.setDatabaseName(this.databaseName);
        entity.setUsername(this.username);
        entity.setPassword(this.password);
        entity.setExtraProperties(this.extraProperties);
        return entity;
    }


    public static DataBaseInfoDTO fromEntity(DataBaseInfo entity) {
        return new DataBaseInfoDTO(
                entity.getId(),
                entity.getName(),
                entity.getType(),
                entity.getDescription(),
                entity.getHost(),
                entity.getPort(),
                entity.getDatabaseName(),
                entity.getUsername(),
                entity.getPassword(),
                entity.getExtraProperties(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}

