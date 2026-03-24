package com.zwbd.agentnexus.datasource.dto.metadata;

import lombok.With;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/9/19 11:08
 * @Desc: Represents catalog/schema level metadata in database hierarchy.
 * In database systems, catalogs and schemas provide logical grouping of tables.
 * - Catalog: Top-level container (database name in MySQL, database name in SQL Server)
 * - Schema: Secondary-level container (schema name in PostgreSQL/SQL Server, database name in MySQL)
 *
 * 新的设计为，取消catalog层级抽象，只维护schema层级，catalog只用于业务流程中的数据库遍历
 *
 */
@With
public record SchemaMetadata(
        String schemaName,
        String remarks,
        List<TableMetadata> tables
) {

    /**
     * Create a CatalogMetadataDo with minimal information
     */
    public static SchemaMetadata of(String schemaName) {
        return new SchemaMetadata(schemaName, null, List.of());
    }

    /**
     * Create a CatalogMetadata with tables
     */
    public static SchemaMetadata of(String schemaName, List<TableMetadata> tables) {
        return new SchemaMetadata(schemaName, null, tables);
    }


    public String getSchemaName() {
        return schemaName;
    }


    public String getRemarks() {
        return remarks;
    }

    public List<TableMetadata> getTables() {
        return tables;
    }
}
