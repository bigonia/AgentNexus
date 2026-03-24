package com.zwbd.agentnexus.document.etl.processor;

import com.zwbd.agentnexus.document.entity.DocumentContext;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/12/3 9:33
 * @Desc:
 */

@FunctionalInterface
public interface DocumentProcessor {
    /**
     * 处理单个文档
     *
     * @param document 输入文档
     * @return 处理后的文档列表 (返回空列表则过滤，返回多个元素则拆分)
     */
    List<DocumentContext> process(DocumentContext document);

}
