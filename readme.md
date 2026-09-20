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

服务默认监听 `8080`。启动后可访问 Swagger UI：`http://localhost:8080/swagger-ui.html`；LCD_085 v2 设备 WebSocket：`ws://localhost:8080/ws/sdui/v2`。旧 `/ws/sdui` 仅在迁移期保留。

## 文档

| 文档 | 作用 |
| --- | --- |
| [项目总览](docs/PROJECT_OVERVIEW.md) | 系统边界、模块和文档权威性约定。 |
| [SDUI 文档入口](docs/sdui/README.md) | LCD_085 平台重构的四份权威文档与维护规则。 |
| [平台重构指南](docs/sdui/PLATFORM_REFACTOR.md) | 目标架构、职责边界、工作流闭环与迁移顺序。 |
| [端云契约](docs/sdui/TERMINAL_CONTRACT.md) | v2 协议基线与 T1–T16 终端确认项。 |
| [交付台账](docs/sdui/DELIVERY_CHECKLIST.md) | 已实现、待实现、阻塞项与真机验收矩阵。 |
| [HTTP API](docs/API_REFERENCE.md) | API 分组、版本策略及 Swagger 使用方法。 |

## 文档权威性

实现行为以 Java 代码、`src/main/resources/sdui-event-catalog.yml` 和运行中的 OpenAPI 为准。Markdown 负责解释设计与使用方式；若与前述来源冲突，应先修正文档。
