package com.zwbd.agentnexus.datasource.dialect.impl;

import com.zwbd.agentnexus.datasource.dialect.DatabaseDialect;
import com.zwbd.agentnexus.datasource.dto.metadata.*;
import com.zwbd.agentnexus.datasource.entity.DataBaseInfo;
import com.zwbd.agentnexus.datasource.entity.DataBaseType;
import com.zwbd.agentnexus.datasource.entity.TableType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

/**
 * @Author: wnli
 * @Date: 2025/12/25
 * @Desc: MySQL 方言实现 (System Catalog 优化版)
 * 更新日志：
 * - 适配精简后的 ExtendedMetrics 结构。
 */
@Slf4j
@Component
public class MySQLDialect extends DatabaseDialect {

    @Override
    public DataBaseType getDataBaseType() {
        return DataBaseType.MYSQL;
    }

    @Override
    protected String getDriverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    protected String buildConnectionUrl(DataBaseInfo dataBaseInfo) {
        return String.format("jdbc:mysql://%s:%s/%s?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&useInformationSchema=true&tinyInt1isBit=false",
                dataBaseInfo.getHost(), dataBaseInfo.getPort(), dataBaseInfo.getDatabaseName());
    }

    @Override
    public List<SchemaMetadata> getSchemas(Connection connection) throws SQLException {
        List<SchemaMetadata> schemas = new ArrayList<>();
        String sql = "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String dbName = rs.getString("SCHEMA_NAME");
                    if (!isSystemSchema(dbName)) {
                        schemas.add(new SchemaMetadata(dbName, null, new ArrayList<>()));
                    }
                }
            }
        }
        return schemas;
    }

    @Override
    public List<TableMetadata> getTablesWithStats(Connection connection, String schemaName) throws SQLException {
        List<TableMetadata> tables = new ArrayList<>();
        // MySQL information_schema.TABLES 提供了表级的近似行数 (TABLE_ROWS)
        String sql = "SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT, TABLE_ROWS, DATA_LENGTH, CREATE_TIME " +
                "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, schemaName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    String tableTypeStr = rs.getString("TABLE_TYPE");
                    String comment = rs.getString("TABLE_COMMENT");
                    long rowCount = rs.getLong("TABLE_ROWS");

                    TableType tableType = "VIEW".equalsIgnoreCase(tableTypeStr) ? TableType.VIEW : TableType.TABLE;

                    tables.add(new TableMetadata(
                            tableName,
                            tableType,
                            Optional.ofNullable(comment).filter(s -> !s.isEmpty()),
                            rowCount,
                            new ArrayList<>(),
                            Optional.empty()
                    ));
                }
            }
        }
        return tables;
    }

    @Override
    public Map<String, List<ColumnMetadata>> getColumnsBatch(Connection connection, String schemaName, List<String> tableNames) throws SQLException {
        Map<String, List<ColumnMetadata>> result = new HashMap<>();
        if (tableNames == null || tableNames.isEmpty()) {
            return result;
        }

        String placeholders = String.join(",", Collections.nCopies(tableNames.size(), "?"));
        // MySQL 列信息不包含统计值 (Cardinality等在STATISTICS表里，且只针对索引列)
        // 这里的策略是：保持极速，暂不查询统计信息，返回 ExtendedMetrics.empty()
        String sql = String.format(
                "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_COMMENT, ORDINAL_POSITION " +
                        "FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = ? AND TABLE_NAME IN (%s) " +
                        "ORDER BY TABLE_NAME, ORDINAL_POSITION",
                placeholders
        );

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, schemaName);
            for (int i = 0; i < tableNames.size(); i++) {
                stmt.setString(i + 2, tableNames.get(i));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    String colName = rs.getString("COLUMN_NAME");
                    String fullDataType = rs.getString("COLUMN_TYPE");
                    boolean isNullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                    boolean isPrimaryKey = "PRI".equalsIgnoreCase(rs.getString("COLUMN_KEY"));
                    String comment = rs.getString("COLUMN_COMMENT");

                    ColumnMetadata col = new ColumnMetadata(
                            colName,
                            fullDataType,
                            Optional.ofNullable(comment).filter(s -> !s.isEmpty()),
                            isPrimaryKey,
                            isNullable,
                            // 对于 MySQL 批量获取，默认不提供扩展统计，以换取性能
                            Optional.of(ExtendedMetrics.empty())
                    );

                    result.computeIfAbsent(tableName, k -> new ArrayList<>()).add(col);
                }
            }
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> sampleTableData(Connection connection, String schemaName, String tableName) throws SQLException {
        long rowCount = getEstimatedRowCount(connection, schemaName, tableName);

        String sql;
        if (rowCount < 50000) {
            sql = String.format("SELECT * FROM `%s`.`%s` ORDER BY RAND() LIMIT %d", schemaName, tableName, DEFAULT_SAMPLE_LIMIT);
        } else if (rowCount < 10000000) {
            long maxOffset = Math.max(0, rowCount - DEFAULT_SAMPLE_LIMIT);
            long randomOffset = (long) (Math.random() * maxOffset);
            sql = String.format("SELECT * FROM `%s`.`%s` LIMIT %d OFFSET %d", schemaName, tableName, DEFAULT_SAMPLE_LIMIT, randomOffset);
        } else {
            sql = String.format("SELECT * FROM `%s`.`%s` LIMIT %d", schemaName, tableName, DEFAULT_SAMPLE_LIMIT);
        }

        List<Map<String, Object>> data = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnLabel(i), rs.getObject(i));
                }
                data.add(row);
            }
        }
        return data;
    }

    private long getEstimatedRowCount(Connection connection, String schema, String table) {
        String sql = "SELECT TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, schema);
            stmt.setString(2, table);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to get estimated row count for {}.{}", schema, table);
        }
        return 0;
    }

    private boolean isSystemSchema(String schema) {
        return Set.of("information_schema", "mysql", "performance_schema", "sys").contains(schema.toLowerCase());
    }
}