package com.zwbd.agentnexus.datasource.dto.metadata;

/**
 * @Desc: 统计指标来源
 */
public enum MetricSource {
    /**
     * 系统内部统计表 (如 MySQL information_schema, PG pg_stats)
     * 特点：获取极快，但数据可能有延迟或偏差。
     */
    SYSTEM_CATALOG,

    /**
     * 实时计算 (Select count...)
     * 特点：精准但耗时。
     */
    REAL_TIME_ANALYSIS,

    /**
     * 未知/未统计
     */
    UNKNOWN
}
