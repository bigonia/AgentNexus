package com.zwbd.agentnexus.document.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.document.dto.GenerateScriptRequest;
import com.zwbd.agentnexus.document.entity.BizAction;
import com.zwbd.agentnexus.document.entity.DocumentContext;
import com.zwbd.agentnexus.document.entity.DomainDocument;
import com.zwbd.agentnexus.document.repository.DomainDocumentRepository;
import com.zwbd.agentnexus.document.service.CleaningCopilotService;
import com.zwbd.agentnexus.document.service.DocumentContextService;
import com.zwbd.agentnexus.document.service.DomainDocumentService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.zwbd.agentnexus.common.config.CommonConfig.objectMapper;

/**
 * @Author: wnli
 * @Date: 2025/11/25 11:03
 * @Desc:
 */
@Slf4j
@RestController
@RequestMapping("/api/domain-docs")
public class DomainDocumentController {

    @Autowired
    private DomainDocumentService domainDocumentService;

    @Autowired
    private DocumentContextService documentContextService;

    @Autowired
    @Qualifier("pythonCoder")
    private ChatClient chatClient;

    /**
     * 利用 AI 生成 Python 清洗脚本
     * 逻辑：
     * 1. 如果前端提供了 sampleData，直接使用。
     * 2. 如果没提供，后端根据 docId 自动读取前 3 条数据作为采样。
     * 3. 结合用户需求 (requirement) 构建 Prompt。
     */
    @PostMapping("/generate-script")
    public Flux<String> generateCleaningScript(@RequestBody GenerateScriptRequest request) {
        // 1. 准备采样数据 (Context)
        String contextData = request.getSampleData();

        // 如果前端没传采样数据，后端自动去查原始文档获取
        if (contextData == null || contextData.isEmpty()) {
            if (request.getDocId() != null) {
                try (Stream<DocumentContext> stream = domainDocumentService.getStream(request.getDocId())) {
                    // 取前 3 条作为样本
                    List<DocumentContext> samples = stream.limit(3).collect(Collectors.toList());
                    contextData = objectMapper.writeValueAsString(samples);
                } catch (Exception e) {
                    contextData = "[无法自动获取采样数据，请手动提供]";
                }
            } else {
                contextData = "[无采样数据]";
            }
        }

        // 2. 构建 Prompt (严格限制输出格式)
        String promptText = String.format("""
                你是一个数据治理专家，精通 Python 和 Java GraalVM 环境。
                请根据以下数据样本和用户需求，编写一个 Python 清洗脚本。
                                
                【数据样本】：
                %s
                                
                【用户需求】：
                %s
                                
                【代码规范与限制】：
                1. 必须定义函数 `def process(doc):`。
                2. 输入 `doc` 是 Java DocumentContext 对象，其属性均为 Java 对象：
                   - 获取内容: `content = doc.getText()` (返回 Java String，可直接视为 Python str)
                   - 获取元数据: `meta = doc.getMetadata()` (返回 java.util.Map)
                   - **严禁**对 meta 使用 Python 字典的双参数 get 写法 `meta.get('key', 'default')`。
                   - **必须**使用 Java Map 方法: `meta.get('key')` 或 `meta.getOrDefault('key', 'default')`。
                   - 设置内容: `doc.setText(new_str)`
                   - 设置元数据: `doc.getMetadata().put('key', val)`
                3. 输出必须是列表 `list`：
                   - 返回 `[doc]` 表示修改。
                   - 返回 `[]` 表示过滤删除。
                   - 返回 `[doc1, doc2]` 表示拆分。
                4. 禁止 import 第三方库 (pandas/numpy)，仅使用标准库 (json, re)。
                5. 直接返回 Python 代码，不要包含 Markdown 代码块标记（如 ```python），不要包含解释性文字，可以包含代码注释。
                                
                【Python 代码】：
                """, contextData, request.getRequirement());

        // 3. 调用 AI
        Flux<String> content = chatClient.prompt()
                .user(promptText)
                .stream().content();
        // 清理可能存在的 markdown 标记 (以防万一 LLM 不听话)
//        generatedScript = generatedScript.replace("```python", "").replace("```", "").trim();
        return content;
    }

    /**
     * 获取文档数据流
     * 使用 StreamingResponseBody 保持连接，逐行写入 JSON 数据，防止内存溢出。
     * 响应格式为 NDJSON (Newline Delimited JSON) 或 JSON Array Stream
     */
    @GetMapping(value = "/{docId}/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public StreamingResponseBody getDocumentStream(@PathVariable Long docId) {
        StreamingResponseBody stream = outputStream -> {
            // 获取业务流 (已包含可能的 Python 清洗装饰器)
            try (Stream<DocumentContext> docStream = domainDocumentService.getStream(docId)) {
                // 遍历流，逐条写入 Response
                docStream.forEach(doc -> {
                    try {
                        String json = objectMapper.writeValueAsString(doc);
                        outputStream.write(json.getBytes(StandardCharsets.UTF_8));
                        outputStream.write("\n".getBytes(StandardCharsets.UTF_8)); // 换行符分隔
                        outputStream.flush(); // 及时推送
                    } catch (IOException e) {
                        throw new RuntimeException("Error writing stream", e);
                    }
                });
            } catch (Exception e) {
                // 流处理过程中的异常捕获
                throw new RuntimeException("Stream processing failed", e);
            }
        };
        return stream;
    }

    /**
     * 创建衍生文档
     * 用户在前端编写好 Python 脚本后，调用此接口保存/执行清洗规则。
     */
    @PostMapping("/{parentId}/derive")
    public ApiResponse createDerivedDocument(
            @PathVariable Long parentId,
            @RequestBody String script) {
        // 调用 Service 层的核心分流方法
        // 如果是静态文档，这里会立即执行 ETL；如果是流式文档，这里只保存 Metadata
        domainDocumentService.createDerivedDocument(parentId, script);
        return ApiResponse.success();
    }

    @Autowired
    private CleaningCopilotService cleaningCopilotService;

    @Autowired
    private DomainDocumentRepository domainDocumentRepository;

    @GetMapping("entity/{id}")
    public ApiResponse entity(@PathVariable Long id) {
        log.info("document id: {}", id);
        cleaningCopilotService.genEntity(id);
        return ApiResponse.success();
    }

    @GetMapping("/{docId}/export/excel")
    public void downloadExcel(@PathVariable Long docId, HttpServletResponse response) {
        try {
            // 1. 设置响应头 (Controller 的职责)
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("utf-8");
            String fileName = URLEncoder.encode("数据清洗结果_" + docId, "UTF-8").replaceAll("\\+", "%20");
            response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");
            // 2. 调用 Service，传入 Response 的输出流
            domainDocumentService.exportToExcel(docId, response.getOutputStream());
        } catch (IOException e) {
            // 异常处理
            response.setStatus(500);
        }
    }

    /**
     * 下载 SQL 脚本
     * 用法: GET /api/documents/101/export/sql?tableName=order_deal_result&pkKey=sourceId
     * * @param tableName 目标表名 (如 order_deal_result)
     * @param pkKey 元数据中作为主键的 Key (根据你的样例数据，应该是 "sourceId")
     */
    @GetMapping("/{docId}/export/sql")
    public void downloadSql(@PathVariable Long docId,
                            @RequestParam String tableName,
                            @RequestParam(defaultValue = "sourceId") String pkKey,
                            HttpServletResponse response) {
        try {
            domainDocumentService.exportToSql(docId, tableName, pkKey, response);
        } catch (IOException e) {
            response.setStatus(500);
        }
    }

    @GetMapping("vector/{id}")
    public ApiResponse vectorDocuments(@PathVariable Long id) {
        domainDocumentService.triggerVectorization(id);
        return ApiResponse.success();
    }

    @GetMapping("/{id}")
    public ApiResponse<DomainDocument> getDocument(@PathVariable Long id) {
        return ApiResponse.success(domainDocumentService.getDomainDocument(id));
    }

    @GetMapping("/{id}/context")
    public ApiResponse<Page<DocumentContext>> getDocumentContext(@PathVariable Long id, @RequestParam int page, @RequestParam int size) {
        return ApiResponse.success(documentContextService.getDocumentContentPage(id, page, size));
    }

    @GetMapping("/{id}/stream")
    public ApiResponse<Stream<DocumentContext>> getStream(@PathVariable Long id) {
        return ApiResponse.success(domainDocumentService.getStream(id));
    }

    @GetMapping("/list")
    public ApiResponse<List<DomainDocument>> listDocuments() {
        return ApiResponse.ok(domainDocumentService.list());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteDocument(@PathVariable Long id) {
        domainDocumentService.deleteDomainDocument(id);
        return ApiResponse.success();
    }

    /**
     * 获取支持的业务动作列表
     * 辅助前端渲染下拉菜单
     */
    @GetMapping("/actions/support-list")
    public ApiResponse<Map<BizAction, String>> getSupportedActions() {
//        return ApiResponse.ok(domainDocumentService.getSupportedActions());
        return ApiResponse.ok(Map.of(BizAction.VECTORIZED, "向量化",
                BizAction.GEN_QA, "生成QA", BizAction.GEN_TAG, "生成标签"
        ));
    }

    /**
     * 统一业务触发入口
     * POST /api/domain-docs/{id}/actions
     * Body: { "action": "VECTORIZE", "params": { "chunkSize": 500 } }
     */
    @PostMapping("/{id}/actions")
    public ApiResponse triggerAction(@PathVariable Long id, @RequestBody TriggerActionRequest request) {
        // 将分发逻辑下沉到 Service 层
        domainDocumentService.triggerBusinessAction(id, request.getAction(), request.getParams());
        return ApiResponse.success();
    }

    /**
     * 手动更新清洗结果 (对应 Processor 完成)
     */
//    @PutMapping("/{id}/complete")
//    public ApiResponse completeProcessing(@PathVariable Long id, @RequestBody UpdateContentRequest request) {
//        domainDocumentService.completeProcessing(id, request.getContentList(), request.getMetadata());
//        return ApiResponse.success();
//    }


    // --- DTOs ---
    @Data
    public static class TriggerActionRequest {
        private BizAction action;
        private Map<String, Object> params;
    }


    @Data
    public static class UpdateContentRequest {
        private List<Document> contentList;
        private Map<String, Object> metadata;
    }

    @Data
    public static class UpdateBusinessStatusRequest {
        private String serviceKey; // e.g., "VECTOR", "QA"
        private String status;     // e.g., "COMPLETED", "FAILED"
    }
}
