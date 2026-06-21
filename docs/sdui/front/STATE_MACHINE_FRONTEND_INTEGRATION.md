# 状态机 API 参考（前端接入文档）

本文档定义状态机前后端交互的全部 REST API，供前端集成参考。交互设计见 [STATE_MACHINE_UX_DESIGN.md](STATE_MACHINE_UX_DESIGN.md)。

核心理念：**状态机是一份纯静态的事件流定义，部署是独立的轻量记录**（当前状态 + 上下文数据 + 设备绑定）。无生命周期状态字段；运行时状态由部署记录独立维护。

---

## 一、实体模型

### 1.1 状态机定义（StateMachine）

表 `sdui_state_machine`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | UUID | 唯一标识 |
| `name` | string | 名称 |
| `description` | string | 描述 |
| `boardTypes` | string[] | 目标板卡类型（多选，创建时确定，不可修改） |
| `definition` | JSON | 状态机逻辑：`{ states: [...], transitions: [...] }` |
| `editorModel` | JSON | 前端编辑器画布状态（节点位置、视口等），可选 |
| `createdAt` | datetime | 创建时间 |
| `updatedAt` | datetime | 最近修改时间 |

**definition 结构（多 Page 格式）：**

```json
{
  "states": [
    {
      "id": "idle",
      "label": "空闲待机",
      "pages": [
        {
          "pageId": "main",
          "layout": "vertical_scroll",
          "autoScroll": true,
          "autoScrollMs": 3000,
          "sections": [
            {
              "sectionId": "welcome_title",
              "sectionType": "hero_section",
              "fields": { "title": "欢迎使用", "subtitle": "请点击确认" }
            },
            {
              "sectionId": "actions",
              "sectionType": "action_section",
              "fields": {
                "actions": [
                  { "id": "ok", "label": "OK", "tone": "primary", "enabled": true }
                ]
              }
            }
          ]
        }
      ]
    }
  ],
  "transitions": [
    {
      "id": "t_001",
      "fromStateId": "idle",
      "toStateId": "active",
      "event": { "eventId": "ui:action.click" },
      "actions": [
        { "type": "context.set", "name": "clicked", "value": true },
        { "type": "command.dispatch", "commandId": "rgb.effect.set", "params": { "r": 0, "g": 255, "b": 0 } }
      ],
      "priority": 0
    }
  ]
}
```

**向后兼容**：也支持旧格式——直接在 state 下放置 `sections` 数组（不套 `pages`），后端自动转为单 page `"main"`。

### 1.2 部署记录（Deployment）

表 `sdui_state_machine_deployment`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | UUID | 唯一标识 |
| `stateMachineId` | string | 所属状态机 ID |
| `devices` | string[] | 绑定的设备 ID 列表 |
| `currentStateId` | string | 运行时当前状态 ID（初始为 definition 中第一个 state） |
| `contextData` | JSON | 运行时上下文数据（`context.set` 动作写入的键值对） |
| `deployedAt` | datetime | 部署时间 |

部署记录的生命周期：创建（部署）→ 删除（卸载）。卸载即物理删除记录，停止事件监听。每条部署独立维护自己的运行时状态。

---

## 二、状态机 CRUD

Base: `/api/v1/sdui/state-machines`

### 2.1 列表

```
GET /api/v1/sdui/state-machines?page=0&size=20
```

Query params:
- `page` (int, 默认 0)
- `size` (int, 默认 20，最大 100)

Response: `ApiResponse<{ items: StateMachine[], page: int, size: int, totalPages: int, totalItems: long }>`

按 `updatedAt` 降序排列。

### 2.2 创建

```
POST /api/v1/sdui/state-machines
Body: {
  name: string,             // 必填
  description?: string,
  boardTypes: string[],     // 必填，至少一个，创建后不可修改
  definition: {
    states: StateDef[],
    transitions: TransitionDef[]
  },
  editorModel?: {}          // 可选，前端画布状态
}
```

- 创建时自动校验 definition，不合法返回 `40000` + errors 数组
- `boardTypes` 一旦创建即不可修改（update 接口拒绝修改此字段）
- 返回完整实体（含 `id`、`createdAt`、`updatedAt`）

### 2.3 读取

```
GET /api/v1/sdui/state-machines/{id}
```

返回完整实体，包含 `definition`、`boardTypes`、`editorModel`。

### 2.4 更新

```
PUT /api/v1/sdui/state-machines/{id}
Body: { name?, description?, definition?, editorModel? }
```

- `boardTypes` 不可通过此接口修改（传入即抛错）
- 仅更新传入的字段
- 更新 definition 时自动校验

### 2.5 删除

```
DELETE /api/v1/sdui/state-machines/{id}
```

Response: `{ deleted: true, id: "sm-xxx" }`

级联删除所有部署记录（物理删除）。

---

## 三、校验

```
POST /api/v1/sdui/state-machines/validate
Body: {
  states: StateDef[],
  transitions: TransitionDef[]
}
```

Response: `ApiResponse<{ valid: boolean, errors: ValidationError[] }>`

```typescript
interface ValidationError {
  path: string;    // 如 "definition.transitions[t_001].event.eventId"
  code: string;    // 如 "UNKNOWN_INBOUND_EVENT", "MISSING_REQUIRED_FIELD", "DUPLICATE_ID", "INVALID_REFERENCE", "UNSUPPORTED_ACTION", "TYPE_MISMATCH"
  message: string; // 如 "unknown inbound event: ui:action.click"
}
```

校验项：
- state ID 重复检查
- transition ID 重复检查
- transition 引用的 `fromStateId` / `toStateId` 是否存在
- transition 的 `event.eventId` 是否为已知入站事件
- action 中的 `commandId` 是否为已知命令
- action 中的 `sectionType` 是否合法
- 各 action 类型必填字段完整性（`context.set` 需 name、`section.add/update` 需 sectionType、`section.remove` 需 sectionId、`command.dispatch` 需 commandId、`http.request` 需 url、`list.instantiate` 需 sectionType + itemsSource + itemTemplate、`timer.delay` 需 durationMs、`state.transition` 需 eventId）
- `list.instantiate` 的 itemsSource 必须以 `$` 开头
- `timer.delay` 的 durationMs 必须 >= 0
- `priority` 必须为整数
- section 字段级类型和范围校验

---

## 四、部署管理

Base: `/api/v1/sdui/state-machines/{id}/deployments`

### 4.1 部署记录列表

```
GET /api/v1/sdui/state-machines/{id}/deployments
```

Response: `ApiResponse<Deployment[]>`（按 `deployedAt` 降序）

### 4.2 创建部署（部署到设备）

```
POST /api/v1/sdui/state-machines/{id}/deployments
Body: { devices: string[] }
```

- `devices` 必填，至少一个
- 部署时执行三项前置检查（结果以 warnings 返回，不阻止部署）：
  1. **在线检查**：标记离线的设备（不阻止，只警告）
  2. **板卡类型匹配检查**：设备板卡类型是否在状态机的 `boardTypes` 列表中
  3. **能力交集检查**：每台设备是否支持 definition 中用到的所有事件/命令/section 类型

- 部署成功时自动推送初始状态的 Page 到所有绑定设备（full scene push）
- 部署成功后，`StateMachineEventBridge` 和 `StateMachineCommandEventBridge` 开始监听这些设备的入站事件和命令生命周期事件

Response:
```json
{
  "code": 20000,
  "data": {
    "id": "dep-xxx",
    "stateMachineId": "sm-xxx",
    "devices": ["esp32-abc123", "esp32-def456"],
    "deployedAt": "2026-06-17T10:30:00",
    "pushResults": [
      { "type": "scene", "pageId": "main", "deviceId": "esp32-abc123", "sent": true, "sections": 2 }
    ],
    "allSent": true,
    "warnings": [
      {
        "deviceId": "esp32-def456",
        "message": "device esp32-def456 missing capabilities: commands: [rgb.effect.set]",
        "severity": "warning",
        "missingEvents": [],
        "missingCommands": ["rgb.effect.set"],
        "missingSections": []
      }
    ]
  }
}
```

### 4.3 卸载（删除部署记录）

```
DELETE /api/v1/sdui/state-machines/{id}/deployments/{deployId}
```

Response: `{ deleted: true, id: "dep-xxx", stateMachineId: "sm-xxx" }`

物理删除部署记录，停止对应设备的事件监听。

---

## 五、触发

### 5.1 手动触发

```
POST /api/v1/sdui/state-machines/{id}/trigger
Body: {
  event: {
    eventId: string,      // 必填
    deviceId?: string,    // 可选，不提供则用部署中第一个设备
    pageId?: string,
    sectionId?: string,
    nodeId?: string,
    value?: any
  }
}
```

- 根据 `deviceId` 找到匹配的部署记录，在该部署上执行转换链
- 如果 `deviceId` 不在任何部署中，返回 `40000` 错误
- 支持链式转换（`state.transition` 动作），最多 100 次链式迭代

Response（**关键字段**）:
```json
{
  "code": 20000,
  "data": {
    "status": "SUCCEEDED",        // "SUCCEEDED" | "FAILED"
    "matched": true,               // 是否匹配到转换
    "reason": "...",               // 未匹配时的原因（仅 unmatched 时有）
    "stateMachineId": "sm-xxx",
    "deploymentId": "dep-xxx",
    "triggerDeviceId": "esp32-abc123",
    "currentStateId": "done",      // 转换后的新状态
    "context": { "clicked": true }, // 当前部署的上下文数据
    "transitionIds": ["t_001"],     // 执行过的转换 ID 列表
    "chainCount": 1,                // 链式转换执行次数
    "actions": [                    // 每个动作的执行结果
      {
        "type": "context.set",
        "status": "SUCCEEDED",
        "name": "clicked"
      },
      {
        "type": "command.dispatch",
        "commandId": "rgb.effect.set",
        "validated": true,
        "status": "DISPATCHED",
        "targetDevices": ["esp32-abc123"],
        "dispatches": [
          { "deviceId": "esp32-abc123", "cmdId": "cmd-1", "sent": true, "status": "SENT", "action": "rgb.effect.set" }
        ]
      }
    ],
    "commandResults": [            // 所有命令 dispatch 结果的扁平列表
      { "deviceId": "esp32-abc123", "cmdId": "cmd-1", "sent": true, "status": "SENT", "action": "rgb.effect.set" }
    ],
    "projectionResults": [         // 页面推送结果
      { "type": "patch", "pageId": "main", "deviceId": "esp32-abc123", "sent": true, "patches": 1 }
    ],
    "durationMs": 45
  }
}
```

### 5.2 设备事件入口（WebSocket 事件触发）

```
POST /api/v1/sdui/state-machines/events/device
Body: {
  event: {
    eventId: string,     // 必填
    deviceId: string,    // 必填
    pageId?: string,
    sectionId?: string,
    nodeId?: string,
    value?: any
  }
}
```

- 根据 `deviceId` 查找所有匹配的部署记录，在每个部署上独立触发
- 返回数组：`ApiResponse<TriggerResult[]>`（每个匹配的部署一条结果）
- 此端点由 `StateMachineEventBridge` 在设备事件到达时自动调用

### 5.3 定时触发（Cron）

`system:cron` 事件由 `CronTriggerScheduler` 每 60 秒扫描一次。当 cron 表达式匹配当前时间时，对部署的第一台设备自动触发（内部调用 `trigger()`）。前端无需手动操作。

---

## 六、编辑器辅助 API

### 6.1 数据架构

编辑器中的事件选择器、命令下拉、section 类型下拉的数据来自两层体系：

- **静态配置层（全量词典）**：YAML 配置文件定义平台能理解的所有事件/命令/section 类型，不依赖设备在线
- **动态设备层（板卡能力）**：设备连接时上报的能力快照，`CapabilityRegistry` 自动按 board 型号聚类为板卡类型

**编辑器数据源选择逻辑**：状态机创建时选了 boardTypes，编辑器将全量词典与这些板卡的能力取交集，仅显示交集内的事件/命令/section。

### 6.2 板卡类型列表

```
GET /api/v1/sdui/board-types
```

Response: `ApiResponse<BoardTypeInfo[]>` — 自动发现的板卡类型列表。

```typescript
interface BoardTypeInfo {
  key: string;                 // 唯一键，如 "board:ESP32-S3:0"
  board: string;               // 硬件板卡名，如 "ESP32-S3"
  label: string;               // 显示名，如 "ESP32-S3 (全功能版)"
  labelSource: "board" | "fingerprint";
  hasVariants: boolean;        // 是否有多能力变体
  inputEvents: string[];       // 该板卡类型的入站事件 ID 列表
  outputCommands: string[];    // 该板卡类型的输出命令 ID 列表
  sectionTypes: string[];      // 该板卡类型的 section 类型列表
  deviceCount: number;         // 该类型设备总数
  onlineCount: number;         // 当前在线数
  exampleDeviceIds: string[];  // 最多 3 个示例设备 ID
  lastSeen: string;            // 最后在线时间 ISO-8601
}
```

### 6.3 按板卡类型过滤

```
GET /api/v1/sdui/board-types/{typeKey}
GET /api/v1/sdui/board-types/{typeKey}/events       // 该板卡支持的事件列表 + 事件树 + 下拉选项
GET /api/v1/sdui/board-types/{typeKey}/commands     // 该板卡支持的命令 + 参数定义
GET /api/v1/sdui/board-types/{typeKey}/sections     // 该板卡支持的 section 类型 + 字段定义 + 编辑器数据
```

板卡查询内部逻辑：找一台该类型的在线设备作为示例，读取其能力快照，返回它实际报告过的 events/commands/sections。

### 6.4 入站事件树（编辑器事件选择器数据源）

```
GET /api/v1/sdui/events/inbound-events
```

返回按 category 分组的入站事件树：
```json
[
  {
    "category": "SECTION",
    "label": "Section 交互",
    "capabilities": [
      {
        "capability": "action_section",
        "label": "action_section",
        "events": [
          { "eventId": "ui:action.click", "displayName": "按钮点击", "category": "SECTION", "source": "action_section", ... }
        ]
      }
    ]
  },
  {
    "category": "COMMAND",
    "label": "命令生命周期",
    "capabilities": [
      {
        "capability": "command.lifecycle",
        "label": "命令生命周期",
        "events": [ ... ]
      }
    ]
  }
]
```

### 6.5 全量目录

```
GET /api/v1/sdui/events/catalog          // 全量：{ commands: [...], sections: [...] }
GET /api/v1/sdui/events/commands         // 命令列表（按 capability 分组）
GET /api/v1/sdui/events/sections         // section 类型列表 + 字段定义 + 交互事件
```

Section 目录每项的字段：
```typescript
interface SectionCatalogEntry {
  type: string;              // section 类型，如 "hero_section"
  displayName: string;       // 显示名
  operations: string[];      // 支持的操作，如 ["add", "update", "remove"]
  fields: FieldDef[];        // 字段定义
  constraints?: {};          // 约束（如 compact 模式下的字段过滤）
  events: EventDef[];        // 该 section 能触发的交互事件
}

interface FieldDef {
  name: string;              // 字段名
  type: string;              // "string" | "int" | "float" | "boolean" | "color" | "enum" | "array" | "object"
  required: boolean;
  min?: number;
  max?: number;
  values?: string[];         // enum 的合法值
  description?: string;
  children?: FieldDef[];     // 嵌套字段（用于 array/object 类型）
}
```

### 6.6 字段级校验

```
POST /api/v1/sdui/events/validate          // 校验事件 payload
Body: { eventId: string, payload: {} }

POST /api/v1/sdui/events/commands/validate // 校验命令参数
Body: { commandId: string, params: {} }

POST /api/v1/sdui/events/sections/validate // 校验 section 字段
Body: { sectionType: string, fields: {} }
```

Response 格式统一：`{ valid: boolean, errors: [{ path, code, message }] }`

---

## 七、前端数据模型（TypeScript 参考）

```typescript
// ── 状态机定义 ──

interface StateMachine {
  id: string;
  name: string;
  description: string;
  boardTypes: string[];       // 目标板卡类型，多选，创建后不可修改
  definition: StateMachineDefinition;
  editorModel?: EditorModel;
  createdAt: string;          // ISO-8601
  updatedAt: string;          // ISO-8601
}

interface StateMachineDefinition {
  states: StateDef[];
  transitions: TransitionDef[];
}

interface StateDef {
  id: string;                 // 唯一标识，如 "idle"
  label?: string;             // 显示名
  pages?: PageDef[];          // 多 Page 格式（推荐）
  sections?: SectionDef[];    // 旧格式（兼容），自动转为单 page "main"
  layout?: string;            // 旧格式：page 布局，默认 "vertical_scroll"
  autoScroll?: boolean;
  autoScrollMs?: number;
}

interface PageDef {
  pageId: string;             // 唯一标识（可自动生成）
  layout: string;             // "vertical_scroll" | "fixed_single" 等
  autoScroll?: boolean;       // 是否自动滚动
  autoScrollMs?: number;      // 自动滚动间隔（毫秒）
  sections: SectionDef[];
}

interface SectionDef {
  sectionId: string;          // 唯一标识（可自动生成）
  sectionType: string;        // 如 "hero_section"
  fields: Record<string, any>;
}

interface TransitionDef {
  id: string;                 // 唯一标识（可自动生成）
  fromStateId: string;
  toStateId: string;
  event: {
    eventId: string;          // 触发事件，单事件（不支持多选）
    cron?: string;            // system:cron 时必填，标准 5 字段 cron
  };
  actions: ActionDef[];
  priority?: number;          // 默认 0，值越高优先级越高
}

// ── 部署记录 ──

interface Deployment {
  id: string;
  stateMachineId: string;
  devices: string[];          // 绑定的设备 ID
  currentStateId: string;     // 运行时当前状态
  contextData: Record<string, any>; // 运行时上下文数据
  deployedAt: string;         // ISO-8601
}

// ── 触发结果 ──

interface TriggerResult {
  status: "SUCCEEDED" | "FAILED";
  matched: boolean;
  reason?: string;
  stateMachineId: string;
  deploymentId: string;
  triggerDeviceId: string;
  currentStateId: string;
  context: Record<string, any>;
  transitionIds: string[];
  chainCount: number;
  actions: ActionResult[];
  commandResults: CommandDispatchEntry[];
  projectionResults: ProjectionEntry[];
  durationMs: number;
  error?: string;             // status === "FAILED" 时
}

interface ActionResult {
  type: string;
  status: "SUCCEEDED" | "ERROR" | "WARNING" | "SKIPPED" | "DISPATCHED";
  // type-specific fields below...
}

interface CommandDispatchEntry {
  deviceId: string;
  cmdId: string;
  sent: boolean;
  status: string;
  action: string;
}

interface ProjectionEntry {
  type: "scene" | "patch" | "none";
  pageId: string;
  deviceId: string;
  sent: boolean;
  sections?: number;          // scene 推送的 section 数量
  patches?: number;           // patch 推送的变更数量
  reason?: string;            // type === "none" 时
}

// ── 动作类型（8 种）──

type ActionDef =
  | ContextSetAction
  | SectionAction
  | CommandDispatchAction
  | HttpRequestAction
  | ListInstantiateAction
  | TimerDelayAction
  | StateTransitionAction
  | PlatformCapabilityAction;

interface ContextSetAction {
  type: "context.set";
  name: string;               // 变量名
  value: any;                 // 支持 $变量 和 ${模板}
}

interface SectionAction {
  type: "section.add" | "section.update" | "section.remove";
  sectionId?: string;         // add 时可省略（自动生成），update/remove 时必填
  sectionType?: string;       // add 时必填
  fields?: Record<string, any>; // add/update 时填写
  pageId?: string;            // 可选，目标 page ID（空 = 全部 page）
}

interface CommandDispatchAction {
  type: "command.dispatch";
  commandId: string;
  params: Record<string, any>;
  pageId?: string;
}

interface HttpRequestAction {
  type: "http.request";
  url: string;
  method: "GET" | "POST" | "PUT" | "DELETE" | "PATCH";
  headers?: Record<string, string>;
  body?: any;
}

interface ListInstantiateAction {
  type: "list.instantiate";
  sectionType: string;
  itemsSource: string;        // $变量引用，如 "$http.response.body.items"
  itemTemplate: Record<string, any>; // 每项的字段模板，支持 $row.xxx
  pageId?: string;
}

interface TimerDelayAction {
  type: "timer.delay";
  durationMs: number;
}

interface StateTransitionAction {
  type: "state.transition";
  eventId: string;            // 链式转换的事件 ID
  event?: Record<string, any>; // 可选的附加事件字段
}

/** 同 command.dispatch，用于平台级能力调用 */
interface PlatformCapabilityAction {
  type: "platform.capability";
  commandId: string;
  params: Record<string, any>;
}

// ── 编辑器画布状态 ──

interface EditorModel {
  nodes: Array<{ id: string; x: number; y: number }>;
  viewport: { x: number; y: number; zoom: number };
}

// ── 部署创建响应 ──

interface CreateDeploymentResponse {
  id: string;
  stateMachineId: string;
  devices: string[];
  deployedAt: string;
  pushResults: ProjectionEntry[];
  allSent: boolean;
  warnings?: DeploymentWarning[];
}

interface DeploymentWarning {
  deviceId: string;
  message: string;
  severity: "warning";
  missingEvents?: string[];
  missingCommands?: string[];
  missingSections?: string[];
}
```

---

## 八、变量引用系统

状态机动作和 section 字段值中支持变量引用，后端自动解析。

### 8.1 可用变量

| 变量 | 含义 | 示例值 |
|------|------|--------|
| `$event.eventId` | 触发事件 ID | `"ui:action.click"` |
| `$event.deviceId` | 触发事件的设备 ID | `"esp32-abc123"` |
| `$event.nodeId` | 触发事件的节点 ID（如按钮 id） | `"ok"` |
| `$event.sectionId` | 触发事件的 section ID | `"actions"` |
| `$event.pageId` | 触发事件的 page ID | `"main"` |
| `$event.value` | 事件携带的值（如 toggle 状态） | `true` |
| `$event.source` | 触发来源 | `"DEVICE_EVENT"`, `"MANUAL"`, `"COMMAND_EVENT"` |
| `$context.xxx` | 上下文变量（`context.set` 写入） | `$context.clicked` |
| `$prev.xxx` | 上一个动作的执行结果 | `$prev.status` |
| `$http.response.body` | HTTP 请求的响应体 | `{ ... }` |
| `$http.response.status` | HTTP 响应状态码 | `200` |
| `$http.response.headers` | HTTP 响应头 | `{ ... }` |
| `$trigger_device` | 触发设备 ID | `"esp32-abc123"` |
| `$row.xxx` | `list.instantiate` 的行数据 | `$row.title` |
| `${event.xxx}` | 模板插值写法（同 $ 写法） | `${event.nodeId}` |
| `${ctx.xxx}` | 模板插值写法（`$context.` 的简写） | `${ctx.clicked}` |
| `${prev.xxx}` | 模板插值写法（`$prev.` 的简写） | `${prev.status}` |
| `${trigger_device}` | 模板插值写法 | `"esp32-abc123"` |

### 8.2 变量作用域

- **静态 section 字段**：进入状态时用当前 `$context` 解析（无 `$event`）
- **section.* 动作的 fields**：用完整的 runtime 上下文解析（含 `$event`、`$context`、`$prev`）
- **command.dispatch 的 params**：同上，完整上下文
- **list.instantiate 的 itemTemplate**：额外有 `$row` 指向当前迭代项

### 8.3 前端变量提示

在字段输入框中输入 `$` 时，弹出变量候选列表。候选列表根据上下文过滤：
- 在 `command.params` 或 `section.fields` 中 → 显示全部变量
- 在 `http.body` 中 → 显示 `$event.*`、`$context.*`、`$prev.*`
- 在非首个动作中 → 额外显示 `$prev.*`
- 在 `list.instantiate` 的 itemTemplate 中 → 额外显示 `$row.*`

---

## 九、运行时执行模型

### 9.1 触发流程

```
设备事件到达
  ↓
StateMachineEventBridge.onEvent()
  ↓
StateMachineService.handleDeviceEvent()
  ↓
找到 deviceId 匹配的所有部署记录
  ↓ 对每个部署:
triggerDeployment(sm, deployment, event, source)
  ├─ 构建 runtime 上下文 ($event + $context + triggerDeviceId)
  ├─ 从当前状态构建 currentPages（带变量解析）
  ├─ 链式循环（最多 100 次）:
  │   ├─ findTransition — 按 priority 降序匹配
  │   ├─ 快照 previousPages
  │   ├─ executeActions — section 动作原地修改 currentPages
  │   ├─ projectTransition(previousPages, currentPages) — diff → patch/scene
  │   ├─ 状态切换: toStateId → 重建目标状态 pages
  │   ├─ projectTransition(preTransitionPages, currentPages) — 状态切换 diff
  │   └─ 检查 __pendingEventId__ (state.transition 链式触发)
  └─ 保存 deployment（currentStateId + contextData 持久化）
```

### 9.2 投影策略（Projection）

`StateMachineProjectionService` 负责将页面变化推送到设备：

- **新 Page**（之前不存在）→ 推送 full scene
- **Page 级属性变化**（pageId、layout、autoScroll 等变化）→ 推送 full scene
- **Section 级变化** → diff 计算 patch（add/update/remove）
- **自适应阈值**：当 patch 数量超过 `totalSections / 2` 时，自动切换为 full scene（避免过多碎片 patch）
- 每次推送按 page 的 devices 列表发送到对应设备

---

## 十、事件桥接架构

```
入站事件流:
  设备 WebSocket → EventInputHandler → StateMachineEventBridge
    → StateMachineService.handleDeviceEvent()
    → 按 deviceId 匹配部署 → triggerDeployment()

命令生命周期事件流:
  CommandResultStreamService → StateMachineCommandEventBridge
    → StateMachineService.handleCommandEvent()
    → 按 deviceId 匹配部署 → triggerDeployment()

定时事件流:
  CronTriggerScheduler (每 60s 扫描)
    → 匹配 system:cron 转换 → StateMachineService.trigger()
```

命令生命周期事件 ID：
| 事件 ID | 触发条件 |
|---------|---------|
| `command.ack` | 设备确认收到命令 |
| `command.failed` | 命令执行失败 |
| `command.timeout` | 命令超时未确认 |
| `command.result` | 命令返回结果 |
| `command.dispatch` | 命令已下发 |
| `command.rejected` | 设备拒绝命令 |

---

## 十一、错误码

| 场景 | 后端返回 | 前端行为 |
|------|---------|---------|
| 状态机不存在 | `40400` | 跳转列表，toast 提示 |
| 校验失败 | `40000` + errors[] | 画布和编辑面板红色标注，汇总错误数 |
| boardTypes 为空 | `40000 "boardTypes is required"` | 创建对话框中标红 |
| boardTypes 修改被拒 | `40000 "boardTypes cannot be modified"` | 禁用编辑器中的板卡选择器 |
| deployment devices 为空 | `40000 "devices is required"` | 设备选择区标红 |
| definition 无 states | `40000 "definition has no states"` | 校验时提示 |
| trigger 时 device 不在部署中 | `40000 "device xxx is not in any deployment"` | toast 提示 |
| 状态机无部署 | `40000 "state machine has no deployments"` | toast 提示 |
| 设备能力部分不匹配 | 部署成功但返回 warnings[] | 黄色警告列表展示，允许继续 |
| 设备全部离线 | 部署成功但 warnings 中标注 | 黄色警告，不阻止部署 |
| 链式转换超限 | trigger 结果中 chainCount=100 | chainCount 字段用于前端展示 |
| 删除已部署的状态机 | 级联删除部署记录 | 确认对话框提示"将同时卸载 N 条部署记录" |
| 保存时网络错误 | — | 保留草稿，toast 提示重试 |

---

## 十二、前端契约边界

**不应做的事：**
- ❌ 不应在前端硬编码 eventId、commandId、sectionType 的枚举值（应从目录接口动态获取）
- ❌ 不应在前端自行校验 section/command 合法性（应由校验接口完成）
- ❌ 不应在操作成功后不拉取最新状态而依赖本地计算
- ❌ 不应手动拼接 action 或 section 的 JSON（应通过动态表单生成）
- ❌ 不应修改已有状态机的 `boardTypes` 字段（后端拒绝）

**应做的事：**
- ✅ 以 `/events/inbound-events` 和 `/board-types/{key}/...` 为合法值来源
- ✅ 创建时选择的 boardTypes 作为编辑器全流程的能力过滤器
- ✅ 操作成功后重新拉取状态
- ✅ 将校验结果直接映射到编辑器的错误标注（path → UI 位置）
- ✅ 动作编辑器中提供下拉/选择器，避免用户手写参数
- ✅ section、command 配置表单根据类型定义动态渲染
- ✅ 部署前可调用 `/state-machines/validate` 和 `/board-types/{key}/...` 做前端预校验
- ✅ 部署响应中的 `warnings` 数组需在前端呈现（黄色警告，不阻塞）
- ✅ trigger 响应的 `context` 和 `currentStateId` 可用于调试面板展示运行时状态
