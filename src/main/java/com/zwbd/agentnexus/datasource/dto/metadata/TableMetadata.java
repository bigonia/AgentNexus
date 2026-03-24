package com.zwbd.agentnexus.datasource.dto.metadata;

import com.zwbd.agentnexus.datasource.entity.TableType;
import lombok.With;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @Author: wnli
 * @Date: 2025/9/11 10:36
 * @Desc:
 */

/**
 * 代表单个表或视图的完整元数据。
 *
 * @param tableName  表名.
 * @param tableType  表的类型 (TABLE or VIEW).
 * @param comment    表的注释/描述.
 * @param rowCount   表的行数 (可能是估算值或精确值).
 * @param columns    表中所有列的详细元数据列表.
 * @param sampleData 表的少量数据样例 (e.g., 10 rows).
 */
public record TableMetadata(
        String tableName,
        TableType tableType,
        Optional<String> comment,
        long rowCount,
        @With
        List<ColumnMetadata> columns,
        Optional<List<Map<String, Object>>> sampleData
) {

    public TableMetadata withColumns(final List<ColumnMetadata> columns) {
        return this.columns == columns ? this : new TableMetadata(this.tableName, this.tableType, this.comment, this.rowCount, columns, this.sampleData);
    }

    /**
     * 裁剪样例数据
     *
     * @param rows       保留的样例条数，若为负数则保留全部
     * @param maxSize    每个值的最大长度，若为负数则不限制长度
     * @return 裁剪后的样例数据
     */
    public Optional<List<Map<String, Object>>> trimSampleData(
            int rows,
            int maxSize) {

        // 如果原始数据为空，直接返回空Optional
        if (sampleData.isEmpty()) {
            return Optional.empty();
        }

        List<Map<String, Object>> originalData = sampleData.get();
        List<Map<String, Object>> trimmedData = new ArrayList<>();

        // 确定需要处理的行数
        int rowsToProcess = rows > 0 ? Math.min(rows, originalData.size()) : originalData.size();

        // 处理每行数据
        for (int i = 0; i < rowsToProcess; i++) {
            Map<String, Object> originalRow = originalData.get(i);
            Map<String, Object> trimmedRow = new java.util.HashMap<>();

            // 处理每个字段值
            for (Map.Entry<String, Object> entry : originalRow.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                // 裁剪值的长度
                Object trimmedValue = trimValue(value, maxSize);
                trimmedRow.put(key, trimmedValue);
            }

            trimmedData.add(trimmedRow);
        }

        return Optional.of(trimmedData);
    }

    /**
     * 裁剪单个值的长度
     *
     * @param value   原始值
     * @param maxSize 最大长度，若为负数则不裁剪
     * @return 裁剪后的值
     */
    private Object trimValue(Object value, int maxSize) {
        // 处理null值
        if (value == null) {
            return null;
        }

        // 如果不需要裁剪，直接返回原值
        if (maxSize <= 0) {
            return value;
        }

        // 将值转换为字符串处理
        String stringValue = value.toString();

        // 如果长度超过最大值，则裁剪
        if (stringValue.length() > maxSize) {
            return stringValue.substring(0, maxSize);
        }

        return value;
    }


}



