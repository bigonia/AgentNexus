package com.zwbd.agentnexus.document.service;

import com.zwbd.agentnexus.document.entity.DomainDocument;
import com.zwbd.agentnexus.document.entity.DomainDocumentStatus;
import com.zwbd.agentnexus.document.entity.BizAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @Author: wnli
 * @Date: 2025/11/25 11:35
 * @Desc:
 */
@Slf4j
@Service
public class BusinessActionService {

    private final DomainDocumentService domainDocumentService;
    private final Map<BizAction, BusinessProcessor> processorMap = new ConcurrentHashMap<>();

    /**
     * 自动注入所有实现了 BusinessProcessor 接口的 Bean
     */
    public BusinessActionService(DomainDocumentService domainDocumentService,
                                 List<BusinessProcessor> processors) {
        this.domainDocumentService = domainDocumentService;
        for (BusinessProcessor processor : processors) {
            this.processorMap.put(processor.getActionKey(), processor);
            log.info("Registered Business Processor: [{}] -> {}", processor.getActionKey(), processor.getActionName());
        }
    }

    /**
     * 触发业务操作
     *
     * @param docId     文档ID
     * @param actionKey 动作Key (e.g., "VECTORIZE")
     * @param params    动态参数
     */
    public void triggerAction(Long docId, BizAction actionKey, Map<String, Object> params) {
        // 1. 校验文档是否存在
        DomainDocument doc = domainDocumentService.getDomainDocument(docId);

        // 2. 校验文档状态
        if (doc.getStatus() == DomainDocumentStatus.PROCESSING) {
            throw new IllegalStateException("Document is not READY. Current status: " + doc.getStatus());
        }

        // 3. 获取处理器
        BusinessProcessor processor = processorMap.get(actionKey);
        Assert.notNull(processor, "Unsupported action: " + actionKey);

        // 4. 执行 (execute 方法内部是异步的)
        try {
            processor.execute(doc, params);
        } catch (Exception e) {
            // 虽然 execute 是异步的，但这里捕获的是调用前的同步校验异常
            log.error("Failed to trigger action {} for doc {}", actionKey, docId, e);
//            throw e;
        }
    }

    /**
     * 获取支持的所有动作列表 (供前端下拉框使用)
     */
    public Map<BizAction, String> getSupportedActions() {
        return processorMap.values().stream()
                .collect(Collectors.toMap(BusinessProcessor::getActionKey, BusinessProcessor::getActionName));
    }
}