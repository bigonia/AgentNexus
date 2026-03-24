package com.zwbd.agentnexus.document.service;

import com.alibaba.excel.EasyExcel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zwbd.agentnexus.ai.dto.document.metadata.BaseMetadata;
import com.zwbd.agentnexus.ai.dto.document.metadata.DocumentType;
import com.zwbd.agentnexus.ai.service.DocumentManagementService;
import com.zwbd.agentnexus.document.entity.*;
import com.zwbd.agentnexus.document.etl.loader.DocumentLoader;
import com.zwbd.agentnexus.document.etl.processor.PythonScriptProcessor;
import com.zwbd.agentnexus.document.etl.reader.DomainDocumentReader;
import com.zwbd.agentnexus.document.repository.DomainDocumentRepository;
import com.zwbd.agentnexus.utils.MapUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;

import static com.zwbd.agentnexus.common.config.CommonConfig.objectMapper;
import static com.zwbd.agentnexus.document.entity.DocConstant.META_KEY_PIPELINE;
import static com.zwbd.agentnexus.document.entity.DocConstant.PARENT_ID;

/**
 * @Author: wnli
 * @Date: 2025/11/25 10:33
 * @Desc:
 */
@Slf4j
@Service
public class DomainDocumentService {

    @Autowired
    private DomainDocumentRepository domainDocumentRepository;

    @Autowired
    private DocumentManagementService documentManagementService;


    @Autowired
    private DocumentContextService documentContextService;
//    @Autowired
//    private DomainDocumentSegmentRepository domainDocumentSegmentRepository;

    @Autowired
    @Lazy
    private BusinessActionService businessActionService;

    private Map<DocumentType, DocumentLoader> documentLoaders;
    private Map<DocumentType, DomainDocumentReader> documentReaders;

    @Autowired
    public void setDocumentLoaders(List<DocumentLoader> loaderImplementations, List<DomainDocumentReader> readers) {
        documentLoaders = new HashMap<DocumentType, DocumentLoader>();
        if (loaderImplementations != null) {
            for (DocumentLoader loader : loaderImplementations) {
                loader.getSourceType().forEach(type -> documentLoaders.put(type, loader));
            }
        }
        log.info("Initialized Service with loaders: {}", documentLoaders.keySet());
        documentReaders = new HashMap<>();
        if (readers != null) {
            for (DomainDocumentReader reader : readers) {
                reader.getSourceType().forEach(type -> documentReaders.put(type, reader));
            }
        }
        log.info("Initialized Service with readers: {}", documentReaders.keySet());
    }

    /**
     * 初始化领域文档
     */
    @Transactional
    public DomainDocument initDomainDocument(BaseMetadata metadata) {
        DocumentType sourceType = metadata.getDocumentType();
        log.info("Starting ingestion for type: {}", sourceType);

        // 1. 获取加载器
        DocumentLoader loader = documentLoaders.get(sourceType);
        Assert.notNull(loader, "Unsupported source type: " + sourceType);

        // 2. 加载文档
        List<Document> documents = loader.load(metadata);

        // 生成规范化领域文档
        DomainDocument doc = new DomainDocument();
        doc.setSourceId(metadata.getSourceId());
        doc.setDocName(metadata.getSourceName());
        doc.setDocumentType(metadata.getDocumentType());
        // 记录是否流式
        doc.setDocMode(metadata.getDocMode());
        // 记录metadata
        doc.setMetadata(metadata.toMap());
        doc.setStatus(DomainDocumentStatus.CREATED);
        // 记录文档
        DomainDocument saved = domainDocumentRepository.save(doc);

        // 记录文档内容
        documentContextService.saveDocuments(saved.getId(), documents);
        log.info("Initialized DomainDocument id={} for sourceId={} segment size={}", saved.getId(), metadata.getSourceId(),documents.size());
        return saved;
    }

    /**
     * 流式文档内容查询
     *
     * @param docId
     * @return
     */
    public Stream<DocumentContext> getStream(Long docId) {
        DomainDocument domainDocument = getDomainDocument(docId);
        if (domainDocument.getDocMode().equals(DocMode.VIRTUAL)) {
            DomainDocumentReader reader = documentReaders.get(domainDocument.getDocumentType());
            Stream<DocumentContext> baseStream = reader.openContentStream(domainDocument);
            String pipelineJson = (String) domainDocument.getMetadata().get(META_KEY_PIPELINE);
            //数据清洗
            if (StringUtils.hasText(pipelineJson)) {
                List<PipelineConfig> configs = parsePipelineJson(pipelineJson);
                // 简化逻辑：直接遍历并应用，默认都是 Python 脚本
                for (PipelineConfig config : configs) {
                    // 实例化处理器 (注意：Stream close 时需要关闭 Context)
                    PythonScriptProcessor processor = new PythonScriptProcessor(config.getScript());

                    // 使用 flatMap 挂载
                    baseStream = baseStream
                            .flatMap(d -> processor.process(d).stream())
                            .onClose(processor::close);
                }
            }

            return baseStream;
        } else {
            return documentContextService.getDocumentContents(docId).stream();
        }
    }

    /**
     * 导出 Excel (动态表头)
     */
    public void exportToExcel(Long docId, OutputStream outputStream) throws IOException {
        List<DocumentContext> contexts = documentContextService.getDocumentContents(docId);

        // 1. 数据预处理：扁平化所有 Metadata
        List<Map<String, Object>> flatDataList = new ArrayList<>();
        Set<String> allKeys = new LinkedHashSet<>();

        // 优先固定展示的列
        allKeys.add("id");
        allKeys.add("content"); // 核心文本内容

        for (DocumentContext ctx : contexts) {
            Map<String, Object> flatMap = MapUtil.flatten(ctx.getMetadata());

            // 注入核心属性
            flatMap.put("id", ctx.getId()); // 文档片段ID
            flatMap.put("content", ctx.getText());

            flatDataList.add(flatMap);
            allKeys.addAll(flatMap.keySet()); // 收集所有可能的列名
        }

        // 2. 构建 EasyExcel 需要的动态表头
        List<List<String>> heads = new ArrayList<>();
        List<String> sortedKeys = new ArrayList<>(allKeys);
        for (String key : sortedKeys) {
            heads.add(Collections.singletonList(key));
        }

        // 3. 构建数据行
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> data : flatDataList) {
            List<Object> row = new ArrayList<>();
            for (String key : sortedKeys) {
                row.add(data.get(key));
            }
            rows.add(row);
        }

        // 4. 直接写入传入的 outputStream
        EasyExcel.write(outputStream)
                .head(heads)
                .sheet("清洗结果")
                .doWrite(rows);
    }

    /**
     * 导出 SQL 更新脚本
     * @param tableName 目标数据库表名
     * @param pkKey 在 metadata 中代表主键的 key (例如 "sourceId")
     */
    public void exportToSql(Long docId, String tableName, String pkKey, HttpServletResponse response) throws IOException {
        List<DocumentContext> contexts = documentContextService.getDocumentContents(docId);

        response.setContentType("application/sql");
        response.setHeader("Content-Disposition", "attachment; filename=\"update_script_" + docId + ".sql\"");
        response.setCharacterEncoding("UTF-8");

        try (PrintWriter writer = response.getWriter()) {
            writer.println("-- SQL Update Script for Document: " + docId);
            writer.println("-- Target Table: " + tableName);
            writer.println("START TRANSACTION;");

            for (DocumentContext ctx : contexts) {
                Map<String, Object> flatMap = MapUtil.flatten(ctx.getMetadata());

                // 获取主键 (从扁平化 map 中取，或者直接从 ctx.getMetadata() 取)
                Object pkValue = flatMap.get(pkKey);
                if (pkValue == null) {
                    writer.println("-- Skip row: Missing Primary Key (" + pkKey + ")");
                    continue;
                }

                // 构建 UPDATE 语句
                // 策略：只更新 '映射后实体' 相关的字段，避免更新无关的 sourceSystem 等元数据
                List<String> setList = new ArrayList<>();

                // 这里针对你的样例数据做了特殊逻辑：自动识别 extraction 结果
                // 你也可以改为遍历 flatMap 的所有 key 生成全量 update
                if (flatMap.containsKey("映射后实体.entity")) {
                    setList.add(String.format("clean_entity = '%s'", escapeSql(flatMap.get("映射后实体.entity"))));
                }
                if (flatMap.containsKey("映射后实体.category")) {
                    setList.add(String.format("clean_category = '%s'", escapeSql(flatMap.get("映射后实体.category"))));
                }

                if (!setList.isEmpty()) {
                    String sets = String.join(", ", setList);
                    // 假设 PK 是数字，如果是字符串请加单引号
                    writer.printf("UPDATE %s SET %s WHERE id = %s;%n", tableName, sets, pkValue);
                }
            }
            writer.println("COMMIT;");
        }
    }

    private void configureResponse(HttpServletResponse response, String fileName) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + encodedName);
    }

    private String escapeSql(Object val) {
        if (val == null) return "";
        return val.toString().replace("'", "\\'");
    }

    /**
     * 创建衍生文档（应用清洗规则）
     *
     * @param parentId     父文档ID
     * @param pythonScript 用户编写的 Python 清洗脚本
     * @return 新生成的衍生文档 ID
     */
    @Transactional
    public Long createDerivedDocument(Long parentId, String pythonScript) {
        DomainDocument parent = domainDocumentRepository.findById(parentId)
                .orElseThrow(() -> new RuntimeException("Parent doc not found"));
        // 1. 创建衍生文档对象
        DomainDocument derived = parent.clone();
        // 处理元数据
        derived.getMetadata().put(PARENT_ID, parentId);
        // 2. 保存清洗脚本到 Metadata (无论哪种模式都保存，用于血缘和流式回放)
        List<PipelineConfig> pipelines = new ArrayList<>();
        // 优化：流式场景下，如果父文档已有管道配置，需要继承并合并，形成全量规则链路
        // 这样可以确保当前文档包含完整的处理逻辑，注意：读取端 getStream 需适配此全量策略避免重复执行
        if (parent.getDocMode() == DocMode.VIRTUAL) {
            pipelines.addAll(parsePipelineJson((String) parent.getMetadata().get(META_KEY_PIPELINE)));
        }
        // 追加当前脚本 (列表顺序：父级规则在前，当前规则在后)
        pipelines.add(new PipelineConfig(pythonScript));


        //写入规则
        try {
            derived.getMetadata().put(META_KEY_PIPELINE, objectMapper.writeValueAsString(pipelines));
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize pipeline", e);
        }
        // 4. 保存并返回
        Long id = domainDocumentRepository.save(derived).getId();
        //即使是流式文档，目前系统也会写入几条数据用于预览，所以继续进行清洗操作，以便预览效果
        try (PythonScriptProcessor processor = new PythonScriptProcessor(pythonScript)) {
            List<DocumentContext> cleanedContent = new ArrayList<>();
            List<DocumentContext> documentContent;
            if (parent.getDocMode() == DocMode.VIRTUAL) {
                documentContent = documentContextService.getDocumentContentPage(parentId, 0, 20).getContent();
            } else {
                documentContent = documentContextService.getDocumentContents(parentId);
            }
            for (DocumentContext document : documentContent) {
                List<DocumentContext> results = processor.process(document);
                cleanedContent.addAll(results);
            }
            documentContextService.saveDocumentContext(id, cleanedContent);
        }
        return id;
    }

    private List<PipelineConfig> parsePipelineJson(String pipelineJson) {
        List<PipelineConfig> pipelines = new ArrayList<>();
        if (StringUtils.hasText(pipelineJson)) {
            try {
                List<PipelineConfig> parentPipelines = objectMapper.readValue(
                        pipelineJson,
                        new TypeReference<List<PipelineConfig>>() {
                        }
                );
                pipelines.addAll(parentPipelines);
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse parent pipeline", e);
            }
        }
        return pipelines;
    }

    public void triggerVectorization(Long docId) {
        DomainDocument domainDocument = getDomainDocument(docId);
        documentManagementService.ingest(domainDocument);
    }

    /**
     * 获取支持的动作列表
     */
    public Map<BizAction, String> getSupportedActions() {
        return businessActionService.getSupportedActions();
    }

    /**
     * 触发业务动作 (Facade Method)
     * 可以在这里添加领域层面的校验逻辑（如：只有 VIP 租户才能使用 GPT-4）
     */
    public void triggerBusinessAction(Long docId, BizAction actionKey, Map<String, Object> params) {
        DomainDocument doc = getDomainDocument(docId);

        // 1. 领域前置校验
        if (doc.getStatus() == DomainDocumentStatus.PROCESSING) {
            throw new IllegalStateException("文档尚未就绪，无法执行业务操作。当前状态: " + doc.getStatus());
        }

        // 可以在这里扩展策略，例如：
        // if ("GEN_QA".equals(actionKey) && "EXAM".equals(doc.getBusinessType())) { ... }

        // 2. 委托给专业的 Action Service 执行
        log.info("DomainService delegating action [{}] for doc {}", actionKey, docId);
        businessActionService.triggerAction(docId, actionKey, params);
    }

    /**
     * 核心：更新文档内容并标记为 READY
     * 这通常是 ProcessorEngine 处理完毕后的回调
     */
//    @Transactional
//    public void completeProcessing(Long id, List<Document> processedContent, Map<String, Object> newMetadata) {
//        DomainDocument doc = getDomainDocument(id);
//
//        if (processedContent != null) {
//            doc.setDocument(processedContent);
//        }
//        if (newMetadata != null) {
//            doc.getMetadata().putAll(newMetadata);
//        }
//
//        doc.setStatus(DomainDocumentStatus.PROCESSED); // 处理完成
//        domainDocumentRepository.save(doc);
//        log.info("DomainDocument id={} processing completed. Status: READY", id);
//    }

    /**
     * 注册/更新下游业务状态
     * 供 Business Layer (VectorService, QAService) 调用
     * * @param id 文档ID
     *
     * @param serviceKey 业务标识 (e.g., "VECTOR", "QA", "TAG")
     * @param status     状态 (e.g., "PENDING", "COMPLETED", "FAILED")
     */
    @Transactional
    public void updateDownstreamStatus(Long id, String serviceKey, String status) {
//        DomainDocument doc = getDomainDocument(id);
//
//        // 仅在 JSON Map 中更新对应业务的状态
//        // 注意：这里需要先把 Map 拿出来，put 进去，再 save，以触发 JPA 更新
//        Map<String, String> currentStatus = doc.getDownstreamStatus();
//        if (currentStatus == null) {
//            currentStatus = new java.util.HashMap<>();
//        }
//        currentStatus.put(serviceKey, status);
//        doc.setDownstreamStatus(currentStatus); // 显式 set 确保脏检查生效
//
//        domainDocumentRepository.save(doc);
//        log.info("Updated downstream status for doc id={}: {} -> {}", id, serviceKey, status);
    }

    /**
     * 获取文档详情
     */
    public DomainDocument getDomainDocument(Long id) {
        return domainDocumentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("DomainDocument not found: " + id));
    }

    /**
     * 状态流转控制 (通用)
     */
    @Transactional
    public void updateStatus(Long id, DomainDocumentStatus status, String errorMessage) {
        domainDocumentRepository.updateStatus(id, status, errorMessage);
    }

    @Transactional
    public void deleteDomainDocument(Long id) {
        domainDocumentRepository.deleteById(id);
        documentContextService.deleteDocumentContext(id);
        log.info("Deleted DomainDocument id={}", id);
    }

    public List<DomainDocument> list() {
        return domainDocumentRepository.findAll();
    }
}
