package com.zwbd.agentnexus.document.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: wnli
 * @Date: 2025/12/9 10:53
 * @Desc:
 * 清洗过程记录表 (中间表)
 * 用于存储每一行的执行结果，支持前端 Diff 和分页查看
 * 策略：全量重跑时，会先按 JobID 清空此表
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "cleaning_job_record", indexes = {
        @Index(name = "idx_job_id", columnList = "jobId")
})
public class CleaningJobRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long jobId;

    /** * 原始数据引用
     * 关联 DomainDocumentSegment 表的id
     */
    @Column(nullable = false)
//    private Long sourceSegmentSequence;
    private Long sourceSegmentId;

    /** 清洗后的文本结果 */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String resultContent;

    /** 结构化提取结果 (JSON) */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String resultMetadata;

    /** 该行执行状态 (SUCCESS / FAILED) */
    @Enumerated(EnumType.STRING)
    private RecordStatus status;

    /** 具体的行级报错信息 */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    public enum RecordStatus {
        SUCCESS, FAILED
    }
}
