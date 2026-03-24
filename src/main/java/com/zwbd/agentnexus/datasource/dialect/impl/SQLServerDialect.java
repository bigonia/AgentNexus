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
 * @Desc: SQL Server 方言实现 (System Views 深度集成版)
 * 特性：
 * 1. 使用 sys.dm_db_partition_stats 获取精准的实时行数和大小，无IO扫描。
 * 2. 支持 TABLESAMPLE 语法进行物理页级采样。
 * 3. 自动解析 MS_Description 扩展属性作为注释。
 */
@Slf4j
@Component
public class SQLServerDialect extends DatabaseDialect {

    @Override
    public DataBaseType getDataBaseType() {
        return DataBaseType.SQLSERVER;
    }

    @Override
    protected String getDriverClassName() {
        return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    }

    @Override
    protected String buildConnectionUrl(DataBaseInfo dataBaseInfo) {
        // SQL Server 现代驱动通常需要 encrypt=false 和 trustServerCertificate=true 以避免 SSL 握手错误
        // ApplicationName 用于在数据库监控中标识来源
        return String.format("jdbc:sqlserver://%s:%s;databaseName=%s;encrypt=false;trustServerCertificate=true;ApplicationName=DBCrawler",
                dataBaseInfo.getHost(), dataBaseInfo.getPort(), dataBaseInfo.getDatabaseName());
    }

    @Override
    protected String getIdentifierQuote() {
        // SQL Server 使用中括号 [] 引用标识符
        // 虽然双引号 "" 也是 ANSI 标准，但 [] 更为原生和通用
        return "\""; // 保持与基类一致使用双引号，或重写为 return "" 并在 SQL 拼接时手动处理。
        // 注意：为了兼容性，大多数 SQL Server 配置支持双引号标识符 (QUOTED_IDENTIFIER ON)。
        // 若环境特殊，建议此处改为 return ""; 并在 SQL 中手动拼接 []。
    }

    @Override
    public List<SchemaMetadata> getSchemas(Connection connection) throws SQLException {
        List<SchemaMetadata> schemas = new ArrayList<>();
        // 排除系统 Schema
        String sql = "SELECT name FROM sys.schemas " +
                "WHERE name NOT IN ('dbo', 'guest', 'sys', 'INFORMATION_SCHEMA') " +
                "AND name NOT LIKE 'db_%'"; // 排除 db_owner 等角色 Schema

        // 默认将 dbo 也加入，因为它是最常用的
        schemas.add(new SchemaMetadata("dbo", "Default Schema", new ArrayList<>()));

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString("name");
                schemas.add(new SchemaMetadata(name, null, new ArrayList<>()));
            }
        }
        return schemas;
    }

    @Override
    public List<TableMetadata> getTablesWithStats(Connection connection, String schemaName) throws SQLException {
        List<TableMetadata> tables = new ArrayList<>();

        // 核心查询：关联 sys.objects, sys.dm_db_partition_stats, sys.extended_properties
        // object_id 是核心连接键
        String sql = """
            SELECT
                t.name AS table_name,
                t.type AS table_type,
                ep.value AS remarks,
                SUM(ps.row_count) AS row_count,
                SUM(ps.reserved_page_count) * 8192 AS data_size -- 8KB per page
            FROM sys.objects t
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            LEFT JOIN sys.dm_db_partition_stats ps ON t.object_id = ps.object_id AND ps.index_id IN (0, 1) -- 0=Heap, 1=Clustered
            LEFT JOIN sys.extended_properties ep ON t.object_id = ep.major_id AND ep.minor_id = 0 AND ep.name = 'MS_Description'
            WHERE s.name = ? AND t.type IN ('U', 'V') -- U=User Table, V=View
            GROUP BY t.name, t.type, ep.value
        """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, schemaName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    String typeCode = rs.getString("table_type").trim();
                    String remarks = rs.getString("remarks");
                    long rowCount = rs.getLong("row_count");

                    // 映射 TableType
                    TableType tableType = "V".equalsIgnoreCase(typeCode) ? TableType.VIEW : TableType.TABLE;

                    tables.add(new TableMetadata(
                            tableName,
                            tableType,
                            Optional.ofNullable(remarks).filter(s -> !s.isEmpty()),
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
        if (tableNames == null || tableNames.isEmpty()) return result;

        // 构建 IN 子句
        String placeholders = String.join(",", Collections.nCopies(tableNames.size(), "?"));

        // 查询列信息、主键信息和注释
        // sys.types 需要注意 system_type_id 和 user_type_id
        String sql = String.format("""
            SELECT
                t.name AS table_name,
                c.name AS column_name,
                ty.name AS data_type,
                c.max_length,
                c.is_nullable,
                ep.value AS remarks,
                CASE WHEN ic.column_id IS NOT NULL THEN 1 ELSE 0 END AS is_pk,
                c.column_id AS ordinal_position
            FROM sys.columns c
            JOIN sys.objects t ON c.object_id = t.object_id
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            JOIN sys.types ty ON c.user_type_id = ty.user_type_id
            LEFT JOIN sys.extended_properties ep ON c.object_id = ep.major_id AND c.column_id = ep.minor_id AND ep.name = 'MS_Description'
            LEFT JOIN sys.indexes i ON t.object_id = i.object_id AND i.is_primary_key = 1
            LEFT JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id AND c.column_id = ic.column_id
            WHERE s.name = ? AND t.name IN (%s)
            ORDER BY t.name, c.column_id
        """, placeholders);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, schemaName);
            for (int i = 0; i < tableNames.size(); i++) {
                stmt.setString(i + 2, tableNames.get(i));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    String colName = rs.getString("column_name");
                    String dataType = rs.getString("data_type");
                    // 补充长度信息
                    short maxLen = rs.getShort("max_length");
                    String fullType = (maxLen == -1) ? dataType + "(MAX)" : dataType; // 简单处理

                    boolean isNullable = rs.getBoolean("is_nullable");
                    boolean isPk = rs.getInt("is_pk") == 1;
                    String remarks = rs.getString("remarks");

                    ColumnMetadata col = new ColumnMetadata(
                            colName,
                            fullType,
                            Optional.ofNullable(remarks).filter(s -> !s.isEmpty()),
                            isPk,
                            isNullable,
                            // SQL Server 批量获取列级统计较复杂，暂置空以保证性能
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
        // 判断是否为视图 (视图不支持 TABLESAMPLE)
        boolean isView = isView(connection, schemaName, tableName);

        String sql;
        String qSchema = "[" + schemaName + "]";
        String qTable = "[" + tableName + "]";

        if (isView) {
            // 视图：简单的 Random Sort
            sql = String.format("SELECT TOP %d * FROM %s.%s ORDER BY NEWID()", DEFAULT_SAMPLE_LIMIT, qSchema, qTable);
        } else {
            // 表：TABLESAMPLE 策略
            // TABLESAMPLE (1000 ROWS) 并不保证精确返回 1000 行，也不保证一定有数据（如果表很小）
            // 混合策略：尝试取 10 PERCENT，再限制 TOP N
            sql = String.format("SELECT TOP %d * FROM %s.%s TABLESAMPLE (10 PERCENT) REPEATABLE(123)",
                    DEFAULT_SAMPLE_LIMIT, qSchema, qTable);
        }

        List<Map<String, Object>> data = new ArrayList<>();
        // 容错执行：如果 TABLESAMPLE 返回空（小表可能发生），回退到普通查询
        try {
            data = executeQuery(connection, sql);
            if (data.isEmpty() && !isView) {
                // 回退策略：针对小表，TABLESAMPLE 可能拿不到数据
                log.debug("TABLESAMPLE returned empty for {}.{}, falling back to standard select.", schemaName, tableName);
                String fallbackSql = String.format("SELECT TOP %d * FROM %s.%s", DEFAULT_SAMPLE_LIMIT, qSchema, qTable);
                data = executeQuery(connection, fallbackSql);
            }
        } catch (SQLException e) {
            log.warn("Sampling failed for {}.{}, error: {}", schemaName, tableName, e.getMessage());
            // 最后的兜底
            String fallbackSql = String.format("SELECT TOP %d * FROM %s.%s", DEFAULT_SAMPLE_LIMIT, qSchema, qTable);
            data = executeQuery(connection, fallbackSql);
        }

        return data;
    }

    private List<Map<String, Object>> executeQuery(Connection connection, String sql) throws SQLException {
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

    private boolean isView(Connection connection, String schema, String table) {
        String sql = "SELECT type FROM sys.objects t JOIN sys.schemas s ON t.schema_id = s.schema_id WHERE s.name = ? AND t.name = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, schema);
            stmt.setString(2, table);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return "V".equals(rs.getString("type").trim());
                }
            }
        } catch (SQLException e) {
            return false;
        }
        return false;
    }
}