# SDUI 前端开发指南

> 版本: 1.0  
> 最后更新: 2026-05-14  
> 目标读者: 前端开发工程师  
> 涵盖内容: 设备管理、注册认领、实时通信、Section UI 渲染、工作流编排

---

## 目录

1. [系统概述](#1-系统概述)
2. [快速开始](#2-快速开始)
3. [设备生命周期](#3-设备生命周期)
4. [REST API 参考](#4-rest-api-参考)
5. [WebSocket 协议](#5-websocket-协议)
6. [Section UI 场景协议](#6-section-ui-场景协议)
7. [工作流系统](#7-工作流系统)
8. [完整交互流程](#8-完整交互流程)
9. [数据模型参考](#9-数据模型参考)
10. [错误处理](#10-错误处理)

---

## 1. 系统概述

SDUI（Smart Device UI）是一个物联网设备管理与 UI 渲染系统。核心能力：

- **设备管理**: 设备上线、注册认领、状态监控、遥测采集
- **Section UI**: 服务端定义 UI 场景，二进制帧下发到设备端渲染，支持增量更新
- **工作流编排**: 定义触发条件→动作序列，自动化设备交互
- **多租户**: 通过 `X-Space-Id` 请求头隔离不同空间/租户的数据

### 架构示意

```
┌──────────────┐       WebSocket        ┌──────────────────┐
│   设备终端    │ ◄─────────────────────► │   后端服务        │
│  (ESP32等)   │   二进制帧 + JSON       │   (Spring Boot)  │
└──────────────┘                         └────────┬─────────┘
                                                   │
                                          REST API │ X-Space-Id
                                                   │
                                          ┌────────┴─────────┐
                                          │   前端管理台      │
                                          │   (Web UI)       │
                                          └──────────────────┘
```

---

## 2. 快速开始

### 2.1 基础配置

所有 REST API 请求需携带以下请求头：

```
Content-Type: application/json
X-Space-Id: <空间ID>       # 多租户隔离，未传时默认 "default"
```

### 2.2 通用响应格式

```typescript
interface ApiResponse<T> {
  code: number;        // 200 = 成功，其他 = 错误码
  message: string;     // 提示信息
  data: T;             // 业务数据
}
```

成功响应示例:
```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

错误响应示例:
```json
{
  "code": 40400,
  "message": "设备不存在: ABC123",
  "data": null
}
```

---

## 3. 设备生命周期

### 3.1 状态机

```
                    ┌──────────┐
       设备上电      │          │
    ──────────────► │  UNCLAIMED │  (自动生成6位认领码, 15分钟有效)
                    │  ONLINE   │
                    └────┬─────┘
                         │ 用户提交认领码
                         │ POST /claim
                         ▼
                    ┌──────────┐
                    │  CLAIMED  │  (绑定到当前 Space)
                    │  ONLINE   │
                    └────┬─────┘
                         │ 90秒无心跳
                         ▼
                    ┌──────────┐
                    │  CLAIMED  │
                    │  OFFLINE  │
                    └──────────┘
```

两个正交的状态维度:

| 维度 | 字段 | 可能值 | 含义 |
|------|------|--------|------|
| 连接状态 | `status` | `ONLINE` / `OFFLINE` | 是否有活跃 WebSocket 连接 |
| 注册状态 | `registrationStatus` | `UNCLAIMED` / `CLAIMED` | 是否已认领到某个空间 |

### 3.2 关键行为差异

| 行为 | UNCLAIMED | CLAIMED |
|------|-----------|---------|
| 心跳记录遥测 | ❌ 不记录 | ✅ 存入 `sdui_device_telemetry` |
| 认领码 | ✅ 自动生成(15分钟TTL) | ❌ 已清除 |
| 控制指令下发 | ❌ 不可用 | ✅ 可用 |
| Section 场景推送 | ❌ 不可用 | ✅ 可用 |
| 列表可见性 | 所有空间可见(未认领列表) | 仅所属空间可见 |

---

## 4. REST API 参考

> 基础路径: `/api/v1/sdui`

### 4.1 设备管理

#### 4.1.1 获取已认领设备列表

```
GET /api/v1/sdui/devices
```

响应:
```typescript
ApiResponse<SduiDevice[]>
```

```json
{
  "code": 200,
  "data": [
    {
      "deviceId": "AABBCCDDEEFF",
      "name": "客厅温湿度计",
      "status": "ONLINE",
      "registrationStatus": "CLAIMED",
      "currentPageId": "dashboard_v1",
      "lastSeenAt": "2026-05-14T10:30:00",
      "claimedAt": "2026-05-10T08:00:00",
      "createdAt": "2026-05-10T08:00:00"
    }
  ]
}
```

#### 4.1.2 获取未认领设备列表

```
GET /api/v1/sdui/devices/unclaimed
```

响应与已认领设备结构相同，仅包含 `registrationStatus = "UNCLAIMED"` 的设备。此接口不过滤 Space，所有未认领设备对所有空间可见。

#### 4.1.3 获取设备详情

```
GET /api/v1/sdui/devices/{deviceId}
```

响应:
```typescript
ApiResponse<SduiDeviceDetailResponse>
```

```json
{
  "code": 200,
  "data": {
    "deviceId": "AABBCCDDEEFF",
    "name": "客厅温湿度计",
    "status": "ONLINE",
    "registrationStatus": "CLAIMED",
    "board": "esp32-s3",
    "screenShape": "round",
    "screenWidth": 240,
    "screenHeight": 240,
    "inputMode": "touch",
    "availableCommands": [
      "display.section.render",
      "display.section.patch",
      "display.brightness.set",
      "audio.prompt.play",
      "audio.volume.set",
      "rgb.effect.set",
      "rgb.off",
      "device.reboot"
    ],
    "capabilitiesSnapshot": "{...}",
    "lastSeenAt": "2026-05-14T10:30:00",
    "claimedAt": "2026-05-10T08:00:00",
    "createdAt": "2026-05-10T07:55:00"
  }
}
```

`availableCommands` 表示该设备支持的指令列表，前端可据此动态渲染控制面板。

#### 4.1.4 认领设备

```
POST /api/v1/sdui/devices/{deviceId}/claim
```

请求:
```typescript
interface SduiClaimDeviceRequest {
  claimCode: string;     // 必填, 4-12个字符, 设备屏幕显示的6位码
  deviceName?: string;   // 选填, 最多100个字符, 给设备起个名字
}
```

```json
{
  "claimCode": "A1B2C3",
  "deviceName": "客厅温湿度计"
}
```

验证规则:
- `claimCode` 必须与设备当前认领码匹配 (不区分大小写)
- 认领码有效期为 15 分钟，过期需等待设备下一次心跳自动刷新
- 设备不能被其他空间认领

成功响应:
```json
{
  "code": 200,
  "data": {
    "deviceId": "AABBCCDDEEFF",
    "name": "客厅温湿度计",
    "registrationStatus": "CLAIMED",
    ...
  }
}
```

错误响应:
```json
{
  "code": 40000,
  "message": "认领码错误"
}
```

#### 4.1.5 获取设备遥测数据

```
GET /api/v1/sdui/devices/{deviceId}/telemetry
```

响应: 最近 50 条遥测数据 (按时间倒序)

```json
{
  "code": 200,
  "data": [
    {
      "id": 1234,
      "deviceId": "AABBCCDDEEFF",
      "wifiRssi": -45,
      "temperature": 32.5,
      "freeHeapInternal": 123456,
      "freeHeapTotal": 200000,
      "uptimeS": 3600,
      "createdAt": "2026-05-14T10:30:00"
    },
    ...
  ]
}
```

#### 4.1.6 下发控制指令

```
POST /api/v1/sdui/devices/{deviceId}/control
```

请求:
```typescript
interface SduiDeviceControlRequest {
  command: string;  // 必填, 语义化指令名
  value?: number;   // 选填, 0-100 之间的数值
}
```

```json
{
  "command": "display.brightness.set",
  "value": 75
}
```

可用指令列表 (`command` 字段):

| 指令 | 说明 | 是否需要 value |
|------|------|---------------|
| `display.section.render` | 触发 Section UI 渲染 | 否 |
| `display.section.patch` | 触发 Section UI 增量更新 | 否 |
| `display.layout.render` | 触发 Layout UI 渲染 | 否 |
| `display.layout.patch` | 触发 Layout UI 增量更新 | 否 |
| `display.brightness.set` | 设置屏幕亮度 | 是 (0-100) |
| `audio.prompt.play` | 播放提示音 | 否 |
| `audio.stream.play` | 播放音频流 | 否 |
| `audio.volume.set` | 设置音量 | 是 (0-100) |
| `rgb.effect.set` | 设置 RGB 灯效 | 否 |
| `rgb.off` | 关闭 RGB | 否 |
| `device.reboot` | 重启设备 | 否 |

注意: 前端应通过设备详情的 `availableCommands` 字段动态展示可用指令，而非硬编码上述列表。

响应:
```json
{
  "code": 200,
  "data": {
    "sent": true,
    "deviceId": "AABBCCDDEEFF",
    "command": "display.brightness.set",
    "cmdId": "uuid-string",
    "requestedValue": 75,
    "status": "SENT"
  }
}
```

指令状态生命周期:

```
SENT ──► ACKED     (设备成功执行)
     ├─► REJECTED  (设备拒绝执行)
     ├─► ERROR     (设备执行出错)
     ├─► TIMEOUT   (10秒内未收到ACK)
     └─► FAILED    (设备离线, 发送失败)
```

### 4.2 运维概览

```
GET /api/v1/sdui/ops/overview
```

响应:
```json
{
  "code": 200,
  "data": {
    "totalDevices": 150,
    "onlineDevices": 120,
    "offlineDevices": 30,
    "sentCommands": 500,
    "failedCommands": 3,
    "ackedCommands": 480,
    "rejectedCommands": 5,
    "errorCommands": 2,
    "timeoutCommands": 10
  }
}
```

### 4.3 Section UI 场景 (测试/调试用)

> 基础路径: `/api/v1/sdui/section`

#### 4.3.1 获取预设场景列表

```
GET /api/v1/sdui/section/presets
```

响应:
```json
{
  "code": 200,
  "data": {
    "presets": ["hero_dashboard", "metrics_grid", "chart_trend", "full_dashboard", "system_overview"]
  }
}
```

#### 4.3.2 发送预设场景到设备

```
POST /api/v1/sdui/section/scene/{deviceId}?preset=full_dashboard
```

响应:
```json
{
  "code": 200,
  "data": {
    "sent": true,
    "deviceId": "AABBCCDDEEFF",
    "preset": "full_dashboard",
    "pageId": "full_dashboard_v1",
    "sections": 4
  }
}
```

#### 4.3.3 启动自动更新

```
POST /api/v1/sdui/section/auto/{deviceId}/start?preset=full_dashboard&intervalMs=3000
```

参数:
- `preset`: 预设场景名 (默认 `full_dashboard`)
- `intervalMs`: 推送间隔，最小 500ms (默认 3000ms)

#### 4.3.4 停止自动更新

```
POST /api/v1/sdui/section/auto/{deviceId}/stop
```

#### 4.3.5 查看自动更新状态

```
GET /api/v1/sdui/section/auto/status
```

响应:
```json
{
  "code": 200,
  "data": [
    {
      "deviceId": "AABBCCDDEEFF",
      "presetName": "full_dashboard",
      "intervalMs": 3000,
      "running": true
    }
  ]
}
```

#### 4.3.6 查看设备 Section 能力

```
GET /api/v1/sdui/section/capability/{deviceId}
```

响应:
```json
{
  "code": 200,
  "data": {
    "hasSection": true,
    "deviceId": "AABBCCDDEEFF",
    "capability": {
      "enabled": true,
      "commands": ["display.section.render", "display.section.patch"],
      "transport": "binary",
      "supportedSectionTypes": [
        "hero_section", "metric_section", "chart_section",
        "action_section", "progress_section", "text_section",
        "overlay_section", "list_section", "toggle_section", "nav_section"
      ],
      "supportedLayouts": ["vertical_scroll", "horizontal_pages", "fixed_single"],
      "limits": {
        "max_sections_per_page": 8,
        "max_metrics": 4,
        "max_points": 16
      }
    }
  }
}
```

### 4.4 工作流管理

> 基础路径: `/api/v1/sdui/workflow`

#### 4.4.1 获取工作流定义列表

```
GET /api/v1/sdui/workflow/definition
```

#### 4.4.2 获取单个工作流定义

```
GET /api/v1/sdui/workflow/definition/{id}
```

#### 4.4.3 创建工作流定义

```
POST /api/v1/sdui/workflow/definition
```

请求体: `WorkflowDefinitionEntity` (JSON), 见 [工作流系统](#7-工作流系统)

#### 4.4.4 更新工作流定义

```
PUT /api/v1/sdui/workflow/definition/{id}
```

#### 4.4.5 删除工作流定义

```
DELETE /api/v1/sdui/workflow/definition/{id}
```

#### 4.4.6 获取可用节点类型

```
GET /api/v1/sdui/workflow/node-types
```

返回所有可用的触发器、动作、Section 类型及其参数描述，用于前端绘制工作流编辑器。

#### 4.4.7 加载工作流到设备

```
POST /api/v1/sdui/workflow/{deviceId}/load?definitionId=xxx
```

#### 4.4.8 卸载工作流

```
POST /api/v1/sdui/workflow/{deviceId}/unload
```

#### 4.4.9 手动触发

```
POST /api/v1/sdui/workflow/{deviceId}/trigger/{triggerId}
```

#### 4.4.10 获取设备工作流状态

```
GET /api/v1/sdui/workflow/{deviceId}/status
```

#### 4.4.11 Webhook 触发

```
POST /api/v1/sdui/webhook/{path}
```

外部系统通过此端点 POST JSON 来触发指定路径对应的工作流。

---

## 5. WebSocket 协议

### 5.1 连接

```
ws://<host>:8080/ws/sdui
wss://<host>:8443/ws/sdui

可选参数: ?spaceId=xxx
```

设备通过 WebSocket 与后端保持长连接。前端管理台无需建立 WebSocket (使用 REST API 即可)。

**Space 解析优先级**:
1. 数据库中设备已绑定的 `ownerSpaceId`
2. 心跳消息中的 `space_id` 字段
3. WebSocket URL query 参数 `?spaceId=xxx`
4. WebSocket 握手请求头 `X-Space-Id`
5. 默认 `"default"`

### 5.2 通信方式

设备与后端之间支持两种通信方式:

#### 方式一: 二进制帧 (主要方式, 推荐)

**帧格式 (16字节头 + TLV Payload, Little-Endian 字节序)**:

| 偏移 | 大小 | 字段 | 说明 |
|------|------|------|------|
| 0 | 2 | Magic | 固定 `0x5344` |
| 2 | 1 | Version | `0x01` |
| 3 | 1 | msgType | 消息类型 |
| 4 | 4 | seq | 序列号 (int32) |
| 8 | 4 | payloadLen | Payload 长度 |
| 12 | 4 | CRC32 | 对 byte[0..11] + 4 零字节 + Payload 的 CRC32 |

**TLV 结构 (每个 TLV 条目)**:

| 偏移 | 大小 | 字段 |
|------|------|------|
| 0 | 2 | Type (uint16 LE) |
| 2 | 2 | Length (uint16 LE) |
| 4 | Length | Value |

**已知消息类型**:

| msgType | 方向 | 说明 | 关键 TLV |
|---------|------|------|----------|
| 1 | 上行 | HELLO / 设备注册 | 100: deviceId (string) |
| 9 | 上行 | 事件输入 | 120: 事件类型, 121: 节点ID |
| 10 | 上行 | ACK 应答 | 200: 状态码(uint16), 201: 详情 |
| 11 | 上行 | 错误报告 | 200: 状态码, 201: 错误信息 |
| 12 | 下行 | 控制指令 | 31: 指令名, 32: 参数JSON |
| 15 | 下行 | Section Scene 场景 | 31: "section", 32: 场景JSON |
| 16 | 下行 | Section Patch 增量更新 | 31: "section", 32: Patch JSON |

#### 方式二: JSON 文本消息 (逐步废弃, 仍支持)

```json
{
  "topic": "telemetry/heartbeat",
  "device_id": "AABBCCDDEEFF",
  "space_id": "default",
  "payload": { ... }
}
```

**JSON 支持的上行 Topic**:

| Topic | 说明 | Payload |
|-------|------|---------|
| `telemetry/heartbeat` | 心跳 | `{ wifi_rssi, temperature, free_heap_internal, free_heap_total, uptime_s }` |
| `ui/page_changed` | 页面切换 | `{ page: "dashboard_v1" }` |
| `device/capabilities` | 能力上报 | 见下方能力上报结构 |
| `cmd/control_ack` | 控制指令应答 | `{ cmd_id, status: "ACKED"\|"REJECTED"\|"ERROR", reason }` |

---

## 6. Section UI 场景协议

Section UI 是后端构建 UI 场景、设备端渲染的协议。前端不直接构建场景 JSON，而是通过工作流定义来编排场景内容。

### 6.1 场景 (Scene) JSON 结构

下发到设备渲染的完整场景:

```json
{
  "page_id": "full_dashboard_v1",
  "layout": "vertical_scroll",
  "auto_scroll": true,
  "auto_scroll_ms": 3000,
  "sections": [
    {
      "type": "hero_section",
      "section_id": "cpu_hero",
      "data": {
        "value": "85%",
        "label": "CPU Usage",
        "subtitle": "Running Normal",
        "tone": "primary",
        "icon_src": "cpu",
        "progress": 85
      }
    }
  ]
}
```

### 6.2 增量更新 (Patch) JSON 结构

```json
{
  "page_id": "full_dashboard_v1",
  "patches": [
    {
      "section_id": "cpu_hero",
      "op": "update",
      "data": {
        "value": "92%",
        "label": "CPU Usage",
        "subtitle": "High Load",
        "tone": "warning",
        "icon_src": "cpu",
        "progress": 92
      }
    }
  ]
}
```

### 6.3 Section 类型与数据结构

#### Hero Section (`hero_section`) - 主指标卡片

```typescript
interface HeroData {
  value: string;      // 主值, 如 "85%"
  label: string;      // 标签, 如 "CPU Usage"
  subtitle: string;   // 副标题, 如 "Running Normal"
  tone: string;       // 色调: "primary" | "success" | "warning" | "danger" | "info"
  iconSrc: string;    // 图标标识, 如 "cpu"
  progress: number;   // 进度 0-100
}
```

#### Metric Section (`metric_section`) - 指标列表

```typescript
interface MetricData {
  metrics: Array<{
    label: string;    // 指标名, 如 "Memory"
    value: string;    // 指标值, 如 "64%"
  }>;
}
```

#### Chart Section (`chart_section`) - 趋势图

```typescript
interface ChartData {
  title: string;          // 图表标题
  points: number[];       // 数据点, 如 [30, 45, 60, 55, 70, 85, 80, 92]
  progress: number;       // 当前进度 0-100
}
```

#### Timer Section (`timer_section`) - 计时器

```typescript
interface TimerData {
  title: string;
  progress: number;
  timer: {
    elapsedMs: number;    // 已耗时(毫秒)
    running: boolean;     // 是否运行中
  };
}
```

#### Image Section (`image_section`) - 图片展示

```typescript
interface ImageData {
  iconSrc: string;
  title: string;
  subtitle: string;
}
```

#### Action Section (`action_section`) - 操作按钮组

```typescript
interface ActionData {
  actions: Array<{
    id: string;           // 按钮唯一标识
    label: string;        // 按钮文字
    tone: string;         // 色调: "primary" | "success" | "warning" | "danger" | "info"
    enabled: boolean;     // 是否可点击
  }>;
}
```

#### Progress Section (`progress_section`) - 进度条

```typescript
interface ProgressData {
  title: string;
  progress: number;       // 0-100
  progressText: string;   // 进度文本, 如 "75%"
}
```

#### Text Section (`text_section`) - 文本块

```typescript
interface TextData {
  title: string;
  body: string;
}
```

#### Overlay Section (`overlay_section`) - 覆层通知

```typescript
interface OverlayData {
  title: string;
  body: string;
  tone: string;
  unreadCount: number;
  autoHideMs: number;     // 自动隐藏毫秒数, 0 = 不自动隐藏
  visible: boolean;
}
```

#### List Section (`list_section`) - 列表

```typescript
interface ListData {
  items: Array<{
    id: string;
    title: string;
    subtitle: string;
    tone: string;
  }>;
}
```

#### Toggle Section (`toggle_section`) - 开关组

```typescript
interface ToggleData {
  options: Array<{
    id: string;
    label: string;
    active: boolean;
  }>;
}
```

#### Nav Section (`nav_section`) - 导航标签

```typescript
interface NavData {
  tabs: Array<{
    id: string;
    label: string;
  }>;
  activeTab: number;      // 当前激活的 Tab 索引 (从0开始)
}
```

### 6.4 布局类型

| 枚举值 | 线传值 (wireName) | 说明 |
|--------|-------------------|------|
| `VERTICAL_SCROLL` | `vertical_scroll` | 垂直滚动 |
| `HORIZONTAL_PAGES` | `horizontal_pages` | 水平翻页 |
| `FIXED_SINGLE` | `fixed_single` | 固定单页 |

### 6.5 设备能力适配

设备上报的能力快照包含 Section 限制。服务端下发场景时会自动适配:

1. **过滤不支持的类型**: 只保留设备 `supportedSectionTypes` 中的 section
2. **截断数据量**: 按设备 `limits` 截断 metrics/points/actions/items 数量
3. **回退布局**: 如果设备不支持请求的 layout，自动回退到 `vertical_scroll`
4. **屏幕尺寸分级**: SMALL (< 200px) / MEDIUM / LARGE (≥ 400px)，不同级别有不同的数据上限

设备能力限制对照表:

| 限制项 | SMALL | MEDIUM | LARGE |
|--------|-------|--------|-------|
| max_metrics | 4 | 6 | 8 |
| max_points (图表) | 8 | 16 | 32 |
| max_actions (按钮) | 2 | 3 | 4 |
| max_list_items | 3 | 5 | 8 |
| max_toggle_options | 3 | 5 | 6 |
| max_nav_tabs | 4 | 5 | 6 |
| max_text_chars | 0 (不支持) | 80 | 300 |
| max_overlay_body | 0 (不支持) | 100 | 300 |

---

## 7. 工作流系统

### 7.1 概念模型

工作流 = 触发器(Trigger) + 动作列表(Actions) + 页面定义(Pages)

```
┌──────────────┐    触发     ┌─────────────────────┐
│   Trigger    │ ──────────► │   Actions (顺序执行)  │
│              │             │  1. fetch HTTP API   │
│ - manual     │             │  2. patch_section    │
│ - cron       │             │  3. play_audio       │
│ - webhook    │             │  4. switch_page      │
│ - device_event│            │  5. control          │
└──────────────┘             └─────────────────────┘
```

### 7.2 工作流定义结构

```typescript
interface WorkflowDefinition {
  id: string;                           // 唯一标识
  name: string;                         // 名称
  icon?: string;                        // 图标
  pages: PageDef[];                     // 页面定义列表
  triggers: TriggerDef[];               // 触发器列表
  actions: Record<string, ActionDef[]>; // triggerId -> 动作列表
}
```

### 7.3 触发器类型

```typescript
type TriggerDef =
  | { type: "manual";       id: string }
  | { type: "cron";         id: string; interval?: number; cron?: string }
  | { type: "webhook";      id: string; path: string }
  | { type: "device_event"; id: string; event: string }
```

| 类型 | 说明 | 触发方式 |
|------|------|----------|
| `manual` | 手动触发 | POST `/trigger/{id}` |
| `cron` | 定时触发 | `interval`(秒) 或 `cron` 表达式 |
| `webhook` | 外部回调触发 | POST `/webhook/{path}` |
| `device_event` | 设备事件触发 | 设备上行 msgType=9 事件帧 |

### 7.4 动作类型

```typescript
type ActionDef =
  | { type: "fetch";        url: string; method: string; body?: string; save: string }
  | { type: "update_page";  page: string }
  | { type: "patch_section"; page: string; sectionId: string; bind: Record<string, string> }
  | { type: "play_audio";   preset?: string; text?: string }
  | { type: "tts";          text: string }
  | { type: "switch_page";  page: string }
  | { type: "control";      command: string; value?: number }
```

| 类型 | 说明 | 关键参数 |
|------|------|----------|
| `fetch` | 请求外部 HTTP API | `url`, `method`, `body`, `save`(结果存储变量名) |
| `update_page` | 切换到指定页面并推送场景 | `page` (PageDef.id) |
| `patch_section` | 增量更新某个 Section | `page`, `sectionId`, `bind`(变量绑定) |
| `play_audio` | 播放音频 | `preset` 或 `text` |
| `tts` | 文字转语音 (未完全实现) | `text` |
| `switch_page` | 切换到指定页面 (不发场景) | `page` |
| `control` | 下发控制指令 | `command`, `value` |

### 7.5 页面定义

```typescript
interface PageDef {
  id: string;                        // 页面ID
  layout: string;                    // "vertical_scroll" | "horizontal_pages" | "fixed_single"
  autoScroll: boolean;
  autoScrollMs: number;
  sections: SectionBindDef[];        // Section 绑定定义
}

interface SectionBindDef {
  id: string;                        // Section 唯一ID (对应 Patch 的 sectionId)
  type: string;                      // Section 类型 wireName, 如 "hero_section"
  bind: Record<string, string>;     // 数据绑定: 字段名 -> 变量表达式
}
```

### 7.6 变量表达式语法

`bind` 中的值支持以下表达式:

| 表达式 | 含义 | 示例 |
|--------|------|------|
| `'literal text'` | 字面字符串 | `'CPU Usage'` |
| `$data.path.to.field` | 运行时数据变量 | `$data.cpu.usage` |
| `$trigger.path.to.field` | 触发器数据 | `$trigger.payload.temperature` |
| `$env.VAR_NAME` | 环境变量 | `$env.API_BASE_URL` |
| `123` | 纯数字 | `85` |
| `$data.items[0].label` | 数组索引 | `$data.list[2].name` |

### 7.7 工作流示例

```json
{
  "id": "weather_dashboard",
  "name": "天气仪表盘",
  "icon": "cloud",
  "pages": [
    {
      "id": "main",
      "layout": "vertical_scroll",
      "autoScroll": false,
      "autoScrollMs": 0,
      "sections": [
        {
          "id": "temp_hero",
          "type": "hero_section",
          "bind": {
            "value": "$data.weather.temp",
            "label": "'Temperature'",
            "subtitle": "$data.weather.desc",
            "tone": "'primary'",
            "icon_src": "'thermometer'",
            "progress": "$data.weather.temp_pct"
          }
        },
        {
          "id": "metrics",
          "type": "metric_section",
          "bind": {
            "metrics": "$data.weather.metrics"
          }
        }
      ]
    }
  ],
  "triggers": [
    { "type": "cron", "id": "refresh", "interval": 60 }
  ],
  "actions": {
    "refresh": [
      {
        "type": "fetch",
        "url": "https://api.weather.com/current",
        "method": "GET",
        "save": "weather"
      },
      {
        "type": "update_page",
        "page": "main"
      }
    ]
  }
}
```

---

## 8. 完整交互流程

### 8.1 设备注册认领流程

```
 设备终端                    后端服务                     前端管理台
    │                          │                            │
    │── WebSocket 连接 ───────►│                            │
    │                          │                            │
    │── HELLO/心跳 ───────────►│                            │
    │   (deviceId)             │  创建 UNCLAIMED 记录        │
    │                          │  生成6位认领码(15分钟TTL)    │
    │                          │                            │
    │◄─ 屏幕显示认领码 ───────│                            │
    │                          │                            │
    │                          │    GET /devices/unclaimed  │
    │                          │◄───────────────────────────│
    │                          │    返回未认领设备列表        │
    │                          │───────────────────────────►│
    │                          │                            │
    │                          │    用户看到设备在列表中      │
    │                          │    用户查看设备屏幕上的码   │
    │                          │                            │
    │                          │    POST /devices/{id}/claim│
    │                          │    {claimCode, deviceName} │
    │                          │◄───────────────────────────│
    │                          │                            │
    │                          │    校验认领码               │
    │                          │    设置 ownerSpaceId        │
    │                          │    设置 CLAIMED             │
    │                          │                            │
    │                          │    返回设备信息             │
    │                          │───────────────────────────►│
    │                          │                            │
    │── 下一次心跳 ───────────►│                            │
    │   isClaimed=true         │  开始保存遥测数据           │
    │                          │                            │
    │                          │    GET /devices             │
    │                          │◄───────────────────────────│
    │                          │    设备出现在已认领列表中    │
    │                          │───────────────────────────►│
```

### 8.2 控制指令下发流程

```
 前端管理台                  后端服务                    设备终端
    │                          │                            │
    │  POST /devices/{id}/control                          │
    │  {command, value}        │                            │
    │─────────────────────────►│                            │
    │                          │                            │
    │                          │  校验设备归属               │
    │                          │  校验设备在线               │
    │                          │  构建二进制帧(msgType=12)   │
    │                          │                            │
    │                          │──── WebSocket 二进制帧 ──►│
    │                          │                            │
    │                          │                     设备执行指令
    │                          │                            │
    │                          │◄─── ACK (msgType=10) ─────│
    │                          │   status: ACKED/REJECTED   │
    │                          │                            │
    │     返回结果              │                            │
    │◄─────────────────────────│                            │
    │  {sent:true, status:"SENT"}                           │
    │                          │                            │
    │  (可轮询 /telemetry 查看) │                            │
    │  (指令状态更新)                                     │
```

### 8.3 Section 场景推送流程

```
 前端管理台                  后端服务                    设备终端
    │                          │                            │
    │  创建工作流定义           │                            │
    │─────────────────────────►│                            │
    │                          │                            │
    │  加载工作流到设备         │                            │
    │─────────────────────────►│                            │
    │                          │                            │
    │                          │  构建 SectionScene          │
    │                          │  能力适配                   │
    │                          │  序列化为 JSON              │
    │                          │  封装为二进制帧(msgType=15) │
    │                          │                            │
    │                          │──── WebSocket 二进制帧 ──►│
    │                          │                            │
    │                          │                     设备渲染 UI
    │                          │                            │
    │                          │◄─── 事件输入(msgType=9) ──│
    │                          │   (按钮点击/传感器触发)     │
    │                          │                            │
    │                          │  匹配 DeviceEventTrigger    │
    │                          │  执行关联的动作列表         │
    │                          │  (可能发送 Patch 或新场景)  │
    │                          │                            │
    │                          │──── Patch(msgType=16) ───►│
    │                          │                            │
    │                          │                     设备更新 UI
```

### 8.4 设备离线与重连

```
 设备终端                         后端服务
    │                                │
    │  WebSocket 连接断开             │
    │  (网络问题/设备重启)            │
    │                                │
    │                      afterConnectionClosed
    │                      清除会话映射
    │                      status 仍为 ONLINE (90s超时才改)
    │                                │
    │  设备重连                      │
    │── WebSocket 连接 ────────────►│
    │── HELLO/心跳 ────────────────►│
    │                                │
    │                      registerSession
    │                      恢复 deviceId → session 映射
    │                                │
    │  (如果90s内重连, status 保持 ONLINE)        │
```

---

## 9. 数据模型参考

### 9.1 SduiDevice (设备)

```typescript
interface SduiDevice {
  deviceId: string;              // 主键, eFuse MAC 地址
  name: string;                  // 设备名称 (认领时可自定义)
  status: string;                // "ONLINE" | "OFFLINE"
  registrationStatus: string;    // "UNCLAIMED" | "CLAIMED"
  currentPageId: string | null;  // 当前展示的页面ID
  lastSeenAt: string;            // ISO datetime, 最后在线时间
  claimedAt: string | null;      // ISO datetime, 认领时间
  createdAt: string;             // ISO datetime, 首次上线时间
}
```

### 9.2 SduiDeviceDetailResponse (设备详情)

```typescript
interface SduiDeviceDetailResponse extends SduiDevice {
  board: string;                 // 硬件型号, 如 "esp32-s3"
  screenShape: string;           // 屏幕形状: "round" | "rect"
  screenWidth: number;           // 屏幕宽度(像素)
  screenHeight: number;          // 屏幕高度(像素)
  inputMode: string;             // 输入方式: "touch" | "button" | "encoder"
  availableCommands: string[];   // 可用指令列表
  capabilitiesSnapshot: string | null;  // 原始能力JSON
}
```

### 9.3 SduiDeviceTelemetry (遥测数据)

```typescript
interface SduiDeviceTelemetry {
  id: number;
  deviceId: string;
  wifiRssi: number | null;       // WiFi 信号强度 (dBm)
  temperature: number | null;    // 温度 (°C)
  freeHeapInternal: number | null;  // 内部可用堆内存 (bytes)
  freeHeapTotal: number | null;     // 总可用堆内存 (bytes)
  uptimeS: number | null;        // 运行时长 (秒)
  createdAt: string;             // ISO datetime
}
```

### 9.4 能力快照结构 (设备上报)

```typescript
interface CapabilitySnapshot {
  device_profile: {
    shape: string;               // "round" | "rect"
    screen_w: number;
    screen_h: number;
    input_mode: string;          // "touch" | "button" | "encoder"
    auto_sleep_by_inactive: boolean;
  };
  inputs: Array<{
    name: string;                // 输入名称, 如 "button_a"
    module: string;              // 硬件模块, 如 "gpio"
    enabled: boolean;
    events: string[];            // 支持的事件, 如 ["click", "long_press"]
  }>;
  outputs: Array<{
    name: string;                // 输出名称, 如 "display"
    capability: string;          // 能力标识, 如 "display.section"
    module: string;              // 硬件模块, 如 "st7789"
    enabled: boolean;
    commands: string[];          // 支持的命令列表
    legacy_topics?: string[];    // 兼容的旧版topic
    limits?: Record<string, number>;  // 能力限制
    supported_section_types?: string[];
    supported_layouts?: string[];
    transport?: string;          // "json" | "binary"
  }>;
}
```

---

## 10. 错误处理

### 10.1 HTTP 错误码

| HTTP Status | code | 场景 |
|-------------|------|------|
| 400 | 40000 | 参数校验失败 (如认领码长度不对) |
| 400 | 40001 | 认领码错误 |
| 400 | 40002 | 认领码已过期 |
| 400 | 40003 | 设备已被其他空间认领 |
| 404 | 40400 | 设备不存在 |
| 404 | 40401 | 设备不属于当前空间 |
| 500 | 50000 | 服务端内部错误 |

### 10.2 常见业务错误处理建议

| 场景 | 错误 | 前端处理 |
|------|------|----------|
| 认领码输错 | `认领码错误` | 提示用户重新输入，注意不区分大小写 |
| 认领码过期 | `认领码已过期` | 提示用户刷新设备页面/等待下一次心跳，获得新认领码 |
| 设备离线无法下发 | `设备不在线` | 提示稍后重试，展示设备离线状态 |
| 设备不在当前空间 | `设备不属于当前空间` | 检查 X-Space-Id 请求头是否正确 |
| 下发指令但设备不支持 | 见 `availableCommands` | 始终用 `availableCommands` 动态渲染控制面板 |

### 10.3 前端轮询建议

- **设备列表**: 建议 10-15 秒轮询 `GET /devices`，获取最新在线状态
- **设备详情**: 打开详情页时一次性获取，停留期间可 30 秒刷新一次
- **遥测数据**: 打开遥测面板时获取，可 5-10 秒轮询最新数据
- **运维概览**: 打开仪表盘时获取，可 30 秒轮询

---

## 附录 A: 预设场景说明

系统内置 5 个预设 Section 场景，可用于快速测试和调试:

| 预设名 | Sections | 说明 |
|--------|----------|------|
| `hero_dashboard` | 1 | 单个 Hero 卡片, 展示 CPU 使用率 |
| `metrics_grid` | 1 | 4 项指标网格: Memory/Disk/Network/Load |
| `chart_trend` | 1 | 16 个数据点的趋势图 |
| `full_dashboard` | 4 | 完整仪表盘: Hero + Metrics + Chart + Actions |
| `system_overview` | 4 | 系统概览: Hero + Metrics + Progress + Chart |

## 附录 B: Section 字段中英对照表

| 字段名 (JSON) | 中文说明 | 适用 Section |
|---------------|----------|-------------|
| `value` | 主值文本 | Hero |
| `label` | 标签文本 | Hero, Metric, Toggle, Nav |
| `subtitle` | 副标题 | Hero, Image, List |
| `tone` | 色调 | Hero, Action, List, Overlay |
| `icon_src` | 图标标识 | Hero, Image |
| `progress` | 进度值 (0-100) | Hero, Chart, Timer, Progress |
| `points` | 数据点数组 | Chart |
| `title` | 标题 | Chart, Timer, Image, Progress, Text, Overlay, List |
| `body` | 正文内容 | Text, Overlay |
| `metrics` | 指标列表 | Metric |
| `actions` | 操作按钮列表 | Action |
| `items` | 列表项 | List |
| `options` | 开关选项 | Toggle |
| `tabs` | 标签页 | Nav |
| `active_tab` | 当前激活标签索引 | Nav |
| `timer` | 计时器对象 | Timer |
| `elapsed_ms` | 已耗时(毫秒) | Timer |
| `running` | 是否运行中 | Timer |
| `unread_count` | 未读数 | Overlay |
| `auto_hide_ms` | 自动隐藏毫秒 | Overlay |
| `visible` | 是否可见 | Overlay |
| `enabled` | 是否可点击 | Action |
| `active` | 是否激活 | Toggle |
| `op` | 操作类型 | Patch |
| `section_id` | Section 唯一标识 | Scene/Patch |
| `page_id` | 页面标识 | Scene/Patch |
| `layout` | 布局类型 | Scene |
| `auto_scroll` | 是否自动滚动 | Scene |
