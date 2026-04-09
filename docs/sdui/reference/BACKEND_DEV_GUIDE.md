# SDUI 服务端与应用开发指南

## 1. 文档目标
本文档面向后端开发者、应用编排开发者、测试同学以及需要通过 WebSocket 驱动终端界面的服务端工程。

如果你要回答下面这些问题，这份文档就是主入口：
- 终端当前到底支持哪些 UI 组件、字段和交互方式。
- `ui/layout`、`ui/update`、`ui/features`、`audio/play`、`cmd/control` 应该怎么发。
- 终端会上报哪些事件，以及每个事件实际携带哪些字段。
- 一个后端应用应该如何组织“首屏布局 + 增量更新 + 交互回调 + 设备控制”的完整闭环。

配套文档：
- [TERMINAL_GUIDE.md](d:/esp-idf/src/sdui/docs/TERMINAL_GUIDE.md)：终端实现与内部机制
- [PROTOCOL_CHEATSHEET.md](d:/esp-idf/src/sdui/docs/PROTOCOL_CHEATSHEET.md)：联调速查表
- [EXAMPLE_LIBRARY.md](d:/esp-idf/src/sdui/docs/EXAMPLE_LIBRARY.md)：可直接复制的 JSON 示例库
- [BACKEND_INTEGRATION_FLOW.md](d:/esp-idf/src/sdui/docs/BACKEND_INTEGRATION_FLOW.md)：标准接入流程
- [FIELD_REFERENCE.md](d:/esp-idf/src/sdui/docs/FIELD_REFERENCE.md)：统一字段字典

---

## 2. 系统总览
系统采用“服务端声明式描述 UI，终端负责渲染与执行交互”的模式。

典型链路如下：
1. 服务端建立 WebSocket 连接。
2. 服务端发送 `ui/layout` 完成首屏渲染。
3. 用户在设备上点击、滑动、录音或触发 IMU 动作。
4. 终端上报 `ui/click`、`ui/page_changed`、`audio/record`、`motion`、`telemetry/heartbeat` 等主题。
5. 服务端根据上行事件更新业务状态，并优先通过 `ui/update` 做局部更新。
6. 如需大场景切换，再发送新的 `ui/layout`。

推荐原则：
- 首次进入页面、场景切换、结构大改：用 `ui/layout`
- 文本、颜色、数值、显隐、小范围替换：用 `ui/update`
- 高频动画数据、波形/频谱/仪表值更新：尽量只推局部字段
- 强交互控制：优先本地 `local://`，降低时延

---

## 3. 通信信封
所有上下行消息都使用统一信封：

```json
{
  "topic": "ui/layout",
  "device_id": "A1B2C3D4E5F6",
  "payload": {}
}
```

字段说明：
- `topic`：消息主题。
- `device_id`：设备唯一标识。终端侧会自动补充，上行时无需服务端回填。
- `payload`：主题对应的数据体，通常是 JSON 对象；少数情况下也可能是字符串。

约束建议：
- `topic` 使用稳定、短小、可路由的字符串。
- `payload` 尽量保持对象结构，便于扩展和兼容。
- 同一业务页面建议显式维护 `page_id` 和 `revision`。

---

## 4. 下行主题

### 4.1 `ui/layout`
用途：下发完整页面树，终端会重建当前 UI。

最小示例：

```json
{
  "topic": "ui/layout",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "safe_pad": 16,
    "children": [
      {
        "type": "container",
        "id": "page_root",
        "w": "100%",
        "h": "100%",
        "flex": "column",
        "justify": "center",
        "align_items": "center",
        "gap": 12,
        "children": [
          {
            "type": "label",
            "id": "title",
            "text": "Hello SDUI",
            "font_size": 24,
            "text_color": "#E8F6FF"
          },
          {
            "type": "button",
            "id": "btn_start",
            "text": "开始",
            "w": 140,
            "h": 48,
            "on_click": "server://ui/click?action=start"
          }
        ]
      }
    ]
  }
}
```

实现要点：
- `payload` 可以是一个对象，也可以是一个数组。
- 如果 `payload` 是对象且包含 `children`，终端会把它当作根布局描述。
- `safe_pad` 会覆盖根视图安全边距，适合圆屏裁切保护。
- 每次 `ui/layout` 都会重建对象树，旧对象及其动画会被清理。

适用场景：
- 首屏初始化
- 页面整体切换
- 结构变化较大，难以通过 `ops[]` 表达
- 需要重置整个组件树状态

### 4.2 `ui/update`
用途：对已存在组件做局部更新，是日常业务更新的主力通道。

支持两种模式：
- 直接 patch 一个对象
- 通过 `ops[]` 批量执行事务式更新

#### 4.2.1 直接 patch 模式

```json
{
  "topic": "ui/update",
  "payload": {
    "id": "title",
    "text": "识别中...",
    "text_color": "#FFD971"
  }
}
```

当前实现支持的直接 patch 字段包括：
- 通用：`id`
- 文本与显隐：`text`、`hidden`
- 尺寸定位：`w`、`h`、`x`、`y`、`align`
- 样式：`bg_color`、`bg_opa`、`text_color`、`border_color`、`border_w`、`radius`、`clip_corner`、`pad`、`opa`
- 数值类：`value`
- `bar` 特有：`indic_color`
- 动画：`anim`
- `widget` 扩展：`dial` 的 `min/max/value/ring_bg/ring_fg/arc_width`，`waveform/spectrum` 的 `values/color`

#### 4.2.2 `ops[]` 模式

```json
{
  "topic": "ui/update",
  "payload": {
    "page_id": "home",
    "revision": 12,
    "transaction": true,
    "ops": [
      {"op": "set", "id": "title", "path": "text", "value": "处理中..."},
      {"op": "patch", "id": "title", "path": "text_color", "value": "#FFB347"},
      {"op": "set", "id": "progress", "path": "value", "value": 72}
    ]
  }
}
```

支持的操作：
- `set`
- `patch`
- `remove`
- `insert`
- `replace`

`transaction=true` 的行为：
- 终端会先校验整批 `ops[]` 是否可执行。
- 只要其中一条不合法，整批放弃，不会部分生效。

`revision` 的行为：
- 终端会按 `page_id` 记录最新 revision。
- 若收到更小或相等 revision，会直接丢弃，防止乱序覆盖。
- 未提供 `page_id` 时，会落到 `_global` 维度。

#### 4.2.3 `path` 映射表
当前 `set/patch` 支持的 `path`：

| path | 等价写法 | 说明 |
|---|---|---|
| `text` | `props.text` | 文本内容 |
| `hidden` | `style.hidden` | 显示/隐藏 |
| `bg_color` | `style.bg_color` | 背景色 |
| `bg_opa` | `style.bg_opa` | 背景透明度 |
| `text_color` | `style.text_color` | 字体颜色 |
| `border_color` | `style.border_color` | 边框颜色 |
| `border_w` | `style.border_w` | 边框宽度 |
| `radius` | `style.radius` | 圆角 |
| `clip_corner` | `style.clip_corner` | 裁角 |
| `pad` | `layout.pad` | 内边距 |
| `w` | `layout.w` | 宽度 |
| `h` | `layout.h` | 高度 |
| `x` | `layout.x` | X 偏移 |
| `y` | `layout.y` | Y 偏移 |
| `align` | `layout.align` | 对齐方式 |
| `value` | `props.value` | 数值型组件值 |
| `indic_color` | `style.indic_color` | `bar` 指示色 |
| `opa` | `style.opa` | 整体透明度 |

#### 4.2.4 结构操作示例

插入：

```json
{
  "topic": "ui/update",
  "payload": {
    "transaction": true,
    "ops": [
      {
        "op": "insert",
        "parent_id": "button_row",
        "index": 1,
        "node": {
          "type": "button",
          "id": "btn_extra",
          "text": "更多",
          "w": 80,
          "h": 36,
          "on_click": "server://ui/click?action=more"
        }
      }
    ]
  }
}
```

替换：

```json
{
  "topic": "ui/update",
  "payload": {
    "ops": [
      {
        "op": "replace",
        "id": "status_area",
        "node": {
          "type": "label",
          "id": "status_area_new",
          "text": "任务完成",
          "font_size": 18,
          "text_color": "#8AFFC1"
        }
      }
    ]
  }
}
```

删除：

```json
{
  "topic": "ui/update",
  "payload": {
    "ops": [
      {"op": "remove", "id": "loading_overlay"}
    ]
  }
}
```

### 4.3 `ui/features`
用途：动态调整终端运行特性。

当前实现支持字段：
- `debug`: `true/false`
- `smoke`: `true/false`
- `effects`: `lite | balanced | rich`
- `server_query_merge`: `true/false`
- `request_state`: `true` 时请求终端返回当前状态

示例：

```json
{
  "topic": "ui/features",
  "payload": {
    "debug": true,
    "effects": "lite",
    "server_query_merge": true,
    "request_state": true
  }
}
```

终端收到后会回发：

```json
{
  "topic": "ui/features_state",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "debug": true,
    "smoke": true,
    "effects": "lite",
    "server_query_merge": true
  }
}
```

说明：
- `effects` 会影响特效复杂度，弱资源场景建议优先切到 `lite`。
- `smoke` 当前主要用于禁止 `server://ui/run_smoke` 之类的测试动作。
- `server_query_merge` 会影响 `server://topic?a=1&b=2` 的 query 是否自动并入 payload。

### 4.4 `audio/play`
用途：让终端播放服务端下发的音频。

当前接收端订阅了 `audio/play`，处理函数为 `audio_play_base64`，因此推荐 payload 直接使用 Base64 音频数据或由你的服务端约定具体字段后统一封装。

建议示例：

```json
{
  "topic": "audio/play",
  "payload": "<base64-audio-data>"
}
```

### 4.5 `cmd/control`
用途：设备控制。

当前终端实现支持：
- `action=volume`，`value=0~100`
- `action=brightness`，`value=0~100`

示例：

```json
{
  "topic": "cmd/control",
  "payload": {
    "action": "brightness",
    "value": 60
  }
}
```

---

## 5. 上行主题

### 5.1 `ui/click`
来源：按钮等交互组件触发 `on_click` / `on_press` / `on_release`，且 URI 指向 `server://...`。

默认 payload 至少包含：
- `id`: 组件 id

当开启 `server_query_merge=true` 且 URI 带 query 时，query 会合并进 payload。

示例：按钮配置

```json
{
  "type": "button",
  "id": "btn_confirm",
  "text": "确认",
  "on_click": "server://ui/click?action=confirm&scene=checkout"
}
```

对应上行：

```json
{
  "topic": "ui/click",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "id": "btn_confirm",
    "action": "confirm",
    "scene": "checkout"
  }
}
```

如果 `server_query_merge=false`，query 不会合并进 payload，且上行主题会取 `server://` 后面的基础 topic。

### 5.2 `ui/page_changed`
来源：`viewport` 初始化完成后，以及滑动切页后。

示例：

```json
{
  "topic": "ui/page_changed",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "viewport": "main_viewport",
    "page": "page_settings",
    "index": 1
  }
}
```

说明：
- `viewport` 为 viewport 的 `id`
- `page` 为当前页对象的 `id`
- `index` 为页索引，从 0 开始

### 5.3 `audio/record`
来源：本地录音流程。

录音上报分三段：
- `start`
- 多次 `stream`
- `stop`

示例：

```json
{
  "topic": "audio/record",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "state": "start",
    "context": "voice_chat"
  }
}
```

```json
{
  "topic": "audio/record",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "state": "stream",
    "data": "<base64-pcm-chunk>"
  }
}
```

```json
{
  "topic": "audio/record",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "state": "stop"
  }
}
```

后端建议：
- 按 `device_id + 会话上下文` 聚合录音分片
- `start` 到 `stop` 之间缓存 PCM chunk
- `stop` 后再统一送入 STT

### 5.4 `motion`
来源：IMU 动作识别。

当前实现会发送三类动作：
- `shake`
- `wrist_raise`
- `flip`

示例：

```json
{
  "topic": "motion",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "type": "shake",
    "magnitude": 15.8
  }
}
```

```json
{
  "topic": "motion",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "type": "wrist_raise",
    "tilt": 43.2,
    "delta": 15.7
  }
}
```

```json
{
  "topic": "motion",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "type": "flip",
    "from": "face_up",
    "to": "face_down",
    "tilt": 91.5
  }
}
```

### 5.5 `telemetry/heartbeat`
来源：终端定时心跳，默认 30 秒。

当前字段包括：
- `device_id`
- `wifi_rssi`
- `ip`
- `temperature`
- `free_heap_internal`
- `largest_heap_internal`
- `free_heap_dma`
- `largest_heap_dma`
- `free_heap_psram`
- `largest_heap_psram`
- `free_heap_total`
- `frag_internal_pct`
- `frag_dma_pct`
- `frag_psram_pct`
- `uptime_s`

示例：

```json
{
  "topic": "telemetry/heartbeat",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "device_id": "A1B2C3D4E5F6",
    "wifi_rssi": -51,
    "ip": "192.168.1.88",
    "temperature": 37.6,
    "free_heap_internal": 102400,
    "largest_heap_internal": 65536,
    "free_heap_dma": 32768,
    "largest_heap_dma": 28672,
    "free_heap_psram": 6710886,
    "largest_heap_psram": 6291456,
    "free_heap_total": 6893568,
    "frag_internal_pct": 36,
    "frag_dma_pct": 12,
    "frag_psram_pct": 6,
    "uptime_s": 1832
  }
}
```

推荐用途：
- 做设备在线状态与弱网判定
- 做资源告警与性能看板
- 服务端根据心跳动态下发 `ui/features.effects=lite`

---

## 6. UI Schema 参考

### 6.1 通用字段
几乎所有组件都支持以下字段中的一部分：

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | string | 组件类型 |
| `id` | string | 组件唯一标识，强烈建议全页面唯一 |
| `w` | number/string | 宽度，支持数字、`"full"`、`"content"`、`"92%"` |
| `h` | number/string | 高度 |
| `align` | string | 对齐方式，如 `center`、`top_mid`、`bottom_left` |
| `x` | number | 基于对齐点的 X 偏移 |
| `y` | number | 基于对齐点的 Y 偏移 |
| `bg_color` | string | 背景色，如 `#112233` |
| `bg_opa` | number | 背景透明度 0~255 |
| `text_color` | string | 文本颜色 |
| `font_size` | number | 字号，终端会按尺寸选字体 |
| `pad` | number | 内边距 |
| `gap` | number | 行列间距 |
| `radius` | number | 圆角 |
| `border_w` | number | 边框宽度 |
| `border_color` | string | 边框颜色 |
| `shadow_w` | number | 阴影宽度 |
| `shadow_color` | string | 阴影颜色 |
| `opa` | number | 对象整体透明度 0~255 |
| `hidden` | bool | 初始是否隐藏 |
| `clip_corner` | bool | 是否裁角 |
| `pointer_events` | string | 当前只支持 `none`，用于禁用点击/滚动 |
| `anim` | object | 动画配置 |
| `z` | number | 子节点排序辅助字段 |

### 6.2 容器与页面层

#### `container`
基础布局容器，支持：
- `children`
- `flex`: `row | column | row_wrap | column_wrap`
- `justify`
- `align_items`
- `scrollable: true`

示例：

```json
{
  "type": "container",
  "id": "card",
  "w": "92%",
  "h": "content",
  "pad": 16,
  "radius": 24,
  "bg_color": "#14221A",
  "flex": "column",
  "gap": 10,
  "children": []
}
```

#### `scene`
全屏场景层，本质上是全屏 `container`，常用于背景层。

#### `overlay`
全屏浮层，本质上是全屏 `container`，支持通过 `hidden` 控制显示与隐藏。
适合：
- loading
- modal
- 录音面板
- 半透明遮罩

#### `viewport`
多页滑动容器，终端内部基于 `tileview` 实现。

支持字段：
- `id`
- `direction`: `horizontal | vertical`，默认水平
- `initial_page`: 初始页索引
- `pages`: 页面数组

页对象常用字段：
- `id`
- `children`
- 页面级样式和布局字段

示例：

```json
{
  "type": "viewport",
  "id": "main_viewport",
  "direction": "horizontal",
  "initial_page": 0,
  "pages": [
    {
      "id": "page_home",
      "children": [
        {"type": "label", "id": "home_title", "text": "首页"}
      ]
    },
    {
      "id": "page_settings",
      "children": [
        {"type": "label", "id": "settings_title", "text": "设置"}
      ]
    }
  ]
}
```

注意：
- 当前最多支持 8 页。
- 邻页会被预构建，用于减少首次滑动卡顿。
- 初始化后终端会立即上报一次 `ui/page_changed`。

### 6.3 基础组件

#### `label`
字段：
- `text`
- `long_mode`: `wrap | scroll | dot | marquee`

#### `button`
字段：
- `text`
- `on_click`
- `on_press`
- `on_release`

#### `image`
字段：
- `src`: Base64 编码的 RGB565 图像数据
- `img_w`
- `img_h`

说明：
- 终端会把解码后的图像放入 PSRAM。
- 大图会显著消耗内存，不建议频繁重下发。

#### `bar`
字段：
- `min`
- `max`
- `value`
- `bg_color`
- `indic_color`

#### `slider`
字段：
- `min`
- `max`
- `value`
- `on_change`

当前行为：
- `slider` 在 `LV_EVENT_RELEASED` 时才上报一次。
- payload 格式为 `{"id":"...","value":123}`。

### 6.4 `widget` 扩展组件
终端当前已实现 3 个 `widget_type`：
- `dial` / `dial_v2`
- `waveform` / `waveform_v2`
- `spectrum` / `spectrum_v2`

#### `widget_type: dial`
字段：
- `min`
- `max`
- `value`
- `start_angle`
- `sweep_angle`
- `arc_width`
- `ring_bg`
- `ring_fg`
- `value_format`
- `throttle_ms`
- `inertia`
- `inertia_friction`
- `inertia_min_velocity`
- `inertia_interval_ms`
- `on_change`
- `on_change_final`
- `bind.text_id`

示例：

```json
{
  "type": "widget",
  "id": "dial_temp",
  "widget_type": "dial",
  "min": 16,
  "max": 30,
  "value": 22,
  "arc_width": 18,
  "ring_bg": "#22384F",
  "ring_fg": "#5CBcff",
  "value_format": "%ld°C",
  "inertia": true,
  "on_change": "server://ui/dial_change?target=temp",
  "on_change_final": "server://ui/dial_commit?target=temp",
  "bind": {
    "text_id": "dial_temp_value"
  }
}
```

上报 payload 示例：

```json
{
  "id": "dial_temp",
  "value": 24
}
```

#### `widget_type: waveform`
字段：
- `canvas_w`
- `canvas_h`
- `values`
- `color`

`values` 建议是 0~100 的数组。

#### `widget_type: spectrum`
字段：
- `canvas_w`
- `canvas_h`
- `values`
- `color`

同样建议 `values` 为 0~100 的数组。

---

## 7. Action URI 规范
交互路由统一使用 URI 字符串。

### 7.1 `local://`
表示本地短路执行，不经过服务端。

示例：
- `local://audio/cmd/record_start`
- `local://audio/cmd/record_start?context=voice_chat`
- `local://audio/cmd/record_stop`

当前实现行为：
- 对于按钮类 `dispatch_action`，如果是 `local://xxx?foo=1`，payload 直接传原始 query 字符串，例如 `foo=1`。
- 对于 `slider`，payload 是 JSON 字符串，例如 `{"id":"slider1","value":40}`。
- 对于 `dial`，payload 是 JSON 字符串，例如 `{"id":"dial1","value":25}`。

因此建议：
- 按钮型本地动作用 query 传简单参数。
- 数值型组件直接读取 JSON payload。

### 7.2 `server://`
表示上报到服务端。

示例：
- `server://ui/click?action=play`
- `server://app/weather/select?city=shanghai`

行为说明：
- topic 取 `server://` 后面的部分。
- 当 `server_query_merge=true` 时，query 会自动并入 payload。
- 当 `server_query_merge=false` 时，payload 只保留组件侧默认字段。

推荐实践：
- 一个按钮对应一个稳定业务 topic，例如 `server://app/order/submit`
- 如果仍想统一由一个路由处理，也可以全部收敛到 `server://ui/click?...`

---

## 8. 推荐的后端开发模式

### 8.1 页面状态模型
推荐每个设备维护一份页面状态，例如：

```python
state = {
    "page": "home",
    "revision": 17,
    "recording": False,
    "progress": 32,
    "theme": "ice",
}
```

约束建议：
- 每个页面或场景单独维护 revision。
- 同一设备的 revision 只增不减。
- 后端只从状态生成 UI，不直接拼接随机 patch。

### 8.2 首屏 + 增量更新闭环
推荐流程：
1. 设备连接后，根据当前业务状态生成 `ui/layout`
2. 交互后只发送最小必要 `ui/update`
3. 真正发生场景切换时重新发送 `ui/layout`

### 8.3 服务端伪代码

```python
async def on_ws_connected(device_id, ws):
    layout = build_home_layout(device_id)
    await ws.send_json({
        "topic": "ui/layout",
        "device_id": device_id,
        "payload": layout,
    })

async def on_ui_click(device_id, payload, ws):
    action = payload.get("action")
    if action == "start":
        await ws.send_json({
            "topic": "ui/update",
            "device_id": device_id,
            "payload": {
                "page_id": "home",
                "revision": next_revision(device_id, "home"),
                "transaction": True,
                "ops": [
                    {"op": "set", "id": "title", "path": "text", "value": "处理中..."},
                    {"op": "set", "id": "progress", "path": "value", "value": 10},
                    {"op": "set", "id": "loading_overlay", "path": "hidden", "value": False}
                ]
            }
        })
```

### 8.4 常见业务模板

#### 1. 状态标签 + 进度条
- 用一个 `label` 显示文案
- 用一个 `bar` 显示进度
- 每次仅更新 `text` 和 `value`

#### 2. 录音面板
- 按钮 `on_press` -> `local://audio/cmd/record_start?context=voice_chat`
- 按钮 `on_release` -> `local://audio/cmd/record_stop`
- 服务端收到 `audio/record.start` 后显示“录音中” overlay
- 收到 STT 结果后更新文案并隐藏 overlay

#### 3. 滑页应用
- 顶层放一个 `viewport`
- 服务端监听 `ui/page_changed`
- 仅在需要时对当前页发送对应 `page_id` 的更新

#### 4. 实时数据面板
- 数值：更新 `label.text`
- 仪表：更新 `dial.value`
- 波形：更新 `waveform.values`
- 频谱：更新 `spectrum.values`
- 强烈建议带 `revision`

---

## 9. 视觉配置说明
仓库中存在视觉配置文件：
- `configs/ui/ui2_visual_tokens.json`
- `configs/ui/ui2_theme_scene_styles.json`

这些文件当前更适合作为“服务端布局生成模板”或“演示服务器视觉配置源”，而不是终端解析器直接原生识别的 token 语法。

这意味着：
- 终端渲染层当前直接识别的是最终值，如 `#35d0ff`、`24`、`92%`
- 如果你想使用 token/preset/theme，建议在服务端先把 token 渲染成最终 JSON，再下发给终端
- 文档和示例中出现的 theme/preset 概念，建议理解为服务端编排层能力

推荐做法：
1. 服务端读取视觉配置文件
2. 选择 preset/theme/layout variant
3. 生成最终布局 JSON
4. 再发送 `ui/layout` 或 `ui/update`

---

## 10. 接入注意事项
- `id` 必须稳定且唯一，增量更新依赖它。
- 高并发更新必须带 `revision`。
- 高频小改动用 `ui/update`，不要反复全量 `ui/layout`。
- `image` 会占 PSRAM，谨慎使用大图。
- `viewport` 最多 8 页，建议只在必要时放复杂页面。
- 终端在低内存情况下会主动跳过部分 `ui/update`、`motion`、`telemetry`，后端要能容忍偶发丢包。
- 若设备心跳中的 `frag_dma_pct`、`frag_internal_pct` 持续走高，优先降低 `effects` 并减少大图和大画布。

---

## 11. 一份完整示例
下面是一个“首页 + overlay + 仪表 + 波形 + 滑页”的综合布局示例：

```json
{
  "topic": "ui/layout",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "safe_pad": 16,
    "children": [
      {
        "type": "scene",
        "id": "bg_scene",
        "children": []
      },
      {
        "type": "viewport",
        "id": "main_viewport",
        "initial_page": 0,
        "pages": [
          {
            "id": "page_home",
            "children": [
              {
                "type": "container",
                "id": "home_card",
                "w": "92%",
                "h": "88%",
                "align": "center",
                "pad": 16,
                "radius": 28,
                "bg_color": "#13263A",
                "flex": "column",
                "gap": 12,
                "children": [
                  {
                    "type": "label",
                    "id": "title",
                    "text": "环境面板",
                    "font_size": 24,
                    "text_color": "#E8F3FF"
                  },
                  {
                    "type": "label",
                    "id": "subtitle",
                    "text": "设备在线，等待操作",
                    "font_size": 14,
                    "text_color": "#8CA5BF"
                  },
                  {
                    "type": "widget",
                    "id": "dial_temp",
                    "widget_type": "dial",
                    "min": 0,
                    "max": 100,
                    "value": 32,
                    "arc_width": 18,
                    "ring_bg": "#22384F",
                    "ring_fg": "#5CBcff",
                    "value_format": "%ld%%",
                    "on_change_final": "server://app/temp/set"
                  },
                  {
                    "type": "widget",
                    "id": "wave_live",
                    "widget_type": "waveform",
                    "canvas_w": 248,
                    "canvas_h": 88,
                    "color": "#54B7FF",
                    "values": [50,55,48,60,66,52,40,35]
                  },
                  {
                    "type": "container",
                    "id": "button_row",
                    "flex": "row",
                    "gap": 8,
                    "children": [
                      {
                        "type": "button",
                        "id": "btn_start",
                        "text": "启动",
                        "w": 90,
                        "h": 40,
                        "on_click": "server://ui/click?action=start"
                      },
                      {
                        "type": "button",
                        "id": "btn_record",
                        "text": "录音",
                        "w": 90,
                        "h": 40,
                        "on_press": "local://audio/cmd/record_start?context=voice_chat",
                        "on_release": "local://audio/cmd/record_stop"
                      }
                    ]
                  }
                ]
              }
            ]
          },
          {
            "id": "page_settings",
            "children": [
              {
                "type": "slider",
                "id": "slider_brightness",
                "min": 0,
                "max": 100,
                "value": 60,
                "w": 220,
                "align": "center",
                "on_change": "server://app/settings/brightness"
              }
            ]
          }
        ]
      },
      {
        "type": "overlay",
        "id": "loading_overlay",
        "hidden": true,
        "children": [
          {
            "type": "label",
            "id": "loading_text",
            "text": "处理中...",
            "align": "center",
            "font_size": 18,
            "text_color": "#E8F3FF"
          }
        ]
      }
    ]
  }
}
```

---

## 12. 建议的联调清单
联调时建议至少覆盖以下检查项：
1. 首屏 `ui/layout` 是否能稳定渲染。
2. `button` 的 `on_click`、`on_press`、`on_release` 是否符合预期。
3. `slider` 与 `dial` 的数值上报格式是否正确。
4. `ui/update` 的 `revision` 是否能拦截乱序包。
5. `viewport` 切页是否能收到 `ui/page_changed`。
6. `audio/record` 三段式上报是否完整。
7. `motion` 和 `telemetry/heartbeat` 是否进入服务端日志链路。
8. `ui/features` 下发后是否能收到 `ui/features_state`。

这份文档的重点是“按当前实现可直接接入”。如果后续终端新增组件、字段或主题，我们建议优先同步更新本文件的组件表、主题表和示例，而不是只在代码里加注释。
