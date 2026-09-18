# HTTP API 参考

## 权威契约

启动应用后访问 `/swagger-ui.html`。它是 HTTP API 的权威契约，包含请求模型、响应模型和当前可用操作；本文件仅提供稳定的分组入口，避免重复维护每个字段。

大部分接口返回 `ApiResponse<T>`。SSE 接口返回 `text/event-stream`，个别历史接口的返回包装可能不同，应以 OpenAPI 和控制器声明为准。

## API 分组

| 前缀 | 职责 |
| --- | --- |
| `/api/auth` | 登录、登出和当前用户信息。 |
| `/api/v1/sdui/devices` | 设备、认领、遥测、连接日志与命令查询。 |
| `/api/v1/sdui/capabilities` | 单设备能力、树、事件、Section、命令与协议视图。 |
| `/api/v1/sdui/board-types` | 板型及其可用命令、事件、Section 和节点。 |
| `/api/v1/sdui/events` | 全局事件/命令/Section 目录与校验。 |
| `/api/v1/sdui/debug` | 命令、Section、输入节点、会话、产物与 SSE 调试。 |
| `/api/v1/sdui/node-workflows` | 工作流定义、部署、运行与运维。 |
| `/api/v1/sdui/ui-templates` | UI 模板 CRUD 与预览。 |
| `/api/v1/sdui/artifacts` | 工作流产物及二进制下载。 |
| `/api/v1/agent`、`/api/v1/chat-client` | Agent 与对话流。 |
| `/api/v1/mcp` | MCP 连接管理。 |
| `/api/ai/conversations` | 会话与消息。 |
| `/api/v1/drawthings/image` | 本地 DrawThings 图像生成。 |

## 使用与维护

- 客户端应先按设备或板型读取能力目录，再构造命令、Section 和工作流请求。
- 新增、删除或改名 Controller 路由时，更新本表并核验 Swagger。
- 不再维护已移除的数据源、文件和文档管理 API 的静态说明。
