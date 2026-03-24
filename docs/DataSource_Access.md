# 异构数据源接入模块

## 1. 模块概述
本模块（`com.zwbd.agentnexus.datasource`）专注于解决“数据连接”与“元数据采集”问题。作为 RAG 引擎的上游生产者，它负责适配各种异构数据库，提取高质量的 Schema 信息、统计数据和采样数据。

## 2. 支持的数据源
目前已实现以下 **方言 (Dialect)** 支持：
*   **MySQL**: 支持 5.7 及 8.0+
*   **PostgreSQL**: 支持 PG 12+ (包括 Greenplum 等变体)
*   **SQL Server**: 支持 2012+
*   **扩展接口**: 任何实现了 `DatabaseDialect` 接口的类均可自动被系统识别。

## 3. 核心组件

### 3.1 MetadataCollectorService
位于 `datasource.service`。
*   **功能**: 总协调器。根据用户提供的 JDBC URL 和账号密码，识别数据库类型，并调度相应的 Dialect 进行并行采集。
*   **输出**: 标准化的 `DatabaseMetadata` 对象（包含 Tables, Columns, Indexes, Foreign Keys, Sample Data）。

### 3.2 DynamicContextFactory
位于 `datasource.service`。
*   **功能**: 解决 Java 应用连接多数据源的上下文切换问题。支持在不重启服务的情况下，动态创建、缓存和销毁 JDBC 连接池。

### 3.3 DatabaseDialect 接口
位于 `datasource.dialect`。
开发者若需支持新数据库（如 Oracle），仅需实现此接口：
```java
public interface DatabaseDialect {
    // 获取所有表名
    List<String> getTableNames(Connection conn);
    // 获取列详情
    List<ColumnMetadata> getColumns(Connection conn, String tableName);
    // 获取采样数据
    List<Map<String, Object>> getSampleData(Connection conn, String tableName, int limit);
}
```

## 4. 采集策略
*   **全量采集**: 首次接入时，会对目标库进行全量 Schema 扫描。
*   **采样策略**: 为了保护业务库，默认仅采集前 10-50 行数据用于 AI 理解（如推断字段枚举值），绝不拉取全量业务数据。
*   **异步执行**: 采集任务均为异步 `CompletableFuture` 执行，通过 WebSocket 推送进度。

## 5. 与 RAG 的对接
本模块 **不负责** 向量化。采集完成后，数据流向如下：
1.  `MetadataCollectorService` 产出 `DatabaseMetadata`。
2.  `DocumentConverter` 将其渲染为 Markdown。
3.  封装为 `DomainDocument`。
4.  交给下游 `RAG Engine` 进行处理。
