# AgentNexus 项目说明

## 1. 项目定位

AgentNexus 当前以 **SDUI 终端平台** 为主线，核心目标是让服务端能够统一管理智能终端设备、识别终端能力、下发动态 UI、接收终端事件，并通过工作流把设备输入、页面变化、音频交互、AI 能力和业务系统串成闭环。

项目的重点不是传统后台管理系统，而是一个面向智能硬件和嵌入式终端的云端运行底座：

- 终端通过 WebSocket 接入平台。
- 终端上报屏幕、输入、输出、音频、RGB、Section 渲染等能力。
- 服务端根据能力生成适配设备的 Section UI。
- 用户操作、按键、触摸、motion、录音等事件回传服务端。
- 工作流根据事件、定时器、Webhook 或人工触发执行动作。
- 执行结果再下发到终端，形成设备交互闭环。

AI Agent、RAG、MCP 和图像生成等模块作为平台辅助能力存在，可被工作流或业务功能调用，但当前项目说明以 SDUI 为核心。

## 2. 主要价格与成本说明

当前仓库未内置正式的商品价格、套餐价格、订单或订阅计费模块。项目本身更接近“终端平台能力底座”，不是一个已经内置商业收费体系的 SaaS 产品。

运行成本主要来自以下几类资源：

| 成本项 | 说明 |
| --- | --- |
| 终端硬件 | ESP32 类设备、屏幕、音频模块、RGB 模块、传感器等硬件成本，仓库内不包含硬件定价。 |
| 服务端资源 | Spring Boot 服务、PostgreSQL 数据库、文件存储、日志与监控资源。 |
| 长连接与消息吞吐 | SDUI 终端依赖 WebSocket，成本与在线设备数、心跳、事件、场景下发和音频流量有关。 |
| AI 模型调用 | 如工作流使用 LLM、RAG、STT、TTS 等能力，则由对应模型服务商计费。 |
| MCP 与外部工具 | 若接入的 MCP Server 或第三方系统收费，则按外部服务规则计算。 |
| 图像生成 | DrawThings 默认连接本地服务，主要消耗本机算力；若改接云端生图服务，则按云端服务计费。 |

## 3. SDUI 核心功能

### 3.1 终端接入与设备管理

- 支持终端通过 WebSocket 建立长连接。
- 支持设备注册、认领、详情查询和在线状态管理。
- 记录设备连接日志、遥测数据、命令记录和运行状态。
- 提供调试接口，用于查看设备能力、下发测试命令和验证终端行为。

主要模块：

| 模块 | 说明 |
| --- | --- |
| `SduiWebSocketHandler` | 终端 WebSocket 连接入口。 |
| `DeviceLifecycleService` | 设备生命周期管理。 |
| `SduiDeviceService` | 设备查询、详情、状态等业务能力。 |
| `ClaimService` | 设备认领。 |
| `TelemetryRetentionService` | 遥测数据保留与清理。 |

### 3.2 终端能力上报

终端连接成功后，通过 `device/capabilities` 上报自身能力。服务端根据能力生成设备画像，并决定后续能下发哪些 UI、命令和工作流节点。

能力信息包括：

| 能力类型 | 示例 |
| --- | --- |
| 屏幕能力 | 宽高、圆屏或方屏、size class。 |
| 输入能力 | 触屏、按键、录音、motion。 |
| 输出能力 | 屏幕亮度、设备重启、音频播放、音量、RGB 灯效。 |
| 渲染能力 | 支持的 Section 类型和布局模式。 |
| 协议能力 | `capability.v2`、`sdui.topic.v1`、`ui3_binary:SECTION_SCENE`。 |

当前重点终端：

| 终端 | 屏幕 | 输入方式 | 典型能力 |
| --- | --- | --- | --- |
| AMOLED_175 | 466×466 圆形屏 | 触屏 | Section 渲染、触摸交互、录音、motion、音频播放、亮度控制。 |
| LCD_085 | 128×128 方形屏 | 按键 | Section 渲染、按键交互、录音、音频播放、RGB 灯效、亮度控制。 |

### 3.3 Section UI 下发与渲染

SDUI 使用 Section 作为终端 UI 的基础单元。服务端构建页面场景后，通过二进制协议下发给设备，终端负责渲染。

支持的典型 Section：

| Section | 用途 |
| --- | --- |
| `hero_section` | 大标题、摘要、图标等主视觉内容。 |
| `metric_section` | 指标数值展示。 |
| `chart_section` | 折线图、柱状图等图表。 |
| `timer_section` | 倒计时或计时器。 |
| `image_section` | 图片展示。 |
| `action_section` | 按钮动作区。 |
| `progress_section` | 进度展示。 |
| `text_section` | 文本内容。 |
| `list_section` | 列表选择。 |
| `toggle_section` | 开关项。 |
| `overlay_section` | 覆盖层内容。 |
| `nav_section` | 导航区域。 |

服务端支持完整场景下发和增量补丁：

| 消息 | 说明 |
| --- | --- |
| `SECTION_SCENE` | 下发完整页面场景。 |
| `SECTION_PATCH` | 下发局部 Section 更新。 |

### 3.4 双通道通信协议

终端和服务端通过一个 WebSocket 连接承载两类消息：

| 通道 | WebSocket 帧 | 用途 |
| --- | --- | --- |
| JSON Topic | TEXT | 能力上报、命令下发、事件上行、心跳。 |
| UI3 Binary | BINARY | Section 场景、Section Patch、音频 PCM、UI 输入事件。 |

UI3 Binary 使用固定头 + TLV payload 的二进制结构，适合 UI 场景、音频和高频输入事件。

主要消息类型：

| 类型 | 方向 | 用途 |
| --- | --- | --- |
| `EVENT_INPUT` | 终端到服务端 | UI 交互、物理按键、motion 等输入事件。 |
| `ACK` | 终端到服务端 | 命令执行确认。 |
| `ERROR` | 终端到服务端 | 命令执行失败。 |
| `SECTION_SCENE` | 服务端到终端 | 完整 UI 场景。 |
| `SECTION_PATCH` | 服务端到终端 | UI 增量补丁。 |
| `AUDIO_PCM` | 服务端到终端 | PCM 音频流播放。 |

### 3.5 命令控制与能力运行时

服务端根据终端能力下发语义化命令，终端执行后回传结果。

典型命令能力：

| 能力 | 说明 |
| --- | --- |
| `display.brightness` | 调节屏幕亮度。 |
| `device.reboot` | 重启设备。 |
| `audio.stream` | 播放服务端下发的 PCM 音频。 |
| `audio.volume` | 设置音量。 |
| `rgb.effect` | 设置 RGB 灯效。 |

相关模块：

| 模块 | 说明 |
| --- | --- |
| `CommandDispatcher` | 命令分发。 |
| `CommandService` | 设备命令业务处理。 |
| `PlatformCapabilityRuntimeService` | 平台能力运行时。 |
| `SduiCapabilityService` | 终端能力查询与适配。 |
| `CapabilityInvocationValidator` | 能力调用校验。 |

### 3.6 工作流编排

SDUI 工作流是项目的核心编排层。它把“事件发生”与“设备动作、页面更新、平台能力调用”连接起来。

工作流支持：

- 定义、校验、保存和反向构建编辑器模型。
- 绑定到指定设备运行。
- 暂停、恢复、解绑和手动触发。
- 记录变量、状态和执行历史。
- 根据设备能力做 strict、adaptive、force 等绑定策略。

触发器类型：

| 类型 | 说明 |
| --- | --- |
| `manual` | 手动触发。 |
| `cron` | 定时触发。 |
| `webhook` | 外部 HTTP 调用触发。 |
| `device.ui.event` | 设备触摸、按键、列表选择等 UI 事件触发。 |
| `device_command` | 设备命令触发。 |
| `device_message` | 设备消息触发。 |

节点类型：

| 类型 | 示例 |
| --- | --- |
| 设备节点 | 控制设备、读取输入、发送消息、更新页面、切换页面、Patch Section、播放音频。 |
| 平台节点 | LLM Chat、RAG 查询、STT、TTS。 |
| 流程节点 | 顺序、并行、条件、循环、变量设置、HTTP Fetch。 |

主要入口：

| 模块 | 路径 |
| --- | --- |
| 工作流编辑与运行 | `/api/v1/sdui/workflows` |
| Webhook 触发 | `/api/v1/sdui/workflows/webhook/{path}` |

### 3.7 音频与语音能力

项目支持终端录音、服务端音频处理和终端音频播放，可与工作流结合形成语音交互。

相关能力：

| 能力 | 说明 |
| --- | --- |
| 录音上行 | 终端采集音频并上报。 |
| STT | 将语音转文本，可由工作流节点调用。 |
| TTS | 将文本转音频，可由工作流节点调用。 |
| PCM 播放 | 服务端通过 `AUDIO_PCM` 下发音频流到终端播放。 |

相关模块：

| 模块 | 说明 |
| --- | --- |
| `AudioService` | 音频业务入口。 |
| `AudioRecordHandler` | 录音处理。 |
| `FfmpegSttProvider` | 基于 ffmpeg/whisper 的 STT。 |
| `FfmpegTtsProvider`、`MacOsTtsEngine` | TTS 实现。 |
| `AudioConversionService` | 音频格式转换。 |

## 4. 其他辅助能力

除 SDUI 主线外，项目还保留以下平台能力：

| 能力 | 简述 |
| --- | --- |
| AI Agent | 支持 Agent 配置、模型选择、工具调用和 SSE 对话。 |
| RAG | 基于 Spring AI 和 pgvector 提供知识检索增强。 |
| MCP | 支持 STDIO 和 SSE 方式接入外部 MCP Server。 |
| 业务空间 | 通过 `BusinessSpace` 做资源隔离。 |
| 安全认证 | 基于 Spring Security 和 JWT。 |
| 图像生成 | 通过 DrawThings 本地服务提供文生图能力。 |

这些能力可被 SDUI 工作流或业务接口复用，但不是本文档的主线。

## 5. 主要 API 入口

| 模块 | 路径 |
| --- | --- |
| SDUI 设备 | `/api/v1/sdui/devices` |
| SDUI 能力 | `/api/v1/sdui/capabilities` |
| SDUI 调试 | `/api/v1/sdui/debug` |
| SDUI 工作流 | `/api/v1/sdui/workflows` |
| Agent | `/api/v1/agent` |
| MCP | `/api/v1/mcp` |
| Chat Client | `/api/v1/chat-client` |
| Conversation | `/api/ai/conversations` |
| RAG | `/api/v1/rag` |
| 文档 | `/api/documents` |

## 6. 技术栈与运行依赖

| 类型 | 内容 |
| --- | --- |
| 后端框架 | Spring Boot 3.5.5 |
| Java 版本 | JDK 17+ |
| AI 框架 | Spring AI 1.0.3 |
| 数据库 | PostgreSQL，建议开启 pgvector |
| 持久层 | Spring Data JPA、Spring JDBC |
| 安全 | Spring Security、JWT |
| API 文档 | springdoc-openapi |
| 通信 | HTTP、SSE、WebSocket、二进制 TLV 协议 |
| 音频工具 | ffmpeg、whisper 可选 |
| 图像生成 | DrawThings 本地服务可选 |

## 7. 本地启动

开发环境可使用 Maven 启动：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=macos
```

或打包后运行：

```bash
mvn clean package -DskipTests
java -jar target/AgentNexus-0.0.1-SNAPSHOT.jar --spring.profiles.active=macos
```

默认服务端口为 `8080`。启动前需要确认 PostgreSQL 连接、模型 API 配置、pgvector 表结构和可选外部工具已经准备好。

## 8. 参考文档

- `docs/sdui/terminal/PROTOCOL_AND_COMMANDS.md`：终端协议与命令说明。
- `docs/sdui/terminal/CAPABILITY_REPORTING.md`：终端能力上报说明。
- `docs/sdui/terminal/SECTION_SCHEMA.md`：Section Schema 说明。
- `docs/sdui/front/WORKFLOW_BUSINESS_GUIDE.md`：SDUI 工作流业务说明。
- `docs/sdui/front/WORKFLOW_API_REFERENCE.md`：SDUI 工作流 API 参考。
- `docs/api/ai-api-reference.md`：AI 辅助能力 API 说明。
