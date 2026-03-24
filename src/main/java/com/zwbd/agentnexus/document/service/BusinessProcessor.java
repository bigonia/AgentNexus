package com.zwbd.agentnexus.document.service;

import com.zwbd.agentnexus.document.entity.DomainDocument;
import com.zwbd.agentnexus.document.entity.BizAction;

import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/11/25 11:36
 * @Desc:
 */
public interface BusinessProcessor {

    /**
     * 获取业务动作标识
     * e.g., "VECTORIZE", "GEN_QA", "EXTRACT_TAGS"
     * 用户前端通过此 Key 来触发对应的操作。
     */
    BizAction getActionKey();

    /**
     * 业务名称（用于展示）
     */
    String getActionName();

    /**
     * 执行业务逻辑
     *
     * @param doc    领域文档 (必须是 READY 状态)
     * @param params 用户传递的动态参数 (例如: {"qa_count": 10, "model": "gpt-4"})
     * @throws Exception 处理异常
     */
    void execute(DomainDocument doc, Map<String, Object> params) throws Exception;
}
