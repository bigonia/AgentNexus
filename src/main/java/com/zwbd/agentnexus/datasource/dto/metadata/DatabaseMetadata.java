package com.zwbd.agentnexus.datasource.dto.metadata;

import lombok.With;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/9/11 10:34
 * @Desc: Represents complete database metadata including schema/catalog hierarchy
 */
@With
public record DatabaseMetadata(String databaseProductName,
                               String databaseProductVersion,
                               List<SchemaMetadata> catalogs) {
}
