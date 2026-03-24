package com.zwbd.agentnexus.document.dto;

import com.zwbd.agentnexus.document.entity.CleaningJobRecord;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: wnli
 * @Date: 2025/12/9 14:54
 * @Desc:
 */
@Data
@AllArgsConstructor
public class CleaningRecordDiffDto {
    private Long recordId;
    private Long sourceSegmentId;

    /** 原始文本 (Before) */
    private String originalContent;

    /** 清洗后文本 (After) */
    private String cleanedContent;

    /** 执行状态 */
    private CleaningJobRecord.RecordStatus status;
    private String errorMessage;

    /** 是否发生变更 (前端可据此只显示有变动的行) */
    private boolean changed;

    // JPQL 构造器
    public CleaningRecordDiffDto(Long recordId, Long sourceSegmentId, String originalContent, String cleanedContent, CleaningJobRecord.RecordStatus status, String errorMessage) {
        this.recordId = recordId;
        this.sourceSegmentId = sourceSegmentId;
        this.originalContent = originalContent;
        this.cleanedContent = cleanedContent;
        this.status = status;
        this.errorMessage = errorMessage;
        // 简单判断文本是否变化
        this.changed = status == CleaningJobRecord.RecordStatus.SUCCESS &&
                originalContent != null &&
                !originalContent.equals(cleanedContent);
    }
}
