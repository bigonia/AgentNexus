package com.zwbd.agentnexus.datasource.dialect;

import com.zwbd.agentnexus.common.exception.CommonException;
import lombok.Getter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * @Desc: 数据库会话包装类
 * 封装了 DataSource 和 Dialect，提供自动管理 Connection 的 execute 方法。
 * 实现 AutoCloseable 接口以支持 try-with-resources 语法。
 */
public class DatabaseSession implements AutoCloseable {

    /**
     * 获取原始数据源（仅供特殊场景使用，如手动开启流式上下文）
     */
    @Getter
    private final DataSource dataSource;

    /**
     * 获取方言（仅供特殊场景使用）
     */
    @Getter
    private final DatabaseDialect dialect;

    public DatabaseSession(DataSource dataSource, DatabaseDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    /**
     * 执行数据库操作（自动管理连接的开启和关闭）。
     * 适用于常规的元数据抓取和指标计算。
     *
     * @param callback 具体的业务逻辑
     * @param <R>      返回值类型
     * @return 业务逻辑执行结果
     */
    public <R> R execute(SessionCallback<R> callback) {
        try (Connection connection = dataSource.getConnection()) {
            // 可以在此处统一设置连接属性，如 readOnly
            return callback.doInSession(dialect, connection);
        } catch (SQLException e) {
            throw new CommonException("Database execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * 适配 try-with-resources 语法。
     * 注意：由于 DataSource 是由 DialectFactory 缓存和管理的（通常是连接池），
     * Session 结束并不意味着要关闭 DataSource，因此此处留空。
     * 真正的 Connection 关闭逻辑在 execute 方法内部的 try-with-resources 中处理。
     */
    @Override
    public void close() {
        // No-op
    }

    @FunctionalInterface
    public interface SessionCallback<R> {
        R doInSession(DatabaseDialect dialect, Connection connection) throws SQLException;
    }
}