package com.zwbd.agentnexus.datasource.dto.metadata;

import java.util.Date;
import java.util.Optional;

/**
 * @Desc: 列级扩展统计指标 (精简版)
 * 仅保留系统视图(System Catalog)能直接提供的核心指标，
 * 移除需要全表扫描才能获取的复杂分布数据。
 */
public record ExtendedMetrics(
        // 基数 (唯一值数量估算) - 对应 PG n_distinct 或 MySQL Index Cardinality
        Optional<Long> distinctValues,

        // 空值数量 (估算)
        Optional<Long> nullValues,

        // 平均列长度 (字节)
        Optional<Long> avgLength,

        // 统计信息的来源 (用于前端展示是"精准值"还是"过期的估算值")
        MetricSource source,

        // 统计信息最后更新时间
        Date lastAnalyzed
) {
    // 快速构建一个空的指标对象
    public static ExtendedMetrics empty() {
        return new ExtendedMetrics(Optional.empty(), Optional.empty(), Optional.empty(), MetricSource.UNKNOWN, new Date());
    }
}


