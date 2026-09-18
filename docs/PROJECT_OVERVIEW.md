# AgentNexus 项目总览

## 定位

AgentNexus 是 Java 17 / Spring Boot 的 SDUI 终端平台后端。它管理设备连接、能力、命令、Section UI、音频和 Node Workflow；AI Agent、MCP、对话与 DrawThings 是可选的平台能力。

系统当前由服务端驱动终端 UI，不包含独立的前端工程，也不包含计费、订单或订阅功能。

## 运行时边界

```text
终端 ── WebSocket (/ws/sdui) ──> SDUI 接入层
                                   ├─ 设备、能力、命令、遥测
                                   ├─ Section 场景 / Patch / 音频
                                   └─ Node Workflow 运行时
                                           └─ 平台能力（TTS/STT、AI、MCP 等）

HTTP 客户端 ── REST / SSE ──> 管理与调试 API
```

主要实现目录：

| 目录 | 职责 |
| --- | --- |
| `sdui/` | 设备 WebSocket、协议、能力目录、Section、工作流、调试与音频。 |
| `ai/` | Agent、对话、MCP。 |
| `drawthings/` | 本地 DrawThings 图像生成接入。 |
| `security/` | 认证与 JWT。 |
| `common/` | API 响应、异常与上下文等公共能力。 |

## 权威来源与维护规则

| 主题 | 权威来源 |
| --- | --- |
| HTTP 请求、响应与鉴权 | 运行中的 Swagger/OpenAPI，其次是 `*Controller`。 |
| WebSocket 与二进制帧 | `SduiWebSocketHandler`、`BinaryProtocolCodec` 和相关 handler。 |
| 命令、事件、Section 字段与约束 | `sdui-event-catalog.yml`。 |
| Section Java 数据模型 | `SectionData`、`SectionDataCodec`。 |
| 工作流执行语义 | `sdui/workflow/` 与 Node Workflow 文档。 |

变更以上来源时，应在同一变更中更新本目录对应 Markdown；设计尚未实现时只放在 `docs/sdui/lcd085-refactor/` 并标注为提案。不要在文档或示例中写入真实凭据。

## 文档导航

- [SDUI 架构](sdui/ARCHITECTURE.md)
- [终端协议](sdui/PROTOCOL.md)
- [Node Workflow](sdui/NODE_WORKFLOW_INTERACTION_FLOW.md)
- [HTTP API](API_REFERENCE.md)
- [LCD_085 重构设计（提案）](sdui/lcd085-refactor/2026-09-18/README.md)
