# SDUI 终端架构与开发指南

## 1. 文档定位
本文档面向终端侧开发者，重点说明当前 ESP32-S3 终端实现的真实架构、组件支持范围、协议处理细节、性能保护策略以及扩展方式。

如果你关心下面的问题，请先读这份文档：
- 终端收到 `ui/layout` / `ui/update` 后内部是怎么处理的。
- 组件渲染、事件上报、动画、滑页、overlay、音频、IMU、心跳之间是怎么协同的。
- 为什么某些更新在弱内存场景下会被延迟或丢弃。
- 想新增一个组件或扩展一个现有字段，应该改哪里。

配套文档：
- [BACKEND_DEV_GUIDE.md](d:/esp-idf/src/sdui/docs/BACKEND_DEV_GUIDE.md)：服务端接入主指南
- [PROTOCOL_CHEATSHEET.md](d:/esp-idf/src/sdui/docs/PROTOCOL_CHEATSHEET.md)：协议速查表
- [EXAMPLE_LIBRARY.md](d:/esp-idf/src/sdui/docs/EXAMPLE_LIBRARY.md)：组件与协议示例库
- [BACKEND_INTEGRATION_FLOW.md](d:/esp-idf/src/sdui/docs/BACKEND_INTEGRATION_FLOW.md)：后端标准接入流程
- [FIELD_REFERENCE.md](d:/esp-idf/src/sdui/docs/FIELD_REFERENCE.md)：统一字段字典

---

## 2. 架构总览
终端不是“单纯的 LVGL 页面”，而是一套端云协同渲染运行时，核心由以下几部分组成：
- `websocket_manager`：维护 WebSocket 通道
- `sdui_bus`：主题路由与上下行封装
- `sdui_parser`：把 JSON UI 描述转换为 LVGL 对象
- `sdui_widget_v2`：高级组件实现，如 dial / waveform / spectrum
- `audio_manager`：录音、播放、上行音频分片
- `imu_manager`：动作识别与上行
- `telemetry_manager`：设备状态心跳上报
- `main`：系统入口、主题订阅、特性开关、兜底页面

### 2.1 核心数据流

#### 下行
1. `websocket_manager` 收到 JSON 消息。
2. `sdui_bus_route_down()` 解析外层信封。
3. 根据 `topic` 分发到订阅者。
4. `ui/layout` 进入 `sdui_parser_render()`。
5. `ui/update` 进入 `sdui_parser_update()`。
6. `audio/play`、`cmd/control`、`ui/features` 等进入对应模块。

#### 上行
1. LVGL 交互回调、IMU 任务、音频任务、心跳任务产生事件。
2. `sdui_bus_publish_up()` 或 `sdui_bus_publish_up_try()` 统一封装。
3. bus 自动补充 `device_id`。
4. 通过 WebSocket 发回服务端。

#### 本地短路
部分动作不走服务端，直接通过 `sdui_bus_publish_local()` 在终端内部流转。
典型例子：
- `local://audio/cmd/record_start`
- `local://audio/cmd/record_stop`
- `ui/viewport_scroll`
- `ui/scene_scheduler`

---

## 3. 模块职责

### 3.1 `websocket_manager`
负责：
- 建连
- 收发 JSON 文本
- 提供阻塞和非阻塞发送接口
- 暴露连接状态给上层做降级判断

说明：
- 多个模块会在发送前检查 `websocket_is_connected()`。
- 高频或次要消息常使用 `*_try()` 方式，避免阻塞主流程。

### 3.2 `sdui_bus`
负责：
- 统一管理 topic 订阅
- 统一上下行信封
- `topic?query=...` 的拆分与 query 合并
- 本地 topic 分发
- cJSON 内存钩子初始化，优先使用 PSRAM

当前特性：
- 最多 15 个订阅者
- 上行时会自动带 `device_id`
- 支持 `server_query_merge` 开关

`server_query_merge` 的实现逻辑：
- 如果 topic 中带 query，且开关开启，会把 query 合并进 payload object
- 如果原始 payload 不是合法 JSON object，会尝试构造对象，必要时保留 `_raw`

### 3.3 `sdui_parser`
负责：
- 初始化 SDUI 根视图
- 完整布局重建
- 增量更新与事务更新
- 组件 id 索引维护
- 动画挂载
- viewport/overlay 调度
- 调试 overlay

### 3.4 `sdui_widget_v2`
负责高级组件：
- `dial`
- `waveform`
- `spectrum`

特点：
- 通过独立 registry 管理实例
- 提供 `create` 与 `update` 两套入口
- 在全局 pause 状态下可暂停部分动画/惯性效果

### 3.5 `audio_manager`
负责：
- 录音开始/停止
- 录音 PCM 缓冲与 Base64 编码
- 通过 `audio/record` 上行三阶段消息
- 订阅 `audio/play` 做播放
- 音量设置

### 3.6 `imu_manager`
负责：
- 采集 QMI8658
- 识别 `shake` / `wrist_raise` / `flip`
- 通过 `motion` 上行

### 3.7 `telemetry_manager`
负责：
- 采集设备 ID、RSSI、IP、温度、内存、碎片率、运行时长
- 定时上报 `telemetry/heartbeat`

---

## 4. 当前主题订阅清单
在 `main/main.c` 中，当前终端订阅了以下主题：

| 主题 | 方向 | 处理模块 | 说明 |
|---|---|---|---|
| `ui/layout` | 下行 | `sdui_parser` | 全量渲染 |
| `ui/update` | 下行 | `sdui_parser` | 增量更新 |
| `ui/features` | 下行 | `main` | 运行特性开关 |
| `cmd/control` | 下行 | `main` | 音量/亮度控制 |
| `audio/play` | 下行 | `audio_manager` | 播放服务端音频 |
| `audio/cmd/record_start` | 本地 | `audio_manager` | 开始录音 |
| `audio/cmd/record_stop` | 本地 | `audio_manager` | 停止录音 |
| `cmd/retry_network` | 本地 | `main` | 重试联网 |
| `cmd/reset_network` | 本地 | `main` | 清配网并重启 |

补充说明：
- `ui/page_changed`、`motion`、`audio/record`、`telemetry/heartbeat` 是上行主题，不需要订阅。
- `ui/viewport_scroll`、`ui/scene_scheduler` 是终端内部本地消息，用于联动调度。

---

## 5. 组件支持清单

### 5.1 基础组件
当前 `sdui_parser` 直接支持：
- `container`
- `label`
- `button`
- `image`
- `bar`
- `slider`
- `scene`
- `viewport`
- `overlay`
- `widget`

### 5.2 组件能力矩阵

| 类型 | 当前能力 | 关键字段 |
|---|---|---|
| `container` | Flex 布局、滚动容器 | `children` `flex` `justify` `align_items` `scrollable` |
| `label` | 文本、换行、滚动、跑马灯 | `text` `long_mode` |
| `button` | 点击/按下/释放事件 | `text` `on_click` `on_press` `on_release` |
| `image` | Base64 RGB565 图像 | `src` `img_w` `img_h` |
| `bar` | 进度显示 | `min` `max` `value` `bg_color` `indic_color` |
| `slider` | 数值滑块 | `min` `max` `value` `on_change` |
| `scene` | 全屏背景层 | 继承 `container` |
| `overlay` | 全屏浮层 | 继承 `container`，支持 `hidden` |
| `viewport` | 多页滑动容器 | `pages` `direction` `initial_page` |
| `widget:dial` | 仪表盘、惯性拖动 | `min` `max` `value` `ring_bg` `ring_fg` 等 |
| `widget:waveform` | 波形画布 | `canvas_w` `canvas_h` `values` `color` |
| `widget:spectrum` | 频谱画布 | `canvas_w` `canvas_h` `values` `color` |

### 5.3 通用样式字段
`apply_common_style()` 当前支持：
- 尺寸：`w`、`h`
- 对齐：`align`、`x`、`y`
- 背景：`bg_color`、`bg_opa`
- 内边距：`pad`
- 圆角：`radius`
- 裁角：`clip_corner`
- 行列间距：`gap`
- 边框：`border_w`、`border_color`
- 文本：`text_color`、`font_size`
- 阴影：`shadow_w`、`shadow_color`
- 整体透明度：`opa`
- 显隐：`hidden`

附加行为：
- 如果设置了 `align`，对象会被标记为 `IGNORE_LAYOUT`，从 flex 流中脱离。
- 默认会设置文字字距、行距、正常混合模式。

### 5.4 通用交互字段
`apply_interaction_props()` 当前支持：
- `pointer_events: "none"`

效果：
- 清除可点击标记
- 清除滚动标记

---

## 6. 动画支持
`anim` 当前支持以下类型：
- `blink`
- `breathe`
- `spin`
- `slide_in`
- `shake`
- `color_pulse`
- `marquee`

### 6.1 公共动画字段
常见字段：
- `type`
- `duration`
- `repeat`

### 6.2 特定动画字段
- `spin.direction`: `ccw` 可逆时针
- `slide_in.from`: `left | right | top | bottom`
- `shake.amplitude`: 抖动幅度
- `color_pulse.color_a` / `color_b`

限制与保护：
- `spin` 只允许用于 `image`
- 同时最多允许 2 个旋转动画，超出会降级跳过

---

## 7. `ui/layout` 处理细节

### 7.1 根视图行为
`sdui_parser_render()` 会执行：
1. 根视图 fade out
2. 清理旧子节点
3. 重置 viewport registry、deferred update 队列、widget registry
4. 解析新 JSON
5. 根视图 fade in

特点：
- `ui/layout` 是彻底重建，不保留旧对象实例。
- 只要布局结构变了，旧的 `id` 绑定关系也会被重建。

### 7.2 根对象输入形式
支持：
- `payload` 为数组：直接当作根 children 解析
- `payload` 为对象且带 `children`：先把对象样式应用到根，再解析 children
- `payload` 为单个对象：直接作为一个根节点解析

### 7.3 安全边距
- 默认安全边距为 `SDUI_SAFE_PADDING = 40`
- 可通过根级 `safe_pad` 覆盖
- 本地 loading / 网络错误兜底页会使用更小的 `LOCAL_UI_SAFE_PAD = 8`

---

## 8. `ui/update` 处理细节

### 8.1 直接 patch
`sdui_parser_update()` 支持通过 `id` 找对象并更新字段。

典型可更新字段：
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
- `anim`
- widget 特定字段

### 8.2 `ops[]`
实现位于：
- `apply_update_ops()`
- `build_update_from_op()`
- `structural_op_validate()`
- `structural_op_apply()`

支持：
- `set`
- `patch`
- `remove`
- `insert`
- `replace`

结构操作行为：
- `remove`：删除目标对象并清理整棵子树 id 注册
- `insert`：把 `node` 解析到 `parent_id` 下，可选 `index`
- `replace`：删除旧对象，再在原父对象下创建新对象，并尽量复用原 index

### 8.3 revision gate
实现位于 `update_revision_accept()`。

行为：
- 终端按 `page_id` 维护最近 revision
- 若新 revision 小于等于已记录 revision，则整包丢弃
- 最多维护 8 个 page 维度 revision 槽位

### 8.4 延迟更新与调度
为了避免滑页和高频刷新的卡顿，终端实现了 deferred update 机制。

核心思路：
- overlay 更新优先级最高，尽量直达
- viewport 正在滚动时，非关键更新可能被延迟
- 跨页对象、波形/频谱等高频对象可能被延迟合并
- 滚动结束或页 hydration 完成后会 flush 队列

相关状态：
- `viewport_scrolling`
- `overlay_visible_count`
- `widgets_paused`

---

## 9. `viewport` 实现细节
当前 `viewport` 基于 LVGL `tileview` 实现。

### 9.1 上限与约束
- 最多 8 页 `VIEWPORT_MAX_PAGES`
- 最多 4 个 viewport `MAX_VIEWPORTS`

### 9.2 懒构建策略
每页的 `children` 会先缓存为 JSON 字符串：
- 当前页优先构建
- 邻页预构建 1 页，减少首滑卡顿
- 切页时如果目标页未建，会先 hydrate

### 9.3 事件上报
`viewport_event_cb()` 会处理：
- `LV_EVENT_SCROLL_BEGIN`：本地发布 `ui/viewport_scroll {state:begin}`
- `LV_EVENT_SCROLL_END`：本地发布 `ui/viewport_scroll {state:end}`，并 flush deferred updates
- `LV_EVENT_VALUE_CHANGED`：上报 `ui/page_changed`

### 9.4 初始化就会上报当前页
viewport 创建完成后，当前实现会主动发送一次 `ui/page_changed`，不是只有滑动后才发。
服务端联调时要考虑这一点。

---

## 10. `overlay` 与调度器

### 10.1 overlay 行为
`overlay` 本质是全屏 `container`，创建时：
- 默认全屏
- 默认透明背景
- 脱离布局流
- `hidden=true` 时直接隐藏
- 可通过 `hidden` 更新触发 show/hide

### 10.2 调度器联动
`overlay_show()` / `overlay_hide()` 会更新调度器计数。
当任一 overlay 可见时：
- `overlay_visible_count` 增加
- `ui/scene_scheduler` 本地事件会刷新状态
- widget 可进入 pause 状态

`ui/scene_scheduler` 的本地 payload 结构：

```json
{
  "paused": true,
  "scrolling": false,
  "overlay_visible_count": 1
}
```

这个机制的用途是：
- overlay 打开时暂停非关键视觉效果
- 滚动时暂停重型 widget 效果
- 为交互关键区域让路

---

## 11. 交互事件与 Action URI

### 11.1 按钮事件
当前 `button` 支持：
- `on_click`
- `on_press`
- `on_release`

实际触发关系：
- `LV_EVENT_CLICKED` -> `on_click`
- `LV_EVENT_PRESSED` -> `on_press`
- `LV_EVENT_RELEASED` 或 `LV_EVENT_PRESS_LOST` -> `on_release`

### 11.2 URI 分发逻辑
`dispatch_action()` 处理：
- `local://topic?...`
- `server://topic?...`
- 无前缀时默认按上行 topic 处理

要点：
- 按钮类 `local://` 带 query 时，本地 payload 直接是 query 原串
- 按钮类 `server://` 的 payload 基础为 `{"id":"widget_id"}`
- 若 `server_query_merge=true` 且 URI 带 query，则 query 会并入 payload
- 非阻塞上行失败后，在 websocket 健康且内存允许时会重试一次可靠发送

### 11.3 slider 上报
`slider_changed_cb()` 当前在 `LV_EVENT_RELEASED` 触发，不是连续拖动上报。

payload：

```json
{
  "id": "slider_x",
  "value": 40
}
```

### 11.4 dial 上报
`dial` 支持两类回调：
- `on_change`：拖动过程中按 `throttle_ms` 节流上报
- `on_change_final`：释放后最终上报一次

适合：
- `on_change` 用于预览
- `on_change_final` 用于真正提交

---

## 12. 高级组件实现说明

### 12.1 `dial`
当前能力：
- 基于 arc 渲染
- 可拖动
- 可选惯性物理效果
- 可绑定一个 label id 自动同步值文本

重要字段：
- `min` / `max` / `value`
- `start_angle` / `sweep_angle`
- `arc_width`
- `ring_bg` / `ring_fg`
- `value_format`
- `throttle_ms`
- `inertia`
- `inertia_friction`
- `inertia_min_velocity`
- `inertia_interval_ms`
- `bind.text_id`

更新能力：
- `min`
- `max`
- `value`
- `ring_bg`
- `ring_fg`
- `arc_width`

### 12.2 `waveform`
实现方式：
- 在 canvas 上画折线
- `values` 最多取 64 个点
- 每个点按 0~100 映射高度

更新能力：
- `values`
- `color`

### 12.3 `spectrum`
实现方式：
- 在 canvas 上画柱条
- 数组长度决定柱数
- 每个值按 0~100 映射柱高

更新能力：
- `values`
- `color`

---

## 13. 心跳、音频、IMU 的实现事实

### 13.1 `telemetry/heartbeat`
默认每 30 秒一次，前提：
- WebSocket 已连接
- 不在音频上传中
- internal heap 与 DMA heap 高于阈值

当前字段见服务端文档，此处额外强调：
- `frag_internal_pct`、`frag_dma_pct`、`frag_psram_pct` 是终端现场排障的关键指标
- 如果这些指标持续恶化，应优先检查 layout 重建频率、大图、画布类组件和特效级别

### 13.2 `audio/record`
录音上传特点：
- start / stream / stop 三段式
- chunk 为 Base64 PCM
- 发送期间会检查 WebSocket 连接和内存阈值
- 上传过程中 telemetry 与 motion 会收敛或跳过，减少竞争

### 13.3 `motion`
当前上报类型：
- `shake`
- `wrist_raise`
- `flip`

这些事件已经是高层动作，不是原始三轴传感器数据。
如果后续服务端需要原始 IMU 数据，应新增独立主题，不建议复用 `motion`。

---

## 14. 运行特性开关
`ui/features` 当前会驱动以下终端状态：
- `debug_enabled`
- `smoke_enabled`
- `effects`
- `server_query_merge`

### 14.1 `debug`
开启后显示端侧调试层，内容包括：
- 当前 page id
- viewport id
- page index
- overlay 可见数
- 最近 hydration 耗时
- 更新计数等调试信息

### 14.2 `effects`
当前有三档：
- `lite`
- `balanced`
- `rich`

用途：
- 为高压场景提供降级开关
- 让服务端可根据心跳资源情况动态调整视觉负载

### 14.3 `smoke`
当前主要用于屏蔽 `server://ui/run_smoke` 类动作。
这是一个调试/测试安全阀，不是通用权限系统。

### 14.4 `server_query_merge`
会同时影响：
- `sdui_parser` 中按钮 Action URI 的处理
- `sdui_bus` 中统一上行封装时 query 合并逻辑

---

## 15. 内存与性能策略

### 15.1 内存分层
当前实现遵循以下原则：
- cJSON 尽量走 PSRAM
- 图像解码数据放 PSRAM
- waveform/spectrum canvas buffer 放 PSRAM
- 关键实时数据尽量留给 internal SRAM / DMA

### 15.2 主动止损策略
系统在多个入口都设置了低内存保护：
- `ui/update`：heap 太低会直接跳过
- `motion`：heap 太低或音频上传中会跳过
- `telemetry`：heap 太低会跳过
- 音频上传：heap 太低会延期或终止

### 15.3 为什么会“看起来丢包”
如果你看到：
- 某些 update 没生效
- motion 偶尔缺失
- telemetry 心跳间隔变大

优先怀疑：
- internal heap 不足
- DMA heap 不足
- viewport 滚动期间更新被 defer
- overlay 可见期间 widget 被 pause
- revision 被判旧包丢弃

### 15.4 高频更新建议
对于服务端：
- 文本和值分离更新
- 尽量只更新当前页对象
- 高频曲线只更新 `values`
- 加上 `revision`
- 避免在 `viewport` 滚动时推大量非关键补丁

---

## 16. 终端已知边界
当前实现存在以下边界，文档中需要明确：
- `MAX_ID_ENTRIES = 64`，复杂页面不要无限堆叠带 id 的对象
- `VIEWPORT_MAX_PAGES = 8`
- `MAX_VIEWPORTS = 4`
- `MAX_SPIN_ANIM = 2`
- `slider` 只在释放时上报，不是连续流式
- `image` 当前依赖 Base64 RGB565，不支持网络图片 URL
- 视觉 token 文件目前主要是服务端模板源，不是终端 parser 直接解析的 token 语法

---

## 17. 扩展一个新组件的步骤

### 17.1 在 parser 中接入新 `type`
在 `parse_node()` 的分发逻辑中增加：
- 新建对象
- 应用通用样式
- 应用交互属性
- 注册 id
- 解析 children 或特定字段

### 17.2 如果是复杂组件，优先放到 `sdui_widget_v2`
适合放到 `widget_v2` 的情况：
- 需要独立实例状态
- 需要专门 update 逻辑
- 需要定时器、画布、物理模拟

接入步骤：
1. 在 `sdui_widget_v2_create()` 增加 create 分支
2. 在 `sdui_widget_v2_update()` 增加 patch 分支
3. 需要时增加 delete 回调释放资源

### 17.3 让 `ui/update` 能更新它
如果新组件字段不属于通用字段，需要：
- 在 `sdui_widget_v2_update()` 或 parser update 分支中接住字段
- 如果希望 `ops[].path` 也能写，需要扩展 `update_path_to_key()`

---

## 18. 调试与排障建议

### 18.1 先看几类日志
优先检查：
- `ui/layout` 是否 parse 成功
- `ui/update` 是否被 revision gate 丢弃
- 是否出现 low heap skip
- viewport hydrate 耗时
- overlay 与 scrolling 状态

### 18.2 遇到页面不更新
按顺序排查：
1. `id` 是否存在且唯一
2. `path` 是否在支持列表内
3. `revision` 是否过期
4. 是否处于低内存跳过
5. 目标对象是否在 inactive viewport 中被 defer

### 18.3 遇到交互不上报
按顺序排查：
1. `on_click` / `on_press` / `on_release` URI 是否正确
2. `pointer_events` 是否被设置为 `none`
3. `server_query_merge` 是否导致你预期的 payload 结构变化
4. websocket 是否连接正常

### 18.4 遇到页面滑动卡顿
优先检查：
- 单页是否塞入过多大图和大 canvas
- 是否在滑动过程中下发太多 update
- 是否未利用 viewport 的懒构建与邻页预建机制
- 是否可以下调 `effects=lite`

---

## 19. 推荐维护方式
为了避免文档和实现再次脱节，建议以后按下面规则维护：
- 新增 topic：同时更新“主题订阅清单”和“上下行协议说明”
- 新增组件或字段：同时更新“组件支持清单”和“组件能力矩阵”
- 修改 `ui/update` 兼容路径：同时更新 `path` 映射表
- 修改性能阈值或策略：同时更新“内存与性能策略”
- 新增调试开关：同时更新“运行特性开关”

当前这份文档已经尽量按代码实现对齐，后续如果 parser、widget、bus 的行为变化，优先同步本文件，而不是只让示例脚本自行分叉。
