# 协议基础与命令控制

本文档定义 SDUI 终端的**通信协议基础、能力上报机制、命令控制 API 和输入事件通道**。Section UI 渲染相关内容请参阅 [`SECTION_SCHEMA.md`](SECTION_SCHEMA.md)。

---

## 1. 传输层

### 1.1 WebSocket 双通道

终端与服务器之间通过 **单一 WebSocket 连接** 承载两条逻辑通道：

| 通道 | WebSocket 帧类型 | 用途 |
|------|-----------------|------|
| **JSON Topic** | TEXT | 能力上报、命令下发、事件上行、心跳 |
| **UI3 Binary** | BINARY | Section 场景下发/补丁、音频 PCM 下行、UI 交互事件上行 |

### 1.2 JSON Topic 信封

所有 JSON Topic 消息遵循统一信封格式：

```json
{
  "topic": "<topic-name>",
  "device_id": "esp32-A1B2C3D4E5F6",
  "payload": { }
}
```

| 方向 | `topic` | `device_id` | `payload` |
|------|--------|------------|-----------|
| 下行（服务器→终端） | 必填 | 可选 | 命令/数据 |
| 上行（终端→服务器） | 必填 | 终端自动填入 | 事件/数据 |

### 1.3 UI3 Binary 帧格式

二进制帧用于对延迟和吞吐敏感的数据传输（Section 渲染、PCM 音频、UI 交互事件）。

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|           magic (0x5344)      |  version=1   |   msg_type     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                            seq (32-bit)                        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        payload_len (32-bit)                    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        crc32 (32-bit)                          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    TLV payload (variable)                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

**固定 16 字节头**，后跟 TLV 条目序列。每个 TLV：`type:2B + len:2B + value:N`（全部小端序）。

**完整消息类型表：**

| msg_type | 名称 | 方向 | payload | 用途 |
|----------|------|------|---------|------|
| 9  | `EVENT_INPUT`    | ↑ 上行 | TLV: event_kind, node_id, event_type, ts | UI 交互事件、物理按键、IMU motion |
| 10 | `ACK`            | ↑ 上行 | TLV: code(u16), detail(str) | 操作确认 |
| 11 | `ERROR`          | ↑ 上行 | TLV: code(u16), detail(str) | 错误响应 |
| 15 | `SECTION_SCENE`  | ↓ 下行 | TLV 包裹 JSON 文本 | 完整 Section 场景下发 |
| 16 | `SECTION_PATCH`  | ↓ 下行 | TLV 包裹 JSON 文本 | 增量 Section 补丁 |
| 17 | `AUDIO_PCM`      | ↓ 下行 | 原始 PCM 字节 | 音频流播放 |

> `SECTION_SCENE`、`SECTION_PATCH` 的 JSON payload 结构见 [`SECTION_SCHEMA.md`](SECTION_SCHEMA.md)。

---

## 2. 能力上报

### 2.1 概述

WebSocket 连接成功后，终端发送**单条** `device/capabilities` 消息，声明全部能力和物理特征。服务器据此获得终端完整画像，无需等待第二条消息。

### 2.2 协议

- **传输方式**: WebSocket TEXT 帧
- **topic**: `device/capabilities`
- **capability_schema**: `capability.v2`
- **生成机制**: 编译期预计算 + 运行时零分配读取

### 2.3 完整上报样例

#### AMOLED_175（圆形触摸屏，466×466）

```json
{
  "capability_schema": "capability.v2",
  "protocol_version": "sdui.topic.v1",
  "board": "ESP32-S3-Touch-AMOLED-1.75C",

  "screen": {
    "w": 466,
    "h": 466,
    "shape": "round"
  },

  "input_mode": "touch",

  "inputs": [
    "buttons.pwr",
    "audio.record",
    "motion"
  ],

  "outputs": [
    "display.brightness",
    "device.reboot",
    "audio.stream",
    "audio.volume"
  ],

  "display": {
    "transport": "ui3_binary:SECTION_SCENE",
    "size_class": "large",
    "section_types": [
      "hero_section", "metric_section", "chart_section", "timer_section",
      "image_section", "action_section", "progress_section", "text_section",
      "list_section", "toggle_section", "overlay_section", "nav_section"
    ],
    "layouts": ["vertical_scroll", "horizontal_pages", "fixed_single", "overlay"]
  }
}
```

> 说明：AMOLED_175 同时支持触屏交互和实体按钮交互。其实体按钮能力会在 `inputs[]` 中声明为 `buttons.pwr`，运行时通过 UI3 Binary `EVENT_INPUT` 上报 `node_id="pwr"`；`boot` 为保留键，不对外上报。

#### LCD_085（方形按键屏，128×128）

```json
{
  "capability_schema": "capability.v2",
  "protocol_version": "sdui.topic.v1",
  "board": "ESP32-S3-LCD-0.85",

  "screen": {
    "w": 128,
    "h": 128,
    "shape": "rect"
  },

  "input_mode": "keys",

  "inputs": [
    "buttons.pwr",
    "buttons.plus",
    "audio.record"
  ],

  "outputs": [
    "display.brightness",
    "device.reboot",
    "audio.stream",
    "audio.volume",
    "rgb.effect"
  ],

  "display": {
    "transport": "ui3_binary:SECTION_SCENE",
    "size_class": "small",
    "section_types": [
      "hero_section", "metric_section", "chart_section", "timer_section",
      "image_section", "progress_section", "text_section"
    ],
    "layouts": ["vertical_scroll", "horizontal_pages", "fixed_single", "overlay"]
  }
}
```

### 2.4 两板差异速查

| 字段 | AMOLED_175 | LCD_085 |
|------|-----------|---------|
| `board` | `ESP32-S3-Touch-AMOLED-1.75C` | `ESP32-S3-LCD-0.85` |
| `screen.w × h` | 466 × 466 | 128 × 128 |
| `screen.shape` | `round` | `rect` |
| `input_mode` | `touch` | `keys` |
| `inputs[]` | buttons.pwr, audio.record, motion | buttons.boot, buttons.plus, audio.record |
| `outputs[]` | brightness, reboot, audio.stream, audio.volume | + rgb.effect |
| `display.size_class` | `large` | `small` |

### 2.5 字段参考

#### 2.5.1 顶层字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `capability_schema` | string | 固定 `"capability.v2"` |
| `protocol_version` | string | 固定 `"sdui.topic.v1"` |
| `board` | string | 板卡名称，诊断用途 |
| `screen` | object | 屏幕物理参数，见 §2.5.2 |
| `input_mode` | string | `"touch"` 或 `"keys"` |
| `inputs` | string[] | 支持的输入能力名称列表，见 §2.5.4 |
| `outputs` | string[] | 支持的输出能力名称列表，见 §2.5.5 |
| `display` | object | Section 渲染能力，见 §2.5.6 |

#### 2.5.2 screen — 屏幕参数

| 字段 | 类型 | 说明 |
|------|------|------|
| `w` | int | 屏幕物理宽度（px） |
| `h` | int | 屏幕物理高度（px） |
| `shape` | string | `"round"` 或 `"rect"` |

#### 2.5.3 input_mode

| 值 | 说明 | 适用板卡 |
|----|------|---------|
| `"touch"` | 支持触屏交互；同时提供实体 `buttons.pwr` 作为正常输入能力 | AMOLED_175 |
| `"keys"` | 实体按键导航，`inputs[]` 含 `buttons.*` | LCD_085 |

`input_mode` 决定 Section 交互范式，详见 [`SECTION_SCHEMA.md` §2.5](SECTION_SCHEMA.md#25-input_mode-对交互的影响)。

#### 2.5.4 inputs[] — 输入能力声明

| 名称 | 含义 | 出现条件 | 详细定义 |
|------|------|---------|----------|
| `buttons.pwr` | PWR 按键事件 | AMOLED_175 | §4.1 |
| `buttons.pwr` | PWR 按键事件 | `input_mode: "keys"` | §4.1 |
| `buttons.plus` | PLUS 按键事件 | `input_mode: "keys"` | §4.1 |
| `audio.record` | 麦克风录音 | `board_support_has_audio()` | §4.2 |
| `motion` | IMU 姿态事件 | `board_support_has_imu()` | §4.3 |

> `inputs[]` 用于声明通用输入能力项。AMOLED_175 固定声明 `buttons.pwr`，其运行时事件同样通过 UI3 Binary `EVENT_INPUT` 上报；`boot` 为保留键，不对外上报。Section 触屏交互事件也走同一通道，不在此列。

#### 2.5.5 outputs[] — 输出能力声明

| 名称 | 含义 | 出现条件 | 详细定义 |
|------|------|---------|----------|
| `display.brightness` | 屏幕亮度控制 | 始终支持 | §3.1 |
| `device.reboot` | 设备重启 | 始终支持 | §3.2 |
| `audio.stream` | PCM 音频流播放 | `board_support_has_audio()` | §3.3 |
| `audio.volume` | 音量控制 | `board_support_has_audio()` | §3.4 |
| `rgb.effect` | RGB 灯效控制 | `board_support_has_rgb()` | §3.5 |

#### 2.5.6 display — Section 渲染能力

| 字段 | 类型 | 说明 |
|------|------|------|
| `transport` | string | 固定 `"ui3_binary:SECTION_SCENE"` |
| `size_class` | string | 终端根据屏幕物理尺寸自主判定：`"small"` / `"large"` |
| `section_types` | string[] | 支持的 12 种 section 类型 |
| `layouts` | string[] | 支持的 4 种布局模式 |

各 `size_class` 对应的字段渲染差异、资源限制详见 [`SECTION_SCHEMA.md` §2](SECTION_SCHEMA.md#2-屏幕适配模型)。

---

## 3. 命令控制（输出能力）

所有命令通过 **JSON Topic `cmd/control`** 下发，终端处理后通过 **`cmd/control_ack`** 回执。

### 3.1 `display.brightness` — 屏幕亮度

**能力名称**: `display.brightness` |
**出现条件**: 始终支持 |
**协议**: `cmd/control`

```json
{
  "topic": "cmd/control",
  "payload": {
    "cmd_id": "br-001",
    "action": "brightness",
    "value": 80
  }
}
```

| 字段 | 类型 | 范围 | 说明 |
|------|------|------|------|
| `action` | string | `"brightness"` | 固定值 |
| `value` | int | 0–100 | 亮度百分比 |
| `cmd_id` | string | — | 可选，用于 ACK 关联 |

**ACK 回执**:

```json
{
  "topic": "cmd/control_ack",
  "device_id": "esp32-A1B2C3D4E5F6",
  "payload": {
    "cmd_id": "br-001",
    "action": "brightness",
    "status": "ACKED",
    "applied_value": 80,
    "ts": 1774412030123
  }
}
```

### 3.2 `device.reboot` — 设备重启

**能力名称**: `device.reboot` |
**出现条件**: 始终支持 |
**协议**: `cmd/control`

```json
{
  "topic": "cmd/control",
  "payload": {
    "cmd_id": "rb-001",
    "action": "reboot"
  }
}
```

无需额外参数。终端收到后**排队重启**（当前帧处理完毕后执行，非即时）。

### 3.3 `audio.stream` — 音频流播放

**能力名称**: `audio.stream` |
**出现条件**: `board_support_has_audio() == true` |
**协议**: 两种下发路径

#### 3.3.1 二进制路径：UI3 `AUDIO_PCM`（msg_type=17）

原始 PCM 字节直接装入 UI3 二进制帧 payload。适合低延迟、大批量音频。

**音质规格**: 22050 Hz, mono, signed 16-bit little-endian。

#### 3.3.2 JSON 路径：`audio/play_stream`（流式）

```json
// 开始
{ "topic": "audio/play_stream", "payload": { "state": "start", "codec": "pcm_s16le" } }

// 数据流
{ "topic": "audio/play_stream", "payload": { "state": "stream", "codec": "pcm_s16le", "data": "<base64>" } }

// 结束
{ "topic": "audio/play_stream", "payload": { "state": "stop" } }
```

#### 3.3.3 JSON 路径：`audio/play`（兼容单段）

```json
{ "topic": "audio/play", "payload": "<base64-pcm>" }
```

直接发送 Base64 编码 PCM。适合短音频（< 数秒）。

**播放限制**:
- 内部 heap < 6KB 或 DMA heap < 1.5KB 时跳过
- 播放队列深度 16，满时丢弃最旧分片
- 每次写扬声器 1024 字节

### 3.4 `audio.volume` — 音量控制

**能力名称**: `audio.volume` |
**出现条件**: `board_support_has_audio() == true` |
**协议**: `cmd/control`

```json
{
  "topic": "cmd/control",
  "payload": {
    "cmd_id": "vol-001",
    "action": "volume",
    "value": 60
  }
}
```

| 字段 | 类型 | 范围 | 说明 |
|------|------|------|------|
| `action` | string | `"volume"` | 固定值 |
| `value` | int | 0–100 | 音量百分比，默认 60 |

### 3.5 `rgb.effect` — RGB 灯效

**能力名称**: `rgb.effect` |
**出现条件**: `board_support_has_rgb() == true`（仅 LCD_085） |
**协议**: `cmd/control`

#### 固定颜色模式

```json
{
  "topic": "cmd/control",
  "payload": {
    "cmd_id": "rgb-001",
    "action": "rgb_set",
    "r": 80, "g": 180, "b": 255,
    "brightness": 120
  }
}
```

#### 策略灯效模式

```json
{
  "topic": "cmd/control",
  "payload": {
    "cmd_id": "rgb-002",
    "action": "rgb_policy",
    "mode": "breathe",
    "r": 80, "g": 180, "b": 255,
    "brightness": 120,
    "period_ms": 1200,
    "step_ms": 40
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `action` | string | `"rgb_set"`（固定色）/ `"rgb_policy"`（灯效）/ `"rgb_off"`（关闭） |
| `mode` | string | `"solid"` / `"blink"` / `"breathe"` / `"rainbow"` / `"chase"` |
| `r/g/b` | int (0–255) | 基础色 |
| `brightness` | int (0–255) | 亮度 |
| `period_ms` | int | 周期（ms），blink/breathe/rainbow/chase 使用 |
| `step_ms` | int | 步进间隔（ms），breathe/rainbow/chase 使用 |

**关闭 RGB**:

```json
{ "topic": "cmd/control", "payload": { "action": "rgb_off" } }
```

---

## 4. 输入能力（事件通道）

### 4.1 `buttons.<name>` — 物理按键

**能力名称**: `buttons.pwr` / `buttons.boot` / `buttons.plus` |
**出现条件**: AMOLED_175 在 `inputs[]` 中声明 `buttons.pwr`；LCD_085 在 `input_mode: "keys"` 时于 `inputs[]` 中声明 `buttons.boot` / `buttons.plus` |
**传输**: UI3 Binary `EVENT_INPUT`（msg_type=9）

物理按键事件通过 UI3 Binary 帧上行。

**TLV 字段**:

| TLV type | 字段 | 类型 | 说明 |
|----------|------|------|------|
| 120 | `event_kind` | u8 | 固定 `4`（`UI3_EVENT_BUTTON`） |
| 121 | `node_id` | string | 按键标识：`"boot"` / `"plus"` |
| 122 | `event_type` | string | 事件类型（见下表） |
| 125 | `ts` | u32 | 毫秒时间戳 |

**按键事件类型**:

| event_type | 说明 |
|------------|------|
| `press_down` | 按下 |
| `press_up` | 释放 |
| `single_click` | 单击 |
| `double_click` | 双击 |
| `long_press_start` | 长按开始 |
| `long_press_up` | 长按释放 |

**可用按键**:

| 物理按键 | node_id | 说明 |
|----------|---------|------|
| BOOT | `boot` | 多功能键（LCD_085；AMOLED_175 上保留，不对外上报） |
| PLUS | `plus` | 导航/辅助键（LCD_085） |
| PWR | `pwr` | AXP2101 PKEY 电源键（AMOLED_175 对外实体按钮） |

> 物理按键事件使用 500ms 去抖。LCD_085 对外按键为 `boot` / `plus`；AMOLED_175 对外按键为 `pwr`。AMOLED_175 的 `boot` 为保留键，不对外上报。

### 4.2 `audio.record` — 麦克风录音

**能力名称**: `audio.record` |
**出现条件**: `board_support_has_audio() == true` |
**传输**: JSON Topic `audio/record`，三阶段发送

**音质规格**:
- 编码: PCM signed 16-bit little-endian, mono
- 采样率: 22050 Hz
- 分片大小: 2048 字节 PCM（Base64 编码后约 2732 字符）
- 最小上传阈值: 16 KB PCM（约 0.37 秒）

#### 阶段 1 — 录音开始

```json
{
  "topic": "audio/record",
  "device_id": "esp32-A1B2C3D4E5F6",
  "payload": {
    "state": "start",
    "context": "button_boot",
    "codec": "pcm_s16le",
    "sample_rate": 22050,
    "channels": 1,
    "bits_per_sample": 16,
    "total_bytes": 90112
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `state` | string | `"start"` |
| `context` | string | 触发上下文，如 `"button_boot"` |
| `codec` | string | `"pcm_s16le"` |
| `sample_rate` | int | `22050` |
| `channels` | int | `1` |
| `bits_per_sample` | int | `16` |
| `total_bytes` | int | 总 PCM 字节数 |

#### 阶段 2 — 流式上传

```json
{
  "topic": "audio/record",
  "device_id": "esp32-A1B2C3D4E5F6",
  "payload": {
    "state": "stream",
    "seq": 1,
    "total": 44,
    "codec": "pcm_s16le",
    "data": "<base64-pcm-2048-bytes>"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `state` | string | `"stream"` |
| `seq` | int | 分片序号（从 1 开始） |
| `total` | int | 总分片数 |
| `codec` | string | `"pcm_s16le"` |
| `data` | string | Base64 编码的 PCM 数据 |

#### 阶段 3 — 录音结束

```json
{
  "topic": "audio/record",
  "device_id": "esp32-A1B2C3D4E5F6",
  "payload": { "state": "stop" }
}
```

**错误处理**:
- 录音不足 16 KB 时不上传（终端丢弃）
- WS 断开时中断上传，已发分片不重传
- 低内存时跳过上传

### 4.3 `motion` — IMU 姿态事件
**能力名称**: `motion` |
**出现条件**: `board_support_has_imu() == true`（仅 AMOLED_175） |
**传输**: UI3 Binary `EVENT_INPUT`（msg_type=9）

IMU 姿态事件不再走 JSON `motion` topic，而是与物理按钮共用同一条 UI3 输入事件上行通道。

**TLV 字段**:

| TLV type | 字段 | 类型 | 说明 |
|----------|------|------|------|
| 120 | `event_kind` | u8 | 固定 `4`（`UI3_EVENT_BUTTON`） |
| 121 | `node_id` | string | 固定 `"motion"` |
| 122 | `event_type` | string | 动作类型，见下表 |
| 125 | `ts` | u32 | 毫秒时间戳 |

**motion 事件类型**:

| event_type | node_id | 说明 |
|------------|---------|------|
| `shake` | `"motion"` | 摇动动作 |
| `wrist_raise` | `"motion"` | 抬腕动作 |
| `flip` | `"motion"` | 翻转动作 |

#### 事件 1 — 摇动（shake）

触发条件: 原始加速度模长 `raw_mag > 13.0 m/s²` 且相邻采样变化量 `delta > 5.5 m/s²`。冷却期 ~1 秒（10 tick × 100ms）。

#### 事件 2 — 抬腕（wrist_raise）

触发条件: 前 18 tick 内手臂接近水平（tilt < 12°），随后快速倾斜超过 40°，且 `delta > 12°`。冷却期 ~1.5 秒（15 tick）。

#### 事件 3 — 翻转（flip）

触发条件: Z 轴重力分量符号翻转（`|z_g| > 0.78`）。冷却期 ~1.8 秒（18 tick）。

**低资源保护**: 内部 heap < 8KB 或 DMA heap < 1.5KB 时暂停上报；音频上传期间暂停上报。
---

### 4.4 Section 触屏交互事件

**能力名称**: 各交互 Section（`action_section`, `list_section`, `toggle_section`, `overlay_section`, `nav_section`） |
**出现条件**: `input_mode: "touch"` |
**传输**: UI3 Binary `EVENT_INPUT`（msg_type=9）

交互式 Section 上的用户触控操作通过 UI3 Binary 帧上行。TLV 结构与物理按键事件相同（msg_type=9），但 `event_kind` 和 `event_type` 按 Section 类型区分。

**TLV 字段**:

| TLV type | 字段 | 类型 | 说明 |
|----------|------|------|------|
| 120 | `event_kind` | u8 | `1`（UI3_EVENT_UI_CLICK）、`2`（UI3_EVENT_OVERLAY_CONFIRM）或 `3`（UI3_EVENT_TOGGLE_CHANGE） |
| 121 | `node_id` | string | 交互元素的 `id` 字段（如按钮 id、列表项 id、开关 id + 状态值） |
| 122 | `event_type` | string | 事件类型（见下表） |
| 125 | `ts` | u32 | 毫秒时间戳 |

**Section 触控事件类型**:

| event_type | event_kind | node_id 示例 | 说明 |
|------------|------------|-------------|------|
| `action.click` | 1 | `"submit_btn"` | 用户点击 action_section 中的按钮 |
| `list.select` | 1 | `"item_3"` | 用户点击 list_section 中的列表项 |
| `toggle.change` | 3 | `"wifi:1"` | 用户切换 toggle_section 中的开关（`:0`=关, `:1`=开） |
| `overlay.confirm` | 2 | `""` | 用户确认/关闭 overlay_section 弹窗 |
| `nav.switch` | 1 | `"tab_home"` | 用户点击 nav_section 中的标签页切换 |

> 去抖动：`action.click` 和 `list.select` 每个唯一 node_id 500ms 内不重复上报。`toggle.change` 和 `overlay.confirm` 无去抖动限制。`nav.switch` 每个唯一标签 id 500ms 内不重复上报。

---

## 5. 遥测心跳

### 5.1 概述

终端连接成功后以固定周期（默认 30 秒）上报遥测快照，包含网络、内存、温度、电源、运行时长等设备健康指标。心跳通过 JSON Topic 上行，由独立低优先级任务驱动，不影响 UI 渲染和音频播放。

- **传输方式**: WebSocket TEXT 帧
- **topic**: `telemetry/heartbeat`
- **上报间隔**: 30 秒（默认，可通过 `telemetry_app_start(interval_s)` 调整）
- **任务属性**: 优先级 2（低于音频/UI），栈 4KB 开在 PSRAM
- **发送策略**: `sdui_bus_publish_up_try()` — 队列满则丢弃，不阻塞
- **抑制条件**:
  - WebSocket 未连接时跳过，不堆积
  - 音频流上传期间延迟 1 秒重试
  - 内部 heap < 8KB 或 DMA heap < 1.5KB 时跳过

### 5.2 上报样例

```json
{
  "topic": "telemetry/heartbeat",
  "device_id": "esp32-A1B2C3D4E5F6",
  "payload": {
    "device_id": "esp32-A1B2C3D4E5F6",
    "wifi_rssi": -45,
    "ip": "192.168.1.100",
    "temperature": 42.5,
    "free_heap_internal": 98304,
    "largest_heap_internal": 65536,
    "free_heap_dma": 8519680,
    "largest_heap_dma": 8388608,
    "free_heap_psram": 4194304,
    "largest_heap_psram": 4194304,
    "free_heap_total": 4500000,
    "frag_internal_pct": 33,
    "frag_dma_pct": 1,
    "frag_psram_pct": 0,
    "uptime_s": 3600,
    "power_supported": true,
    "battery_mv": 3850,
    "battery_pct": 72,
    "charging": false,
    "ext_power_present": false,
    "ext_power_ctrl": false,
    "ext_power_on": false
  }
}
```

### 5.3 字段参考

#### 5.3.1 设备标识

| 字段 | 类型 | 说明 |
|------|------|------|
| `device_id` | string | eFuse MAC 派生的设备唯一标识，如 `"esp32-A1B2C3D4E5F6"`，首次读取后缓存 |

#### 5.3.2 网络状态

| 字段 | 类型 | 说明 |
|------|------|------|
| `wifi_rssi` | int | WiFi 信号强度（RSSI），无连接时为 `0` |
| `ip` | string | 当前 IP 地址，无连接时为 `"0.0.0.0"` |

#### 5.3.3 芯片温度

| 字段 | 类型 | 说明 |
|------|------|------|
| `temperature` | float | 芯片内部温度（°C），传感器不可用时为 `-1.0` |

#### 5.3.4 内存状态

| 字段 | 类型 | 说明 |
|------|------|------|
| `free_heap_internal` | int | 内部 SRAM 空闲字节数 |
| `largest_heap_internal` | int | 内部 SRAM 最大连续空闲块（字节） |
| `free_heap_dma` | int | DMA 可用内存空闲字节数 |
| `largest_heap_dma` | int | DMA 内存最大连续空闲块（字节） |
| `free_heap_psram` | int | PSRAM 空闲字节数 |
| `largest_heap_psram` | int | PSRAM 最大连续空闲块（字节） |
| `free_heap_total` | int | `esp_get_free_heap_size()` 总空闲字节 |
| `frag_internal_pct` | int | 内部 SRAM 碎片率（%），公式 `(1 - largest / free) × 100` |
| `frag_dma_pct` | int | DMA 内存碎片率（%） |
| `frag_psram_pct` | int | PSRAM 碎片率（%） |

> 碎片率为 0% 表示无碎片，值越高表示内存越分散。当 free 为 0 时返回 0%。

#### 5.3.5 运行时长

| 字段 | 类型 | 说明 |
|------|------|------|
| `uptime_s` | int | 设备上电后运行秒数，来源 `esp_timer_get_time() / 1000000` |

#### 5.3.6 电源状态

通过 `board_power_get_state()` 采集，字段仅在硬件支持时有效：

| 字段 | 类型 | 说明 |
|------|------|------|
| `power_supported` | bool | 当前板卡是否支持电源检测 |
| `battery_mv` | int | 电池电压（mV），仅在 `power_supported=true` 且读取成功时出现 |
| `battery_pct` | int | 电量百分比（0–100），仅在 `power_supported=true` 且读取成功时出现 |
| `charging` | bool | 是否正在充电 |
| `ext_power_present` | bool | 外部电源是否已接入 |
| `ext_power_ctrl` | bool | 外部电源是否可控（开关） |
| `ext_power_on` | bool | 外部电源是否已开启 |

### 5.4 与 WebSocket Ping/Pong 的关系

终端同时维护两层心跳，互不替代：

| 层级 | 机制 | 间隔 | 用途 |
|------|------|------|------|
| **传输层** | WebSocket Ping/Pong | 20s ping, 30s timeout | 检测连接存活，触发断线重连 |
| **应用层** | `telemetry/heartbeat` | 30s | 设备健康指标上报，服务器监控用 |

> WebSocket Ping/Pong 不含应用数据，仅维持连接。`telemetry/heartbeat` 包含完整遥测快照，仅在连接正常时发送。

---

## 6. 错误码

UI3 Binary `ACK` / `ERROR` 消息的 `code` 字段：

| code | 名称 | 说明 |
|------|------|------|
| 0 | `UI3_ERR_OK_APPLIED` | 成功（ACK 中使用） |
| 1 | `UI3_ERR_CRC` | 帧 CRC 校验失败 |
| 2 | `UI3_ERR_TLV_PARSE` | TLV 解析错误 |
| 3 | `UI3_ERR_UNKNOWN_FIELD` | 未知字段/消息类型 |
| 4 | `UI3_ERR_SCHEMA` | JSON schema 错误 |
| 5 | `UI3_ERR_LAYOUT_OVERFLOW` | 布局溢出 |
| 6 | `UI3_ERR_OOM_NODE_POOL` | Section 内存池耗尽 |
| 7 | `UI3_ERR_OOM_RESOURCE` | 资源分配失败 |
| 8 | `UI3_ERR_UNSUPPORTED_INPUT_MAP` | 不支持的输入映射 |

---

## 7. 完整时序

```
终端 (ESP32-S3)                          服务器 (AgentNexus)
     |                                         |
     |======= WebSocket 连接建立 =============>|
     |                                         |
     |--- WS TEXT: device/capabilities ------->|  ① 能力声明
     |                                         |
     |<-- WS BINARY: UI3 SECTION_SCENE -------|  ② 首屏 Section 场景
     |                                         |
     |--- WS BINARY: UI3 ACK ---------------->|  ③ 渲染确认
     |                                         |
     |<-- WS BINARY: UI3 SECTION_PATCH -------|  ④ 增量更新
     |                                         |
     |--- WS BINARY: UI3 EVENT_INPUT -------->|  ⑤ 用户交互
     |                                         |
     |--- WS BINARY: UI3 EVENT_INPUT ------->|  ⑥ IMU 事件 (AMOLED_175)
     |--- WS TEXT: audio/record stream ------>|  ⑦ 录音流 (按键触发)
     |                                         |
     |<-- WS TEXT: cmd/control brightness ----|  ⑧ 控制命令
     |--- WS TEXT: cmd/control_ack ---------->|  ⑨ 命令回执
     |                                         |
     |<-- WS BINARY: UI3 AUDIO_PCM ----------|  ⑩ TTS/音效播放
     |                                         |
     |--- WS TEXT: telemetry/heartbeat ------>|  ⑪ 遥测心跳 (每 30s)
```

---

## 8. 代码路径速查

### 8.1 各模块能力注册

| 模块 | 注册函数 | 注册内容 |
|------|----------|----------|
| `main.c` | `system_register_capabilities()` | `display.brightness`(O), `device.reboot`(O), `buttons.boot/buttons.plus`(I, LCD_085), `buttons.pwr`(I, AMOLED_175) |
| `main.c` | `rgb_register_capabilities()` | `rgb.effect`(O) — 按 `board_support_has_rgb()` |
| `audio_manager.c` | `audio_manager_register_capabilities()` | `audio.stream`(O), `audio.volume`(O), `audio.record`(I) |
| `imu_manager.c` | `imu_manager_register_capabilities()` | `motion`(I) — 按 `board_support_has_imu()` |
| `ui3_section_runtime.c` | `ui3_section_runtime_register_capabilities()` | `display` — section 类型、布局、size_class |
| `telemetry_manager.c` | `telemetry_app_start()` | `telemetry/heartbeat` — 遥测心跳 |

> I = Input, O = Output

### 8.2 关键代码位置

| 功能 | 文件 | 关键函数 |
|------|------|----------|
| 能力注册 API | `components/terminal_capability/terminal_capability.c` | `terminal_capability_add()`, `terminal_capability_add_section_types()` |
| 预编译 JSON | `components/terminal_capability/include/terminal_capability_data.h` | `STATIC_CAPABILITY_JSON` |
| JSON 上报发送 | `main/main.c` | `report_capabilities_once()` |
| 二进制帧编解码 | `components/ui3_protocol/` | `ui3_frame_encode()`, `ui3_frame_verify()` |
| Section 渲染调度 | `components/ui3_runtime/ui3_runtime.c` | `ui3_runtime_on_binary_frame()` |
| Section 事件上报 | `components/ui3_runtime/ui3_runtime.c` | `section_event_reporter()` → `UI3_MSG_EVENT_INPUT` |
| 按钮事件上报 | `components/ui3_runtime/ui3_runtime.c` | `ui3_runtime_report_button_event()` |
| motion 事件上报 | `components/ui3_runtime/ui3_runtime.c` | `ui3_runtime_report_motion_event()` → `UI3_MSG_EVENT_INPUT` |
| 遥测心跳上报 | `components/telemetry_manager/telemetry_manager.c` | `telemetry_report_task()` → `telemetry_collect()` → `sdui_bus_publish_up_try("telemetry/heartbeat", ...)` |
| 能力头生成脚本 | `scripts/generate_capability_header.py` | `build_capability_json()` → `json_to_c_lines()` |

---

## 9. 相关文档

| 文档 | 内容 |
|------|------|
| `PROTOCOL_AND_COMMANDS.md`（本文档） | 协议基础、能力上报、命令控制、输入事件、错误码 |
| [`SECTION_SCHEMA.md`](SECTION_SCHEMA.md) | 12 种 Section 类型定义、per-board 渲染行为、布局模式、Patch 机制 |
| `PROTOCOL.md` | WebSocket 信封、legacy topic 定义、兼容策略 |
| `ARCHITECTURE.md` | 终端架构、模块职责、设计哲学 |
