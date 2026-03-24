package com.zwbd.agentnexus.datasource.service;

import com.zaxxer.hikari.HikariDataSource;
import com.zwbd.agentnexus.datasource.entity.DataBaseInfo;
import com.zwbd.agentnexus.datasource.entity.DataBaseType;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Author: wnli
 * @Date: 2025/11/28 16:32
 * @Desc:
 * 基础设施层：负责动态构建数据库连接和 JOOQ 上下文
 * 实现了无状态的 DSLContext 创建，屏蔽了底层 Driver 差异
 */
@Component
public class DynamicContextFactory {

    // 简单缓存 DataSource，避免每次请求都重新建立连接池带来的开销
    // 实际生产中建议使用 Guava Cache 或 Caffeine 设置过期时间
    private final Map<Long, DataSource> dataSourceCache = new ConcurrentHashMap<>();

    /**
     * 获取 JOOQ DSLContext (核心入口)
     */
    public DSLContext createDsl(DataBaseInfo dbInfo) {
        DataSource dataSource = getOrCreateDataSource(dbInfo);
        SQLDialect dialect = mapToJooqDialect(dbInfo.getType());
        return DSL.using(dataSource, dialect);
    }

    private DataSource getOrCreateDataSource(DataBaseInfo dbInfo) {
        // 使用数据库ID作为缓存Key
        return dataSourceCache.computeIfAbsent(dbInfo.getId(), k -> {
            HikariDataSource ds = DataSourceBuilder.create()
                    .type(HikariDataSource.class)
                    .url(dbInfo.getUrl())
                    .username(dbInfo.getUsername())
                    .password(dbInfo.getPassword())
                    .build();

            // 设置连接池参数，针对探查任务进行限流，防止拖垮业务库
            ds.setMaximumPoolSize(5); // 限制最大连接数
            ds.setConnectionTimeout(5000); // 快速失败
            ds.setReadOnly(true); // 强制只读
            return ds;
        });
    }

    private SQLDialect mapToJooqDialect(DataBaseType type) {
        if (type == null) return SQLDialect.DEFAULT;
        switch (type) {
            case MYSQL: return SQLDialect.MYSQL;
            case POSTGRESQL: return SQLDialect.POSTGRES;
//            case ORACLE: return SQLDialect.ORACLE;
//            case SQLSERVER: return SQLDialect.SQLSERVER;
            default: return SQLDialect.DEFAULT;
        }
    }

    /**
     * 清理缓存（当数据源配置变更时调用）
     */
    public void invalidateCache(Long dbId) {
        DataSource ds = dataSourceCache.remove(dbId);
        if (ds instanceof HikariDataSource) {
            ((HikariDataSource) ds).close();
        }
    }
}
