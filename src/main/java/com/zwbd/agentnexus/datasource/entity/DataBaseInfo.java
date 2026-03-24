package com.zwbd.agentnexus.datasource.entity;


import com.zwbd.agentnexus.common.converter.MapToJsonConverter;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.util.CollectionUtils;

import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Entity representing a data source configuration
 * Supports multiple data source types including databases and files
 */
@Entity
@Table(name = "database_info")
@Data
public class DataBaseInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "数据源名称不能为空")
    @Column(nullable = false, unique = true)
    private String name;

    @NotNull(message = "数据源类型不能为空")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataBaseType type;

    private String description;

    // --- 通用核心属性 ---
    private String host;
    private Integer port;
    @Column(name = "db_name") // 'database' may be a reserved keyword
    private String databaseName;
    private String username;
    private String password; // 实际生产中应加密存储

    /**
     * 存储额外的、非通用的连接属性
     * - 对于PostgreSQL: 可能包含 schema
     * - 对于文件: originalFileName, internalFileId 等
     */
    @Convert(converter = MapToJsonConverter.class)
    @Column(columnDefinition = "TEXT")
    private Map<String, String> extraProperties;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 根据数据库类型和配置属性生成 JDBC URL
     */
    public String getUrl() {
        if (type == null) {
            throw new IllegalStateException("Database type is missing");
        }

        StringBuilder url = new StringBuilder();

        switch (type) {
            case MYSQL:
                // 格式: jdbc:mysql://host:port/database?param1=value1&param2=value2
                url.append("jdbc:mysql://")
                        .append(host).append(":").append(port)
                        .append("/").append(databaseName);

                // MySQL 使用 ? 开头，& 分隔
                appendUrlParams(url, "?", "&");
                break;

            case POSTGRESQL:
                // 格式: jdbc:postgresql://host:port/database?param=value
                url.append("jdbc:postgresql://")
                        .append(host).append(":").append(port)
                        .append("/").append(databaseName);

                // PG 使用 ? 开头，& 分隔
                appendUrlParams(url, "?", "&");
                break;

            case ORACLE:
                // 格式 (Service Name): jdbc:oracle:thin:@//host:port/service_name
                // 注意：这里假设 databaseName 填的是 Service Name。如果是 SID 模式，通常用 @host:port:SID
                // 现代 Oracle 部署推荐使用 Service Name
                url.append("jdbc:oracle:thin:@//")
                        .append(host).append(":").append(port)
                        .append("/").append(databaseName);

                // Oracle 较少在 URL 后追加参数，但如果需要，通常也是 ?key=value
                // 这里暂不主动追加，除非 extraProperties 有特殊定义
                break;

            case SQLSERVER:
                // 格式: jdbc:sqlserver://host:port;databaseName=dbName;encrypt=true
                url.append("jdbc:sqlserver://")
                        .append(host).append(":").append(port)
                        .append(";databaseName=").append(databaseName);

                // SQL Server 使用 ; 分隔参数
                appendUrlParams(url, ";", ";");
                break;

            default:
                throw new UnsupportedOperationException("Unsupported database type: " + type);
        }

        return url.toString();
    }

    /**
     * 辅助方法：将 extraProperties 拼接到 URL 后面
     * @param url StringBuilder buffer
     * @param prefix 第一个参数的前缀 (MySQL/PG 为 "?", SQLServer 为 ";")
     * @param delimiter 后续参数的分隔符 (MySQL/PG 为 "&", SQLServer 为 ";")
     */
    private void appendUrlParams(StringBuilder url, String prefix, String delimiter) {
        if (CollectionUtils.isEmpty(extraProperties)) {
            return;
        }

        // 检查 URL 是否已经包含了 prefix (防止重复添加 '?')
        boolean isFirstParam = url.indexOf(prefix) == -1;

        for (Map.Entry<String, String> entry : extraProperties.entrySet()) {
            if (isFirstParam) {
                url.append(prefix);
                isFirstParam = false;
            } else {
                url.append(delimiter);
            }
            url.append(entry.getKey()).append("=").append(entry.getValue());
        }
    }

}