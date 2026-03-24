package com.zwbd.agentnexus.document.service;

import com.zwbd.agentnexus.ai.service.DocumentManagementService;
import com.zwbd.agentnexus.document.dto.RecordIssueFlagRequest;
import com.zwbd.agentnexus.document.entity.*;
import com.zwbd.agentnexus.document.repository.CleaningJobRecordRepository;
import com.zwbd.agentnexus.document.repository.CleaningJobRepository;
import com.zwbd.agentnexus.document.repository.DomainDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/12/9 15:00
 * @Desc:
 */
@Slf4j
@Service
public class CleaningCopilotService {

    @Autowired
    @Qualifier("pythonCoder")
    private ChatClient chatClient;
    @Autowired
    private CleaningJobRepository jobRepo;
    @Autowired
    private CleaningJobEngine jobEngine;
    @Autowired
    private CleaningJobRecordRepository recordRepo;
    @Autowired
    private DocumentContextService documentContextService;

    @Autowired
    private DocumentManagementService documentManagementService;

    @Autowired
    private DomainDocumentRepository domainDocumentRepository;

    public record EntityResult(
            String entity,      // 提取出的实体名称
            String category     // 分类：小区、乡镇/街道、其他
    ) {
    }

    public void genEntity(Long documentId) {
        //获取原始地址
        ArrayList<DocumentContext> results = new ArrayList<>();
        log.info("document id: {}", documentId);
        List<DocumentContext> documentContents = documentContextService.getDocumentContents(documentId);
        documentContents.stream().forEach(dc -> {
            //搜索目标实体
            List<Document> documents = documentManagementService.search(dc.getText(), 5, 0.4);
            List<String> list = documents.stream().map(Document::getText).toList();
            log.info("search entity {} ,list: {}", documents.size(), list);
            //llm实体映射
            String prompt = String.format("""
                    我需要进行地址对齐。
                    原始地址：【 %s 】
                    标准地址库候选：
                    %s

                    要求：
                    1. entity: 提取核心地名（去除楼栋、单元、户号等详细后缀）。
                    2. category: 必须从以下三个类别中选择一个：["小区", "乡镇/街道", "其他"]。
                       - "小区"：包括公寓、家园、新村、住宅区。
                       - "乡镇/街道"：包括行政村、工业园、镇政府、街道办。
                       - "其他"：无法归类或非地名。
                    """, dc, list);
            EntityResult entity = chatClient.prompt(prompt).call().entity(EntityResult.class);
            HashMap<String, Object> map = new HashMap<>(dc.getMetadata());
            map.put("原始地址", dc);
            map.put("近似实体列表", list);
            map.put("映射后实体", entity);
            results.add(new DocumentContext(entity.entity,map));
        });
        log.info("gen entity result: {}", results);
        log.info("document id: {}", documentId);
        DomainDocument domainDocument = domainDocumentRepository.findById(documentId).get();
        DomainDocument clean = domainDocument.clone();
        DomainDocument save = domainDocumentRepository.save(clean);
        documentContextService.saveDocumentContext(save.getId(),results);
    }

    /**
     * 用户介入：标记某行数据有问题 (User Feedback Loop)
     * 将具体的 Bad Case 直接追加到 Job 的指令列表中
     */
    @Transactional
    public void flagRecordAsIssue(Long jobId, RecordIssueFlagRequest request) {
        CleaningJob job = jobRepo.findById(jobId).orElseThrow();
        CleaningJobRecord record = recordRepo.findById(request.getRecordId()).orElseThrow();
        // todo
        DomainDocumentSegment source = documentContextService.getDocSegment(record.getId());

        // 构造结构化的反馈文本
        StringBuilder issueText = new StringBuilder();
        issueText.append(String.format("[User Feedback on Row ID %d] ", request.getRecordId()));
        issueText.append(String.format("Input: '%s', ", source.getContent()));
        issueText.append(String.format("Current Output: '%s', ", record.getResultContent()));

        if (request.getUserComment() != null && !request.getUserComment().isEmpty()) {
            issueText.append(String.format("User Comment: %s", request.getUserComment()));
        } else {
            issueText.append("Status: Incorrect (Please Fix)");
        }

        // 追加到历史列表
        job.getInstructionHistory().add(issueText.toString());
        jobRepo.save(job);
    }

    /**
     * 用户提交修复请求
     */
    public void fixScriptAndRestart(Long jobId, String userInstruction) {
        CleaningJob job = jobRepo.findById(jobId).orElseThrow();

        // 1. 记录本次主要指令
        if (userInstruction != null && !userInstruction.isEmpty()) {
            job.getInstructionHistory().add("Instruction: " + userInstruction);
            jobRepo.save(job);
        }

        // 2. 获取上下文
        String currentScript = job.getScriptContent();
        String errorContext = job.getLastErrorLog();
        List<String> instructions = job.getInstructionHistory();

        // 3. 构建 Prompt
        String promptText = buildRepairPrompt(currentScript, errorContext, instructions);
        String newScript = chatClient.prompt(promptText).call().content();
        newScript = extractCodeBlock(newScript);

        // 4. 更新并重启
        job.setScriptContent(newScript);
        jobRepo.save(job);

        jobEngine.startOrRestartJob(jobId);
    }

    private String buildRepairPrompt(String script, String error, List<String> instructions) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个 Python 数据清洗专家。请根据以下[问题列表]和[报错信息]修复代码。\n\n");

        sb.append("--- 当前脚本 ---\n").append(script).append("\n\n");

        if (error != null) {
            sb.append("--- 系统报错 (Sample Data) ---\n").append(error).append("\n\n");
        }

        sb.append("--- 待解决问题列表 (Feedback List) ---\n");
        // 取最近 20 条反馈，保证涵盖主要问题
        int start = Math.max(0, instructions.size() - 20);
        for (int i = start; i < instructions.size(); i++) {
            sb.append(String.format("%d. %s\n", i + 1, instructions.get(i)));
        }

        sb.append("\n--- 任务要求 ---\n");
        sb.append("1. 请修复 'process' 函数，使其能解决上述列表中的所有问题。\n");
        sb.append("2. 如果有报错信息，请优先解决运行时错误。\n");
        sb.append("3. 仅返回 Python 代码。\n");
        return sb.toString();
    }

    private String extractCodeBlock(String llmResponse) {
        if (llmResponse.contains("```python")) {
            int start = llmResponse.indexOf("```python") + 9;
            int end = llmResponse.indexOf("```", start);
            if (end > start) {
                return llmResponse.substring(start, end).trim();
            }
        }
        return llmResponse;
    }
}