# SDUI 应用模板包（当前版本）

## 1. 目的

本文件沉淀可反复复用的 SDUI 应用模板包，面向当前 Java + Python Worker 架构，目标是：

1. 让 AI 生成结果在首版就接近可用。
2. 降低每次从 0 到 1 的试错成本。
3. 为后续“优秀样例库”提供统一结构。

## 2. 使用方式

1. 先选模板包（展示/控制/媒体）。
2. 填写模板包的“场景规格卡”。
3. 用对应 Prompt 生成脚本。
4. 按模板包验收清单做 3 轮快测。
5. 稳定后沉淀为团队样例。

## 3. 模板包 A：展示类（Dashboard / Status）

## 3.1 适用场景

1. 设备状态看板。
2. 环境监控面板。
3. 任务进度展示。

## 3.2 结构规范

1. 背景层：`scene` 或大容器。
2. 内容层：单卡片 `container`。
3. 信息层：
- 标题 `label`
- 副标题 `label`
- 关键数值 `label/bar`
- 主按钮 `button`

## 3.3 事件规范

1. 主事件：`refresh`、`toggle`、`ack`。
2. 未识别动作必须 `noop`。
3. 更新优先 `ui/update`，仅页面模式切换使用 `layout`。

## 3.4 状态键建议（`cap.store`）

1. `status_text`
2. `status_color`
3. `metric_value`
4. `last_action`

## 3.5 样例骨架

```python

def on_start(ctx):
    st = ctx.get("store", {})
    return {
        "action": "layout",
        "page_id": "home",
        "payload": {
            "children": [
                {"type": "scene", "id": "bg", "bg_color": "#081420"},
                {
                    "type": "container",
                    "id": "card",
                    "w": "92%",
                    "h": "88%",
                    "align": "center",
                    "pad": 12,
                    "radius": 20,
                    "bg_color": "#13263A",
                    "flex": "column",
                    "gap": 8,
                    "children": [
                        {"type": "label", "id": "title", "text": st.get("title", "Device Dashboard"), "font_size": 22, "text_color": "#E8F3FF"},
                        {"type": "label", "id": "status", "text": st.get("status_text", "Ready"), "font_size": 13, "text_color": st.get("status_color", "#9AB8D5")},
                        {"type": "bar", "id": "progress", "w": 220, "h": 14, "min": 0, "max": 100, "value": int(st.get("metric_value", 30))},
                        {"type": "button", "id": "btn_refresh", "text": "刷新", "w": 108, "h": 40, "on_click": "server://ui/click?action=refresh"}
                    ]
                }
            ]
        }
    }


def on_event(ctx, event):
    action = (event or {}).get("action", "")
    if action == "refresh":
        val = int(cap.store.get("metric_value", 30)) + 7
        if val > 100:
            val = 12
        cap.store.set("metric_value", val)
        cap.store.set("status_text", "Updated")
        return {
            "action": "update",
            "page_id": "home",
            "payload": {
                "ops": [
                    {"op": "set", "id": "progress", "path": "value", "value": val},
                    {"op": "set", "id": "status", "path": "text", "value": "Updated"},
                    {"op": "set", "id": "status", "path": "text_color", "value": "#8AFFC1"}
                ],
                "transaction": True
            }
        }
    return {"action": "noop", "page_id": "home", "payload": {}}
```

## 3.6 验收要点

1. 首屏信息是否 2 秒内可理解。
2. 主按钮点击后是否只更新局部组件。
3. 连续点击 20 次是否仍稳定。

## 4. 模板包 B：控制类（Control Panel）

## 4.1 适用场景

1. 音量/亮度控制。
2. 模式切换控制台。
3. 功能开关面板。

## 4.2 结构规范

1. 标题区。
2. 当前值区（`label` + `slider/bar`）。
3. 操作区（`+/-`、切换按钮）。
4. 反馈区（操作结果）。

## 4.3 事件规范

1. 事件动作建议：`inc`、`dec`、`apply`、`mode_next`。
2. 值域强约束（例如 `0-100`）。
3. 每次操作写入 `store` 并回显。

## 4.4 状态键建议

1. `value`
2. `mode`
3. `last_result`

## 4.5 样例骨架

```python

def clamp(v, lo=0, hi=100):
    if v < lo:
        return lo
    if v > hi:
        return hi
    return v


def on_start(ctx):
    v = int(ctx.get("store", {}).get("value", 50))
    return {
        "action": "layout",
        "page_id": "home",
        "payload": {
            "children": [
                {
                    "type": "container",
                    "id": "root",
                    "w": "100%",
                    "h": "100%",
                    "flex": "column",
                    "justify": "center",
                    "align_items": "center",
                    "gap": 10,
                    "children": [
                        {"type": "label", "id": "title", "text": "Control", "font_size": 22, "text_color": "#E8F3FF"},
                        {"type": "label", "id": "value_text", "text": str(v), "font_size": 20, "text_color": "#DFF1FF"},
                        {"type": "bar", "id": "value_bar", "w": 220, "h": 16, "min": 0, "max": 100, "value": v},
                        {"type": "container", "id": "row_btn", "flex": "row", "gap": 8, "children": [
                            {"type": "button", "id": "btn_dec", "text": "-", "w": 64, "h": 40, "on_click": "server://ui/click?action=dec"},
                            {"type": "button", "id": "btn_inc", "text": "+", "w": 64, "h": 40, "on_click": "server://ui/click?action=inc"}
                        ]},
                        {"type": "label", "id": "result", "text": "Ready", "font_size": 12, "text_color": "#9AB8D5"}
                    ]
                }
            ]
        }
    }


def on_event(ctx, event):
    action = (event or {}).get("action", "")
    value = int(cap.store.get("value", 50))
    if action == "inc":
        value = clamp(value + 5)
    elif action == "dec":
        value = clamp(value - 5)
    else:
        return {"action": "noop", "page_id": "home", "payload": {}}

    cap.store.set("value", value)
    return {
        "action": "update",
        "page_id": "home",
        "payload": {
            "ops": [
                {"op": "set", "id": "value_text", "path": "text", "value": str(value)},
                {"op": "set", "id": "value_bar", "path": "value", "value": value},
                {"op": "set", "id": "result", "path": "text", "value": "Applied"}
            ],
            "transaction": True
        }
    }
```

## 4.6 验收要点

1. 值域是否永不越界。
2. 连续快速点击时是否无错位。
3. 更新是否只触达 `value_*` 与反馈区。

## 5. 模板包 C：媒体类（Media / Asset）

## 5.1 适用场景

1. 音频播放控制页。
2. 图文内容浏览。
3. 资源轮播页。

## 5.2 结构规范

1. 封面/图像区域（`image`）。
2. 文本信息区（标题/副标题）。
3. 控制区（播放、上一首、下一首）。
4. 进度区（`bar` + 时间文本）。

## 5.3 资源约束

1. 必须先从 `ctx.assets` 取资源。
2. 资源缺失时展示 placeholder 文案，不得崩溃。
3. 只消费 `processed_payload`，不做重处理。

## 5.4 状态键建议

1. `track_index`
2. `playing`
3. `progress`
4. `track_title`

## 5.5 样例骨架

```python

def pick_track(assets, idx):
    if not isinstance(assets, list) or not assets:
        return None
    if idx < 0:
        idx = 0
    if idx >= len(assets):
        idx = 0
    return assets[idx], idx


def on_start(ctx):
    idx = int(ctx.get("store", {}).get("track_index", 0))
    picked = pick_track(ctx.get("assets", []), idx)
    if not picked:
        return {
            "action": "layout",
            "page_id": "home",
            "payload": {"children": [{"type": "label", "id": "empty", "text": "No Media Assets", "font_size": 18, "text_color": "#E8F3FF"}]}
        }
    track, idx = picked
    cap.store.set("track_index", idx)
    cap.store.set("track_title", track.get("name", "Track"))
    return {
        "action": "layout",
        "page_id": "home",
        "payload": {
            "children": [
                {
                    "type": "container",
                    "id": "media_card",
                    "w": "92%",
                    "h": "88%",
                    "align": "center",
                    "pad": 12,
                    "radius": 20,
                    "bg_color": "#13263A",
                    "flex": "column",
                    "gap": 8,
                    "children": [
                        {"type": "label", "id": "media_title", "text": track.get("name", "Track"), "font_size": 20, "text_color": "#E8F3FF"},
                        {"type": "label", "id": "media_sub", "text": track.get("usage_type", "media"), "font_size": 12, "text_color": "#9AB8D5"},
                        {"type": "bar", "id": "media_progress", "w": 220, "h": 14, "min": 0, "max": 100, "value": int(cap.store.get("progress", 0))},
                        {"type": "container", "id": "media_ctrl_row", "flex": "row", "gap": 8, "children": [
                            {"type": "button", "id": "btn_prev", "text": "Prev", "w": 72, "h": 40, "on_click": "server://ui/click?action=prev"},
                            {"type": "button", "id": "btn_play", "text": "Play", "w": 72, "h": 40, "on_click": "server://ui/click?action=toggle"},
                            {"type": "button", "id": "btn_next", "text": "Next", "w": 72, "h": 40, "on_click": "server://ui/click?action=next"}
                        ]}
                    ]
                }
            ]
        }
    }


def on_event(ctx, event):
    action = (event or {}).get("action", "")
    assets = ctx.get("assets", [])
    idx = int(cap.store.get("track_index", 0))
    if action == "next":
        idx += 1
    elif action == "prev":
        idx -= 1
    elif action == "toggle":
        playing = bool(cap.store.get("playing", False))
        cap.store.set("playing", not playing)
        return {"action": "update", "page_id": "home", "payload": {"id": "media_sub", "text": "Playing" if not playing else "Paused"}}
    else:
        return {"action": "noop", "page_id": "home", "payload": {}}

    picked = pick_track(assets, idx)
    if not picked:
        return {"action": "noop", "page_id": "home", "payload": {}}
    track, idx = picked
    cap.store.set("track_index", idx)
    return {
        "action": "update",
        "page_id": "home",
        "payload": {
            "ops": [
                {"op": "set", "id": "media_title", "path": "text", "value": track.get("name", "Track")},
                {"op": "set", "id": "media_sub", "path": "text", "value": track.get("usage_type", "media")}
            ],
            "transaction": True
        }
    }
```

## 5.6 验收要点

1. 无资源时是否给出可读兜底。
2. 切曲时是否仅刷新标题/副标题/进度区。
3. `toggle` 是否仅更新状态文本与按钮文案。

## 6. 三类模板共通约束

1. `on_start` 必须可独立执行（不依赖历史事件）。
2. `on_event` 对未知输入必须 `noop`。
3. 所有交互控件使用稳定 `id` 和 `server://` action。
4. 尽量用 `ui/update`，减少整页重建。
5. 所有状态键集中在文档中声明，避免隐式 key。

## 7. 模板沉淀建议

每个通过验证的模板建议记录：

1. 场景标签。
2. 关键状态键。
3. 必要资源类型。
4. 支持事件动作。
5. 已知限制与可调参数。
