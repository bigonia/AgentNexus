package com.zwbd.agentnexus.document.entity;

/**
 * @Author: wnli
 * @Date: 2025/11/25 10:21
 * @Desc: 领域文档生命周期状态
 * 1. CREATED: 初始态 (包含原有的 Created + Loaded 概念，表示文档已就位，等待处理)
 * 2. PROCESSING: 正在执行 Pipeline (加载/清洗/脚本执行)
 * 3. READY: 清洗完毕，数据结构化完成，成为“领域通用文档”，可供下游业务消费
 * 4. FAILED: 处理失败
 */
public enum DomainDocumentStatus {
    /**
     * 初始态：文档记录已创建，等待被调度执行加载和清洗
     */
    CREATED,

    /**
     * 处理中：正在进行加载(Loader)或执行清洗脚本(Processor)
     */
    PROCESSING,

    /**
     * 清洗完毕
     */
    PROCESSED,

    /**
     * 失败
     */
    FAILED
}