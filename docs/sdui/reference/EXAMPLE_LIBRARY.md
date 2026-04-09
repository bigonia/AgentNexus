# SDUI 组件与协议示例库

## 1. 文档用途
这是一份“可复制、可修改、可直接联调”的 JSON 示例库。

建议使用方式：
- 先从这里复制一个最接近的例子
- 再根据业务改 `id`、文字、颜色、路由和字段
- 若需要解释字段含义，再回到主文档

相关文档：
- [BACKEND_DEV_GUIDE.md](d:/esp-idf/src/sdui/docs/BACKEND_DEV_GUIDE.md)
- [TERMINAL_GUIDE.md](d:/esp-idf/src/sdui/docs/TERMINAL_GUIDE.md)
- [PROTOCOL_CHEATSHEET.md](d:/esp-idf/src/sdui/docs/PROTOCOL_CHEATSHEET.md)

---

## 2. 最小首屏布局

```json
{
  "topic": "ui/layout",
  "payload": {
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
            "text_color": "#E8F3FF"
          },
          {
            "type": "button",
            "id": "btn_start",
            "text": "开始",
            "w": 120,
            "h": 44,
            "on_click": "server://ui/click?action=start"
          }
        ]
      }
    ]
  }
}
```

---

## 3. 常用布局示例

### 3.1 卡片布局

```json
{
  "type": "container",
  "id": "card_main",
  "w": "92%",
  "h": "88%",
  "align": "center",
  "pad": 16,
  "radius": 28,
  "bg_color": "#13263A",
  "border_w": 1,
  "border_color": "#5FB2FF70",
  "flex": "column",
  "gap": 10,
  "children": []
}
```

### 3.2 顶部标题 + 副标题

```json
{
  "type": "container",
  "id": "header",
  "w": "100%",
  "h": "content",
  "flex": "column",
  "gap": 4,
  "children": [
    {
      "type": "label",
      "id": "header_title",
      "text": "环境面板",
      "font_size": 24,
      "text_color": "#E8F3FF"
    },
    {
      "type": "label",
      "id": "header_subtitle",
      "text": "设备在线，等待操作",
      "font_size": 14,
      "text_color": "#8CA5BF"
    }
  ]
}
```

### 3.3 横向按钮组

```json
{
  "type": "container",
  "id": "button_row",
  "flex": "row",
  "gap": 8,
  "children": [
    {
      "type": "button",
      "id": "btn_ok",
      "text": "确认",
      "w": 80,
      "h": 40,
      "on_click": "server://ui/click?action=ok"
    },
    {
      "type": "button",
      "id": "btn_cancel",
      "text": "取消",
      "w": 80,
      "h": 40,
      "on_click": "server://ui/click?action=cancel"
    }
  ]
}
```

---

## 4. 基础组件示例

### 4.1 `label`

普通文本：

```json
{
  "type": "label",
  "id": "status_label",
  "text": "待命中",
  "font_size": 18,
  "text_color": "#D8E9FF"
}
```

多行换行：

```json
{
  "type": "label",
  "id": "desc_label",
  "w": "90%",
  "text": "这是一段较长的说明文字，用于演示 wrap 行为。",
  "long_mode": "wrap",
  "font_size": 14,
  "text_color": "#8CA5BF"
}
```

跑马灯：

```json
{
  "type": "label",
  "id": "marquee_label",
  "w": 220,
  "text": "这是一段会循环滚动的长文本示例",
  "long_mode": "marquee"
}
```

### 4.2 `button`

点击按钮：

```json
{
  "type": "button",
  "id": "btn_save",
  "text": "保存",
  "w": 100,
  "h": 42,
  "on_click": "server://app/save"
}
```

按住录音按钮：

```json
{
  "type": "button",
  "id": "btn_record",
  "text": "按住说话",
  "w": 120,
  "h": 48,
  "on_press": "local://audio/cmd/record_start?context=voice_chat",
  "on_release": "local://audio/cmd/record_stop"
}
```

### 4.3 `image`

```json
{
  "type": "image",
  "id": "logo_img",
  "img_w": 64,
  "img_h": 64,
  "src": "<base64-rgb565>",
  "align": "top_mid",
  "y": 20
}
```

### 4.4 `bar`

```json
{
  "type": "bar",
  "id": "progress",
  "w": 220,
  "h": 16,
  "min": 0,
  "max": 100,
  "value": 35,
  "bg_color": "#22384F",
  "indic_color": "#54B7FF",
  "radius": 10
}
```

### 4.5 `slider`

```json
{
  "type": "slider",
  "id": "slider_volume",
  "w": 220,
  "min": 0,
  "max": 100,
  "value": 50,
  "on_change": "server://app/settings/volume"
}
```

---

## 5. 页面层示例

### 5.1 `scene`

```json
{
  "type": "scene",
  "id": "bg_scene",
  "children": [
    {
      "type": "label",
      "id": "scene_hint",
      "text": "背景层",
      "align": "bottom_mid",
      "y": -20,
      "text_color": "#57718B"
    }
  ]
}
```

### 5.2 `overlay`

隐藏态 loading overlay：

```json
{
  "type": "overlay",
  "id": "loading_overlay",
  "hidden": true,
  "children": [
    {
      "type": "container",
      "id": "loading_card",
      "w": 220,
      "h": 120,
      "align": "center",
      "pad": 12,
      "radius": 20,
      "bg_color": "#081626",
      "flex": "column",
      "justify": "center",
      "align_items": "center",
      "gap": 8,
      "children": [
        {
          "type": "label",
          "id": "loading_text",
          "text": "处理中...",
          "font_size": 18,
          "text_color": "#E8F3FF"
        }
      ]
    }
  ]
}
```

显示 overlay：

```json
{
  "topic": "ui/update",
  "payload": {
    "id": "loading_overlay",
    "hidden": false
  }
}
```

隐藏 overlay：

```json
{
  "topic": "ui/update",
  "payload": {
    "id": "loading_overlay",
    "hidden": true
  }
}
```

### 5.3 `viewport`

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
        {
          "type": "label",
          "id": "home_title",
          "text": "首页",
          "align": "center"
        }
      ]
    },
    {
      "id": "page_settings",
      "children": [
        {
          "type": "label",
          "id": "settings_title",
          "text": "设置",
          "align": "center"
        }
      ]
    }
  ]
}
```

---

## 6. `widget` 示例

### 6.1 `widget_type: dial`

最小版本：

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

完整版本：

```json
{
  "type": "widget",
  "id": "dial_temp",
  "widget_type": "dial",
  "w": 220,
  "h": 220,
  "min": 16,
  "max": 30,
  "value": 22,
  "start_angle": 45,
  "sweep_angle": 270,
  "arc_width": 18,
  "ring_bg": "#22384F",
  "ring_fg": "#5CBcff",
  "value_format": "%ld°C",
  "throttle_ms": 120,
  "inertia": true,
  "inertia_friction": 0.92,
  "inertia_min_velocity": 0.02,
  "inertia_interval_ms": 16,
  "on_change": "server://app/temp/preview",
  "on_change_final": "server://app/temp/commit",
  "bind": {
    "text_id": "dial_temp_value"
  }
}
```

与外部 label 联动：

```json
{
  "type": "label",
  "id": "dial_temp_value",
  "text": "22°C",
  "align": "center",
  "y": 90,
  "font_size": 20,
  "text_color": "#E8F3FF"
}
```

### 6.2 `widget_type: waveform`

```json
{
  "type": "widget",
  "id": "wave_live",
  "widget_type": "waveform",
  "canvas_w": 248,
  "canvas_h": 88,
  "color": "#54B7FF",
  "values": [52,60,48,44,70,66,35,42,58,63]
}
```

### 6.3 `widget_type: spectrum`

```json
{
  "type": "widget",
  "id": "spectrum_live",
  "widget_type": "spectrum",
  "canvas_w": 248,
  "canvas_h": 76,
  "color": "#8FD6FF",
  "values": [18,32,44,60,75,66,48,29,22,35]
}
```

---

## 7. 动画示例

### 7.1 闪烁

```json
{
  "type": "label",
  "id": "blink_label",
  "text": "等待中",
  "anim": {
    "type": "blink",
    "duration": 800,
    "repeat": 0
  }
}
```

### 7.2 呼吸

```json
{
  "type": "container",
  "id": "breath_card",
  "w": 120,
  "h": 60,
  "bg_color": "#13263A",
  "anim": {
    "type": "breathe",
    "duration": 1200,
    "repeat": 0
  }
}
```

### 7.3 滑入

```json
{
  "type": "label",
  "id": "slide_title",
  "text": "欢迎",
  "anim": {
    "type": "slide_in",
    "from": "left",
    "duration": 300
  }
}
```

### 7.4 抖动

```json
{
  "type": "button",
  "id": "warn_btn",
  "text": "重试",
  "anim": {
    "type": "shake",
    "duration": 240,
    "amplitude": 8
  }
}
```

### 7.5 颜色脉冲

```json
{
  "type": "container",
  "id": "pulse_card",
  "w": 120,
  "h": 60,
  "anim": {
    "type": "color_pulse",
    "duration": 1000,
    "repeat": 0,
    "color_a": "#13263A",
    "color_b": "#1E8FFF"
  }
}
```

### 7.6 图片旋转

```json
{
  "type": "image",
  "id": "spin_logo",
  "img_w": 48,
  "img_h": 48,
  "src": "<base64-rgb565>",
  "anim": {
    "type": "spin",
    "direction": "ccw",
    "duration": 1200,
    "repeat": 0
  }
}
```

---

## 8. `ui/update` 示例

### 8.1 更新文本

```json
{
  "topic": "ui/update",
  "payload": {
    "id": "status_label",
    "text": "识别中..."
  }
}
```

### 8.2 更新颜色

```json
{
  "topic": "ui/update",
  "payload": {
    "id": "status_label",
    "text_color": "#FFD971"
  }
}
```

### 8.3 更新进度条

```json
{
  "topic": "ui/update",
  "payload": {
    "id": "progress",
    "value": 68
  }
}
```

### 8.4 更新 dial 值

```json
{
  "topic": "ui/update",
  "payload": {
    "id": "dial_temp",
    "value": 24
  }
}
```

### 8.5 更新 waveform

```json
{
  "topic": "ui/update",
  "payload": {
    "id": "wave_live",
    "values": [40,45,50,70,65,42,38,60,72,55]
  }
}
```

### 8.6 更新 spectrum

```json
{
  "topic": "ui/update",
  "payload": {
    "id": "spectrum_live",
    "values": [12,20,48,60,78,70,40,22,15,33]
  }
}
```

### 8.7 使用 `ops[]` 批量更新

```json
{
  "topic": "ui/update",
  "payload": {
    "page_id": "home",
    "revision": 9,
    "transaction": true,
    "ops": [
      {"op": "set", "id": "status_label", "path": "text", "value": "处理中..."},
      {"op": "set", "id": "progress", "path": "value", "value": 18},
      {"op": "set", "id": "loading_overlay", "path": "hidden", "value": false}
    ]
  }
}
```

### 8.8 插入新按钮

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
          "id": "btn_more",
          "text": "更多",
          "w": 80,
          "h": 40,
          "on_click": "server://ui/click?action=more"
        }
      }
    ]
  }
}
```

### 8.9 替换状态区块

```json
{
  "topic": "ui/update",
  "payload": {
    "transaction": true,
    "ops": [
      {
        "op": "replace",
        "id": "status_block",
        "node": {
          "type": "container",
          "id": "status_done_block",
          "flex": "column",
          "gap": 6,
          "children": [
            {
              "type": "label",
              "id": "done_title",
              "text": "任务完成",
              "font_size": 20,
              "text_color": "#8AFFC1"
            },
            {
              "type": "label",
              "id": "done_desc",
              "text": "所有步骤已执行完成",
              "font_size": 14,
              "text_color": "#8CA5BF"
            }
          ]
        }
      }
    ]
  }
}
```

---

## 9. 上行消息示例

### 9.1 点击按钮上行
按钮配置：

```json
{
  "type": "button",
  "id": "btn_confirm",
  "text": "确认",
  "on_click": "server://ui/click?action=confirm&scene=checkout"
}
```

终端上行：

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

### 9.2 slider 上行
组件配置：

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

终端上行：

```json
{
  "topic": "app/settings/brightness",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "id": "slider_brightness",
    "value": 60
  }
}
```

### 9.3 dial 上行
组件配置：

```json
{
  "type": "widget",
  "id": "dial_temp",
  "widget_type": "dial",
  "min": 16,
  "max": 30,
  "value": 22,
  "on_change_final": "server://app/temp/commit"
}
```

终端上行：

```json
{
  "topic": "app/temp/commit",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "id": "dial_temp",
    "value": 24
  }
}
```

### 9.4 viewport 切页上行

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

### 9.5 motion 上行

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

---

## 10. 一套完整综合页面示例

```json
{
  "topic": "ui/layout",
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

这份示例库建议随着新增组件持续扩充。最有价值的维护方式不是写更抽象的概念，而是不断加入“能直接跑”的真实 JSON 片段。
