# SDUI 后端接入流程

## 1. 文档用途
这份文档把“设备连上之后，服务端应该怎么一步步接管终端”串成标准流程，适合：
- 新同学快速上手
- 服务端网关实现接入流程
- 联调时核对事件时序

相关文档：
- [BACKEND_DEV_GUIDE.md](d:/esp-idf/src/sdui/docs/BACKEND_DEV_GUIDE.md)
- [PROTOCOL_CHEATSHEET.md](d:/esp-idf/src/sdui/docs/PROTOCOL_CHEATSHEET.md)
- [EXAMPLE_LIBRARY.md](d:/esp-idf/src/sdui/docs/EXAMPLE_LIBRARY.md)
- [FIELD_REFERENCE.md](d:/esp-idf/src/sdui/docs/FIELD_REFERENCE.md)

---

## 2. 标准接入闭环
一个完整的后端接入通常包含 6 个阶段：
1. 设备建立 WebSocket 连接
2. 服务端识别 `device_id` 并加载设备会话状态
3. 服务端发送首屏 `ui/layout`
4. 终端上报用户交互、页面切换、录音、动作、心跳
5. 服务端根据业务状态发送 `ui/update` 或新的 `ui/layout`
6. 服务端根据终端资源情况动态做降级或控制

---

## 3. 连接建立

### 3.1 连接目标
终端和服务端通过 WebSocket 交换 JSON 信令。

服务端需要做的事：
- 接受设备连接
- 按连接维度维护 session
- 将连接与 `device_id` 绑定
- 允许服务端主动向该连接推送 `ui/layout` / `ui/update`

### 3.2 `device_id` 的使用方式
终端上行时会自动带 `device_id`。
建议服务端做两层索引：
- `device_id -> 当前 websocket 连接`
- `device_id -> 当前应用状态`

推荐状态结构：

```json
{
  "device_id": "A1B2C3D4E5F6",
  "current_page": "home",
  "page_revision": {
    "home": 12,
    "settings": 3
  },
  "features": {
    "effects": "balanced",
    "debug": false
  }
}
```

---

## 4. 首屏渲染流程

### 4.1 推荐时序
1. 连接建立
2. 服务端初始化设备状态
3. 生成首屏布局
4. 发送 `ui/layout`
5. 等待 `ui/page_changed` 或用户交互

### 4.2 最小首屏示例

```json
{
  "topic": "ui/layout",
  "device_id": "A1B2C3D4E5F6",
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
            "text": "欢迎使用 SDUI"
          },
          {
            "type": "button",
            "id": "btn_start",
            "text": "开始",
            "on_click": "server://ui/click?action=start"
          }
        ]
      }
    ]
  }
}
```

### 4.3 服务端建议
- 首屏只放最必要信息，不要一上来就塞复杂大图和高频组件
- 如果存在 `viewport`，要准备接收初始化时自动上报的 `ui/page_changed`
- 首屏发出后，不必立即补很多 `ui/update`，先等终端稳定进入交互状态

---

## 5. 用户交互流程

### 5.1 按钮点击
典型链路：
1. 用户点击按钮
2. 终端解析 `on_click` 的 `server://...`
3. 终端上报 `ui/click`
4. 服务端更新业务状态
5. 服务端发送 `ui/update`

配置示例：

```json
{
  "type": "button",
  "id": "btn_confirm",
  "text": "确认",
  "on_click": "server://ui/click?action=confirm&scene=order"
}
```

上行示例：

```json
{
  "topic": "ui/click",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "id": "btn_confirm",
    "action": "confirm",
    "scene": "order"
  }
}
```

返回示例：

```json
{
  "topic": "ui/update",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "page_id": "order",
    "revision": 5,
    "transaction": true,
    "ops": [
      {"op": "set", "id": "status_label", "path": "text", "value": "已提交"},
      {"op": "set", "id": "submit_overlay", "path": "hidden", "value": true}
    ]
  }
}
```

### 5.2 slider / dial 数值提交
推荐分两类处理：
- 预览更新：走 `on_change`
- 最终提交：走 `on_change_final`

服务端建议：
- `slider` 常用于设置项，收到就直接存档即可
- `dial` 如果拖动频繁，预览回调只做轻量处理，最终回调再落正式状态

---

## 6. 页面切换流程

### 6.1 viewport 切页
典型链路：
1. 服务端下发包含 `viewport` 的首屏布局
2. 终端初始化完成后上报一次当前页 `ui/page_changed`
3. 用户滑动切页后再次上报 `ui/page_changed`
4. 服务端根据当前页决定是否推该页数据

上行示例：

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

### 6.2 服务端推荐策略
- 仅在当前页相关对象上发高频更新
- 当前页切换后，再推该页专属数据
- 不要对非当前页做密集 update

---

## 7. 录音流程

### 7.1 本地触发方案
推荐按钮配置：

```json
{
  "type": "button",
  "id": "btn_record",
  "text": "按住说话",
  "on_press": "local://audio/cmd/record_start?context=voice_chat",
  "on_release": "local://audio/cmd/record_stop"
}
```

### 7.2 时序
1. 用户按下按钮
2. 终端本地开始录音
3. 终端上报 `audio/record start`
4. 终端持续上报 `audio/record stream`
5. 用户松手
6. 终端本地停止录音
7. 终端上报 `audio/record stop`
8. 服务端聚合音频并调用 STT
9. 服务端将结果通过 `ui/update` 更新到页面

### 7.3 服务端建议
- 按 `device_id + context` 聚合当前录音会话
- `stream` 阶段只缓存，不要立即反复刷新 UI
- STT 结果返回后，再统一更新文本和 overlay

---

## 8. 动作识别流程

### 8.1 `motion` 事件使用建议
当前终端会上报：
- `shake`
- `wrist_raise`
- `flip`

推荐用法：
- `shake`：切换卡片、刷新数据、触发轻交互
- `wrist_raise`：亮屏后显示当前卡片状态
- `flip`：正反面切页或模式切换

### 8.2 服务端处理原则
- `motion` 是事件，不是状态
- 同一事件建议做时间窗口防抖
- 若动作用于导航，最好仍带一层服务端状态校验，避免误触多跳

---

## 9. 心跳与降级流程

### 9.1 心跳作用
`telemetry/heartbeat` 适合做三类事：
- 在线状态监控
- 资源健康评估
- 动态降级开关判断

### 9.2 推荐降级策略
如果心跳出现以下趋势：
- `free_heap_internal` 持续偏低
- `free_heap_dma` 持续偏低
- `frag_internal_pct` / `frag_dma_pct` 持续升高

建议服务端做：
1. 发送 `ui/features`，把 `effects` 切到 `lite`
2. 减少 `ui/layout`，改用 `ui/update`
3. 减少波形/频谱更新频率
4. 避免下发大图或结构性 replace

降级示例：

```json
{
  "topic": "ui/features",
  "device_id": "A1B2C3D4E5F6",
  "payload": {
    "effects": "lite",
    "request_state": true
  }
}
```

---

## 10. 推荐的服务端发送策略

### 10.1 什么时候发 `ui/layout`
适合：
- 首屏
- 页面切换
- 大结构变化
- 需要彻底重建组件树

### 10.2 什么时候发 `ui/update`
适合：
- 文本变化
- 颜色变化
- 显隐变化
- `bar/slider/dial` 数值变化
- waveform / spectrum 数据变化
- overlay 开关

### 10.3 什么时候必须带 `revision`
建议以下情况都带：
- 高频更新
- 同一页面可能并发产生多个更新
- 服务端处理链路存在异步任务和队列

---

## 11. 标准接入伪代码

```python
async def on_device_connected(device_id, ws):
    state = load_or_create_state(device_id)
    layout = build_home_layout(state)
    await ws.send_json({
        "topic": "ui/layout",
        "device_id": device_id,
        "payload": layout,
    })

async def on_message(device_id, msg, ws):
    topic = msg["topic"]
    payload = msg.get("payload", {})

    if topic == "ui/click":
        await handle_click(device_id, payload, ws)
    elif topic == "ui/page_changed":
        await handle_page_changed(device_id, payload, ws)
    elif topic == "audio/record":
        await handle_audio_record(device_id, payload, ws)
    elif topic == "motion":
        await handle_motion(device_id, payload, ws)
    elif topic == "telemetry/heartbeat":
        await handle_telemetry(device_id, payload, ws)

async def handle_click(device_id, payload, ws):
    action = payload.get("action")
    if action == "start":
        rev = next_revision(device_id, "home")
        await ws.send_json({
            "topic": "ui/update",
            "device_id": device_id,
            "payload": {
                "page_id": "home",
                "revision": rev,
                "transaction": True,
                "ops": [
                    {"op": "set", "id": "status_label", "path": "text", "value": "处理中..."},
                    {"op": "set", "id": "loading_overlay", "path": "hidden", "value": False}
                ]
            }
        })
```

---

## 12. 联调检查清单
1. 设备连接后是否能收到首屏 `ui/layout`
2. 首屏是否出现预期 `id` 的组件
3. `button` 点击后是否能收到 `ui/click`
4. `viewport` 初始化和切页后是否能收到 `ui/page_changed`
5. 录音时是否出现 `start -> stream -> stop`
6. 心跳是否按预期周期上报
7. 高频更新时 `revision` 是否能正确丢弃旧包
8. 资源吃紧时服务端是否能切 `effects=lite`

这份流程文档的目标不是替代主指南，而是把“接入动作顺序”和“联调时序”单独讲清楚，方便团队按步骤推进。
