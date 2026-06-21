# SDUI 调试模块 API

本文档定义 SDUI 的“设备调试”接口，面向前端调试页、联调工具、运维排障场景，聚焦“直接对真实设备做什么、如何看到结果”。

本文档采用“调试主文档 + Section 子文档”的边界：

- 主接口：命令目录、命令执行、命令观测、事件观测
- Section 调试：参阅 [`DEBUG_SECTION_API.md`](DEBUG_SECTION_API.md)

本文档不覆盖：

- 设备查看：参阅 [`DEVICE_VIEW_API.md`](DEVICE_VIEW_API.md)
- 能力事实查询：参阅能力目录和事件 catalog 文档
- 状态机编排：参阅 [`STATE_MACHINE_FRONTEND_INTEGRATION.md`](STATE_MACHINE_FRONTEND_INTEGRATION.md)

---

## 1. 概览

### 1.1 模块目标

调试模块回答的问题是：

- 设备当前能执行哪些命令
- 我如何直接执行一次命令或下发一次 Section 页面
- 我如何实时观测命令结果和输入事件
- 我如何拿到 Section 调试所需的编辑辅助数据

### 1.2 Base Path

所有接口基于：

`/api/v1/sdui/debug`

### 1.3 通用响应

大多数接口返回 `ApiResponse<T>`：

```json
{
  "code": 20000,
  "message": "Success",
  "data": {}
}
```

两条流式接口返回 `text/event-stream`：

- `GET /api/v1/sdui/debug/{deviceId}/commands/stream`
- `GET /api/v1/sdui/debug/{deviceId}/events/stream`

### 1.4 与其他模块的边界

调试模块包含：

- 命令可执行目录
- 命令直接执行
- 命令详情、历史、实时结果流
- Section / Page 场景调试
- 输入事件实时观测
- Section page-first 编辑辅助元数据

调试模块不包含：

- `GET /api/v1/sdui/devices/*`
- `GET /api/v1/sdui/capabilities/{deviceId}`
- `GET /api/v1/sdui/capabilities/{deviceId}/tree`
- `GET /api/v1/sdui/capabilities/{deviceId}/events`
- `GET /api/v1/sdui/capabilities/{deviceId}/sections`
- `GET /api/v1/sdui/capabilities/{deviceId}/commands`
- `GET /api/v1/sdui/capabilities/{deviceId}/protocol`
- 全部 `state-machines/*`

需要特别区分：

- `capabilities/*` 回答“支持什么”
- `debug/*` 回答“现在直接做什么”
- `state-machines/*` 回答“如何编排自动化”

---

## 2. 前端页面映射

### 2.1 命令调试页

建议使用：

- `GET /api/v1/sdui/debug/{deviceId}/commands`
- `POST /api/v1/sdui/debug/{deviceId}/command`
- `GET /api/v1/sdui/debug/{deviceId}/commands/{cmdId}`
- `GET /api/v1/sdui/debug/{deviceId}/commands/history`
- `GET /api/v1/sdui/debug/{deviceId}/commands/stream`

推荐接入顺序：

1. 先加载命令目录
2. 再执行命令
3. 用详情 / 历史补充排障信息
4. 用 SSE 观察实时结果

### 2.2 Section 调试页

建议使用：

- 参阅 [`DEBUG_SECTION_API.md`](DEBUG_SECTION_API.md)

推荐接入顺序：

1. 先读 `section-editor`
2. 首次渲染调用 `section`
3. 后续交互更新调用 `section/patch`
4. 需要回显当前页面状态时调用 `section/state`

说明：

- Section 调试已经采用 page-first 模型，与工作流保持同一套 `pageId + sectionId` 语义
- `section-editor` 现在输出 page-first 协议视图
- `sectionTypes[].displayFields` 与 `commands/events/sections` 能力目录来自统一设备协议目录
- 需要一次性读取设备命令、Section、事件目录时，可调用 `GET /api/v1/sdui/capabilities/{deviceId}/protocol`

### 2.3 事件观测页

建议使用：

- `GET /api/v1/sdui/debug/{deviceId}/events/stream`

该接口适合展示设备输入事件时间线，不适合当作能力目录接口使用。

---

## 3. API 明细

## 3.1 获取命令调试目录

- 方法：`GET`
- 路径：`/api/v1/sdui/debug/{deviceId}/commands`

### 用途

返回当前设备在调试场景下可直接调用的命令目录。

### 成功响应示例

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "online": true,
    "deviceCommands": [
      {
        "command": "display.brightness.set",
        "params": [
          { "name": "value", "type": "int" }
        ]
      },
      {
        "command": "rgb.effect.set",
        "params": [
          { "name": "mode", "type": "enum" },
          { "name": "r", "type": "int" },
          { "name": "g", "type": "int" },
          { "name": "b", "type": "int" },
          { "name": "brightness", "type": "int" },
          { "name": "period_ms", "type": "int" },
          { "name": "step_ms", "type": "int" }
        ]
      }
    ]
  }
}
```

### 字段说明

每个 command 对象仅包含协议必要字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `command` | string | 命令标识，执行时使用此值 |
| `params` | array | 参数 schema 列表 |

每个 param：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | string | 参数名 |
| `type` | string | 参数类型：`string` / `int` / `float` / `bool` / `enum` / `object` |

### 前端使用建议

- 前端命令面板直接使用 `deviceCommands` 渲染即可
- 设备不支持的命令不会出现（已按设备能力过滤），开发者无需二次过滤
- 命令的展示名称、分组等 UI 信息由前端自行维护映射表，后端不返回

### 命令参数速查

以下为全部可能出现的命令及其参数、约束，前端可据此构建参数表单的校验规则与枚举下拉。

---

**`display.brightness.set`** — 设置屏幕亮度

| 参数 | 类型 | 必填 | 约束 | 默认值 |
|------|------|------|------|--------|
| `value` | int | 是 | 0–100 | - |

---

**`device.reboot`** — 远程重启设备

无参数。

---

**`audio.volume.set`** — 设置扬声器音量

| 参数 | 类型 | 必填 | 约束 | 默认值 |
|------|------|------|------|--------|
| `value` | int | 是 | 0–100 | - |

---

**`audio.prompt.play`** — 播放预设提示音

| 参数 | 类型 | 必填 | 约束 | 默认值 |
|------|------|------|------|--------|
| `preset` | enum | 是 | `notification`, `success`, `error`, `warning`, `click`, `beep` | - |

---

**`audio.tts.speak`** — TTS 文本转语音

| 参数 | 类型 | 必填 | 约束 | 默认值 |
|------|------|------|------|--------|
| `text` | string | 是 | - | - |

---

**`rgb.effect.set`** — 设置 RGB 灯光效果

| 参数 | 类型 | 必填 | 约束 | 默认值 |
|------|------|------|------|--------|
| `mode` | enum | 否 | `solid`, `blink`, `breathe`, `rainbow`, `chase` | `solid` |
| `r` | int | 是 | 0–255 | - |
| `g` | int | 是 | 0–255 | - |
| `b` | int | 是 | 0–255 | - |
| `brightness` | int | 否 | 0–255 | 120 |
| `period_ms` | int | 否 | 200–5000 | 1200 |
| `step_ms` | int | 否 | 10–500 | 40 |

**`mode` 取值说明：**

| 值 | 含义 |
|------|------|
| `solid` | 常亮单色 |
| `blink` | 闪烁 |
| `breathe` | 呼吸渐变 |
| `rainbow` | 彩虹渐变 |
| `chase` | 流水灯 |

---

**`rgb.off`** — 关闭所有 RGB 灯光

无参数。

---

> **注意：** 以上速查表是全部可能命令的全集。实际 `GET /commands` 返回值取决于设备上报的 capability，未报能力则不会出现对应命令。前端应始终以接口实际返回的 `deviceCommands` 为准。

### 备注

- `deviceCommands` 来自设备能力投影和统一设备协议目录
- 接口只返回协议契约（command + params schema），展示信息由前端约定

## 3.2 执行命令

- 方法：`POST`
- 路径：`/api/v1/sdui/debug/{deviceId}/command`

### 用途

直接对设备或平台托管能力发起一次调试执行。

### Body

```json
{
  "command": "display.brightness.set",
  "params": {
    "value": 80
  }
}
```

### 成功响应示例

设备命令执行：

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "sent": true,
    "deviceId": "esp32-A1B2C3",
    "command": "display.brightness.set",
    "cmdId": "cmd-001",
    "dispatchStatus": "sent",
    "ackStatus": "PENDING"
  }
}
```

参数校验失败：

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "sent": false,
    "deviceId": "esp32-A1B2C3",
    "command": "display.brightness.set",
    "status": "VALIDATION_FAILED",
    "validationErrors": [
      "value: required field missing"
    ]
  }
}
```

平台能力执行：

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "command": "audio.prompt.play",
    "status": "OK",
    "sent": true
  }
}
```

### 错误响应

设备离线：

```json
{
  "code": 40000,
  "message": "device is offline",
  "data": null
}
```

缺少命令名：

```json
{
  "code": 40000,
  "message": "command is required",
  "data": null
}
```

### 前端使用建议

- 不要只根据 `code == 20000` 判断执行成功。
- 若返回中包含 `status=VALIDATION_FAILED` 或 `sent=false`，应视为业务失败。
- 平台能力和设备命令可复用同一个执行面板，但结果区需要明确显示执行来源。

### 备注与限制

- 校验失败不是错误响应，而是成功包装中的业务失败结果。
- 平台能力命令和设备命令共用同一入口，但走不同 runtime。

## 3.3 获取命令详情

- 方法：`GET`
- 路径：`/api/v1/sdui/debug/{deviceId}/commands/{cmdId}`

### 用途

查询单条命令的调试详情。

### 成功响应示例

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "cmdId": "cmd-001",
    "deviceId": "esp32-A1B2C3",
    "command": "display.brightness.set",
    "action": "display.brightness.set",
    "params": {
      "value": 80
    },
    "dispatchStatus": "sent",
    "ackStatus": "ACKED",
    "reason": null,
    "createdAt": "2026-06-07T10:00:00",
    "ackAt": "2026-06-07T02:00:01Z"
  }
}
```

### 错误响应

`cmdId` 不存在时：

```json
{
  "code": 40400,
  "message": "command not found",
  "data": null
}
```

## 3.4 获取命令历史

- 方法：`GET`
- 路径：`/api/v1/sdui/debug/{deviceId}/commands/history`

### Query 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `limit` | int | 否 | 返回条数，默认 `20`，有效范围 `1-100` |

### 成功响应示例

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "history": [
      {
        "cmdId": "cmd-001",
        "deviceId": "esp32-A1B2C3",
        "command": "display.brightness.set",
        "action": "display.brightness.set",
        "params": {
          "value": 80
        },
        "dispatchStatus": "sent",
        "ackStatus": "ACKED",
        "reason": null,
        "createdAt": "2026-06-07T10:00:00",
        "ackAt": "2026-06-07T02:00:01Z"
      }
    ]
  }
}
```

### 前端使用建议

- 适合做最近执行记录表格，不建议代替长期审计查询。

## 3.5 订阅命令结果流

- 方法：`GET`
- 路径：`/api/v1/sdui/debug/{deviceId}/commands/stream`
- 返回：`text/event-stream`

### SSE 事件名

- `connected`
- `command_result`

### `connected` 事件示例

```json
{
  "deviceId": "esp32-A1B2C3",
  "message": "Command result stream connected"
}
```

### `command_result` 事件示例

```json
{
  "phase": "ack",
  "deviceId": "esp32-A1B2C3",
  "cmdId": "cmd-001",
  "command": "display.brightness.set",
  "action": "display.brightness.set",
  "status": "ACKED",
  "reason": null,
  "createdAt": "2026-06-07T10:00:00",
  "ackAt": "2026-06-07T02:00:01Z"
}
```

### 前端使用建议

- 推荐在命令调试页建立单独订阅，把 `command_result` 作为实时状态更新源。
- 页面切换或设备切换时主动断开旧连接。

### 备注与限制

- 当前每个设备只支持一个命令结果 emitter；新连接会顶掉旧连接。

## 3.6 Section / Page 调试

Section 调试已经拆分到独立文档：

- [`DEBUG_SECTION_API.md`](DEBUG_SECTION_API.md)

该子文档覆盖：

- `POST /api/v1/sdui/debug/{deviceId}/section`
- `POST /api/v1/sdui/debug/{deviceId}/section/patch`
- `GET /api/v1/sdui/debug/{deviceId}/section/state`
- `GET /api/v1/sdui/debug/{deviceId}/section-editor`

统一约束：

- debug 侧 Section 调试采用 page-first 模型
- 首次渲染用完整 page scene
- 后续更新用 `pageId + sectionId`
- 状态读取围绕 page state

## 3.7 订阅输入事件流

- 方法：`GET`
- 路径：`/api/v1/sdui/debug/{deviceId}/events/stream`
- 返回：`text/event-stream`

### 用途

实时观测设备输入事件。该接口不是能力事件目录接口。

### SSE 事件名

- 初始事件：`connected`
- 后续事件：优先使用真实 `event` 名；若为空则为 `input_event`

### `connected` 事件示例

```json
{
  "deviceId": "esp32-A1B2C3",
  "message": "Event stream connected"
}
```

### 输入事件示例

```json
{
  "ts": 1717725600000,
  "nodeId": "pwr",
  "event": "button.press"
}
```

### 前端使用建议

- 适合做实时事件时间线、事件调试面板、交互联调日志。
- 如果需要“设备支持哪些事件”，应调用能力目录接口，而不是依赖这条流。

### 备注与限制

- 能力事件目录应查看 `GET /api/v1/sdui/capabilities/{deviceId}/events`。
- 当前每个设备只支持一个事件 emitter；新连接会顶掉旧连接。

---

## 4. 数据模型

### 4.1 命令目录项模型

字段：

- `command`
- `displayName`
- `params`
- `source`
- `runtimeHandler`
- `capabilityGroup`

### 4.2 命令参数字段模型

字段：

- `name`
- `type`
- `children`

### 4.3 命令执行结果模型

设备命令执行字段：

- `sent`
- `deviceId`
- `command`
- `cmdId`
- `dispatchStatus`
- `ackStatus`

校验失败字段：

- `sent`
- `deviceId`
- `command`
- `status`
- `validationErrors`

### 4.4 命令详情 / 历史项模型

字段：

- `cmdId`
- `deviceId`
- `command`
- `action`
- `params`
- `dispatchStatus`
- `ackStatus`
- `reason`
- `createdAt`
- `ackAt`

### 4.5 Section / Page 调试模型

参阅 [`DEBUG_SECTION_API.md`](DEBUG_SECTION_API.md)。

### 4.6 命令 SSE 事件模型

事件名：

- `connected`
- `command_result`

`command_result` payload 字段：

- `phase`
- `deviceId`
- `cmdId`
- `command`
- `action`
- `status`
- `reason`
- `createdAt`
- `ackAt`

### 4.7 输入事件 SSE 事件模型

事件名：

- `connected`
- 真实 `event` 名或 `input_event`

payload 字段：

- `ts`
- `nodeId`
- `event`

---

## 5. 已知问题与前端约束

### 5.1 DebugController 同时承载执行与编辑器辅助接口

当前调试控制器里同时有：

- 命令 / Section 执行接口
- 观测接口
- Section 编辑器辅助接口

因此其语义不完全纯。前端接入时应区分“主调试行为”和“辅助元数据”。

### 5.2 `POST /command` 的失败语义不统一

`POST /command` 存在两类失败：

- 错误响应：例如设备离线、缺少命令名
- 成功包装中的业务失败：例如 `VALIDATION_FAILED`

前端不能只看 `code`。

### 5.3 Section 调试已拆分为 page-first 子文档

Section 调试部分已经拆到 [`DEBUG_SECTION_API.md`](DEBUG_SECTION_API.md)。

当前统一语义是：

1. 读 `section-editor`
2. 发完整 page scene
3. 用 `section/patch` 按 `pageId + sectionId` 更新
4. 用 `section/state` 读取当前 page state

这条链路是调试态 page state，不是设备真实 UI 回报。

### 5.4 SSE 仅支持每设备单连接

两条流：

- `commands/stream`
- `events/stream`

当前都只支持每设备一个 emitter。新连接会顶掉旧连接，前端要避免同一设备多页面同时长连。

---

## 6. 接入建议

推荐的调试页接入顺序：

1. 命令调试页先接 `GET /commands`
2. 执行命令走 `POST /command`
3. 实时反馈接 `GET /commands/stream`
4. 历史补接 `GET /commands/history`
5. Section 调试先接 `GET /section-editor`
6. 首次渲染调用 `POST /section`
7. 后续交互更新调用 `POST /section/patch`
8. 编辑回显或调试确认调用 `GET /section/state`
9. 输入事件观测接 `GET /events/stream`

如果只是做设备查看页，不需要引入本篇接口。
