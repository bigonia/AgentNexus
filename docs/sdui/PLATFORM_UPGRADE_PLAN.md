# SDUI 平台扩展升级方案

## 概述

当前 SDUI 平台后端已具备基础能力（设备管理、Section 编排、工作流引擎、二进制协议），但从平台产品视角看存在两个核心问题：

1. **功能模块散落** — 能力散落在不同包中，缺乏统一的模块边界和用户视角的功能分组
2. **设备交互单薄** — 设备控制只有一个通用 `{command, value}` 通道，缺少按能力类型分类的结构化交互界面（音频测试、RGB 调试、输入事件监控）

本文档定义平台 5 大功能模块的完整架构、模块间依赖、API 契约和实现路线。

---

## 1. 平台模块全景

```
┌─────────────────────────────────────────────────────────┐
│                    SDUI 平台                             │
├───────────┬──────────┬──────────┬──────────┬────────────┤
│ 1.设备总览 │2.设备控制台│3.工作流   │4.工作流   │5.模板管理  │
│  Fleet    │ Console  │ 编辑器    │ 运行时    │  Manager   │
├───────────┼──────────┼──────────┼──────────┼────────────┤
│ 设备列表   │ 能力面板  │ vue-flow │ 加载/卸载 │ Section预设│
│ 设备详情   │ 音频测试  │ 节点拖拽  │ 触发/事件 │ 场景预览   │
│ 能力展示   │ RGB调试  │ 变量绑定  │ 状态监控  │ 自动更新   │
│ 遥测历史   │ 输入监控  │ 属性配置  │ Webhook  │ 能力适配   │
│ 认领管理   │ 系统控制  │ 保存/加载 │ 变量存储  │ 布局降级   │
└───────────┴──────────┴──────────┴──────────┴────────────┘
```

### 模块职责边界

| 模块 | 核心职责 | 目标用户 |
|------|---------|---------|
| 1. 设备总览 | 设备注册、在线状态、认领、遥测、能力快照 | 运维/管理员 |
| 2. 设备控制台 | 按能力分类的交互式设备调试面板 | 开发者/运维 |
| 3. 工作流编辑器 | 可视化编排工作流（触发器→动作链→页面） | 业务配置者 |
| 4. 工作流运行时 | 加载/卸载/触发/事件分发/变量管理 | 自动化（后台） |
| 5. 模板管理 | Section 预设库、场景下发、能力适配 | 模板设计者 |

### 模块依赖关系

```
模块1 设备总览 (基础层，无依赖)
  ↑
模块2 设备控制台 (依赖模块1的设备信息和能力快照)
  ↑
模块3 工作流编辑器 (依赖模块1的能力快照做节点过滤，依赖模块2的调试接口做节点预览)
  ↑
模块4 工作流运行时 (依赖模块1的会话管理，依赖模块3的定义产物)
  ↑
模块5 模板管理 (依赖模块1的能力快照做适配)
```

---

## 2. 模块1：设备总览 — 当前状态与增强

### 2.1 当前状态

| 功能 | 后端 | 前端 |
|------|------|------|
| 设备列表 `GET /devices` | ✓ | 待建 |
| 设备详情 `GET /devices/{id}` | ✓ | 待建 |
| 认领 `POST /devices/{id}/claim` | ✓ | 待建 |
| 遥测 `GET /devices/{id}/telemetry` | ✓ | 待建 |
| 通用控制 `POST /devices/{id}/control` | ✓ | 待建 |
| 运维概览 `GET /ops/overview` | ✓ | 待建 |

### 2.2 待增强

**后端：**

- `GET /devices/{id}/detail` 增加 `capabilitiesParsed` 字段（已解析的能力摘要，前端无需自己解析 `capabilitiesSnapshot` JSON 字符串）：
  ```json
  {
    "deviceId": "...",
    "board": "AMOLED_175",
    "screenShape": "round",
    "screenWidth": 466,
    "screenHeight": 466,
    "inputMode": "touch",
    "capabilitiesParsed": {
      "inputs": ["ui.interaction", "buttons.input", "motion", "audio.record"],
      "outputs": ["display.brightness", "rgb.effect", "audio.prompt", "audio.volume"],
      "sectionTypes": ["hero_section", "metric_section", ...],
      "layouts": ["vertical_scroll", "fixed_single"]
    },
    "availableCommands": ["display.brightness.set", "rgb.effect.set", ...]
  }
  ```

- `GET /ops/overview` 增加工作流统计（运行中工作流数、已加载设备数）

**前端：**

- 设备列表页：状态标签（ONLINE 绿色 / OFFLINE 灰色）、板型图标、最后在线时间
- 设备详情页：能力标签云、屏幕尺寸可视化、已加载工作流名称

---

## 3. 模块2：设备控制台（重点新增模块）

### 3.1 设计目标

为每个在线设备提供按能力分类的交互式调试面板。终端上报的 `capabilities` 决定展示哪些面板 —— AMOLED_175 展示 RGB/音频/IMU 面板，LCD_085 仅展示基础控制。

### 3.2 面板布局 API

```
GET /api/v1/sdui/devices/{deviceId}/console/layout
```

响应按设备实际能力动态生成面板布局描述：

```json
{
  "deviceId": "A1B2C3D4E5F6",
  "online": true,
  "panels": [
    {
      "id": "display",
      "label": "显示控制",
      "icon": "monitor",
      "order": 1,
      "controls": [
        {"type": "slider", "id": "brightness", "label": "亮度", "command": "display.brightness.set", "min": 0, "max": 100, "current": 80}
      ]
    },
    {
      "id": "audio",
      "label": "音频",
      "icon": "volume-2",
      "order": 2,
      "controls": [
        {"type": "preset_buttons", "id": "presets", "label": "提示音", "options": ["notification", "success", "error", "warning", "click", "beep"]},
        {"type": "tts_input", "id": "tts", "label": "TTS 朗读"},
        {"type": "slider", "id": "volume", "label": "音量", "command": "audio.volume.set", "min": 0, "max": 100}
      ]
    },
    {
      "id": "rgb",
      "label": "RGB 灯效",
      "icon": "palette",
      "order": 3,
      "controls": [
        {"type": "color_picker", "id": "color", "label": "颜色"},
        {"type": "mode_selector", "id": "mode", "label": "模式", "options": ["solid", "blink", "breathe", "rainbow", "chase"]}
      ]
    },
    {
      "id": "inputs",
      "label": "输入事件",
      "icon": "activity",
      "order": 4,
      "stream": "/api/v1/sdui/devices/{deviceId}/console/events/stream"
    },
    {
      "id": "system",
      "label": "系统",
      "icon": "settings",
      "order": 5,
      "controls": [
        {"type": "button", "id": "reboot", "label": "重启设备", "command": "system.reboot", "confirm": true}
      ]
    }
  ]
}
```

后端实现：`ConsoleLayoutService` 读取设备 capability 快照，为启用的能力生成对应的面板和控件描述。

### 3.3 音频控制 API

```
POST /api/v1/sdui/devices/{deviceId}/console/audio/preset
Body: {"preset": "success"}
Response: {"sent": true, "deviceId": "...", "preset": "success", "samples": 4410, "durationMs": 200}
```

```
POST /api/v1/sdui/devices/{deviceId}/console/audio/tts
Body: {"text": "设备已连接"}
Response: {"sent": true, "deviceId": "...", "text": "设备已连接", "samples": 0}
```

- `preset` → `AudioService.playPreset(deviceId, preset)` → 生成 PCM → base64 → `audio/play` 下发
- `tts` → `AudioService.playTts(deviceId, text)` → 如果有 TtsProvider 则合成，否则返回 `{"sent": false, "reason": "no TTS provider configured"}`

### 3.4 RGB 控制 API

```
POST /api/v1/sdui/devices/{deviceId}/console/rgb/apply
Body: {
  "mode": "breathe",
  "color": "#ff0000",
  "periodMs": 2000
}
Response: {"sent": true, "deviceId": "...", "cmdId": "..."}
```

后端实现：`RgbControlService` 将策略参数翻译为终端命令：
- `{mode: "solid", color: "#ff0000"}` → `rgb.effect.set` value=`"solid:ff0000"`
- `{mode: "breathe", color: "#00ff00", periodMs: 2000}` → `rgb.effect.set` value=`"breathe:00ff00:2000"`
- `{mode: "off"}` → `rgb.off` value=`null`

### 3.5 输入事件流 API

```
GET /api/v1/sdui/devices/{deviceId}/console/events/stream
Content-Type: text/event-stream
```

SSE 流，实时推送终端上报的输入事件：

```
event: button.event
data: {"ts": 1715778000, "button": "boot", "event": "short_press"}

event: motion.shake
data: {"ts": 1715778003}

event: ui.click
data: {"nodeId": "btn_submit", "ts": 1715778005}
```

后端实现：`EventStreamService` 在 `EventInputHandler` 中注册监听器，匹配到目标 deviceId 时写入 `SseEmitter`。

### 3.6 系统控制 API

```
POST /api/v1/sdui/devices/{deviceId}/console/system/reboot
Body: {}
Response: {"sent": true, "deviceId": "...", "cmdId": "..."}
```

### 3.7 前端组件对应

```
DeviceConsole.vue                  ← 根据 /console/layout 动态渲染面板
  ├── panels/
  │   ├── DisplayPanel.vue         ← type=slider 控件
  │   ├── AudioPanel.vue           ← type=preset_buttons + tts_input + slider
  │   ├── RgbPanel.vue             ← type=color_picker + mode_selector
  │   ├── InputEventPanel.vue      ← EventSource → 实时列表
  │   └── SystemPanel.vue          ← type=button (confirm) 控件
  └── composables/
      ├── useConsoleLayout.js      ← fetch /console/layout
      ├── useAudioControl.js       ← POST /console/audio/*
      ├── useRgbControl.js         ← POST /console/rgb/*
      └── useEventStream.js        ← EventSource /console/events/stream
```

### 3.8 实现清单

| 序号 | 文件 | 说明 |
|------|------|------|
| 3.8.1 | `service/ConsoleLayoutService.java` | 能力快照 → 面板布局描述 |
| 3.8.2 | `service/ConsoleControlService.java` | 统一控制下发入口，含命令合法性校验 |
| 3.8.3 | `service/RgbControlService.java` | RGB 策略 → 终端命令翻译 |
| 3.8.4 | `service/EventStreamService.java` | SSE 事件流管理 |
| 3.8.5 | `controller/DeviceConsoleController.java` | `/console/*` REST 端点 |
| 3.8.6 | `AudioService.java` 完善 | 添加 `getPresets()` 枚举、返回播放结果详情 |
| 3.8.7 | 前端 `DeviceConsole.vue` + 5 个子面板 | Vue3 组件 |

---

## 4. 模块3：工作流编辑器 — 当前状态与增强

### 4.1 当前状态

| 功能 | 后端 | 前端 |
|------|------|------|
| 定义 CRUD | ✓ | 待建 |
| 节点类型清单 `GET /workflow/node-types` | ✓ | 待建 |
| 定义 JSON 序列化 | ✓ | — |

### 4.2 能力感知的节点过滤

当前 `GET /workflow/node-types` 返回全量节点类型（所有 4 种触发器 + 7 种动作 + 12 种 Section），不区分设备能力。需要增加可选参数：

```
GET /api/v1/sdui/workflow/node-types?deviceId=A1B2C3D4E5F6
```

当提供 `deviceId` 时，后端检查设备能力快照，在返回的节点列表中增加 `available` 字段：

```json
{
  "actions": [
    {"type": "play_audio", "label": "播放音频", "params": ["preset"], "available": true,
     "requires": ["audio.prompt"]},
    {"type": "control", "label": "执行器命令", "params": ["command", "value"], "available": true,
     "requires": []},
    {"type": "tts", "label": "TTS 朗读", "params": ["text"], "available": true,
     "requires": ["audio.prompt"]}
  ],
  "unavailableReasons": {
    "rgb.effect": "device LCD_085 does not support RGB"
  }
}
```

编辑器中：`available=false` 的节点置灰但可拖拽（提示用户该节点在该设备上不可用），`available=true` 的节点正常显示。

### 4.3 变量插值语法暴露

`getNodeTypes()` 中每个 action 的 `params` 增加 `syntax` 提示：

```json
{
  "type": "tts",
  "label": "TTS 朗读",
  "params": [
    {"name": "text", "label": "文本内容", "type": "string",
     "syntax": "支持 $data.xxx / $trigger.xxx / $env.XXX / 字面量"}
  ]
}
```

编辑器在属性面板的文本输入框旁显示语法提示，输入 `$` 时触发自动补全。

### 4.4 节点调试预览（与模块2联动）

编辑器属性面板增加 **预览按钮**，调用模块2的调试接口：

| 节点类型 | 预览按钮 | 调用接口 |
|---------|---------|---------|
| play_audio | ▸ 试听 | `POST /console/audio/preset` |
| tts | ▸ 朗读 | `POST /console/audio/tts` |
| control (rgb) | ▸ 应用 | `POST /console/rgb/apply` |
| patch_section | ▸ 下发 | 生成临时 Section 场景下发 |
| fetch | ▸ 测试 | 执行 HTTP 请求并展示返回数据 |

编辑器中需选择一个在线设备作为调试目标。

### 4.5 复合节点扩展

新增 2 个 ActionDef 子类型以支持策略封装：

```java
// 新增到 ActionDef.java
record RgbAction(String mode, String color, Integer periodMs, Integer durationMs) implements ActionDef {}
record SequenceAction(List<ActionDef> steps) implements ActionDef {}  // 顺序执行子动作链
```

`RgbAction` 在 `ActionExecutor` 中展开为策略翻译 + 命令下发，实现见 3.4 节 `RgbControlService`。

### 4.6 实现清单

| 序号 | 文件 | 说明 |
|------|------|------|
| 4.6.1 | `workflow/ActionDef.java` 扩展 | 新增 `RgbAction`, `SequenceAction` |
| 4.6.2 | `workflow/ActionExecutor.java` 扩展 | 新增 RgbAction/SequenceAction 的 dispatch 分支 |
| 4.6.3 | `workflow/WorkflowService.getNodeTypes()` 增强 | 支持 `?deviceId=` 参数，返回 `available` 字段和 `requires` |
| 4.6.4 | `workflow/ActionExecutor.java` 增加能力过滤 | `supportsAction(deviceId, action)` 运行时检查 |
| 4.6.5 | 前端工作流编辑器 | vue-flow 画布 + 节点面板 + 属性面板 |

---

## 5. 模块4：工作流运行时 — 当前状态与增强

### 5.1 当前状态

| 功能 | 后端 |
|------|------|
| `loadWorkflow` / `unloadWorkflow` | ✓ |
| `triggerManually` | ✓ |
| `fireEvent` (设备事件) | ✓ |
| TriggerScheduler (cron/webhook/device_event) | ✓ |
| 变量存储 `instance.variables` | ✓ |

### 5.2 待增强

**运行时能力过滤：**

`ActionExecutor.execute()` 增加前置过滤（见 4.6.4），避免在无能力设备上静默执行失败动作。

**工作流实例监控：**

```
GET /api/v1/sdui/workflow/instances
```

返回所有运行中的工作流实例（设备→工作流映射），含运行时长、当前页面、最后触发时间。

**输入事件模拟（调试用）：**

```
POST /api/v1/sdui/workflow/{deviceId}/simulate-event
Body: {"event": "motion.shake", "payload": {"ts": 1715778000}}
```

直接注入工作流引擎的 `fireEvent` 路径，跳过终端上行链路。用于在没有物理设备的情况下测试工作流逻辑。

### 5.3 实现清单

| 序号 | 文件 | 说明 |
|------|------|------|
| 5.3.1 | `workflow/ActionExecutor.java` | 能力过滤 |
| 5.3.2 | `workflow/WorkflowController.java` | 增加 instances 列表 + simulate-event 端点 |
| 5.3.3 | `workflow/WorkflowService.java` | 增加 listInstances / simulateEvent 方法 |

---

## 6. 模块5：模板管理 — 当前状态与增强

### 6.1 当前状态

| 功能 | 后端 |
|------|------|
| 5 种 Section 预设 | ✓ |
| Section 场景/补丁下发 | ✓ |
| 能力适配（类型过滤/截断/降级） | ✓ |
| 自动更新调度器 | ✓ |
| 预设列表 API | ✓ |

### 6.2 待增强

- `GET /section/presets` 增加每种预设的 Section 数量、适用屏幕尺寸说明
- Section 能力查询 `GET /section/capability/{deviceId}` 增加推荐的布局和 Section 类型
- 预设扩展：新增 `audio_test` 预设（含音量滑块 Section + 操作按钮 Section）

模块5整体改动量最小，主要作为模块2和模块3的下游支撑。

---

## 7. 跨模块能力沉淀

开发过程中，将可复用能力沉淀为独立 Service，供多模块共享：

| Service | 提供能力 | 被使用于 |
|---------|---------|---------|
| `AudioService` | PCM 生成、base64 编码、TTS 调度 | 模块2（音频测试）→ 模块3（play_audio/tts 节点）→ 模块4（工作流运行时） |
| `RgbControlService` | RGB 策略翻译 | 模块2（RGB 调试）→ 模块3（RgbAction 节点）→ 模块4（工作流运行时） |
| `ConsoleLayoutService` | 能力→面板布局 | 模块2（控制台渲染）→ 模块3（节点可用性） |
| `EventStreamService` | 设备事件 SSE 推送 | 模块2（输入事件监控）→ 模块3（节点调试反馈） |

---

## 8. 实现路线

### 第一期：设备控制台（模块2）— 解决当前核心痛点

| 阶段 | 内容 | 预计后端代码量 |
|------|------|--------------|
| 1.1 | `ConsoleLayoutService` — 能力→面板布局 | ~80 行 |
| 1.2 | `RgbControlService` — RGB 策略翻译 | ~60 行 |
| 1.3 | `EventStreamService` — SSE 事件流 | ~80 行 |
| 1.4 | `DeviceConsoleController` — `/console/*` 端点 | ~80 行 |
| 1.5 | `AudioService` 完善 — `getPresets()` + 播放结果 | ~30 行 |

**第一期产出**：后端 `/console/*` API 完整可用，curl/Postman 可测试所有设备控制功能。

### 第二期：工作流增强（模块3+4）— 能力感知 + 复合节点

| 阶段 | 内容 | 预计后端代码量 |
|------|------|--------------|
| 2.1 | `getNodeTypes(?deviceId=)` 能力过滤 | ~40 行 |
| 2.2 | `ActionExecutor` 运行时能力过滤 | ~30 行 |
| 2.3 | `RgbAction` + `SequenceAction` 扩展 | ~60 行 |
| 2.4 | 输入事件模拟 API | ~30 行 |
| 2.5 | 工作流实例列表 API | ~20 行 |

### 第三期：前端落地

| 阶段 | 内容 |
|------|------|
| 3.1 | 设备总览（列表 + 详情） |
| 3.2 | 设备控制台（5 个能力面板） |
| 3.3 | 工作流编辑器（vue-flow 集成） |
| 3.4 | 模板管理页 |

### 第四期：TTS 与服务集成

| 阶段 | 内容 |
|------|------|
| 4.1 | `TtsProvider` 实现（边缘 TTS 或云端 TTS） |
| 4.2 | TTS 配置管理 API |
| 4.3 | 音频预设库扩展（自定义音频上传） |

---

## 9. API 总览

### 现有 API（不变）

所有已有 API 路径保持兼容，参数和响应格式不变。

### 新增 API

| 方法 | 路径 | 模块 | 说明 |
|------|------|------|------|
| GET | `/devices/{deviceId}/console/layout` | 2 | 获取设备控制台面板布局 |
| POST | `/devices/{deviceId}/console/audio/preset` | 2 | 播放音频预设 |
| POST | `/devices/{deviceId}/console/audio/tts` | 2 | TTS 朗读 |
| POST | `/devices/{deviceId}/console/rgb/apply` | 2 | 应用 RGB 灯效 |
| GET | `/devices/{deviceId}/console/events/stream` | 2 | SSE 输入事件流 |
| POST | `/devices/{deviceId}/console/system/reboot` | 2 | 重启设备 |
| GET | `/workflow/node-types?deviceId=` | 3 | 能力感知的节点类型清单 |
| POST | `/workflow/{deviceId}/simulate-event` | 4 | 模拟输入事件 |
| GET | `/workflow/instances` | 4 | 运行中实例列表 |

---

## 10. 架构约束

- **不引入新的通信协议** — 控制命令保持 JSON over WebSocket（`cmd/control` topic），Section 保持 UI3 二进制帧
- **不引入新的数据库表** — 设备控制台、事件流均为无状态 API，不持久化
- **Controller 不跨模块依赖** — 每个模块的 Controller 只注入本模块和前序依赖模块的 Service
- **前端组件按面板拆分** — 每个能力面板独立组件，由 `console/layout` 响应驱动加载，不硬编码面板列表
