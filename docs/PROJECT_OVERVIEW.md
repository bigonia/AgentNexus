# AgentNexus 项目总览

## 定位

AgentNexus 是 Java 17 / Spring Boot 的 SDUI 终端平台后端。它管理设备连接、能力、命令、Section UI、音频和 Node Workflow；AI Agent、MCP、对话与 DrawThings 是可选的平台能力。

系统当前由服务端驱动终端 UI，不包含独立的前端工程，也不包含计费、订单或订阅功能。

## 运行时边界

```text
终端 ── WebSocket (/ws/sdui/v2) ──> SDUI v2 接入层
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
| `sdui/v2/` | LCD_085 目标协议通道（端点 `/ws/sdui/v2`）；旧实现仅作为迁移期兼容路径。 |
| `ai/` | Agent、对话、MCP。 |
| `drawthings/` | 本地 DrawThings 图像生成接入。 |
| `security/` | 认证与 JWT。 |
| `common/` | API 响应、异常与上下文等公共能力。 |

## 权威来源与维护规则

| 主题 | 权威来源 |
| --- | --- |
| HTTP 请求、响应与鉴权 | 运行中的 Swagger/OpenAPI，其次是 `*Controller`。 |
| LCD_085 WebSocket 与二进制帧 | `sdui/v2/` 实现与 `sdui/TERMINAL_CONTRACT.md`；未确认项以 T 编号跟踪。 |
| 设备能力 | 终端 `CapabilitySchemaV2`；旧 `sdui-event-catalog.yml` 仅在目录收敛前兼容。 |
| Section Java 数据模型 | `SectionData`、`SectionDataCodec`。 |
| 工作流执行语义 | `sdui/workflow/` 与 Node Workflow 文档。 |

变更以上来源时，应在同一变更中更新 SDUI 四份活跃文档中的对应内容；设计尚未实现时写入交付台账，不再新增平行提案文档。不要在文档或示例中写入真实凭据。

## 文档导航

- [SDUI 文档入口](sdui/README.md)
- [平台重构指南](sdui/PLATFORM_REFACTOR.md)
- [端云契约](sdui/TERMINAL_CONTRACT.md)
- [交付台账](sdui/DELIVERY_CHECKLIST.md)
- [HTTP API](API_REFERENCE.md)
