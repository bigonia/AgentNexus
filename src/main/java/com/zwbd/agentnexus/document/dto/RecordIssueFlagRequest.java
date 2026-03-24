package com.zwbd.agentnexus.document.dto;

import lombok.Data;

/**
 * @Author: wnli
 * @Date: 2025/12/9 14:55
 * @Desc:
 */
@Data
public class RecordIssueFlagRequest {
    /** 用户选中的记录ID */
    private Long recordId;
    /** 用户的指导意见 (可选) e.g. "这里不应该把逗号删掉" */
    private String userComment;
}
