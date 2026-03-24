package com.zwbd.agentnexus.datasource.dialect;

import java.util.stream.Stream;

/**
 * @Author: wnli
 * @Date: 2025/12/1 15:15
 * @Desc:
 * 流式数据上下文。
 * 包装了数据 Stream 和资源关闭钩子，实现了 AutoCloseable 以便支持 try-with-resources。
 */
public class DataStreamContext<T> implements AutoCloseable {

    private final Stream<T> stream;
    private final AutoCloseable resourceCloser;

    public DataStreamContext(Stream<T> stream, AutoCloseable resourceCloser) {
        this.stream = stream;
        this.resourceCloser = resourceCloser;
    }

    public Stream<T> getStream() {
        return stream;
    }

    @Override
    public void close() {
        if (resourceCloser != null) {
            try {
                resourceCloser.close();
            } catch (Exception e) {
                // 记录日志，但不抛出异常中断流程
                // log.error("Error closing data stream resources", e);
            }
        }
    }
}
