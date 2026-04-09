# SDUI 字段字典

## 1. 文档用途
这份文档专门用于统一查字段。

建议把它当作：
- 组件字段参考手册
- `ui/update.path` 的对照表
- 后续新增字段时的统一登记处

相关文档：
- [BACKEND_DEV_GUIDE.md](d:/esp-idf/src/sdui/docs/BACKEND_DEV_GUIDE.md)
- [TERMINAL_GUIDE.md](d:/esp-idf/src/sdui/docs/TERMINAL_GUIDE.md)
- [EXAMPLE_LIBRARY.md](d:/esp-idf/src/sdui/docs/EXAMPLE_LIBRARY.md)

---

## 2. 根布局字段

| 字段 | 类型 | 适用对象 | 说明 |
|---|---|---|---|
| `children` | array | 根 / container / scene / overlay / page | 子节点数组 |
| `safe_pad` | number | 根 | 根视图安全边距 |

---

## 3. 通用字段

| 字段 | 类型 | 适用对象 | 说明 |
|---|---|---|---|
| `type` | string | 所有组件 | 组件类型 |
| `id` | string | 所有组件 | 全局唯一标识，增量更新依赖它 |
| `hidden` | bool | 多数组件 | 初始隐藏或 update 隐藏 |
| `z` | number | 子节点 | 用于子节点排序，越小越靠前 |
| `pointer_events` | string | 多数组件 | 当前仅支持 `none` |

---

## 4. 布局字段

| 字段 | 类型 | 适用对象 | 说明 |
|---|---|---|---|
| `w` | number/string | 多数组件 | 宽度，支持数值、`full`、`content`、百分比 |
| `h` | number/string | 多数组件 | 高度 |
| `align` | string | 多数组件 | 对齐方式 |
| `x` | number | 多数组件 | 基于 `align` 的 X 偏移 |
| `y` | number | 多数组件 | 基于 `align` 的 Y 偏移 |
| `pad` | number | 多数组件 | 内边距 |
| `gap` | number | container 类 | 行列间距 |
| `flex` | string | container / 根 | `row` `column` `row_wrap` `column_wrap` |
| `justify` | string | container / 根 | 主轴对齐 |
| `align_items` | string | container / 根 | 交叉轴对齐 |
| `scrollable` | bool | `container` | 是否启用滚动 |

### 4.1 `align` 可选值
- `center`
- `top_mid`
- `top_left`
- `top_right`
- `bottom_mid`
- `bottom_left`
- `bottom_right`
- `left_mid`
- `right_mid`

### 4.2 `flex` 可选值
- `row`
- `column`
- `row_wrap`
- `column_wrap`

### 4.3 `justify` / `align_items` 可选值
- `start`
- `end`
- `center`
- `space_evenly`
- `space_around`
- `space_between`

---

## 5. 样式字段

| 字段 | 类型 | 适用对象 | 说明 |
|---|---|---|---|
| `bg_color` | string | 多数组件 | 背景色，形如 `#112233` |
| `bg_opa` | number | 多数组件 | 背景透明度 0~255 |
| `text_color` | string | 文本类 / 按钮文本 | 文字颜色 |
| `font_size` | number | 文本类 | 字号 |
| `radius` | number | 多数组件 | 圆角 |
| `border_w` | number | 多数组件 | 边框宽度 |
| `border_color` | string | 多数组件 | 边框颜色 |
| `shadow_w` | number | 多数组件 | 阴影宽度 |
| `shadow_color` | string | 多数组件 | 阴影颜色 |
| `opa` | number | 多数组件 | 整体透明度 0~255 |
| `clip_corner` | bool | 多数组件 | 是否裁角 |

---

## 6. 动画字段

### 6.1 通用动画结构

```json
{
  "anim": {
    "type": "blink",
    "duration": 800,
    "repeat": 0
  }
}
```

### 6.2 公共动画字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `anim.type` | string | 动画类型 |
| `anim.duration` | number | 时长，毫秒 |
| `anim.repeat` | number | 重复次数，`0` 在当前实现里常用于无限循环 |

### 6.3 动画类型与专属字段

| 类型 | 专属字段 | 说明 |
|---|---|---|
| `blink` | 无 | 闪烁 |
| `breathe` | 无 | 呼吸 |
| `spin` | `direction` | 图片旋转，`direction=ccw` 逆时针 |
| `slide_in` | `from` | `left/right/top/bottom` |
| `shake` | `amplitude` | 水平抖动幅度 |
| `color_pulse` | `color_a` `color_b` | 背景颜色脉冲 |
| `marquee` | 无 | 文本滚动 |

---

## 7. 基础组件字段

### 7.1 `container`

| 字段 | 类型 | 说明 |
|---|---|---|
| `children` | array | 子节点数组 |
| `flex` | string | Flex 布局方向 |
| `justify` | string | 主轴对齐 |
| `align_items` | string | 交叉轴对齐 |
| `scrollable` | bool | 是否可滚动 |

### 7.2 `label`

| 字段 | 类型 | 说明 |
|---|---|---|
| `text` | string | 文本内容 |
| `long_mode` | string | `wrap` `scroll` `dot` `marquee` |

### 7.3 `button`

| 字段 | 类型 | 说明 |
|---|---|---|
| `text` | string | 按钮文字 |
| `on_click` | string | 点击时触发 |
| `on_press` | string | 按下时触发 |
| `on_release` | string | 抬起或 press lost 时触发 |

### 7.4 `image`

| 字段 | 类型 | 说明 |
|---|---|---|
| `src` | string | Base64 RGB565 图片数据 |
| `img_w` | number | 图片宽度 |
| `img_h` | number | 图片高度 |

### 7.5 `bar`

| 字段 | 类型 | 说明 |
|---|---|---|
| `min` | number | 最小值 |
| `max` | number | 最大值 |
| `value` | number | 当前值 |
| `indic_color` | string | 指示条颜色 |

### 7.6 `slider`

| 字段 | 类型 | 说明 |
|---|---|---|
| `min` | number | 最小值 |
| `max` | number | 最大值 |
| `value` | number | 当前值 |
| `on_change` | string | 当前实现为释放时上报 |

---

## 8. 页面层字段

### 8.1 `scene`
本质上继承 `container`，通常只额外强调：
- 全屏使用
- 背景层用途

### 8.2 `overlay`
本质上继承 `container`，常用字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `hidden` | bool | 初始是否隐藏 |
| `children` | array | overlay 内子节点 |

### 8.3 `viewport`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | viewport 标识 |
| `direction` | string | `horizontal` 或 `vertical` |
| `initial_page` | number | 初始页索引 |
| `pages` | array | 页面数组 |

### 8.4 `viewport.pages[]`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 页 id，会在 `ui/page_changed` 中上报 |
| `children` | array | 页面子节点 |
| 通用布局/样式字段 | mixed | 页本身也可应用样式 |

---

## 9. `widget` 字段

### 9.1 通用 `widget`

| 字段 | 类型 | 说明 |
|---|---|---|
| `widget_type` | string | `dial` `dial_v2` `waveform` `waveform_v2` `spectrum` `spectrum_v2` |

### 9.2 `widget_type: dial`

| 字段 | 类型 | 说明 |
|---|---|---|
| `min` | number | 最小值 |
| `max` | number | 最大值 |
| `value` | number | 当前值 |
| `start_angle` | number | 起始角度 |
| `sweep_angle` | number | 扫过角度 |
| `arc_width` | number | 圆环宽度 |
| `ring_bg` | string | 背景环颜色 |
| `ring_fg` | string | 前景环颜色 |
| `value_format` | string | 值显示格式，例如 `%ld°C` |
| `throttle_ms` | number | `on_change` 节流周期 |
| `inertia` | bool | 是否启用惯性 |
| `inertia_friction` | number | 惯性摩擦系数 |
| `inertia_min_velocity` | number | 最小惯性速度 |
| `inertia_interval_ms` | number | 惯性定时器周期 |
| `on_change` | string | 拖动过程上报 |
| `on_change_final` | string | 拖动结束上报 |
| `bind.text_id` | string | 绑定外部 label id |

### 9.3 `widget_type: waveform`

| 字段 | 类型 | 说明 |
|---|---|---|
| `canvas_w` | number | 画布宽度 |
| `canvas_h` | number | 画布高度 |
| `values` | array | 波形值数组，建议 0~100 |
| `color` | string | 波形颜色 |

### 9.4 `widget_type: spectrum`

| 字段 | 类型 | 说明 |
|---|---|---|
| `canvas_w` | number | 画布宽度 |
| `canvas_h` | number | 画布高度 |
| `values` | array | 频谱值数组，建议 0~100 |
| `color` | string | 柱条颜色 |

---

## 10. Action URI 字段

### 10.1 URI 前缀

| 前缀 | 说明 |
|---|---|
| `local://` | 本地总线处理，不经过服务端 |
| `server://` | 上行到服务端 |

### 10.2 常见字段来源

| 字段 | 来源 | 说明 |
|---|---|---|
| `id` | 组件本身 | 按钮、slider、dial 上报时常见 |
| query 参数 | URI query | 在 `server_query_merge=true` 时可并入 payload |
| `value` | slider / dial | 数值型组件上报时常见 |

---

## 11. `ui/update` 字段与 `path` 对照

### 11.1 直接 patch 字段

| 字段 | 说明 |
|---|---|
| `id` | 目标组件 id |
| `text` | 文本 |
| `hidden` | 显隐 |
| `bg_color` | 背景色 |
| `bg_opa` | 背景透明度 |
| `text_color` | 文本色 |
| `border_color` | 边框色 |
| `border_w` | 边框宽度 |
| `radius` | 圆角 |
| `clip_corner` | 裁角 |
| `pad` | 内边距 |
| `w` | 宽度 |
| `h` | 高度 |
| `x` | X 偏移 |
| `y` | Y 偏移 |
| `align` | 对齐 |
| `value` | 数值 |
| `indic_color` | bar 指示色 |
| `opa` | 整体透明度 |
| `anim` | 动画对象 |

### 11.2 `ops[].path` 对照表

| path | 等价写法 | 说明 |
|---|---|---|
| `text` | `props.text` | 文本 |
| `hidden` | `style.hidden` | 显隐 |
| `bg_color` | `style.bg_color` | 背景色 |
| `bg_opa` | `style.bg_opa` | 背景透明度 |
| `text_color` | `style.text_color` | 文本色 |
| `border_color` | `style.border_color` | 边框色 |
| `border_w` | `style.border_w` | 边框宽度 |
| `radius` | `style.radius` | 圆角 |
| `clip_corner` | `style.clip_corner` | 裁角 |
| `pad` | `layout.pad` | 内边距 |
| `w` | `layout.w` | 宽度 |
| `h` | `layout.h` | 高度 |
| `x` | `layout.x` | X 偏移 |
| `y` | `layout.y` | Y 偏移 |
| `align` | `layout.align` | 对齐 |
| `value` | `props.value` | 数值 |
| `indic_color` | `style.indic_color` | bar 指示色 |
| `opa` | `style.opa` | 整体透明度 |

### 11.3 `ops[]` 结构字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `op` | string | `set` `patch` `remove` `insert` `replace` |
| `id` | string | 目标对象 id |
| `path` | string | 更新路径 |
| `value` | mixed | 更新值 |
| `parent_id` | string | `insert` 时父容器 id |
| `index` | number | `insert` 时插入索引 |
| `node` | object | `insert` / `replace` 时的新节点 |

---

## 12. `ui/features` 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `debug` | bool | 调试 overlay 开关 |
| `smoke` | bool | smoke 测试动作开关 |
| `effects` | string | `lite` `balanced` `rich` |
| `server_query_merge` | bool | 是否合并 URI query |
| `request_state` | bool | 请求终端回发 `ui/features_state` |

---

## 13. `cmd/control` 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `cmd_id` | string | 命令唯一 id，用于 ACK 关联 |
| `action` | string | 当前支持 `brightness` `volume` |
| `value` | number | 0~100 |

---

## 13.1 `cmd/control_ack` 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `cmd_id` | string | 对应下发命令 id |
| `action` | string | 执行动作 |
| `status` | string | `ACKED` `REJECTED` `ERROR` |
| `reason` | string | 原因描述，空字符串表示无错误 |
| `requested_value` | number | 下发值 |
| `applied_value` | number | 实际执行值（可选） |
| `ts` | number | 终端毫秒时间戳 |

---

## 14. 上行 payload 字段摘要

### `ui/click`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 触发组件 id |
| query 合并字段 | string | 由 URI query 决定 |

### `ui/page_changed`

| 字段 | 类型 | 说明 |
|---|---|---|
| `viewport` | string | viewport id |
| `page` | string | page id |
| `index` | number | 页索引 |

### `audio/record`

| 字段 | 类型 | 说明 |
|---|---|---|
| `state` | string | `start` `stream` `stop` |
| `context` | string | 仅 `start` 常见 |
| `data` | string | 仅 `stream` 常见，Base64 PCM |

### `motion`

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | string | `shake` `wrist_raise` `flip` |
| `magnitude` | number | shake 时常见 |
| `tilt` | number | wrist_raise / flip 常见 |
| `delta` | number | wrist_raise 常见 |
| `from` | string | flip 常见 |
| `to` | string | flip 常见 |

### `telemetry/heartbeat`

| 字段 | 类型 | 说明 |
|---|---|---|
| `device_id` | string | 设备 id |
| `wifi_rssi` | number | Wi-Fi 信号 |
| `ip` | string | 设备 IP |
| `temperature` | number | 芯片温度 |
| `free_heap_internal` | number | internal heap 剩余 |
| `largest_heap_internal` | number | internal 最大连续块 |
| `free_heap_dma` | number | DMA heap 剩余 |
| `largest_heap_dma` | number | DMA 最大连续块 |
| `free_heap_psram` | number | PSRAM 剩余 |
| `largest_heap_psram` | number | PSRAM 最大连续块 |
| `free_heap_total` | number | 总 heap |
| `frag_internal_pct` | number | internal 碎片率 |
| `frag_dma_pct` | number | DMA 碎片率 |
| `frag_psram_pct` | number | PSRAM 碎片率 |
| `uptime_s` | number | 运行时长秒数 |

这份字段字典建议作为后续所有新增字段的统一登记位置。只要 parser、widget、bus 里新增字段，就应该第一时间补到这里。
