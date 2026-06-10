# 终端能力上报机制

## 概述

终端 WebSocket 连接成功后，发送单条 JSON 消息声明全部能力和物理特征：

| 消息 | 协议 | 说明 |
|------|------|------|
| `device/capabilities` | Legacy JSON Topic | 单条 JSON，包含逻辑能力 + 物理特征 + Section 渲染参数 |

服务器收到后即可获得终端的完整画像，无需等待第二条消息。

---

## 1. 能力上报 — `device/capabilities`

### 1.1 协议

- **传输方式**：WebSocket **文本帧**
- **数据格式**：`capability.v2`
- **信封格式**：

```json
{
  "topic": "device/capabilities",
  "device_id": "esp32-A1B2C3D4E5F6",
  "payload": { /* 能力 JSON */ }
}
```

### 1.2 生成机制

能力 JSON 在**编译期预计算**并写入 `terminal_capability_data.h`，运行时通过 `terminal_capability_get_static_json()` 零分配读取并发送。

### 1.3 完整上报样例

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
    "buttons.boot",
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
      "image_section", "action_section", "progress_section", "text_section",
      "list_section", "toggle_section", "overlay_section", "nav_section"
    ],
    "layouts": ["vertical_scroll", "horizontal_pages", "fixed_single", "overlay"]
  }
}
```

### 1.4 两板差异速查

| 字段 | AMOLED_175 | LCD_085 |
|------|-----------|---------|
| `board` | `"ESP32-S3-Touch-AMOLED-1.75C"` | `"ESP32-S3-LCD-0.85"` |
| `screen.w/h` | `466×466` | `128×128` |
| `screen.shape` | `"round"` | `"rect"` |
| `input_mode` | `"touch"` | `"keys"` |
| `inputs[]` | audio.record, motion | buttons.boot, buttons.plus, audio.record |
| `outputs[]` | brightness, reboot, audio.stream, audio.volume | + rgb.effect |
| `display.size_class` | `"large"` | `"small"` |

---

## 2. 字段说明

### 2.1 顶层

| 字段 | 类型 | 说明 |
|------|------|------|
| `capability_schema` | string | 固定 `"capability.v2"` |
| `protocol_version` | string | 固定 `"sdui.topic.v1"` |
| `board` | string | 板卡名称（诊断用途） |
| `screen` | object | 屏幕物理参数 |
| `input_mode` | string | `"touch"` 或 `"keys"` |
| `inputs` | string[] | 支持的输入能力名称列表 |
| `outputs` | string[] | 支持的输出能力名称列表 |
| `display` | object | Section 渲染能力 |

### 2.2 screen — 屏幕参数

| 字段 | 类型 | 说明 |
|------|------|------|
| `w` | int | 屏幕宽度（px） |
| `h` | int | 屏幕高度（px） |
| `shape` | string | `"round"` 或 `"rect"` |

### 2.3 input_mode

| 值 | 说明 |
|----|------|
| `"touch"` | 触屏交互。无平台可用的实体按键。 |
| `"keys"` | 实体按键导航。`inputs[]` 中含 `buttons.*` 条目，列出平台可用的按键。 |

`input_mode` 决定 Section 交互范式：`"touch"` 下 action/list/toggle 直接触屏操作，`"keys"` 下依赖按键焦点导航。

### 2.4 inputs[] — 输入能力声明

字符串数组，每项是 `CAPABILITY_CATALOG.md` 中定义的输入能力名称。

| 名称 | 含义 | 出现条件 |
|------|------|---------|
| `buttons.boot` | BOOT 按键事件 | `input_mode: keys` |
| `buttons.plus` | PLUS 按键事件 | `input_mode: keys` |
| `audio.record` | 麦克风录音 | `board_support_has_audio()` |
| `motion` | IMU 姿态事件（摇动/抬手/翻转） | `board_support_has_imu()` |

> 系统保留按键（PWR 电源键）不出现在 inputs 中。Section 触屏交互事件走 UI3 binary `EVENT_INPUT`，不在此列。

### 2.5 outputs[] — 输出能力声明

字符串数组，每项是 `CAPABILITY_CATALOG.md` 中定义的输出能力名称。

| 名称 | 含义 | 出现条件 |
|------|------|---------|
| `display.brightness` | 屏幕亮度控制 | 始终支持 |
| `device.reboot` | 设备重启 | 始终支持 |
| `audio.stream` | PCM 音频流播放 | `board_support_has_audio()` |
| `audio.volume` | 音量控制 | `board_support_has_audio()` |
| `rgb.effect` | RGB 灯效控制 | `board_support_has_rgb()` |

### 2.6 display — Section 渲染能力

| 字段 | 类型 | 说明 |
|------|------|------|
| `transport` | string | 固定 `"ui3_binary:SECTION_SCENE"` |
| `size_class` | string | `"small"` / `"large"`。终端根据屏幕物理尺寸自主判定后上报，平台读值使用即可。 |
| `section_types` | string[] | 支持的 section 类型名称列表 |
| `layouts` | string[] | 支持的布局模式 |

各 `size_class` 对应的资源限制（max_chart_points、max_list_items 等）由 `CAPABILITY_CATALOG.md` 文档化，不上报。

---

## 3. Section 能力详情

Section 的完整字段 schema、事件类型、行为语义由 `CAPABILITY_CATALOG.md` 定义。此处列出 12 种 Section 的交互属性概览：

| 类型 | 用途 | 产生事件 | 备注 |
|------|------|---------|------|
| `hero_section` | 大标题 + 副标题 + 图标 | — | |
| `metric_section` | 数值指标网格 | — | 最多 4 个 |
| `chart_section` | 折线/柱状图 | — | 点数上限见 CAPABILITY_CATALOG.md |
| `timer_section` | 倒计时/计时器 | — | 服务端计时 |
| `image_section` | 图片展示 | — | |
| `action_section` | 按钮组 | `action.click` | 最多 4 按钮 |
| `progress_section` | 进度条 | — | |
| `text_section` | 文本块 | — | |
| `list_section` | 列表 | `list.select` | 项数上限见 CAPABILITY_CATALOG.md |
| `toggle_section` | 开关选项 | `toggle.change` | 个数上限见 CAPABILITY_CATALOG.md |
| `overlay_section` | 弹窗/确认框 | `overlay.confirm` | compact 模式 5 秒自动隐藏 |
| `nav_section` | 导航标签页 | — | tab 上限见 CAPABILITY_CATALOG.md |

### 3.1 Section 交互事件

交互组件通过 UI3 binary `EVENT_INPUT` (msg_type=9) 上行：

| 事件类型 | TLV_EVENT_KIND | TLV_EVENT_TYPE | 触发组件 |
|----------|----------------|----------------|----------|
| `action.click` | 1 (`UI3_EVENT_UI_CLICK`) | `"action.click"` | action_section 按钮 |
| `list.select` | 1 | `"list.select"` | list_section 列表项 |
| `toggle.change` | 3 (`UI3_EVENT_TOGGLE_CHANGE`) | `"toggle.change"` | toggle_section 开关 |
| `overlay.confirm` | 2 (`UI3_EVENT_OVERLAY_CONFIRM`) | `"overlay.confirm"` | overlay_section 确认框 |

每个事件携带 `node_id`（组件标识，如按钮的 `id` 字段）和 `ts`（毫秒时间戳）。

---

## 4. UI3 Binary Frame 协议

能力上报统一为 JSON 后，UI3 二进制帧专注于运行时数据传输。

### 4.1 二进制帧格式

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

固定 16 字节头部，后跟 TLV 负载。每个 TLV 条目 4 字节头（type:2 + len:2）+ value。

### 4.2 全部消息类型

| msg_type | 名称 | 方向 | 用途 |
|----------|------|------|------|
| 9 | `EVENT_INPUT` | 上行 | UI 交互事件（点击、开关、列表选择、overlay 确认） |
| 10 | `ACK` | 上行 | 确认处理成功 |
| 11 | `ERROR` | 上行 | 错误响应 |
| 15 | `SECTION_SCENE` | 下行 | 完整 Section 场景（JSON payload） |
| 16 | `SECTION_PATCH` | 下行 | 增量 Section 补丁（JSON payload） |
| 17 | `AUDIO_PCM` | 下行 | 原始 PCM 音频数据 |

> `TERMINAL_HELLO` (msg_type=1) 已移除。终端特征统一在 `device/capabilities` JSON 中上报。

---

## 5. 完整上报时序

```
终端 (ESP32-S3)                          服务器 (AgentNexus)
     |                                         |
     |======= WebSocket 连接建立 =============>|
     |                                         |
     |--- WS TEXT: device/capabilities ------->|  ① 能力声明（含全部信息）
     |   { screen, input_mode, inputs[],       |
     |     outputs[], display }                 |
     |                                         |
     |                             服务器根据声明匹配已接入的 API
     |                                         |
     |<-- WS BINARY: UI3_MSG_SECTION_SCENE ----|  ② Section 场景下发
     |    [JSON: { page: { sections: [...] } }]|
     |                                         |
     |--- WS BINARY: UI3_MSG_ACK ------------->|  ③ 确认渲染结果
     |                                         |
     |<-- WS BINARY: UI3_MSG_SECTION_PATCH ----|  ④ 增量补丁
     |                                         |
     |--- WS BINARY: UI3_MSG_EVENT_INPUT ----->|  ⑤ 用户交互事件
     |                                         |
     |--- WS TEXT: topic/event ... ----------->|  ⑥ 其他上行事件
     |    (audio.record, button.event, etc.)   |
```

---

## 6. 各模块能力注册

| 模块 | 注册函数 | 注册内容 |
|------|----------|----------|
| `main.c` | `system_register_capabilities()` | `display.brightness`(O), `device.reboot`(O), `buttons.boot`/`buttons.plus`(I, keys 模式) |
| `main.c` | `rgb_register_capabilities()` | `rgb.effect`(O) — 按 `board_support_has_rgb()` 决定 |
| `audio_manager.c` | `audio_manager_register_capabilities()` | `audio.stream`(O), `audio.volume`(O), `audio.record`(I) |
| `imu_manager.c` | `imu_manager_register_capabilities()` | `motion`(I) — 按 `board_support_has_imu()` 决定 |
| `ui3_section_runtime.c` | `ui3_section_runtime_register_capabilities()` | `display` — section 类型、布局、size_class |

> I=Input, O=Output

---

## 7. 代码路径速查

| 功能 | 文件 | 关键函数 |
|------|------|----------|
| 能力注册 API | `components/terminal_capability/terminal_capability.c` | `terminal_capability_add()`, `terminal_capability_add_section_types()` |
| 预编译 JSON | `components/terminal_capability/include/terminal_capability_data.h` | `STATIC_CAPABILITY_JSON` |
| JSON 上报发送 | `main/main.c` | `report_capabilities_once()` |
| 二进制帧编解码 | `components/ui3_protocol/` | `ui3_frame_encode()`, `ui3_frame_verify()` |
| Section 渲染调度 | `components/ui3_runtime/ui3_runtime.c` | `ui3_runtime_on_binary_frame()` |
| Section 渲染执行 | `components/ui3_section_runtime/` | `ui3_section_runtime_render()`, `ui3_section_runtime_patch()` |
| Section 事件上报 | `components/ui3_runtime/ui3_runtime.c` | `section_event_reporter()` → `UI3_MSG_EVENT_INPUT` |
| 按钮事件上报 | `components/ui3_runtime/ui3_runtime.c` | `ui3_runtime_report_button_event()` |
| 能力头生成脚本 | `scripts/generate_capability_header.py` | `build_capability_json()` → `json_to_c_lines()` |

---

## 8. 相关文档

| 文档 | 用途 | 读者 |
|------|------|------|
| `CAPABILITY_REPORTING.md`（本文档） | 运行时能力声明格式、字段说明、上报时序 | 终端固件开发者、平台协议层开发者 |
| `CAPABILITY_CATALOG.md` | 每种能力的完整 API 定义（参数、字段、事件、语义） | 平台业务层开发者 |
| `PROTOCOL.md` | WebSocket 信封、topic 定义、UI3 二进制帧格式 | 所有开发者 |
| `TEMPLATE_RUNTIME.md` | Template payload schema、patch 字段、约束 | 平台模板开发者 |
