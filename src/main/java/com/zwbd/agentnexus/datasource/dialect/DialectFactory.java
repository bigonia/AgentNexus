package com.zwbd.agentnexus.datasource.dialect;

import com.zwbd.agentnexus.datasource.entity.DataBaseInfo;
import com.zwbd.agentnexus.datasource.entity.DataBaseType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @Desc: 方言工厂与会话管理器
 * 负责管理无状态的 Dialect 实例以及缓存长生命周期的 DataSource。
 */
@Component
public class DialectFactory {

    // 缓存数据源：Key = 数据库ID, Value = DataSource (HikariCP 连接池)
    // 注意：实际生产中可能需要考虑 DataSource 的销毁和刷新机制
    private final Map<Long, DataSource> dataSourceCache = new ConcurrentHashMap<>();

    // 注册表：Key = 数据库类型, Value = 无状态的方言实现
    private final Map<DataBaseType, DatabaseDialect> dialectRegistry;

    /**
     * 自动注入所有实现了 DatabaseDialect 的 Bean (如 MySQLDialect, PostgreSQLDialect)
     */
    @Autowired
    public DialectFactory(List<DatabaseDialect> dialects) {
        dialectRegistry = dialects.stream()
                .collect(Collectors.toMap(
                        DatabaseDialect::getDataBaseType,
                        Function.identity(),
                        (existing, replacement) -> existing,
                        HashMap::new
                ));
    }

    /**
     * 【核心入口】获取数据库会话。
     * 自动处理 DataSource 的缓存和创建，以及 Dialect 的匹配。
     *
     * @param info 数据库连接信息
     * @return 包含 DataSource 和 Dialect 的会话对象
     */
    public DatabaseSession openSession(DataBaseInfo info) {
        // 1. 获取对应的无状态方言
        DatabaseDialect dialect = dialectRegistry.get(info.getType());
        if (dialect == null) {
            throw new UnsupportedOperationException("没有找到支持 '" + info.getType() + "' 的方言Bean。请检查是否已实现该数据库类型的 Dialect。");
        }

        // 2. 获取或创建 DataSource (线程安全)
        // 使用 computeIfAbsent 确保同一个 DatabaseInfo ID 只创建一个连接池
        DataSource dataSource = dataSourceCache.computeIfAbsent(info.getId(), k -> {
            // 调用方言的方法来创建数据源配置
            return dialect.createDataSource(info);
        });

        // 3. 组装返回
        return new DatabaseSession(dataSource, dialect);
    }

    /**
     * 可选：清除指定数据源缓存（例如连接信息更新后）
     */
    public void invalidateDataSource(Long dbId) {
        DataSource ds = dataSourceCache.remove(dbId);
        if (ds instanceof AutoCloseable) {
            try {
                ((AutoCloseable) ds).close();
            } catch (Exception e) {
                // log error
            }
        }
    }
}