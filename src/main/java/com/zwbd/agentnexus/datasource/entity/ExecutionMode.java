package com.zwbd.agentnexus.datasource.entity;

/**
 * @Author: wnli
 * @Date: 2025/9/11 11:06
 * @Desc:
 */
public enum ExecutionMode {

    /**
     * 自动模式：默认行为。
     * 根据表的行数和预设的阈值，自动决定是使用全量扫描还是采样。
     */
    AUTO,

    /**
     * 强制全量扫描模式：
     * 无论表有多大，都强制执行全量计算，以获取最精确的指标。
     * 警告：对大表使用此模式可能非常耗时并消耗大量数据库资源。
     */
    FORCE_FULL_SCAN,

    /**
     * 强制采样模式：
     * 无论表有多小，都强制使用采样方式进行估算。
     * 主要用于快速预览或测试。
     */
    FORCE_SAMPLE

}
