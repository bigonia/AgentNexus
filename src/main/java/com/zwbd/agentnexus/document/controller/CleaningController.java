package com.zwbd.agentnexus.document.controller;

import com.zwbd.agentnexus.document.dto.CleaningRecordDiffDto;
import com.zwbd.agentnexus.document.dto.JobInitRequest;
import com.zwbd.agentnexus.document.dto.RecordIssueFlagRequest;
import com.zwbd.agentnexus.document.repository.CleaningJobRecordRepository;
import com.zwbd.agentnexus.document.repository.CleaningJobRepository;
import com.zwbd.agentnexus.document.service.CleaningCopilotService;
import com.zwbd.agentnexus.document.service.CleaningJobEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/12/9 15:16
 * @Desc:
 */
@RestController
@RequestMapping("/api/cleaning")
public class CleaningController {

    @Autowired
    private CleaningJobRecordRepository recordRepo;
    @Autowired
    private CleaningCopilotService copilotService;
    @Autowired
    private CleaningJobEngine jobEngine;

    @Autowired
    private CleaningJobRepository jobRepo;

    /**
     * 初始化任务
     * POST /api/cleaning/jobs
     */
    @PostMapping("/jobs")
    public Long initJob(@RequestBody JobInitRequest request) {
        return jobEngine.createAndStartJob(request);
    }

    @GetMapping("/jobs/status")
    public Page<CleaningRecordDiffDto> getStatus(
            @PathVariable Long jobId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return recordRepo.findDiffsByJobId(jobId, PageRequest.of(page, size));
    }

    /**
     * 获取 Diff 列表
     */
    @GetMapping("/jobs/{jobId}/diff")
    public Page<CleaningRecordDiffDto> getDiffList(
            @PathVariable Long jobId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return recordRepo.findDiffsByJobId(jobId, PageRequest.of(page, size));
    }

    /**
     * 用户介入：标记某行数据为"Issue"
     */
    @PostMapping("/jobs/{jobId}/records/flag")
    public void flagRecord(
            @PathVariable Long jobId,
            @RequestBody RecordIssueFlagRequest request) {
        copilotService.flagRecordAsIssue(jobId, request);
    }

    /**
     * 一键修复并重启
     */
    @PostMapping("/jobs/{jobId}/fix-and-restart")
    public void fixAndRestart(
            @PathVariable Long jobId,
            @RequestBody Map<String, String> payload) {
        String instruction = payload.getOrDefault("instruction", "请根据上述反馈修复脚本");
        copilotService.fixScriptAndRestart(jobId, instruction);
    }

}
