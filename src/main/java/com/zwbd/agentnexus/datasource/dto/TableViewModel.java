package com.zwbd.agentnexus.datasource.dto;

import com.zwbd.agentnexus.datasource.entity.TableType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @Author: wnli
 * @Date: 2025/9/24 18:00
 * @Desc:
 */
public record TableViewModel(
        String tableName,
        TableType tableType,
        Optional<String> comment,
        long rowCount,
        Optional<List<Map<String, String>>> sampleData
) {
}
