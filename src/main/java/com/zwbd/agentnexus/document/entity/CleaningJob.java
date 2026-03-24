package com.zwbd.agentnexus.document.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/12/9 10:52
 * @Desc:
 *  * 清洗任务主表
 *  * 管理清洗流程的状态、脚本版本以及错误断点
 */
@Entity
@Data
@Table(name = "cleaning_job")
public class CleaningJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的目标文档ID */
//    private Long targetDocumentId;

    /** 关联的源文档ID */
    private Long sourceDocumentId;

    /** * 当前使用的 Python 脚本
     * 每次 AI 修复后，这里会更新为最新版
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String scriptContent;

    /** 任务状态 */
    @Enumerated(EnumType.STRING)
    private JobStatus status;

    /** 进度统计 */
    private Integer totalRows = 0;
    private Integer processedRows = 0;

    /** * 错误快照
     * 存储导致任务暂停的最后一次异常堆栈，用于前端展示和 AI 诊断
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String lastErrorLog;

    /** 错误发生的源数据ID (断点) */
    private Long errorSourceId;

    /** * 用户反馈/指令历史列表 (简化版对话)
     * 存储用户提出的需求变更或标记的 Bad Case 信息
     * Prompt 构建时会将此列表作为 Context 发送给 AI
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cleaning_job_instructions", joinColumns = @JoinColumn(name = "job_id"))
    @Column(name = "instruction", columnDefinition = "TEXT")
    private List<String> instructionHistory = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum JobStatus {
        CREATED,
        RUNNING,
        PAUSED_ON_ERROR, // 核心状态：报错即停
        COMPLETED,       // 清洗完成，等待发布
        PUBLISHED        // 已写入最终文档
    }
}
