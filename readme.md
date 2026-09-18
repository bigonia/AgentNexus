# AgentNexus

AgentNexus 是面向智能终端的 Server-Driven UI（SDUI）后端。终端通过 WebSocket 接入，服务端依据终端能力下发 UI、命令和音频，并由 Node Workflow 将终端事件与平台能力编排为可运行流程。

## 快速开始

运行环境：JDK 17、PostgreSQL 15+（使用 RAG 时需 pgvector）。本地配置不要提交真实密码、API Key 或 JWT 密钥；用环境变量或本地覆盖文件注入。

Windows（PowerShell）：

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

macOS / Linux：

```bash
./mvnw test
./mvnw spring-boot:run -Dspring-boot.run.profiles=macos
```

服务默认监听 `8080`。启动后可访问 Swagger UI：`http://localhost:8080/swagger-ui.html`；设备 WebSocket：`ws://localhost:8080/ws/sdui`。

## 文档

| 文档 | 作用 |
| --- | --- |
| [项目总览](docs/PROJECT_OVERVIEW.md) | 系统边界、模块和文档权威性约定。 |
| [SDUI 架构](docs/sdui/ARCHITECTURE.md) | 设备接入、能力目录、Section 与核心服务。 |
| [终端协议](docs/sdui/PROTOCOL.md) | WebSocket、JSON Topic、二进制帧和兼容原则。 |
| [Node Workflow](docs/sdui/NODE_WORKFLOW_INTERACTION_FLOW.md) | 当前工作流模型、API 与运行闭环。 |
| [Node Workflow 上下文](docs/sdui/NODE_WORKFLOW_CONTEXT_REFERENCE.md) | 节点运行上下文与参数解析参考。 |
| [HTTP API](docs/API_REFERENCE.md) | API 分组、版本策略及 Swagger 使用方法。 |
| [LCD_085 重构设计](docs/sdui/lcd085-refactor/2026-09-18/README.md) | 设计提案；不代表当前实现。 |

## 文档权威性

实现行为以 Java 代码、`src/main/resources/sdui-event-catalog.yml` 和运行中的 OpenAPI 为准。Markdown 负责解释设计与使用方式；若与前述来源冲突，应先修正文档。
