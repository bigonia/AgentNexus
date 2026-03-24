package com.zwbd.agentnexus.datasource.dialect;

import com.zaxxer.hikari.HikariDataSource;
import com.zwbd.agentnexus.common.config.TimeoutConfig;
import com.zwbd.agentnexus.common.exception.CommonException;
import com.zwbd.agentnexus.datasource.dto.metadata.ColumnMetadata;
import com.zwbd.agentnexus.datasource.dto.metadata.SchemaMetadata;
import com.zwbd.agentnexus.datasource.dto.metadata.TableMetadata;
import com.zwbd.agentnexus.datasource.entity.DataBaseInfo;
import com.zwbd.agentnexus.datasource.entity.DataBaseType;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * @Author: wnli
 * @Date: 2025/12/25
 * @Desc: 数据库方言基类 (V2 重构版)
 * 核心变更：
 * 1. 废弃逐表统计，改为基于系统视图 (System Metadata) 的批量获取。
 * 2. 引入批量列获取，解决 N+1 查询问题。
 * 3. 增强采样接口，支持数据库原生的高效采样语法。
 */
public abstract class DatabaseDialect {

    @Autowired
    protected TimeoutConfig timeoutConfig;

    /**
     * 默认采样行数限制
     */
    protected static final int DEFAULT_SAMPLE_LIMIT = 100;

    /**
     * 返回数据库类型枚举
     */
    public abstract DataBaseType getDataBaseType();

    /**
     * 驱动类名
     */
    protected abstract String getDriverClassName();

    /**
     * 构建连接 URL
     */
    protected abstract String buildConnectionUrl(DataBaseInfo dataBaseInfo);

    /**
     * 【通用实现】测试连接是否有效。
     * 基于 JDBC 4.0 标准的 isValid 方法。
     * 大多数现代 JDBC 驱动（MySQL, PG, SQL Server）都已良好支持此方法。
     *
     * @param connection 数据库连接
     * @return true 如果连接有效且可用于查询
     */
    public boolean testConnection(Connection connection) {
        try {
            // 设置 3 秒超时，快速检测
            return connection != null && connection.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 创建数据源 (HikariCP)
     * 保持原有逻辑，确保连接池配置合理
     */
    public DataSource createDataSource(DataBaseInfo dataBaseInfo) {
        try {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setDriverClassName(getDriverClassName());
            dataSource.setJdbcUrl(buildConnectionUrl(dataBaseInfo));
            dataSource.setUsername(dataBaseInfo.getUsername());
            dataSource.setPassword(dataBaseInfo.getPassword());
            // 针对元数据采集场景的优化配置
            dataSource.setReadOnly(true);
            dataSource.setMinimumIdle(1);
            dataSource.setMaximumPoolSize(5);
            dataSource.setConnectionTimeout(10000); // 10秒连接超时
            return dataSource;
        } catch (Exception e) {
            throw new CommonException("Failed to create data source: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // Core Metadata Methods (System Catalog Driven)
    // =========================================================================

    /**
     * 获取数据库所有的 Schema (或 Catalog)。
     */
    public abstract List<SchemaMetadata> getSchemas(Connection connection) throws SQLException;

    /**
     * 【核心重构】批量获取指定 Schema 下的所有表及其系统统计信息。
     * 要求：
     * 1. 必须直接查询系统视图 (如 information_schema.TABLES, pg_class)。
     * 2. 获取 rowCount (估算值) 和 dataLength (存储大小)。
     * 3. 严禁对表执行 count(*) 操作。
     *
     * @param connection 数据库连接
     * @param schemaName 目标 Schema
     * @return 包含统计信息的表元数据列表 (此时 Column 信息为空，等待后续组装)
     */
    public abstract List<TableMetadata> getTablesWithStats(Connection connection, String schemaName) throws SQLException;

    /**
     * 【核心重构】批量获取指定表列表的列信息。
     * 目的：在内存中通过 Map 组装 Table 和 Column，避免 N 次数据库交互。
     *
     * @param connection 数据库连接
     * @param schemaName 所属 Schema
     * @param tableNames 需要获取列的表名列表
     * @return Key: 表名, Value: 列元数据列表
     */
    public abstract Map<String, List<ColumnMetadata>> getColumnsBatch(Connection connection, String schemaName, List<String> tableNames) throws SQLException;

    // =========================================================================
    // Data Sampling Methods
    // =========================================================================

    /**
     * 【数据预览】获取表的采样数据。
     * 实现类应利用数据库特性（如 PG的 TABLESAMPLE, MySQL的 Limit 优化）来减少 IO。
     *
     * @param connection 数据库连接
     * @param schemaName Schema
     * @param tableName 表名
     * @return 采样数据列表
     */
    public abstract List<Map<String, Object>> sampleTableData(Connection connection, String schemaName, String tableName) throws SQLException;

    // =========================================================================
    // Utility Methods
    // =========================================================================

    /**
     * 通用：将 ResultSet 转换为 Stream (用于大数据量导出场景，保留原功能)
     */
    public Stream<Map<String, Object>> streamTableData(Connection connection, String schema, String tableName) throws SQLException {
        String q = getIdentifierQuote();
        // 简单拼接，注意防注入（假设内部调用，表名已校验）
        String fullName = (schema != null && !schema.isEmpty()) ? q + schema + q + "." + q + tableName + q : q + tableName + q;
        String sql = "SELECT * FROM " + fullName;

        PreparedStatement stmt = connection.prepareStatement(
                sql,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY
        );
        applyStreamingSettings(stmt);
        ResultSet rs = stmt.executeQuery();
        return convertResultSetToStream(rs, stmt);
    }

    /**
     * 获取标识符引号 (MySQL为`, 其他通常为")
     */
    protected String getIdentifierQuote() {
        return getDataBaseType() == DataBaseType.MYSQL ? "`" : "\"";
    }

    /**
     * 应用流式读取设置 (FetchSize等)
     */
    protected void applyStreamingSettings(Statement stmt) throws SQLException {
        if (getDataBaseType() == DataBaseType.MYSQL) {
            stmt.setFetchSize(Integer.MIN_VALUE);
        } else {
            stmt.setFetchSize(1000);
            if (getDataBaseType() == DataBaseType.POSTGRESQL) {
                Connection conn = stmt.getConnection();
                if (conn.getAutoCommit()) {
                    conn.setAutoCommit(false);
                }
            }
        }
    }

    /**
     * ResultSet 转 Stream 辅助方法
     */
    private Stream<Map<String, Object>> convertResultSetToStream(ResultSet rs, Statement stmt) throws SQLException {
        Iterator<Map<String, Object>> iterator = new Iterator<>() {
            final ResultSetMetaData metaData = rs.getMetaData();
            final int colCount = metaData.getColumnCount();
            boolean didNext = false;
            boolean hasNextRow = false;

            @Override
            public boolean hasNext() {
                if (!didNext) {
                    try {
                        hasNextRow = rs.next();
                        didNext = true;
                    } catch (SQLException e) {
                        throw new CommonException("Stream read error", e);
                    }
                }
                return hasNextRow;
            }

            @Override
            public Map<String, Object> next() {
                if (!didNext) hasNext();
                if (!hasNextRow) throw new NoSuchElementException();
                try {
                    Map<String, Object> row = new LinkedHashMap<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        row.put(metaData.getColumnLabel(i), rs.getObject(i));
                    }
                    didNext = false;
                    return row;
                } catch (SQLException e) {
                    throw new CommonException("Row map error", e);
                }
            }
        };

        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED),
                false
        ).onClose(() -> {
            try { rs.close(); stmt.close(); } catch (SQLException e) { /* log */ }
        });
    }
}