package com.zwbd.agentnexus.document.dto;

import lombok.Data;

/**
 * @Author: wnli
 * @Date: 2025/12/9 15:48
 * @Desc:
 */
@Data
public class JobInitRequest {
    /**
     * 必须提供源文档ID
     */
    private Long sourceDocumentId;

    /**
     * 目标文档ID (可选)
     * 如果前端已经创建了衍生文档Shell，则传入ID；
     * 否则后端可能会创建一个临时的占位ID (MVP简化处理)
     */
//    private Long targetDocumentId;

    /**
     * 初始脚本 (可选)
     */
    private String initialScript;
}
