# SDUI 平台升级方案：从设备控制到工作流管理

## 概述

当前平台已具备扎实的基础能力（设备管理、Section 编排、工作流引擎、二进制协议），但存在两个结构性短板：

1. **设备调试能力缺失** — 没有交互式控制台，开发者只能通过通用 `{command, value}` 接口盲调设备
2. **工作流停留在自动化层级** — 只能做"定时拉数据→推卡片"式的单向自动化，无法支撑"用户点击按钮→切换状态→更新 UI"的交互式应用

本文档定义从"设备控制台 → 工作流引擎增强 → Applet 打包体系 → 预览沙箱"四个阶段的升级路线，目标是将平台从 **UI 推送引擎** 升级为 **交互式设备应用平台**。

---

## 1. 第一期：设备控制台

### 1.1 设计目标

为每个在线设备提供按能力分类的交互式调试面板。面板布局由设备上报的 `capabilities` 动态驱动——圆形 AMOLED 设备展示 RGB/音频/IMU 面板，方形 LCD 设备仅展示基础控制。

### 1.2 架构

```
┌──────────────────────────────────────────────────┐
│                  前端 DeviceConsole.vue            │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐          │
│  │AudioPanel│ │ RgbPanel │ │EventPanel│ ...       │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘          │
│       │             │            │                 │
│  GET /console/layout (面板布局描述，驱动渲染哪些面板) │
└───────┼─────────────┼────────────┼────────────────┘
        │             │            │
┌───────┴─────────────┴────────────┴────────────────┐
│                  后端 Service 层                    │
│                                                    │
│  ConsoleLayoutService   能力快照 → 面板+控件描述     │
│  RgbControlService      RGB 策略 → 终端命令翻译     │
│  EventStreamService     SSE 事件流管理              │
│  AudioService           已有，增加 getPresets()     │
│  CommandDispatcher      已有，命令下发               │
└────────────────────────────────────────────────────┘
```

### 1.3 面板类型与触发条件

| 面板 | 能力条件 | 控件 |
|------|---------|------|
| 显示控制 | `outputs` 含 `display.brightness` | slider: 亮度 |
| 音频 | `outputs` 含 `audio.prompt` | preset_buttons: 提示音, tts_input: TTS, slider: 音量 |
| RGB 灯效 | `outputs` 含 `rgb.effect` | color_picker: 颜色, mode_selector: 效果模式 |
| 输入事件 | `inputs` 含任何带 `events` 的能力 | SSE 实时事件列表 |
| 系统 | 始终显示 | button: 重启(confirm) |

### 1.4 核心 API

#### 获取控制台布局

```
GET /api/v1/sdui/devices/{deviceId}/console/layout
```

响应：

```json
{
  "deviceId": "A1B2C3D4E5F6",
  "online": true,
  "panels": [
    {
      "id": "audio",
      "label": "音频",
      "icon": "volume-2",
      "order": 1,
      "controls": [
        {
          "type": "preset_buttons",
          "id": "presets",
          "label": "提示音",
          "options": [
            {"value": "notification", "label": "通知"},
            {"value": "success", "label": "成功"},
            {"value": "error", "label": "错误"},
            {"value": "warning", "label": "警告"},
            {"value": "click", "label": "点击"},
            {"value": "beep", "label": "蜂鸣"}
          ]
        },
        {
          "type": "tts_input",
          "id": "tts",
          "label": "TTS 朗读"
        },
        {
          "type": "slider",
          "id": "volume",
          "label": "音量",
          "command": "audio.volume.set",
          "min": 0,
          "max": 100,
          "step": 1
        }
      ]
    },
    {
      "id": "rgb",
      "label": "RGB 灯效",
      "icon": "palette",
      "order": 2,
      "controls": [
        {
          "type": "color_picker",
          "id": "color",
          "label": "颜色",
          "default": "#ffffff"
        },
        {
          "type": "mode_selector",
          "id": "mode",
          "label": "模式",
          "options": [
            {"value": "solid", "label": "常亮"},
            {"value": "blink", "label": "闪烁"},
            {"value": "breathe", "label": "呼吸"},
            {"value": "rainbow", "label": "彩虹"},
            {"value": "chase", "label": "跑马灯"},
            {"value": "off", "label": "关闭"}
          ]
        },
        {
          "type": "slider",
          "id": "period",
          "label": "周期(ms)",
          "min": 200,
          "max": 5000,
          "step": 100,
          "default": 2000
        },
        {
          "type": "button",
          "id": "apply",
          "label": "应用",
          "style": "primary"
        }
      ]
    },
    {
      "id": "inputs",
      "label": "输入事件",
      "icon": "activity",
      "order": 3,
      "stream": "/api/v1/sdui/devices/{deviceId}/console/events/stream"
    },
    {
      "id": "system",
      "label": "系统",
      "icon": "settings",
      "order": 99,
      "controls": [
        {
          "type": "button",
          "id": "reboot",
          "label": "重启设备",
          "confirm": true,
          "confirmText": "确定要重启设备吗？"
        }
      ]
    }
  ]
}
```

#### 音频控制

```
POST /api/v1/sdui/devices/{deviceId}/console/audio/preset
Body: {"preset": "success"}
Response: {"sent": true, "deviceId": "...", "preset": "success", "samples": 4410, "durationMs": 200}

POST /api/v1/sdui/devices/{deviceId}/console/audio/tts
Body: {"text": "设备已连接"}
Response: {"sent": true, "deviceId": "...", "text": "设备已连接"}
```

`preset` → `AudioService.playPreset(deviceId, preset)` → 生成 PCM → base64 → `audio.prompt.play` 命令下发。
`tts` → `AudioService.playTts(deviceId, text)` → TtsProvider 合成 → 同上下发；无 TTS 提供者时返回 `{"sent": false, "reason": "no TTS provider configured"}`。

#### RGB 控制

```
POST /api/v1/sdui/devices/{deviceId}/console/rgb/apply
Body: {"mode": "breathe", "color": "#ff0000", "periodMs": 2000}
Response: {"sent": true, "deviceId": "...", "cmdId": "..."}
```

`RgbControlService` 翻译策略：

| mode | 终端命令 | value 格式 |
|------|---------|-----------|
| `solid` | `rgb.effect.set` | `"solid:ff0000"` |
| `blink` | `rgb.effect.set` | `"blink:ff0000:1000"` |
| `breathe` | `rgb.effect.set` | `"breathe:ff0000:2000"` |
| `rainbow` | `rgb.effect.set` | `"rainbow"` |
| `chase` | `rgb.effect.set` | `"chase:ff0000:2000"` |
| `off` | `rgb.off` | null |

#### 输入事件流 (SSE)

```
GET /api/v1/sdui/devices/{deviceId}/console/events/stream
Content-Type: text/event-stream
```

```
event: button.event
data: {"ts": 1715778000, "button": "boot", "event": "short_press"}

event: motion.shake
data: {"ts": 1715778003}

event: ui.click
data: {"nodeId": "btn_submit", "sectionId": "action_1", "ts": 1715778005}
```

`EventStreamService` 管理 `ConcurrentHashMap<deviceId, SseEmitter>`。在 `EventInputHandler` 中注册回调，设备事件到达时根据 deviceId 写入对应的 SseEmitter。

#### 系统控制

```
POST /api/v1/sdui/devices/{deviceId}/console/system/reboot
Response: {"sent": true, "deviceId": "...", "cmdId": "..."}
```

### 1.5 实现清单

| 序号 | 文件 | 操作 | 说明 |
|------|------|------|------|
| 1.1 | `service/ConsoleLayoutService.java` | 新增 | 能力快照 → 面板布局描述 (~80 行) |
| 1.2 | `service/RgbControlService.java` | 新增 | RGB 策略 → 终端命令翻译 (~60 行) |
| 1.3 | `service/EventStreamService.java` | 新增 | SSE emitter 池管理 + EventInputHandler 回调注册 (~80 行) |
| 1.4 | `controller/DeviceConsoleController.java` | 新增 | `/console/*` 6 个端点 (~80 行) |
| 1.5 | `service/AudioService.java` | 修改 | 抽取 `getPresets()` 公开方法，返回预设清单 (~20 行) |
| 1.6 | `handler/EventInputHandler.java` | 修改 | 增加 `EventListener` 回调接口，事件到达时通知 EventStreamService (~15 行) |

---

## 2. 第二期：工作流引擎增强

### 2.1 当前局限

当前工作流模型：

```
Trigger → [Action1, Action2, Action3]  // 线性执行，无分支，无状态
```

能做的事：定时拉取天气 → 推送到设备；Webhook 触发 → 播放提示音。
不能做的事：用户点播放 → 判断当前状态 → 暂停还是播放 → 更新对应的 UI。

核心缺失：
- **无分支逻辑** — 没法根据变量值走不同路径
- **无变量写入** — Fetch 能写变量，但没有显式的"设置变量"动作
- **UI 事件不区分来源** — `device_event` 只匹配事件类型，无法区分"点了哪个按钮"
- **Section 与变量无绑定** — Section 数据是下发时一次性填充的，变量变了 Section 不会自动更新

### 2.2 新增动作类型

#### ConditionAction — 条件分支

```java
record ConditionAction(
    String variable,              // $data.playState
    String operator,              // eq, neq, gt, gte, lt, lte, contains, isEmpty
    String value,                 // "playing"
    List<ActionDef> thenActions,  // 条件为真时执行
    List<ActionDef> elseActions   // 条件为假时执行（可选）
) implements ActionDef {}
```

示例——音乐播放器的播放/暂停按钮：

```json
{
  "type": "condition",
  "variable": "$data.playState",
  "operator": "eq",
  "value": "playing",
  "thenActions": [
    {"type": "set_variable", "variable": "playState", "value": "paused"},
    {"type": "patch_section", "sectionId": "btn_play", "bind": "play_pause_bind"}
  ],
  "elseActions": [
    {"type": "set_variable", "variable": "playState", "value": "playing"},
    {"type": "patch_section", "sectionId": "btn_play", "bind": "play_pause_bind"},
    {"type": "play_audio", "preset": "click"}
  ]
}
```

#### SequenceAction — 顺序执行

```java
record SequenceAction(List<ActionDef> steps) implements ActionDef {}
```

与直接写多个 Action 的区别：SequenceAction 作为一个整体出现在条件分支的 `thenActions` 或 `elseActions` 中，也作为模板的内置逻辑单元。

#### SetVariableAction — 显式变量写入

```java
record SetVariableAction(String variable, String value) implements ActionDef {}
```

`value` 支持变量表达式：`"$data.count + 1"`、`"$trigger.payload.value"`、字面量 `"playing"`。`VariableResolver` 已有表达式解析能力，需扩展支持简单运算。

### 2.3 UI 事件触发器增强

当前 `DeviceEventTrigger`：

```java
record DeviceEventTrigger(String id, String event) implements TriggerDef {}
```

`event` 只能匹配事件类型（如 `"click"`），问题是设备上可能有 5 个按钮，点了哪个都会触发同一个工作流。

增强后：

```java
record DeviceEventTrigger(
    String id,
    String event,       // 事件类型：click, long_press, swipe, shake
    String sectionId,   // 可选，限定触发源 Section
    String nodeId       // 可选，限定触发源控件
) implements TriggerDef {}
```

匹配规则：`sectionId` 和 `nodeId` 为 `null` 时表示不限定（向后兼容）。都不限定时行为与当前一致。

工作流定义示例——不同按钮触发不同动作链：

```json
{
  "triggers": [
    {"type": "device_event", "id": "t_play", "event": "click", "sectionId": "action_bar", "nodeId": "btn_play"},
    {"type": "device_event", "id": "t_next",  "event": "click", "sectionId": "action_bar", "nodeId": "btn_next"},
    {"type": "device_event", "id": "t_prev",  "event": "click", "sectionId": "action_bar", "nodeId": "btn_prev"},
    {"type": "device_event", "id": "t_shake", "event": "shake"}
  ],
  "actions": {
    "t_play": [{"type": "condition", "variable": "$data.playState", ...}],
    "t_next": [{"type": "fetch", "url": "...", "save": "song"}, {"type": "sequence", ...}],
    "t_prev": [{"type": "fetch", "url": "...", "save": "song"}, {"type": "sequence", ...}],
    "t_shake": [{"type": "switch_page", "page": "music_visualizer"}]
  }
}
```

`TriggerScheduler` 的 `fireEvent` 匹配逻辑需同步更新：事件到达时，除了匹配 `event` 类型，还需检查 `sectionId` 和 `nodeId`（如果 trigger 声明了的话）。

### 2.4 变量驱动的 Section 绑定（声明式绑定）

当前 Section 的数据填充发生在 ActionExecutor 的 `buildPageScene()` / `PatchSectionAction` 中，是一次性 resolve。变量变了，Section 不会自动更新。

新增机制：**在 Applet 定义中声明绑定关系，运行时追踪依赖**。

Applet 中 Section 定义的 `bindings` 字段：

```json
{
  "sections": [
    {
      "id": "metric_temperature",
      "type": "metric_section",
      "bindings": {
        "metrics[0].value": "$data.temperature",
        "metrics[0].label": "'当前温度'"
      }
    },
    {
      "id": "btn_play",
      "type": "action_section",
      "bindings": {
        "actions[0].label": "$data.playState == 'playing' ? '暂停' : '播放'",
        "actions[0].tone": "$data.playState == 'playing' ? 'warning' : 'primary'"
      }
    }
  ]
}
```

运行时行为：

```
SetVariableAction("playState", "playing")
  → VariableWatcher 检测 playState 变更
  → 查找所有 bindings 引用 $data.playState 的 Section
  → 找到 btn_play，重新 resolve bindings
  → 自动生成 SectionPatch 下发
```

`VariableWatcher` 作为 `WorkflowInstance` 的内部组件，维护 `Map<variableName, Set<sectionId>>` 的依赖索引。变量变更时自动触发受影响的 Section 重新渲染。

### 2.5 实现清单

| 序号 | 文件 | 操作 | 说明 |
|------|------|------|------|
| 2.1 | `workflow/ActionDef.java` | 修改 | 新增 `ConditionAction`、`SequenceAction`、`SetVariableAction` (~30 行) |
| 2.2 | `workflow/TriggerDef.java` | 修改 | `DeviceEventTrigger` 增加 `sectionId`、`nodeId` 字段 (~5 行) |
| 2.3 | `workflow/ActionExecutor.java` | 修改 | 新增 3 种 Action 的 dispatch 分支 + 变量变更通知 (~80 行) |
| 2.4 | `workflow/VariableWatcher.java` | 新增 | 变量→Section 依赖追踪 + 自动 patch 生成 (~80 行) |
| 2.5 | `workflow/TriggerScheduler.java` | 修改 | 事件匹配逻辑增加 sectionId/nodeId 过滤 (~20 行) |
| 2.6 | `workflow/VariableResolver.java` | 修改 | 扩展表达式解析，支持三元运算符和简单比较 (~40 行) |
| 2.7 | `workflow/WorkflowService.java` | 修改 | `getNodeTypes()` 返回新增的 3 种 action 类型 (~15 行) |

---

## 3. 第三期：Applet 打包体系

### 3.1 设计目标

将散落的 Section 模板和工作流定义**打包为一个内聚的可部署单元**——Applet（小应用）。

当前模型的问题：

```
SectionPreset（5 个数据填充示例）    WorkflowDefinition（触发器+动作链）
        ↓                                    ↓
  各自独立管理                          各自独立管理
        ↓                                    ↓
  通过 JSON 里的字符串引用松耦合（sectionId 对得上就对，对不上就错）
```

升级后：

```
Applet
  ├── layout 层：页面 + Section 布局 + 绑定声明
  ├── data 层：变量声明 + 默认值 + 数据源
  └── logic 层：触发器 + 动作链（含条件分支、顺序执行）
  
一个 Applet 作为一个整体被创建、预览、版本管理、部署到设备。
```

### 3.2 Applet 数据模型

```java
public record Applet(
    String id,
    String name,
    String description,
    String version,                // semver
    String icon,                   // 图标 URL 或内置图标名
    List<VariableDecl> variables,  // 变量声明
    List<DataSource> dataSources,  // 外部数据源声明
    List<PageDef> pages,           // 页面 + Section 布局 + bindings
    List<TriggerDef> triggers,     // 触发器
    Map<String, List<ActionDef>> actionMap  // triggerId → 动作链
) {}

public record VariableDecl(
    String name,        // 变量名，如 "playState"
    String type,        // "string" | "number" | "boolean" | "object"
    Object defaultValue,
    String label,       // 中文显示名
    String description  // 用途说明
) {}

public record DataSource(
    String name,        // 数据源名，如 "weatherApi"
    String type,        // "fetch" | "static"
    Map<String, Object> config  // fetch: {url, method, interval}; static: {data}
) {}
```

完整的 Applet 示例——音乐播放器：

```json
{
  "id": "music_player",
  "name": "音乐播放器",
  "version": "1.0.0",
  "icon": "music",
  "variables": [
    {"name": "playState", "type": "string", "defaultValue": "idle", "label": "播放状态"},
    {"name": "currentTrack", "type": "object", "defaultValue": {}, "label": "当前曲目"},
    {"name": "volume", "type": "number", "defaultValue": 80, "label": "音量"},
    {"name": "progress", "type": "number", "defaultValue": 0, "label": "播放进度"}
  ],
  "dataSources": [
    {"name": "musicApi", "type": "fetch", "config": {"url": "https://api.example.com/music", "method": "GET"}},
    {"name": "presetTracks", "type": "static", "config": {"data": [{"id": "1", "title": "示例曲目", "artist": "未知"}]}}
  ],
  "pages": [
    {
      "id": "now_playing",
      "layout": "vertical_scroll",
      "autoScroll": false,
      "sections": [
        {
          "id": "cover",
          "type": "image_section",
          "bindings": {
            "iconSrc": "$data.currentTrack.coverUrl",
            "title": "$data.currentTrack.title",
            "subtitle": "$data.currentTrack.artist"
          }
        },
        {
          "id": "progress_bar",
          "type": "progress_section",
          "bindings": {
            "title": "'播放进度'",
            "progress": "$data.progress",
            "progressText": "$data.progress + '%'"
          }
        },
        {
          "id": "action_bar",
          "type": "action_section",
          "bindings": {
            "actions": [
              {"id": "btn_prev", "label": "上一首", "tone": "secondary"},
              {"id": "btn_play", "label": "$data.playState == 'playing' ? '暂停' : '播放'", "tone": "primary"},
              {"id": "btn_next", "label": "下一首", "tone": "secondary"}
            ]
          }
        },
        {
          "id": "volume_control",
          "type": "toggle_section",
          "bindings": {
            "options": [
              {"id": "vol_down", "label": "🔉"},
              {"id": "vol_mute", "label": "$data.volume > 0 ? '🔊' : '🔇'"},
              {"id": "vol_up", "label": "🔊"}
            ]
          }
        }
      ]
    },
    {
      "id": "music_visualizer",
      "layout": "fixed_single",
      "sections": [
        {
          "id": "viz",
          "type": "chart_section",
          "bindings": {"title": "'频谱'", "points": "$data.spectrum"}
        }
      ]
    }
  ],
  "triggers": [
    {"type": "device_event", "id": "t_play_click", "event": "click", "sectionId": "action_bar", "nodeId": "btn_play"},
    {"type": "device_event", "id": "t_next_click", "event": "click", "sectionId": "action_bar", "nodeId": "btn_next"},
    {"type": "device_event", "id": "t_prev_click", "event": "click", "sectionId": "action_bar", "nodeId": "btn_prev"},
    {"type": "device_event", "id": "t_vol_up", "event": "click", "sectionId": "volume_control", "nodeId": "vol_up"},
    {"type": "device_event", "id": "t_vol_down", "event": "click", "sectionId": "volume_control", "nodeId": "vol_down"},
    {"type": "device_event", "id": "t_vol_mute", "event": "click", "sectionId": "volume_control", "nodeId": "vol_mute"},
    {"type": "device_event", "id": "t_shake", "event": "shake"},
    {"type": "manual", "id": "t_init"}
  ],
  "actionMap": {
    "t_init": [
      {"type": "fetch", "url": "$datasource.musicApi/random", "save": "currentTrack"},
      {"type": "update_page", "page": "now_playing"}
    ],
    "t_play_click": [
      {"type": "condition", "variable": "$data.playState", "operator": "eq", "value": "playing",
        "thenActions": [
          {"type": "set_variable", "variable": "playState", "value": "paused"},
          {"type": "control", "command": "audio.pause", "value": null}
        ],
        "elseActions": [
          {"type": "set_variable", "variable": "playState", "value": "playing"},
          {"type": "control", "command": "audio.play", "value": null},
          {"type": "play_audio", "preset": "click"}
        ]
      }
    ],
    "t_next_click": [
      {"type": "fetch", "url": "$datasource.musicApi/next", "save": "currentTrack"},
      {"type": "set_variable", "variable": "progress", "value": "0"},
      {"type": "set_variable", "variable": "playState", "value": "playing"},
      {"type": "control", "command": "audio.play", "value": null}
    ],
    "t_prev_click": [
      {"type": "fetch", "url": "$datasource.musicApi/prev", "save": "currentTrack"},
      {"type": "set_variable", "variable": "progress", "value": "0"},
      {"type": "set_variable", "variable": "playState", "value": "playing"},
      {"type": "control", "command": "audio.play", "value": null}
    ],
    "t_vol_up": [
      {"type": "set_variable", "variable": "volume", "value": "min($data.volume + 10, 100)"},
      {"type": "control", "command": "audio.volume.set", "value": "$data.volume"}
    ],
    "t_vol_down": [
      {"type": "set_variable", "variable": "volume", "value": "max($data.volume - 10, 0)"},
      {"type": "control", "command": "audio.volume.set", "value": "$data.volume"}
    ],
    "t_vol_mute": [
      {"type": "condition", "variable": "$data.volume", "operator": "gt", "value": "0",
        "thenActions": [
          {"type": "set_variable", "variable": "prevVolume", "value": "$data.volume"},
          {"type": "set_variable", "variable": "volume", "value": "0"},
          {"type": "control", "command": "audio.volume.set", "value": "0"}
        ],
        "elseActions": [
          {"type": "set_variable", "variable": "volume", "value": "$data.prevVolume"},
          {"type": "control", "command": "audio.volume.set", "value": "$data.volume"}
        ]
      }
    ],
    "t_shake": [
      {"type": "switch_page", "page": "music_visualizer"}
    ]
  }
}
```

这个示例展示了 Applet 的核心能力：
- **声明式绑定** — 按钮文字随 `playState` 变化自动切换"播放"/"暂停"
- **条件分支** — 同一个按钮根据状态执行不同逻辑
- **连锁 UI 更新** — 切歌同时更新封面、歌名、进度、播放状态
- **跨 Section 联动** — 音量按钮更新音量变量，进度条自动刷新
- **多页面** — 摇一摇切到可视化页面

### 3.3 Applet 生命周期

```
创建(CREATED) → 发布(PUBLISHED) → 部署到设备(DEPLOYED) → 运行(RUNNING)
                                                              ↓
                                              暂停(SUSPENDED) / 卸载(UNLOADED)
                                                              ↓
                                                         归档(ARCHIVED)
```

- **CREATED** — 在编辑器中创建，可修改，不可部署
- **PUBLISHED** — 版本锁定，可部署到设备
- **DEPLOYED** — 已部署到特定设备，等待触发
- **RUNNING** — 至少一个页面已推送到设备，触发器已注册
- **SUSPENDED** — 触发器暂停，UI 保持在设备上但不响应事件
- **UNLOADED** — 从设备移除，触发器注销
- **ARCHIVED** — 旧版本标记，不可再部署

### 3.4 API

```
# Applet CRUD
GET    /api/v1/sdui/applets                    列出所有 Applet
POST   /api/v1/sdui/applets                    创建 Applet
GET    /api/v1/sdui/applets/{id}               获取 Applet 详情
PUT    /api/v1/sdui/applets/{id}               更新 Applet（CREATED 状态可修改）
DELETE /api/v1/sdui/applets/{id}               删除 Applet
POST   /api/v1/sdui/applets/{id}/publish       发布 Applet（版本锁定）

# Applet 部署
POST   /api/v1/sdui/applets/{id}/deploy/{deviceId}   部署到设备
DELETE /api/v1/sdui/applets/{id}/deploy/{deviceId}   从设备卸载
GET    /api/v1/sdui/devices/{deviceId}/applets        列出设备上运行的 Applet
POST   /api/v1/sdui/applets/{id}/deploy/{deviceId}/suspend   暂停
POST   /api/v1/sdui/applets/{id}/deploy/{deviceId}/resume    恢复

# 模板市场（基于已发布的 Applet）
GET    /api/v1/sdui/applets/templates           列出可用的模板（已发布 + 公开）
POST   /api/v1/sdui/applets/{id}/fork           从模板 Fork 一个可编辑副本
```

### 3.5 实现清单

| 序号 | 文件 | 操作 | 说明 |
|------|------|------|------|
| 3.1 | `applet/Applet.java` | 新增 | Applet 数据模型 record (~50 行) |
| 3.2 | `applet/AppletEntity.java` | 新增 | JPA 实体 + 仓库 (~40 行) |
| 3.3 | `applet/AppletController.java` | 新增 | CRUD + 部署端点 (~100 行) |
| 3.4 | `applet/AppletService.java` | 新增 | 生命周期管理 + 部署编排 (~150 行) |
| 3.5 | `applet/AppletRuntime.java` | 新增 | 运行时：解析 Applet → 初始化 WorkflowInstance + 下发首屏 (~120 行) |
| 3.6 | `workflow/WorkflowService.java` | 修改 | 增加 `loadApplet()` 方法，从 Applet 提取 triggers + actions 加载 (~40 行) |
| 3.7 | 前端 Applet 管理页 | 新增 | Applet 列表、详情、部署操作（前端项目，非本文档范围） |

---

## 4. 第四期：预览沙箱

### 4.1 设计目标

在浏览器中预览 Applet 在设备上的实际效果，支持事件注入和状态查看。目标是做到"在编辑器里修改绑定表达式，右侧即时看到设备 UI 变化"，不用拿真机调试。

### 4.2 架构

```
┌────────────────────────────────────────────┐
│  浏览器                                      │
│  ┌──────────────┐  ┌──────────────────────┐ │
│  │ Applet 编辑器  │  │ 设备预览器             │ │
│  │ (vue-flow)   │  │ ┌──────────────────┐ │ │
│  │              │──│ │ 圆形/方形画布      │ │ │
│  │              │  │ │ Section 渲染      │ │ │
│  └──────────────┘  │ │ 事件注入面板      │ │ │
│                    │ │ 变量状态查看器    │ │ │
│                    │ └──────────────────┘ │ │
│                    └──────────────────────┘ │
└────────────────────┬───────────────────────┘
                     │ POST /preview/execute
                     │ (Applet JSON + 事件序列 → Section 输出序列)
┌────────────────────┴───────────────────────┐
│  后端                                        │
│  PreviewEngine                              │
│  - 内存沙箱执行（无 WebSocket）               │
│  - 逐步执行动作链                             │
│  - 输出每步的 Section 状态快照                │
│  - VariableWatcher 追踪变量→Section 更新     │
└──────────────────────────────────────────────┘
```

### 4.3 预览引擎

`PreviewEngine` 是 Applet 运行时的沙箱版本：

- **不依赖 WebSocket** — 不上报命令到真实设备，CommandAction 被记录但不实际发送
- **逐步执行** — 前端传入事件序列，引擎逐步执行并返回每一步后的 Section 状态快照
- **变量追踪** — 维护内部 `Map<String, Object> variables`，展示每一步的变量变化
- **绑定解析** — 复用 `VariableWatcher`，Section 的 bindings 在变量变更时自动重新 resolve

核心 API：

```
POST /api/v1/sdui/preview/execute
Body: {
  "applet": {...},
  "deviceProfile": {"shape": "round", "screenWidth": 466, "screenHeight": 466},
  "events": [
    {"type": "trigger", "triggerId": "t_init"},
    {"type": "click", "nodeId": "btn_play", "sectionId": "action_bar"},
    {"type": "click", "nodeId": "btn_next", "sectionId": "action_bar"}
  ]
}

Response: {
  "steps": [
    {
      "event": {"type": "trigger", "triggerId": "t_init"},
      "variables": {"currentTrack": {"title": "示例曲目", ...}, "playState": "idle", ...},
      "scene": { "pageId": "now_playing", "sections": [...] },
      "commands": []  // 无命令下发（sandbox）
    },
    {
      "event": {"type": "click", "nodeId": "btn_play", "sectionId": "action_bar"},
      "variables": {"playState": "playing", ...},
      "scene": { "pageId": "now_playing", "sections": [...] },
      "patches": [
        {"sectionId": "action_bar", "data": {"actions[0].label": "暂停", "actions[0].tone": "warning"}}
      ],
      "commands": [{"command": "audio.play", "value": null}]  // 记录但不发送
    }
    // ... 更多步骤
  ]
}
```

前端拿到 `steps` 后可以在预览器中逐步回放，展示每一步的 UI 变化。

```
POST /api/v1/sdui/preview/render
Body: {
  "applet": {...},
  "deviceProfile": {...},
  "variables": {"playState": "playing", "currentTrack": {...}, ...},
  "pageId": "now_playing"
}

Response: {
  "pageId": "now_playing",
  "sections": [
    {"sectionId": "cover", "type": "image_section", "data": {"title": "示例曲目", ...}},
    {"sectionId": "progress_bar", "type": "progress_section", "data": {"progress": 45, ...}},
    {"sectionId": "action_bar", "type": "action_section", "data": {"actions": [{"label": "暂停", ...}]}}
  ]
}
```

此端点用于编辑器实时预览：修改 bindings → 前端发送当前变量值 + pageId → 后端返回 resolve 后的 Section 数据 → 前端更新预览画布。不需要走完整的动作执行链路。

### 4.4 实现清单

| 序号 | 文件 | 操作 | 说明 |
|------|------|------|------|
| 4.1 | `preview/PreviewEngine.java` | 新增 | 沙箱执行引擎：逐步执行 + 状态快照 (~150 行) |
| 4.2 | `preview/PreviewController.java` | 新增 | `/preview/execute` + `/preview/render` 端点 (~60 行) |
| 4.3 | `applet/AppletRuntime.java` | 修改 | 抽象出 Sandbox 模式参数，复用核心执行逻辑 (~30 行) |
| 4.4 | 前端设备预览器 | 新增 | 圆形/方形画布 + Section 渲染 + 事件注入面板（前端项目） |
| 4.5 | 前端工作流编辑器集成 | 修改 | 编辑器中增加预览标签页（前端项目） |

---

## 5. 升级路线总览

```
第一期：设备控制台          ← 必须最先做，解决日常调试痛点
  │                         后端 ~320 行，前端 5 个面板组件
  │
  ▼
第二期：工作流引擎增强       ← 为 Applet 提供运行时基座
  │                         后端 ~270 行，无前端依赖
  │
  ▼
第三期：Applet 打包体系      ← 统一模板+逻辑，平台核心价值升级
  │                         后端 ~500 行，前端 Applet 管理页 + 编辑器
  │
  ▼
第四期：预览沙箱            ← 让 Applet 开发形成闭环
                            后端 ~240 行，前端预览器 + 编辑器集成
```

| 阶段 | 后端新增/修改行数 | 前端范围 | 可交付物 |
|------|-----------------|---------|---------|
| 第一期 | ~350 行 | 设备控制台（5 个面板） | 可用的设备调试面板，curl 可测 |
| 第二期 | ~270 行 | 无（引擎层改动） | 条件分支、顺序执行、声明式绑定 |
| 第三期 | ~500 行 | Applet 管理 + 编辑器 | Applet CRUD + 部署 + 模板市场 |
| 第四期 | ~240 行 | 预览器 + 编辑器集成 | 浏览器端可视化预览 |

**总计后端约 1360 行新代码**，分四期交付，每期产出可独立验证。

---

## 6. 向后兼容

- 现有 WorkflowDefinition CRUD 和 WorkflowInstance API **保持不变**，Applet 是在其之上的封装层
- 现有 SectionPresets **保持不变**，Applet 模板市场是独立的模板体系
- 现有 `/devices/{deviceId}/control` 通用控制接口 **保持不变**，Console API 是新增的补充
- UI 事件触发器增加 `sectionId`/`nodeId` 字段为**可选**，不填时行为与当前一致
- `VariableWatcher` 只在 Applet 运行时启用，传统 Workflow 运行时不启用（向后兼容）
