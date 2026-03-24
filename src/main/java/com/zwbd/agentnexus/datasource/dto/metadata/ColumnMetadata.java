package com.zwbd.agentnexus.datasource.dto.metadata;

import lombok.With;

import java.util.Optional;

/**
 * @Author: wnli
 * @Date: 2025/9/11 10:38
 * @Desc: 代表单个列的详细元数据和数据质量指标。
 * * @param columnName 列名.
 * * @param dataType 数据库中的原始数据类型 (e.g., "VARCHAR(255)", "INT").
 * * @param comment 列的注释/描述.
 * * @param isPrimaryKey 是否为主键.
 * * @param isNullable 是否允许为空.
 * * @param metrics 该列的扩展数据质量指标.
 */
public record ColumnMetadata(
        String columnName,
        String dataType,
        Optional<String> comment,
        boolean isPrimaryKey,
        boolean isNullable,
        @With
        Optional<ExtendedMetrics> metrics
) {

        public ColumnMetadata withMetrics(final Optional<ExtendedMetrics> metrics) {
                return this.metrics == metrics ? this : new ColumnMetadata(this.columnName, this.dataType, this.comment, this.isPrimaryKey, this.isNullable, metrics);
        }

}
