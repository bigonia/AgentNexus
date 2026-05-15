# SDUI 前端自回归升级指导

> 版本: 2.0  
> 最后更新: 2026-05-15  
> 目标读者: 前端开发工程师  
> 前置阅读: [SDUI_FRONTEND_GUIDE.md](./SDUI_FRONTEND_GUIDE.md) (v1.0 基础 API/协议参考)  

本文档聚焦于两期后端升级引入的新增和前向不兼容变更，按"前端需要做什么"为主线组织。

---

## 目录

1. [变更总览](#1-变更总览)
2. [新增：设备控制台 API](#2-新增设备控制台-api)
3. [新增：SSE 事件流](#3-新增sse-事件流)
4. [变更：工作流动作类型](#4-变更工作流动作类型)
5. [变更：设备事件触发器](#5-变更设备事件触发器)
6. [变更：变量表达式语法](#6-变更变量表达式语法)
7. [新增：变量驱动的 Section 自动更新](#7-新增变量驱动的-section-自动更新)
8. [变更：node-types 接口返回](#8-变更node-types-接口返回)
9. [前端适配清单](#9-前端适配清单)

---

## 1. 变更总览

| 模块 | 变更类型 | 前端影响 |
|------|---------|---------|
| 设备控制台 | **新增** 6 个 REST 端点 | 需新建 DeviceConsole 页面 |
| SSE 事件流 | **新增** | 需实现 EventSource 消费 |
| 工作流动作 | **新增** 3 种 action 类型 | 工作流编辑器需支持新节点 |
| 设备事件触发器 | **字段扩展** | 编辑器需暴露 sectionId/nodeId 筛选 |
| 变量表达式 | **语法扩展** | 绑定表达式输入框需提示新语法 |
| Section 自动更新 | **新增机制** | 行为变更：变量变了 Section 会自动 patch |
| node-types | **新增** 3 个 action 元数据 | 编辑器节点面板动态渲染 |

### 1.1 新旧接口关系与重叠处理

后端同时保留了两套设备调试接口，它们**不重叠，而是不同抽象层级**：

```
抽象层级
  ▲  高    /console/layout    语义化面板布局（后端动态生成面板+控件描述）
  │        /console/audio/*   结构化参数 → 后端翻译为设备指令
  │        /console/rgb/*     {mode, color, periodMs} → "breathe:ff0000:2000"
  │        /console/system/*  确认重启
  │        /console/events/stream  SSE 实时事件流
  │
  │  低    /devices/{id}/control   通用透传 {command, value} → 直接下发
  │        /devices/{id}          设备详情（含 availableCommands）
  ▼
```

#### 旧接口：通用控制透传

```
POST /api/v1/sdui/devices/{deviceId}/control
Body: { "command": "rgb.effect.set", "value": "breathe:ff0000:2000" }
```

特征是**前端需掌握设备指令格式**——RGB 需要手动拼 `"mode:hex:period"`，音频需要本地生成 PCM 再 base64 编码。

**定位**：保留，作为高级/原始指令通道。适用于：
- 新固件能力尚未被 Console API 覆盖时的临时调试
- 需要发送非标准指令值的场景
- 后端 Console API 出问题时的降级通道

#### 新接口：语义化控制台

```
POST /api/v1/sdui/devices/{deviceId}/console/rgb/apply
Body: { "mode": "breathe", "color": "#ff0000", "periodMs": 2000 }
```

特征是**后端封装翻译逻辑**——前端只需结构化参数，指令格式、PCM 生成、颜色转换全部在后端完成。

**定位**：作为主要交互式调试面板使用。

#### 前端迁移策略

| 现有前端功能 | 处理方式 |
|-------------|---------|
| 设备列表页 | **不变**，`GET /devices` 和 `GET /devices/unclaimed` 保持 |
| 设备详情页 | **不变**，`GET /devices/{id}` 仍返回 `availableCommands` 等字段 |
| 通用控制面板（如果有） | **替换为** `/console/layout` 驱动的动态面板 |
| 通用 control 下发入口 | **保留但降级**为"高级/原始指令"标签页或折叠区域 |
| 遥测面板 | **不变**，`GET /devices/{id}/telemetry` 保持 |
| 认领流程 | **不变**，`POST /devices/{id}/claim` 保持 |

**建议的前端交互方案**：

- 设备列表每行新增 **"控制台"** 按钮/图标，指向 `/devices/{id}/console` 路由
- 控制台页面主体用 `/console/layout` 响应渲染动态面板
- 在控制台底部或侧边栏增加一个 **"高级指令"** 折叠区，内含原有的通用 `command` + `value` 输入框，调 `/control` 接口
- `availableCommands` 列表作为高级指令区的 command 下拉选项

---

## 2. 新增：设备控制台 API

基础路径：`/api/v1/sdui/devices/{deviceId}/console`

所有接口需携带标准请求头 `X-Space-Id`，已认领设备才能访问。

### 2.1 获取控制台布局

```
GET /api/v1/sdui/devices/{deviceId}/console/layout
```

响应包含 `ttsAvailable` 字段（布尔），指示后端是否已配置 TTS 合成引擎。前端可据此决定是否展示 TTS 输入控件。

**响应结构：**

```typescript
interface ConsoleLayoutResponse {
  deviceId: string;
  online: boolean;
  ttsAvailable: boolean;  // 后端是否有 TTS 引擎
  panels: ConsolePanel[];
}

interface ConsolePanel {
  id: string;       // "display" | "audio" | "rgb" | "inputs" | "system"
  label: string;    // 中文显示名
  icon: string;     // lucide 图标名
  order: number;    // 排序
  controls?: ControlDef[];
  stream?: string;  // 仅 inputs 面板，SSE 端点路径
}

interface ControlDef {
  type: string;  // "slider" | "preset_buttons" | "tts_input" | "color_picker"
                 // | "mode_selector" | "button"
  id: string;
  label: string;
  // type-specific props:
  command?: string;     // slider: 绑定的设备指令
  min?: number;         // slider
  max?: number;         // slider
  step?: number;        // slider
  default?: any;        // slider / color_picker
  style?: string;       // button: "primary" | "danger"
  confirm?: boolean;    // button: 是否需要二次确认
  confirmText?: string; // button: 确认提示文本
  options?: Array<{     // preset_buttons / mode_selector
    value: string;
    label: string;
  }>;
}
```

**前端行为要点：**

- 面板按 `order` 升序渲染
- `controls` 中每个控件的 `type` 决定渲染哪个组件
- 设备离线时仍返回布局，`online: false`，此时操作按钮应置灰或提示
- `inputs` 面板无 `controls`，通过 `stream` 字段获取 SSE 事件
- `stream` 中的 `{deviceId}` 需替换为实际设备 ID

### 2.2 播放提示音

```
POST /api/v1/sdui/devices/{deviceId}/console/audio/preset
Content-Type: application/json

{ "preset": "notification" }
```

preset 有效值：`notification` / `success` / `error` / `warning` / `click` / `beep`

响应：

```typescript
interface PresetPlayResponse {
  sent: boolean;
  deviceId: string;
  preset: string;
  cmdId: string;
  samples: number;    // 生成的 PCM 采样数
  durationMs: number; // 音频时长(毫秒)
}
```

错误：`code: 40000`，message 含 `"invalid preset"` 或 `"device is offline"`。

### 2.3 TTS 朗读

```
POST /api/v1/sdui/devices/{deviceId}/console/audio/tts
Content-Type: application/json

{ "text": "设备已连接" }
```

响应：

```typescript
interface TtsPlayResponse {
  sent: boolean;
  deviceId: string;
  text: string;
  cmdId: string;
  samples: number;
}
```

错误：`code: 40000`，message 含 `"TTS not available"` / `"device is offline"` / `"TTS synthesis failed"`。

如果 `ttsAvailable` 为 false，前端应隐藏 TTS 输入控件。

### 2.4 RGB 灯效

```
POST /api/v1/sdui/devices/{deviceId}/console/rgb/apply
Content-Type: application/json

{
  "mode": "breathe",
  "color": "#ff0000",
  "periodMs": 2000
}
```

参数：

| 字段 | 必填 | 说明 |
|------|------|------|
| mode | 是 | `solid` / `blink` / `breathe` / `rainbow` / `chase` / `off` |
| color | 否 | 十六进制颜色，如 `#ff0000` 或 `ff0000` |
| periodMs | 否 | 周期(ms)，仅 blink/breathe/chase 有效 |

响应：

```typescript
interface RgbApplyResponse {
  sent: boolean;
  deviceId: string;
  cmdId: string;
  mode: string;
  color?: string;
  periodMs?: number;
}
```

### 2.5 系统重启

```
POST /api/v1/sdui/devices/{deviceId}/console/system/reboot
```

无请求体。响应：

```typescript
interface RebootResponse {
  sent: boolean;
  deviceId: string;
  cmdId: string;
}
```

前端需实现确认对话框：按钮 `confirm: true`，提示文案取 `confirmText`。

---

## 3. 新增：SSE 事件流

### 3.1 端点

```
GET /api/v1/sdui/devices/{deviceId}/console/events/stream
Accept: text/event-stream
```

### 3.2 事件格式

每个事件以 SSE 标准格式推送：

```
event: <事件类型>
data: <JSON 数据>
```

事件类型即 `kind` 字段值，如 `click`、`long_press`、`shake`、`encoder.rotate` 等。

```typescript
// SSE data 的 JSON 结构
interface SseEventData {
  ts: number;          // 毫秒时间戳
  nodeId?: string;     // 事件来源控件 ID
  sectionId?: string;  // 事件来源 Section ID
}
```

连接成功时首先收到：

```
event: connected
data: {"deviceId":"AABBCCDDEEFF","message":"Event stream connected"}
```

### 3.3 前端实现参考

```typescript
// 使用 EventSource API
const eventSource = new EventSource(
  `/api/v1/sdui/devices/${deviceId}/console/events/stream`
);

eventSource.addEventListener('connected', (e) => {
  console.log('SSE connected:', JSON.parse(e.data));
});

// 通用事件处理
eventSource.onmessage = (e) => {
  const event = JSON.parse(e.data);
  // e.type 是事件类型 (kind)
  // event 包含 { ts, nodeId?, sectionId? }
};
```

**注意事项：**

- EventSource 不支持自定义请求头（如 `X-Space-Id`），需通过 URL query 参数或依赖 cookie
- 切换到其他设备时，先 `close()` 旧 EventSource 再创建新的
- 设备断线 SSE 不会主动断开，前端需自行处理超时/重连
- SSE 流仅推送设备上行事件（msgType=9），不做业务过滤

---

## 4. 变更：工作流动作类型

### 4.1 新增 3 种 Action

原有 7 种（`fetch`、`update_page`、`patch_section`、`play_audio`、`tts`、`switch_page`、`control`），新增 3 种：

#### condition — 条件分支

```typescript
interface ConditionAction {
  type: "condition";
  variable: string;              // 判断变量表达式，如 "$data.playState"
  operator: string;              // "eq" | "neq" | "gt" | "gte" | "lt" | "lte" | "contains" | "isEmpty"
  value: string;                 // 比较值，支持字面量和 $data.xxx
  thenActions: ActionDef[];      // 条件成立时执行的动作列表
  elseActions?: ActionDef[];     // 条件不成立时执行（可选）
}
```

前端编辑器需支持嵌套展示——`thenActions` 和 `elseActions` 内部可以再放任意动作（包括嵌套 condition 或 sequence）。

#### sequence — 顺序执行

```typescript
interface SequenceAction {
  type: "sequence";
  steps: ActionDef[];  // 按顺序执行的动作列表
}
```

与顶层动作列表的区别：sequence 作为一个单元出现在 condition 的 then/else 分支中。

#### set_variable — 设置变量

```typescript
interface SetVariableAction {
  type: "set_variable";
  variable: string;  // 变量名，不含 $data. 前缀，如 "playState"
  value: string;     // 变量值表达式，如 "'playing'"、"$data.count + 1"
}
```

`value` 支持完整表达式语法（见第 6 章）。

### 4.2 JSON 序列化示例

```json
{
  "type": "condition",
  "variable": "$data.playState",
  "operator": "eq",
  "value": "playing",
  "thenActions": [
    { "type": "set_variable", "variable": "playState", "value": "paused" }
  ],
  "elseActions": [
    { "type": "sequence", "steps": [
      { "type": "set_variable", "variable": "playState", "value": "playing" },
      { "type": "play_audio", "preset": "click", "text": null }
    ]}
  ]
}
```

### 4.3 前端编辑器交互建议

- condition 节点显示为菱形/分支框，左右各一个分支
- sequence 节点显示为虚线框，内部可拖入子节点
- set_variable 节点显示为赋值符号，左侧变量名右侧表达式
- 三个新节点均需在拖拽面板和工作流画布中支持

---

## 5. 变更：设备事件触发器

### 5.1 DeviceEventTrigger 字段扩展

**旧结构（仍兼容）：**

```typescript
interface DeviceEventTrigger {
  type: "device_event";
  id: string;
  event: string;  // 事件类型，如 "click"
}
```

**新结构：**

```typescript
interface DeviceEventTrigger {
  type: "device_event";
  id: string;
  event: string;       // 事件类型：click / long_press / shake / encoder.rotate 等
  sectionId?: string;  // 可选，限定触发源 Section ID
  nodeId?: string;     // 可选，限定触发源控件 ID
}
```

### 5.2 匹配逻辑（服务端行为，前端理解即可）

1. 优先匹配同时指定了 `sectionId`/`nodeId` 的触发器
2. 如果精确匹配的触发器没有命中（fired=0），回退到只匹配 `event` 类型且 `sectionId`/`nodeId` 均为空的"旧版"触发器
3. `sectionId` 或 `nodeId` 留空视为"不限定"

### 5.3 前端编辑器适配

设备事件触发器的配置面板需增加两个可选输入框：

- **限定 Section**：下拉可选当前 Applet 内的 Section（留空 = 任意）
- **限定控件**：文本输入，填写 nodeId（留空 = 任意）

---

## 6. 变更：变量表达式语法

### 6.1 表达式系统升级

表达式解析器已从简单变量引用升级为基于 Spring SpEL 的完整表达式引擎（零依赖增加，Spring 内置）。

### 6.2 支持的语法

```typescript
// 变量引用（不变）
$data.playState          // 运行时变量
$data.recent[0].title    // 嵌套 + 数组索引
$trigger.from            // 触发器数据
$env.MAIL_API            // 环境变量

// 字符串字面量（不变）
'文本内容'
'Status'

// 数字字面量（不变）
42
3.14

// 布尔字面量（新增）
true
false

// 比较运算符（新增）
$data.count > 5
$data.status == 'running'
$data.volume != 0
$data.score >= 60
$data.temp <= 30

// 三元表达式（新增）
$data.x == 'playing' ? '暂停' : '播放'
$data.count > 0 ? $data.count : 0

// 加法 / 字符串拼接（新增）
$data.x + 10
'当前温度: ' + $data.temp

// 函数调用（新增）
min($data.x, 100)
max($data.y, 0)

// 组合使用（新增）
$data.playState == 'playing' ? '暂停' : '播放'
$data.volume > 0 ? '🔊' : '🔇'
min($data.volume + 10, 100)
max($data.count - 1, 0)
```

### 6.3 向后兼容

- `$data.x`、`$trigger.x`、`$env.X` 引用语法不变
- 单引号字符串字面量不变
- 纯数字不变
- 所有旧有绑定表达式无需修改

### 6.4 前端适配

- 绑定表达式输入框的 placeholder/提示文本需更新，体现新支持的运算符
- 可考虑提供表达式语法提示下拉或简易公式编辑器
- 如果此前做了表达式校验（如限制只能 `$data.xxx`），需要放宽

---

## 7. 新增：变量驱动的 Section 自动更新

### 7.1 机制说明

当工作流动作执行过程中变量发生变化时（`set_variable`、`fetch` 保存数据），系统会自动：

1. 查找所有绑定了该变量的 Section
2. 重新解析这些 Section 的 bindings
3. 生成 `SectionPatch` 增量更新并推送到设备

前端无需介入此过程，但需要理解这意味着 **Section 的内容可以在不切换页面的情况下自动变化**。

### 7.2 对前端的影响

**工作流编辑器中的 Section 绑定配置需要明确 `$data.xxx` 变量引用。** 当定义 bindings 时（例如 action_section 的按钮文字），如果引用了变量，该 Section 在运行时会在变量变化时自动刷新。

编辑器中设置 `bindings` 时，建议提供当前 Applet 声明的变量列表作为自动补全选项。

### 7.3 运行时示例

```
用户点击"播放"按钮
  → t_play_click 触发
  → condition: $data.playState == 'playing' ?
      then: set_variable("playState", "paused")
      else: set_variable("playState", "playing")
  → VariableWatcher 检测 playState 变更
  → 找到绑定 $data.playState 的 action_bar Section
  → 重新 resolve: actions[0].label = $data.playState == 'playing' ? '暂停' : '播放'
  → 自动下发 SectionPatch 到设备
```

---

## 8. 变更：node-types 接口返回

```
GET /api/v1/sdui/workflow/node-types
```

### 8.1 新增的 actions 元数据

接口返回的 `actions` 数组新增 3 项：

```json
{
  "type": "condition",
  "label": "条件分支",
  "params": [
    {"name": "variable", "label": "判断变量", "type": "string", "syntax": "$data.xxx"},
    {"name": "operator", "label": "运算符", "type": "string"},
    {"name": "value", "label": "比较值", "type": "string"},
    {"name": "thenActions", "label": "条件成立时执行", "type": "actions[]"},
    {"name": "elseActions", "label": "条件不成立时执行", "type": "actions[]"}
  ]
}
```

```json
{
  "type": "sequence",
  "label": "顺序执行",
  "params": [
    {"name": "steps", "label": "步骤列表", "type": "actions[]"}
  ]
}
```

```json
{
  "type": "set_variable",
  "label": "设置变量",
  "params": [
    {"name": "variable", "label": "变量名", "type": "string"},
    {"name": "value", "label": "变量值", "type": "string"}
  ]
}
```

### 8.2 元数据字段说明

| 字段 | 说明 |
|------|------|
| `type` | 动作类型，JSON 序列化的 `type` 字段值 |
| `label` | 中文显示名 |
| `params[].name` | 参数键名 |
| `params[].label` | 参数中文标签 |
| `params[].type` | 参数类型：`string` / `number` / `actions[]`（嵌套动作数组） |
| `params[].syntax` | 可选，表达式语法提示 |

`actions[]` 类型的参数意味着该字段是一个动作数组，前端编辑器应提供嵌套编辑能力。

### 8.3 新增的 device_event 触发器参数

device_event 触发器 params 由原来的 `[id, event]` 扩展为：

```json
{
  "type": "device_event",
  "label": "设备事件触发",
  "params": [
    {"name": "event", "label": "事件类型", "type": "string", "syntax": "click / shake / long_press"},
    {"name": "sectionId", "label": "限定 Section", "type": "string", "syntax": "可选，留空则不限"},
    {"name": "nodeId", "label": "限定控件", "type": "string", "syntax": "可选，留空则不限"}
  ]
}
```

---

## 9. 前端适配清单

### 9.1 必做：设备控制台页面

| 序号 | 任务 | 说明 |
|------|------|------|
| 1.1 | 设备列表增加入口 | 每行新增"控制台"按钮/图标，路由 `/devices/:id/console` |
| 1.2 | 替换旧控制面板 | 如果已有基于 `availableCommands` + `/control` 的控制 UI，替换为 `GET /console/layout` 驱动的动态面板 |
| 1.3 | 面板容器组件 | 调用 `GET /console/layout`，按 `panels[].order` 排序渲染面板 |
| 1.4 | AudioPanel | preset_buttons（调用 POST audio/preset）、tts_input（调用 POST audio/tts）、volume slider（调用通用 control 接口 `audio.volume.set`） |
| 1.5 | RgbPanel | color_picker + mode_selector + period slider + apply 按钮（调用 POST rgb/apply） |
| 1.6 | DisplayPanel | brightness slider（调用通用 control 接口 `display.brightness.set`） |
| 1.7 | InputEventsPanel | EventSource 消费 `/console/events/stream`，以时间线列表展示 |
| 1.8 | SystemPanel | reboot 按钮 + 确认弹窗（调用 POST system/reboot） |
| 1.9 | 高级指令折叠区 | 控制台底部折叠面板，保留 command+value 输入框调 `/control`，`availableCommands` 作 command 下拉选项 |
| 1.10 | 离线状态处理 | `online: false` 时所有操控按钮置灰/显示"设备离线"提示 |

### 9.2 必做：工作流编辑器升级

| 序号 | 任务 | 说明 |
|------|------|------|
| 2.1 | 条件分支节点 | vue-flow 自定义节点，菱形样式，左右两个分支出口 |
| 2.2 | 顺序执行节点 | 虚线边框容器节点，内部可嵌套子节点 |
| 2.3 | 设置变量节点 | 赋值样式节点，变量名 + 表达式值 |
| 2.4 | 嵌套动作编辑 | `actions[]` 类型参数支持展开内嵌编辑器 |
| 2.5 | 设备事件触发器 | 编辑面板增加 sectionId/nodeId 可选输入 |
| 2.6 | node-types 动态渲染 | 用 GET /node-types 的 actions/triggers 数组动态渲染节点面板，不硬编码 |
| 2.7 | 表达式语法提示 | 绑定/变量输入框旁展示语法帮助（引用 `$data.x`、比较运算符、三元、min/max） |

### 9.3 建议：体验优化

| 序号 | 任务 | 说明 |
|------|------|------|
| 3.1 | 变量自动补全 | bindings 编辑时，从 Applet 的 variables 列表提供 `$data.xxx` 自动补全 |
| 3.2 | 表达式校验反馈 | 调用后端时如果返回解析错误，在输入框旁展示红色提示 |
| 3.3 | SSE 重连机制 | EventSource 断线后自动重连，切换设备时关闭旧连接 |
| 3.4 | 控制台面板记忆 | 记住用户展开/折叠的面板状态，设备切换时保留 |

---

## 附录 A：控制台 API 速查

| 方法 | 路径 | 请求体 | 说明 |
|------|------|--------|------|
| GET | `/api/v1/sdui/devices/{id}/console/layout` | — | 获取面板布局 |
| POST | `/api/v1/sdui/devices/{id}/console/audio/preset` | `{preset}` | 播放提示音 |
| POST | `/api/v1/sdui/devices/{id}/console/audio/tts` | `{text}` | TTS 朗读 |
| POST | `/api/v1/sdui/devices/{id}/console/rgb/apply` | `{mode, color?, periodMs?}` | RGB 灯效 |
| GET | `/api/v1/sdui/devices/{id}/console/events/stream` | — | SSE 事件流 |
| POST | `/api/v1/sdui/devices/{id}/console/system/reboot` | — | 重启设备 |

## 附录 B：工作流 API 速查（不变）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/sdui/workflow/node-types` | 节点类型（已扩展，见第 8 章） |
| GET/POST/PUT/DELETE | `/api/v1/sdui/workflow/definition[/{id}]` | 工作流定义 CRUD |
| POST | `/api/v1/sdui/workflow/{deviceId}/load?definitionId=xxx` | 加载工作流 |
| POST | `/api/v1/sdui/workflow/{deviceId}/unload` | 卸载工作流 |
| POST | `/api/v1/sdui/workflow/{deviceId}/trigger/{triggerId}` | 手动触发 |
| GET | `/api/v1/sdui/workflow/{deviceId}/status` | 设备工作流状态 |
| POST | `/api/v1/sdui/webhook/{path}` | Webhook 接收 |

## 附录 C：预设音效列表

| preset | 标签 | 频率(Hz) | 时长(ms) | 说明 |
|--------|------|----------|----------|------|
| notification | 通知 | 880 | 180 | 单音 |
| success | 成功 | 660→880 | 200 | 双音，升调 |
| error | 错误 | 440→330 | 300 | 双音，降调 |
| warning | 警告 | 660 | 200 | 单音 |
| click | 点击 | 1000 | 50 | 短促 |
| beep | 蜂鸣 | 1200 | 80 | 短促高音 |

## 附录 D：RGB 模式说明

| mode | 中文 | 说明 | value 格式 |
|------|------|------|-----------|
| solid | 常亮 | 指定颜色常亮 | `solid:ff0000` |
| blink | 闪烁 | 指定颜色闪烁 | `blink:ff0000:1000` |
| breathe | 呼吸 | 指定颜色呼吸渐变 | `breathe:ff0000:2000` |
| rainbow | 彩虹 | 全彩渐变，无需颜色 | `rainbow` |
| chase | 跑马灯 | 跑马灯效果 | `chase:ff0000:2000` |
| off | 关闭 | 关闭 RGB | — |
