package com.zwbd.agentnexus.document.service;

import com.zwbd.agentnexus.document.dto.JobInitRequest;
import com.zwbd.agentnexus.document.entity.CleaningJob;
import com.zwbd.agentnexus.document.entity.CleaningJobRecord;
import com.zwbd.agentnexus.document.entity.DocumentContext;
import com.zwbd.agentnexus.document.entity.DomainDocumentSegment;
import com.zwbd.agentnexus.document.etl.processor.PythonScriptProcessor;
import com.zwbd.agentnexus.document.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.PolyglotException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Author: wnli
 * @Date: 2025/12/9 10:56
 * @Desc:
 */
@Slf4j
@Service
public class CleaningJobEngine {

    @Autowired
    private CleaningJobRepository jobRepo;
    @Autowired
    private CleaningJobRecordRepository recordRepo;
    @Autowired
    private DomainDocumentSegmentRepository segmentRepo;
    @Autowired
    private CleaningSessionMsgRepository msgRepo;
    @Autowired
    private DocumentContextService documentContextService;

    /**
     * 创建并启动新的清洗任务
     */
    @Transactional
    public Long createAndStartJob(JobInitRequest request) {
        CleaningJob job = new CleaningJob();
        job.setSourceDocumentId(request.getSourceDocumentId());
        // 设置初始脚本，如果未提供则使用默认模板
        String script = request.getInitialScript();
        if (script == null || script.trim().isEmpty()) {
            script = "def process(text):\n    # 默认：原样返回\n    return text";
        }
        job.setScriptContent(script);
        job.setStatus(CleaningJob.JobStatus.CREATED);
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        job = jobRepo.save(job);
        // 自动触发首次运行 (异步执行，避免阻塞接口)
        Long jobId = job.getId();
        startOrRestartJob(jobId);
        return jobId;
    }

    /**
     * 启动或重启清洗任务
     * 策略：Fail Fast & Restart from Scratch (报错即停，修复后全量重跑)
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void startOrRestartJob(Long jobId) {
        CleaningJob job = jobRepo.findById(jobId).orElse(new CleaningJob());

        // 1. 清理旧现场 (重置状态)
        recordRepo.deleteByJobId(jobId); // 物理删除中间表数据
        job.setProcessedRows(0);
        job.setStatus(CleaningJob.JobStatus.RUNNING);
        job.setLastErrorLog(null);
        job.setErrorSourceId(null);
        jobRepo.save(job);

        // todo  3. 批量流式处理 (Batch Processing),应使用 StreamUtils 或 Scroll 游标避免一次性加载
        List<DomainDocumentSegment> sourceSegments = segmentRepo.findByDocumentIdOrderBySequenceAsc(job.getSourceDocumentId());
        int batchSize = 1000;
        List<CleaningJobRecord> batchBuffer = new ArrayList<>();
        DomainDocumentSegment tmp = null;
        try(PythonScriptProcessor processor = new PythonScriptProcessor(job.getScriptContent())){
            for (DomainDocumentSegment segment : sourceSegments) {
                {
                    tmp = segment;
                    // --- 执行清洗 ---
                    List<DocumentContext> process = processor.process(segment.toDocumentContext());
                    // --- 成功：加入缓冲 ---
                    for (DocumentContext context : process) {
                        batchBuffer.add(CleaningJobRecord.builder()
                                .jobId(jobId)
                                .sourceSegmentId(segment.getId())
                                .resultContent(context.getText())
                                .status(CleaningJobRecord.RecordStatus.SUCCESS)
                                .build());
                    }

                }
                // --- 批量落盘 ---
                if (batchBuffer.size() >= batchSize) {
                    recordRepo.saveAll(batchBuffer);
                    job.setProcessedRows(job.getProcessedRows() + batchBuffer.size());
                    jobRepo.save(job); // 更新进度
                    batchBuffer.clear();
                }
            }
        } catch (PolyglotException e) {
            // --- 失败：Fail Fast 逻辑 ---
            handleExecutionError(job, tmp, e);
            // 保存当前已缓冲的成功数据，方便用户查看报错前的数据
            if (!batchBuffer.isEmpty()) {
                recordRepo.saveAll(batchBuffer);
            }
            return; // 立即终止任务
        }


        // 处理剩余缓冲
        if (!batchBuffer.isEmpty()) {
            recordRepo.saveAll(batchBuffer);
            job.setProcessedRows(job.getProcessedRows() + batchBuffer.size());
        }

        // 4. 任务完成
        job.setStatus(CleaningJob.JobStatus.COMPLETED);
        jobRepo.save(job);
    }

    /**
     * 处理行级执行错误
     */
    private void handleExecutionError(CleaningJob job, DomainDocumentSegment segment, PolyglotException e) {
        // 1. 更新任务状态
        job.setStatus(CleaningJob.JobStatus.PAUSED_ON_ERROR);
        job.setErrorSourceId(segment.getId());
        // 截取关键堆栈
        String stackTrace = Arrays.stream(e.getStackTrace())
                .map(StackTraceElement::toString)
                .limit(5)
                .collect(Collectors.joining("\n"));
        job.setLastErrorLog("Line Error: " + e.getMessage() + "\n" + stackTrace);
        jobRepo.save(job);

        // 2. 记录一条失败的 Record (用于 Diff 列表置顶显示红色)
        CleaningJobRecord errorRecord = CleaningJobRecord.builder()
                .jobId(job.getId())
                .sourceSegmentId(segment.getId())
                .status(CleaningJobRecord.RecordStatus.FAILED)
                .errorMessage(e.getMessage())
                .build();
        recordRepo.save(errorRecord);

        // 3. 自动向会话历史中插入 SYSTEM 消息
        // 这样 AI 在接下来的对话中就能看到这个错误
//        saveSystemMessage(job.getId(),
//                String.format("任务在处理数据ID [%d] 时暂停。\n输入内容: %s\n错误信息: %s",
//                        segment.getId(), segment.getContent(), e.getMessage()));
    }

//    private void saveSystemMessage(Long jobId, String content) {
//        CleaningSessionMsg msg = new CleaningSessionMsg();
//        msg.setJobId(jobId);
//        msg.setRole(CleaningSessionMsg.Role.SYSTEM);
//        msg.setContent(content);
//        msg.setCreatedAt(LocalDateTime.now());
//        msgRepo.save(msg);
//    }
}
