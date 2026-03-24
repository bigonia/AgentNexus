# 通用 RAG 引擎与向量化模块

## 1. 模块概述
本模块（`com.zwbd.agentnexus.ai`）实现了通用的 **检索增强生成 (RAG)** 引擎。该引擎不再耦合于特定的数据源（如数据库），而是基于统一的 **领域文档 (Domain Document)** 抽象。这意味着无论是数据库的 Schema info，还是用户上传的 PDF/Word/Markdown 文件，都可以被统一向量化并用于 AI 问答。

## 2. 核心架构

```mermaid
graph TD
    subgraph "Input Layer (多种输入源)"
        DB[Database Source] -->|产生| DomainDoc1[领域文档]
        File[File Upload (PDF/MD)] -->|产生| DomainDoc2[领域文档]
        API[External API] -->|产生| DomainDoc3[领域文档]
    end

    DomainDoc1 & DomainDoc2 & DomainDoc3 --> Ingestion[通用摄取服务 IngestionService]

    subgraph "RAG Engine (核心引擎)"
        Ingestion -->|Split| Chunks[语义分片]
        Chunks -->|Embedding| EmbedClient[模型服务 (Ollama/OpenAI)]
        EmbedClient -->|Vector| PGVector[(PGVector 向量库)]
    end

    Query[用户提问] -->|Search| ChatService(ConversationService)
    ChatService -->|Retrieval| PGVector
    ChatService -->|Context| LLM[大语言模型]
    LLM --> Answer[最终回答]
```

## 3. 关键组件

### 3.1 统一输入抽象：DomainDocument
位于 `com.zwbd.agentnexus.document.entity`。
所有下游 RAG 处理只认 `DomainDocument` 对象。
*   **对于数据库**: `MetadataCollectorService` 将采集到的元数据组装成 `DomainDocument`。
*   **对于文件**: `KnowledgeFileService` 将上传的文件（PDF/Excel等）通过 ETL 转换为 `DomainDocument`。

### 3.2 摄取服务 (IngestionService)
位于 `com.zwbd.agentnexus.ai.service`。
*   **功能**: 负责将 `DomainDocument` 进行切片（Splitting）、向量化（Embedding）并持久化。
*   **更新策略**: 支持全量更新和增量更新（根据 sourceId）。

### 3.3 向量存储 (Vector Store)
*   **技术栈**: Spring AI + PostgreSQL (pgvector插件)。
*   **特点**: 
    *   利用 Postgres 的 `hnsw` 索引实现毫秒级向量检索。
    *   Metadata Filtering: 支持按 `business_domain` (租户)、`source_type` (来源类型) 进行精确过滤检索。

## 4. 使用流程

### 4.1 基于文件的 RAG
1.  用户上传文件 -> `KnowledgeFileService.uploadFile`。
2.  触发转换 -> `KnowledgeFileService.toDomainDocument` (生成 DomainDocument)。
3.  触发向量化 -> `DomainDocumentService.triggerVectorization` -> `IngestionService`。

### 4.2 基于数据库的 RAG
1.  配置数据源 -> `MetadataCollectorService` 采集元数据。
2.  生成文档 -> 将元数据 JSON 转换为 Markdown 格式的 `DomainDocument`。
3.  自动触发向量化 -> `IngestionService`。

## 5. 常见问题
*   **重新索引**: 如果切片策略改变，调用 `IngestionService.reIngest(docId)` 即可重新生成向量，无需重新采集原始数据。
*   **模型切换**: 可在 `application.yml` 中配置 `spring.ai.openai.embedding-base-url` 切换底层的 Embedding 模型（如从 OpenAI 切换到本地 Ollama）。
