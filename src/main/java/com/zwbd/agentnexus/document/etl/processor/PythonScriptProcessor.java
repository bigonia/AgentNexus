package com.zwbd.agentnexus.document.etl.processor;

import com.zwbd.agentnexus.document.entity.DocumentContext;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * @Author: wnli
 * @Date: 2025/12/3 9:38
 * @Desc:
 */
@Slf4j
public class PythonScriptProcessor implements DocumentProcessor, AutoCloseable {

    private final String userScript;
    private final Context context;
    private final Value processFunction;

    public PythonScriptProcessor(String userScript) {
        this.userScript = userScript;

        // 1. 初始化安全沙箱
        this.context = Context.newBuilder("python")
                .allowAllAccess(false)       // 禁止所有系统访问
                .allowIO(false)              // 禁止文件读写
                .allowCreateThread(false)    // 禁止多线程
                .allowHostAccess(HostAccess.ALL) // 允许访问传入的 Java 对象
                .build();

        // 2. 加载用户脚本
        try {
            this.context.eval("python", userScript);
            this.processFunction = this.context.getBindings("python").getMember("process");

            if (this.processFunction == null || !this.processFunction.canExecute()) {
                throw new IllegalArgumentException("Python script must define a 'process(doc)' function.");
            }
        } catch (PolyglotException e) {
            throw new RuntimeException("Failed to initialize Python script", e);
        }
    }

    @Override
    public List<DocumentContext> process(DocumentContext document) {
        synchronized (this) {
            try {
                // 3. 执行 Python 函数
                Value result = processFunction.execute(document);

                // 4. 处理返回值 (支持 None, Single Object, List)
                if (result.isNull()) {
                    return Collections.emptyList(); // 过滤
                }

                // 如果返回的是列表 (支持一对多拆分)
                if (result.hasArrayElements()) {
                    List<DocumentContext> docs = new ArrayList<>();
                    for (int i = 0; i < result.getArraySize(); i++) {
                        // 假设列表元素都是 Document 类型
                        // 在实际生产中可能需要更严格的类型检查或转换
                        Value item = result.getArrayElement(i);
                        // GraalVM Host Access 允许直接转换回 Java 对象
                        docs.add(item.as(DocumentContext.class));
                    }
                    return docs;
                }

                // 如果返回单个对象
                return Collections.singletonList(document);

            } catch (PolyglotException e) {
                log.error("Script execution error: ", e);
                // 降级策略：出错则返回原文档，或者根据配置抛出异常
                throw e;
            }
        }
    }

    @Override
    public void close() {
        if (this.context != null) {
            this.context.close();
        }
    }
}
