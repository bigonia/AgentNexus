# SDUI 协议速查表

## 1. 文档用途
这份文档只做一件事：把联调时最常查的 topic、字段、上行格式和最小 JSON 示例压缩到一页里。

长文请看：
- [BACKEND_DEV_GUIDE.md](d:/esp-idf/src/sdui/docs/BACKEND_DEV_GUIDE.md)
- [TERMINAL_GUIDE.md](d:/esp-idf/src/sdui/docs/TERMINAL_GUIDE.md)
- [EXAMPLE_LIBRARY.md](d:/esp-idf/src/sdui/docs/EXAMPLE_LIBRARY.md)

---

## 2. 通用信封

```json
{
  "topic": "ui/layout",
  "device_id": "A1B2C3D4E5F6",
  "payload": {}
}
```

说明：
- 下行通常由服务端填写 `topic` 和 `payload`
- 上行时 `device_id` 由终端自动补充

---

## 3. 下行 topic 速查

### `ui/layout`
用途：全量重建 UI

```json
{
  "topic": "ui/layout",
  "payload": {
    "children": [
      {
        "type": "label",
        "id": "title",
        "text": "Hello SDUI"
      }
    ]
  }
}
```

### `ui/update`
用途：局部更新 UI

直接 patch：

```json
{
  "topic": "ui/update",
  "payload": {
    "id": "title",
    "text": "处理中..."
  }
}
```

`ops[]`：

```json
{
  "topic": "ui/update",
  "payload": {
    "page_id": "home",
    "revision": 3,
    "transaction": true,
    "ops": [
      {"op": "set", "id": "title", "path": "text", "value": "处理中..."}
    ]
  }
}
```

### `ui/features`
用途：运行特性开关

```json
{
  "topic": "ui/features",
  "payload": {
    "debug": false,
    "effects": "lite",
    "server_query_merge": true,
    "request_state": true
  }
}
```

终端会回：`ui/features_state`

### `cmd/control`
用途：控制亮度、音量

```json
{
  "topic": "cmd/control",
  "payload": {
    "cmd_id": "9d7f5a18-7f71-4f3f-9f26-59eb5bf3edcb",
    "action": "brightness",
    "value": 80
  }
}
```

支持：
- `action=brightness`，`value=0~100`
- `action=volume`，`value=0~100`
- 建议带 `cmd_id`，便于 ACK 闭环关联

### `cmd/control_ack`
来源：终端执行 `cmd/control` 后回执

```json
{
  "topic": "cmd/control_ack",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "cmd_id": "9d7f5a18-7f71-4f3f-9f26-59eb5bf3edcb",
    "action": "brightness",
    "status": "ACKED",
    "reason": "",
    "requested_value": 80,
    "applied_value": 80,
    "ts": 1774412030123
  }
}
```

### `audio/play`
用途：下发音频播放

```json
{
  "topic": "audio/play",
  "payload": "<base64-audio-data>"
}
```

---

## 4. 上行 topic 速查

### `ui/click`
来源：按钮等交互组件触发 `server://...`

```json
{
  "topic": "ui/click",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "id": "btn_start",
    "action": "start"
  }
}
```

### `ui/page_changed`
来源：viewport 初始化完成或切页完成

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

### `audio/record`
来源：录音流程

开始：

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

分片：

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

结束：

```json
{
  "topic": "audio/record",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "state": "stop"
  }
}
```

### `motion`
来源：IMU 动作

```json
{
  "topic": "motion",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "type": "shake",
    "magnitude": 16.2
  }
}
```

类型：
- `shake`
- `wrist_raise`
- `flip`

### `telemetry/heartbeat`
来源：终端心跳，默认 30 秒

```json
{
  "topic": "telemetry/heartbeat",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "wifi_rssi": -48,
    "temperature": 36.7,
    "free_heap_internal": 102400,
    "free_heap_dma": 32768,
    "uptime_s": 1800
  }
}
```

---

## 5. 常用组件最小配置

### `label`

```json
{
  "type": "label",
  "id": "title",
  "text": "标题",
  "font_size": 24,
  "text_color": "#E8F3FF"
}
```

### `button`

```json
{
  "type": "button",
  "id": "btn_ok",
  "text": "确认",
  "on_click": "server://ui/click?action=ok"
}
```

### `slider`

```json
{
  "type": "slider",
  "id": "slider_brightness",
  "min": 0,
  "max": 100,
  "value": 60,
  "on_change": "server://app/settings/brightness"
}
```

### `bar`

```json
{
  "type": "bar",
  "id": "progress",
  "min": 0,
  "max": 100,
  "value": 32,
  "indic_color": "#5CBcff"
}
```

### `overlay`

```json
{
  "type": "overlay",
  "id": "loading_overlay",
  "hidden": true,
  "children": [
    {
      "type": "label",
      "id": "loading_text",
      "text": "处理中...",
      "align": "center"
    }
  ]
}
```

### `viewport`

```json
{
  "type": "viewport",
  "id": "main_viewport",
  "initial_page": 0,
  "pages": [
    {"id": "page_home", "children": []},
    {"id": "page_settings", "children": []}
  ]
}
```

### `widget:dial`

```json
{
  "type": "widget",
  "id": "dial_temp",
  "widget_type": "dial",
  "min": 0,
  "max": 100,
  "value": 35,
  "on_change_final": "server://app/temp/set"
}
```

### `widget:waveform`

```json
{
  "type": "widget",
  "id": "wave_live",
  "widget_type": "waveform",
  "canvas_w": 240,
  "canvas_h": 88,
  "values": [50,55,45,62,48]
}
```

### `widget:spectrum`

```json
{
  "type": "widget",
  "id": "spectrum_live",
  "widget_type": "spectrum",
  "canvas_w": 240,
  "canvas_h": 88,
  "values": [20,40,60,80,50,30]
}
```

---

## 6. `ui/update.ops[]` 速查

支持操作：
- `set`
- `patch`
- `remove`
- `insert`
- `replace`

### `set / patch`

```json
{"op": "set", "id": "title", "path": "text", "value": "新标题"}
```

### `remove`

```json
{"op": "remove", "id": "loading_overlay"}
```

### `insert`

```json
{
  "op": "insert",
  "parent_id": "button_row",
  "index": 1,
  "node": {
    "type": "button",
    "id": "btn_more",
    "text": "更多"
  }
}
```

### `replace`

```json
{
  "op": "replace",
  "id": "status_area",
  "node": {
    "type": "label",
    "id": "status_done",
    "text": "完成"
  }
}
```

常用 `path`：
- `text`
- `hidden`
- `bg_color`
- `bg_opa`
- `text_color`
- `border_color`
- `border_w`
- `radius`
- `clip_corner`
- `pad`
- `w`
- `h`
- `x`
- `y`
- `align`
- `value`
- `indic_color`
- `opa`

---

## 7. Action URI 速查

### 本地动作

```text
local://audio/cmd/record_start
local://audio/cmd/record_start?context=voice_chat
local://audio/cmd/record_stop
```

### 服务端动作

```text
server://ui/click?action=start
server://app/order/submit?sku=123
server://app/settings/brightness
```

说明：
- `server_query_merge=true` 时，query 会自动并入 payload
- `button` 的基础 payload 至少带 `id`
- `slider` / `dial` 的基础 payload 通常带 `id` 和 `value`

---

## 8. 联调建议顺序
1. 先通 `ui/layout`
2. 再通 `ui/click`
3. 再通 `ui/update`
4. 再测 `viewport` 和 `ui/page_changed`
5. 再测 `audio/record`
6. 最后验证 `motion` 和 `telemetry/heartbeat`

如果你在联调时只想先看一份文档，就先看这一份；需要完整解释时，再回到主指南。
