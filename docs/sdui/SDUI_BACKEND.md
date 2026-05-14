# SDUI 平台后端集成文档

## 1. 项目概述

AgentNexus SDUI 是 "软件定义 UI" 物理交互终端的服务端平台。终端（ESP32-S3 固件）作为轻量级能力执行器，负责渲染模板、播放音频、读取传感器、上报事件。**所有业务逻辑在服务端完成。**

**技术栈**：Java 17, Spring Boot 3.5.5, JPA/Hibernate, Jackson, WebSocket (Spring WebSocket)

**包结构** (`com.zwbd.agentnexus.sdui`)：

| 包 | 职责 |
|---|---|
| `handler` | WebSocket 二进制帧处理器（Hello/Ack/Error/EventInput） |
| `protocol` | UI3 二进制帧编解码、TLV 读写、能力清单 Schema、ProtocolMapper |
| `section` | Section 编排：12 种 Section 类型、场景/补丁构建与下发、能力适配、预设、自动更新 |
| `dto` | API 数据传输对象 |
| `model` | JPA 实体（SduiDevice, SduiDeviceTelemetry） |
| `repo` | Spring Data JPA Repository |
| `service` | 设备管理、能力快照、协议下发、运维概览 |
| `workflow` | 工作流引擎：定义模型、触发器、Action、变量解析、实例管理、REST 控制器 |

核心入口类：
- `DeviceSessionManager` — WebSocket 会话注册/查找/下发（ConcurrentHashMap: deviceId ↔ session + sessionId → deviceId）
- `SduiManagementController` — 设备管理 REST API
- `SectionOrchestrationService` — Section 场景/补丁下发的编排层
- `WorkflowService` — 工作流加载/卸载/触发/事件分发
- `SduiCapabilityService` — 设备能力快照缓存与查询

---

## 2. 通信协议

### 2.1 传输层

终端与服务端通过 **WebSocket** 通信。两种消息格式：

| 格式 | 用途 |
|---|---|
| **JSON 文本消息** | 能力上报、心跳、控制命令 ACK、旧版 UI（兼容） |
| **UI3 二进制帧** | Section 场景渲染、Section 补丁、执行器命令（主要路径） |

### 2.2 UI3 二进制帧格式

16 字节定长头 + TLV 负载。大端序。

```
字节 0-1:  Magic    (0x5344)
字节 2:    Version  (1)
字节 3:    MsgType
字节 4-7:  Seq
字节 8-11: PayloadLen
字节 12-15: CRC32 (覆盖前 12 字节 + 负载)
```

**下行消息类型** (服务端→终端)：

| MsgType | 说明 | TLV type=31 | TLV type=32 |
|---|---|---|---|
| 15 | Section 场景 | "section" | JSON 场景数据 |
| 16 | Section 补丁 | "section" | JSON 补丁数据 |
| 12 | 执行器命令 | 命令名 (如 "audio.prompt.play") | JSON 参数 |

**上行消息类型** (终端→服务端)：

| MsgType | 说明 | 关键 TLV |
|---|---|---|
| 1 | Hello (能力上报) | type=31→"hello", type=32→JSON 能力清单 |
| 9 | 事件输入 | type=120→事件类型, type=121→节点ID, type=125→时间戳 |
| 8 | ACK | 命令确认回执 |
| 255 | Error | 错误信息 |

### 2.3 TLV 编码

每个 TLV 条目 4 字节头 + 值：

```
字节 0-1: Type (u16)
字节 2-3: Length (u16)
字节 4+:  Value (Length 字节)
```

TLV 支持的类型方法：`addU8`, `addU16`, `addU32`, `addString`, `addJson`, `addBytes`。

### 2.4 JSON 文本消息（兼容）

topic 信封格式：

```json
{
  "topic": "ui/layout",
  "device_id": "A1B2C3D4E5F6",
  "payload": {}
}
```

新功能（Section、工作流）**全部通过二进制帧通信**。JSON 消息仅用于兼容旧版链路（`cmd/control`, `telemetry/heartbeat`, `ui/click` 等）。

---

## 3. REST API 参考

所有 API 前缀：`/api/v1/sdui`

统一响应格式：`ApiResponse<T>` → `{"code": 20000, "data": {...}, "message": "ok"}`

### 3.1 设备管理

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/devices` | 列出所有设备 |
| GET | `/devices/unclaimed` | 列出未认领设备 |
| GET | `/devices/{deviceId}` | 设备详情（含屏幕尺寸、输入模式、可用命令、能力快照） |
| POST | `/devices/{deviceId}/claim` | 认领设备 `{"claimCode":"...", "deviceName":"..."}` |
| GET | `/devices/{deviceId}/telemetry` | 设备遥测历史 |
| POST | `/devices/{deviceId}/control` | 下发控制命令 `{"action":"brightness", "value":80}` |
| GET | `/ops/overview` | 运维概览 |

### 3.2 Section 编排

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/section/presets` | 获取可用预设列表（5 种） |
| POST | `/section/scene/{deviceId}?preset=full_dashboard` | 向设备发送预设场景 |
| POST | `/section/auto/{deviceId}/start?preset=full_dashboard&intervalMs=3000` | 启动自动更新（循环发送随机数据） |
| POST | `/section/auto/{deviceId}/stop` | 停止自动更新 |
| GET | `/section/auto/status` | 查看所有自动更新任务状态 |
| GET | `/section/devices` | 列出设备 |
| GET | `/section/capability/{deviceId}` | 查询设备的 Section 渲染能力 |

5 种预设场景：`hero_dashboard`, `metrics_grid`, `chart_trend`, `full_dashboard`（4 个 Section）, `system_overview`（4 个 Section）。

### 3.3 工作流引擎

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/workflow/definition` | 列出所有工作流定义 |
| GET | `/workflow/definition/{id}` | 获取单个定义 |
| POST | `/workflow/definition` | 创建工作流定义 |
| PUT | `/workflow/definition/{id}` | 更新工作流定义 |
| DELETE | `/workflow/definition/{id}` | 删除工作流定义 |
| GET | `/workflow/node-types` | **获取编辑器节点类型清单**（触发器/动作/Section 类型及参数） |
| POST | `/workflow/{deviceId}/load?definitionId=...` | 向设备加载工作流 |
| POST | `/workflow/{deviceId}/unload` | 卸载设备上的工作流 |
| POST | `/workflow/{deviceId}/trigger/{triggerId}` | 手动触发工作流中的某个触发器 |
| GET | `/workflow/{deviceId}/status` | 查询设备工作流状态 |
| POST | `/webhook/{path}` | Webhook 回调入口（请求体 JSON 作为 trigger payload） |

---

## 4. 数据模型

### 4.1 JPA 实体

**sdui_device** (设备表)：

| 字段 | 类型 | 说明 |
|---|---|---|
| deviceId (PK) | VARCHAR(64) | 设备唯一标识（eFuse MAC） |
| name | VARCHAR(100) | 设备名称 |
| status | VARCHAR(16) | ONLINE / OFFLINE |
| registrationStatus | VARCHAR(24) | UNCLAIMED / CLAIMED |
| claimCode | VARCHAR(12) | 6 位认领码 |
| currentAppId | VARCHAR(64) | 当前应用 ID |
| currentPageId | VARCHAR(64) | 当前页面 ID |
| capabilitiesSnapshot | TEXT | 原始能力清单 JSON |
| lastSeenAt | TIMESTAMP | 最后心跳时间 |
| ownerSpaceId | VARCHAR | 所属空间 |

**sdui_workflow_definition** (工作流定义表)：

| 字段 | 类型 | 说明 |
|---|---|---|
| id (PK) | VARCHAR(64) | 定义 ID |
| name | VARCHAR(100) | 名称 |
| icon | VARCHAR(32) | 图标标识 |
| definitionJson | TEXT | **完整工作流 JSON**（核心字段） |
| createdAt/updatedAt | TIMESTAMP | 时间戳 |

**sdui_workflow_instance** (工作流实例表)：

| 字段 | 类型 | 说明 |
|---|---|---|
| id (PK, UUID) | VARCHAR | 自动生成 |
| deviceId | VARCHAR(64) | 运行中的设备 |
| definitionId | VARCHAR(64) | 引用的定义 ID |
| variablesJson | TEXT | 运行时变量 JSON |
| activePage | VARCHAR(32) | 当前活跃页面 |
| status | VARCHAR(16) | RUNNING / STOPPED |

### 4.2 内存结构（非持久化）

- **WorkflowInstance** — 运行时工作流对象：deviceId, workflowId, variables (LinkedHashMap), activePage, status
- **CapabilitySnapshot 缓存** — `ConcurrentHashMap<deviceId, CapabilitySnapshot>`
- **Trigger 注册表** — `ConcurrentHashMap<taskKey, ScheduledFuture>` (cron) + path→deviceIds map (webhook) + event→deviceIds map (device_event)
- **运行实例映射** — `deviceId → WorkflowInstance`, `deviceId → WorkflowDefinition`

---

## 5. Section 系统

### 5.1 12 种 Section 类型

| 枚举 | Wire Name | 数据字段 | 说明 |
|---|---|---|---|
| HERO | `hero_section` | value, label, subtitle, tone, iconSrc, progress | 主视觉卡片 |
| METRIC | `metric_section` | metrics[] (label, value) | 指标网格 |
| CHART | `chart_section` | title, points[], progress | 折线/柱状图 |
| TIMER | `timer_section` | title, progress, elapsedMs, running | 计时器 |
| IMAGE | `image_section` | iconSrc, title, subtitle | 图片卡片 |
| ACTION | `action_section` | actions[] (id, label, tone, enabled) | 操作按钮组 |
| PROGRESS | `progress_section` | title, progress, progressText | 进度条 |
| TEXT | `text_section` | title, body | 文本块 |
| OVERLAY | `overlay_section` | title, body, tone, unreadCount, autoHideMs, visible | 覆盖层/弹窗 |
| LIST | `list_section` | items[] (id, title, subtitle, tone) | 列表 |
| TOGGLE | `toggle_section` | options[] (id, label, active) | 开关组 |
| NAV | `nav_section` | tabs[] (id, label), activeTab | 导航标签 |

### 5.2 3 种布局模式

| 布局 | Wire Name | 说明 |
|---|---|---|
| VERTICAL_SCROLL | `vertical_scroll` | 垂直滚动（默认） |
| HORIZONTAL_PAGES | `horizontal_pages` | 水平分页滑动 |
| FIXED_SINGLE | `fixed_single` | 固定单页 |

### 5.3 场景 (Scene) 与补丁 (Patch)

**场景** — 全量下发一个页面的所有 Section：

```json
{
  "page_id": "main",
  "layout": "vertical_scroll",
  "auto_scroll": true,
  "auto_scroll_ms": 3000,
  "sections": [
    {
      "type": "hero_section",
      "section_id": "hero_1",
      "data": {"value": "85%", "label": "CPU", "subtitle": "Normal", "tone": "primary", "icon_src": "cpu", "progress": 85}
    }
  ]
}
```

**补丁** — 增量更新单个 Section 数据（不重建整个页面）：

```json
{
  "page_id": "main",
  "patches": [
    {
      "section_id": "hero_1",
      "op": "update",
      "data": {"value": "92%", "label": "CPU", "subtitle": "High", "tone": "warning", "icon_src": "cpu", "progress": 92}
    }
  ]
}
```

补丁操作类型：`update`（替换 Section 数据）、`delete`（移除 Section）、`append`（追加 Section）。

### 5.4 能力适配

`SectionCapabilityAdapter` 根据设备屏幕尺寸（SMALL/MEDIUM/LARGE）和终端声明的能力限制，自动：
- 过滤终端不支持的 Section 类型
- 截断超量数据（metrics 数、chart 点数、列表项数等）
- 降级不支持的布局
- 单页 Section 数量超限时截断

服务端下发前自动完成适配，上层业务代码无需感知。

### 5.5 自动更新调度器

`SectionAutoUpdateScheduler` 支持按固定间隔向设备循环发送随机化数据的预设场景，用于测试和演示。5 种预设各对应不同的随机化逻辑。

---

## 6. 工作流引擎

### 6.1 设计模型

工作流 = "终端应用运行时"。加载一个工作流 = 打开一个 App；切换工作流 = 切换 App。

工作流定义结构：

```
WorkflowDefinition {
  id, name, icon        // 标识
  pages[]               // 页面定义（每个页面含 Section 绑定列表）
  triggers[]            // 触发器列表
  actions{}             // triggerId → Action 列表的映射
}
```

### 6.2 页面定义 (PageDef)

```json
{
  "id": "main",
  "layout": "vertical_scroll",
  "autoScroll": true,
  "autoScrollMs": 3000,
  "sections": [
    {
      "id": "hero_1",
      "type": "hero_section",
      "bind": {
        "value": "$data.unread_count",
        "label": "'未读邮件'",
        "progress": "$data.unread_count"
      }
    }
  ]
}
```

每个 Section 通过 `bind` 映射将变量表达式绑定到 Section 数据字段。

### 6.3 变量与表达式

变量解析器 (`VariableResolver`) 支持以下表达式语法：

| 表达式 | 含义 | 示例 |
|---|---|---|
| `$data.path` | 工作流变量（点号嵌套，支持 `[N]` 数组索引） | `$data.metrics[0].value` |
| `$trigger.field` | 触发器 payload 字段 | `$trigger.from` |
| `$env.VAR` | 环境变量 | `$env.MAIL_API` |
| `'literal'` | 字符串字面量 | `'primary'` |
| `123` | 数字字面量 | `3000` |
| 裸文本 | 原样返回 | `unread_count` |

### 6.4 触发器 (4 种)

```json
{"type": "manual", "id": "t_debug"}
{"type": "cron", "id": "t_refresh", "interval": 60, "cron": null}
{"type": "webhook", "id": "t_hook", "path": "/new-email"}
{"type": "device_event", "id": "t_btn", "event": "action_click"}
```

| 类型 | 说明 | 注册行为 |
|---|---|---|
| manual | 手动触发 | 无运行时行为，仅记录；通过 REST API 手动调用 |
| cron | 定时触发 | 创建 ScheduledFuture，按 interval 秒轮询 |
| webhook | HTTP 回调触发 | 注册到 path→deviceIds 路由表，POST `/api/v1/sdui/webhook/{path}` 时触发 |
| device_event | 设备事件触发 | 注册到 event→deviceIds 路由表，EventInputHandler 收到上行事件时匹配 |

### 6.5 动作 (7 种)

```json
{"type": "fetch", "url": "$env.MAIL_API/inbox", "method": "GET", "body": null, "save": "data"}
{"type": "update_page", "page": "main"}
{"type": "patch_section", "page": "main", "sectionId": "hero_1", "bind": "data"}
{"type": "play_audio", "preset": "notification", "text": null}
{"type": "tts", "text": "$trigger.from 发来邮件"}
{"type": "switch_page", "page": "detail"}
{"type": "control", "command": "audio.prompt.play", "value": "notification"}
```

| 类型 | 说明 |
|---|---|
| fetch | HTTP 请求，结果存入 `$data.{save}` |
| update_page | 全量构建并下发整个页面场景 |
| patch_section | 增量更新单个 Section 数据 |
| play_audio | 播放终端预置音效（preset: notification/alert/success） |
| tts | 文本转语音（标记未实现，当前仅日志） |
| switch_page | 切换工作流实例的活跃页面 |
| control | 向终端发送任意执行器命令 |

### 6.6 动作执行流程

```
Trigger 触发
  → ActionExecutor.execute(actions, instance, triggerPayload, env)
    → 遍历 actions，逐条 dispatch
      → FetchAction: RestTemplate GET → 解析 JSON → instance.putVariable(save, parsed)
      → UpdatePageAction: buildPageScene() → resolve all section binds → sendScene()
      → PatchSectionAction: resolve bind → buildSectionData() → sendPatch()
      → PlayAudioAction: sendActuatorCmd("audio.prompt.play", params)
      → ControlAction: sendActuatorCmd(command, params)
```

### 6.7 工作流加载流程

1. 客户端调用 `POST /workflow/{deviceId}/load?definitionId=...`
2. 从 DB 读取 `WorkflowDefinitionEntity.definitionJson`
3. Jackson 反序列化为 `WorkflowDefinition`
4. 创建 `WorkflowInstance` (deviceId, workflowId, variables)
5. `TriggerScheduler.registerTriggers()` — 注册 cron/webhook/device_event 触发器
6. 下发首页场景到终端
7. 持久化 `WorkflowInstanceEntity` 到 DB

设备只能同时运行一个工作流；加载新工作流会自动卸载旧工作流。

### 6.8 设备事件集成

终端上报的二进制事件帧（MsgType=9）由 `EventInputHandler` 处理：
- 提取 TLV type=120（事件类型）、type=121（节点 ID）、type=125（时间戳）
- 通过 `DeviceSessionManager.getDeviceIdBySessionId()` 反查 deviceId
- 调用 `WorkflowService.fireEvent(deviceId, eventType, payload)`
- 匹配 `device_event` 触发器，执行对应 Action 链

典型的设备事件类型：`action_click`（按钮点击）、`page_changed`（页面切换）、`motion_gesture`（动作手势）。

---

## 7. 能力系统

### 7.1 能力清单结构

终端上线后通过 Hello 帧（MsgType=1）上报能力清单。服务端解析为 `CapabilitySnapshot`：

```java
record CapabilitySnapshot(
    DeviceProfile deviceProfile,   // shape, screenW, screenH, inputMode, autoSleep
    List<InputCapability> inputs,   // name, module, enabled, events[]
    List<OutputCapability> outputs, // name, capability, module, enabled, commands[], legacyTopics[]
    SectionCapability section       // enabled, supportedSectionTypes[], supportedLayouts[], limits{}
)
```

### 7.2 能力查询

`SduiCapabilityService` 提供：
- `getCapabilities(deviceId)` — 从缓存或 DB 获取完整快照
- `supportsSectionType(deviceId, type)` — 是否支持某 Section 类型
- `getAvailableCommands(deviceId)` — 获取设备可用的执行器命令集
- `getDeviceProfile(deviceId)` — 获取设备屏幕/输入 profile

缓存策略：`ConcurrentHashMap` 内存缓存 + DB 持久化。设备离线不失效缓存（需重新注册时刷新）。

---

## 8. 设备生命周期

```
终端 WebSocket 连接
  → Hello 帧上报（含能力清单 JSON）
  → DeviceSessionManager.registerSession(deviceId, session)
  → SduiCapabilityService.onCapabilitiesReport() — 缓存 + 持久化
  → 设备进入 ONLINE 状态

心跳维持：
  → 终端每 30s 上报 telemetry/heartbeat
  → 更新 lastSeenAt

认领流程：
  → POST /devices/{deviceId}/claim {claimCode, deviceName}
  → 校验 6 位认领码 → registrationStatus=CLAIMED

工作流加载：
  → POST /workflow/{deviceId}/load?definitionId=...
  → 触发器和页面启动

断开连接：
  → DeviceSessionManager.removeSession(session)
  → 状态标记为 OFFLINE
  → 不自动卸载工作流（重连后可继续）
```

---

## 9. 集成点与扩展

### 9.1 Trusted Script 集成

工作流引擎预留了 Trusted Script 触发器的集成点。外部脚本系统可以通过以下方式与平台交互：
- 调用 `WorkflowService.triggerManually(deviceId, triggerId)` 触发工作流
- 注册 `SectionTriggerHook` 监听 Section 下发事件
- 通过 Webhook 路径接收外部回调

### 9.2 Hook 机制

`SectionTriggerHook` 接口允许外部组件注册回调，在每次 Section 场景/补丁成功下发后获得通知：

```java
public interface SectionTriggerHook {
    enum TriggerType { SCENE, PATCH, AUTO_UPDATE }
    void onSectionSent(String deviceId, String pageId, TriggerType triggerType, String json);
}
```

### 9.3 前端编辑器

工作流编辑器（Vue3 + vue-flow）通过以下 API 对接：
- `GET /workflow/node-types` — 获取可用节点类型（触发器/动作/Section）及其参数清单
- `GET /workflow/definition` — 获取已有工作流列表
- `POST /workflow/definition` — 保存编辑器输出的工作流 JSON
- `POST /workflow/{deviceId}/load` — 加载工作流到真实设备调试

工作流定义 JSON 格式完全由 `WorkflowDefinition` record 定义，前端直接按 JSON Schema 构造即可。

### 9.4 环境变量

环境变量通过 `Map<String, Map<String, String>> envConfigs` 存储（内存中，key 为 workflow definition ID）。目前通过代码配置，后续可扩展为 REST API 管理。

---

## 10. 构建与运行

```bash
# 开发环境
mvn spring-boot:run

# 打包
mvn clean package -DskipTests

# 测试
mvn test
```

测试文件：
- `WorkflowDefinitionSerializationTest` — 工作流定义 JSON 序列化/反序列化
- `VariableResolverTest` — 变量解析器 10 个测试用例
- `SectionCapabilityAdapterTest` — Section 能力适配测试
- `CapabilitySnapshotParserTest` — 能力快照解析测试
