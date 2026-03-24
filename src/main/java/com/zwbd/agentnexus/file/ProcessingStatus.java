package com.zwbd.agentnexus.file;

/**
 * 文件处理状态枚举
 * UPLOADED: 已上传，等待处理
 * PROCESSING: 正在处理（向量化）中
 * COMPLETED: 处理成功
 * FAILED: 处理失败
 */
public enum ProcessingStatus {
    UN_VECTORIZATION,
    PROCESSING,
    VECTORIZATION,
    FAILED
}