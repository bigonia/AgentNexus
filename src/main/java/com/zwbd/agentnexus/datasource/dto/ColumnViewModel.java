package com.zwbd.agentnexus.datasource.dto;

/**
 * @Author: wnli
 * @Date: 2025/9/22 18:40
 * @Desc:
 */
// 这个DTO专门用于向table-detail.ftl模板传递数据
public record ColumnViewModel(
        String columnName,
        String dataType,
        boolean isPrimaryKey,
        boolean isNullable,
        String commentAndMetrics // 将格式化逻辑放在Java中，保持模板干净
) {



}
