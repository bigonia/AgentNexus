# Section 渲染 Schema

本文档定义 SDUI 终端唯一的 UI 渲染路径 —— Section 系统。涵盖 12 种 Section 类型的完整字段 schema、4 种布局模式、Scene/Patch 消息结构，以及**每种 Section 在不同屏幕上的渲染行为差异**。

协议基础、能力上报、命令控制等内容请参阅 [`PROTOCOL_AND_COMMANDS.md`](PROTOCOL_AND_COMMANDS.md)。

---

## 1. 架构概述

### 1.1 Section 是唯一 UI 路径

终端不暴露 LVGL 级别的渲染接口。服务器通过两种 UI3 Binary 消息控制界面：

| 消息 | msg_type | 用途 | 触发场景 |
|------|----------|------|---------|
| `SECTION_SCENE` | 15 | 下发完整场景（全量渲染） | 首屏、页面切换 |
| `SECTION_PATCH` | 16 | 增量补丁（局部更新） | 数据刷新、动态追加/删除 |

两种消息的 payload 均为 **JSON 文本**，包裹在 UI3 Binary 帧 TLV 中。

### 1.2 12 种 Section 类型

| 类型 | 用途 | 产生事件 | 交互 |
|------|------|---------|------|
| `hero_section` | 大标题卡片（KPI 展示） | — | 无 |
| `metric_section` | 指标网格 | — | 无 |
| `chart_section` | 折线图/趋势图 | — | 无 |
| `timer_section` | 计时器/倒计时 | — | 无 |
| `image_section` | 图标/图片展示 | — | 无 |
| `action_section` | 按钮组 | `action.click` | 点击 |
| `progress_section` | 进度条 | — | 无 |
| `text_section` | 文本块 | — | 无 |
| `list_section` | 列表 | `list.select` | 点击选择 |
| `toggle_section` | 开关选项 | `toggle.change` | 切换 |
| `overlay_section` | 弹窗/确认框 | `overlay.confirm` | 确认关闭 |
| `nav_section` | 导航标签页 | — | 切换 tab（本地） |

### 1.3 渲染模式判定

终端根据屏幕物理尺寸自主决定 `size_class`，服务器读取上报值即可：

| size_class | 屏幕宽度条件 | 对应板卡 | 渲染模式 |
|------------|------------|----------|---------|
| `"large"` | ≥ 160px | AMOLED_175 (466×466) | **rich**（富渲染） |
| `"small"` | < 160px | LCD_085 (128×128) | **compact**（精简渲染） |

> **关键规则**: 即使是 `large` 屏，当某个 Section 的 `inner_w < 160px`（如分栏布局）或页面高度不足时，个别类型会**自动降级**为 compact 渲染。见 §2.3。

---

## 2. 屏幕适配模型

### 2.1 两板概览

| 属性 | AMOLED_175 | LCD_085 |
|------|-----------|---------|
| **分辨率** | 466 × 466 px | 128 × 128 px |
| **形状** | 圆形 (`round`) | 方形 (`rect`) |
| **输入** | 触摸屏 (`touch`) | 3 按键 (`keys`) |
| **size_class** | `large` | `small` |
| **渲染模式** | rich（部分场景可降级） | compact（始终） |
| **可用 section 数** | 全 12 种 | 全 12 种 |

### 2.2 large 模式（AMOLED_175 默认行为）

- `inner_w ≥ 160px`，所有 Section 以 rich 模式渲染
- 显示字段完整：标题、副标题、图标、进度条等全部渲染
- 资源上限宽松：更多 chart 点、更多 list 项、更多 toggle
- 交互方式：触屏直接操作

**large 模式下的资源上限**:

| 资源 | 上限 |
|------|------|
| 单页 section 数 | ≤ 12 |
| metric 指标数 | ≤ 4 |
| action 按钮数 | ≤ 4，横排布局 |
| chart 数据点 | ≤ 32 |
| list 列表项 | ≤ 20 |
| toggle 开关数 | ≤ 8 |
| nav tab 数 | ≤ 6 |
| text body 长度 | ≤ 200 字符，支持换行 |
| 字体范围 | montserrat 14–24 |

### 2.3 small 模式（LCD_085 默认行为）

- `inner_w < 160px`，所有 Section 以 compact 模式渲染
- **不渲染的字段**：`subtitle`、`icon_src`/`icon_symbol`、`progress`（进度条）在多数类型中被忽略
- 所有尺寸缩小、间距压缩
- 交互方式：按键焦点导航

**small 模式下的资源上限**:

| 资源 | 上限 |
|------|------|
| 单页 section 数 | ≤ 12 |
| metric 指标数 | ≤ 4（cell 过窄时自动变 1 列） |
| action 按钮数 | ≤ 4，竖排布局 |
| chart 数据点 | ≤ 8（下采样） |
| list 列表项 | ≤ 6 |
| toggle 开关数 | ≤ 4 |
| nav tab 数 | ≤ 4 |
| text body 长度 | ≤ 40 字符，单行截断（DOT 模式） |
| 字体范围 | montserrat 14–20 |

### 2.4 高度不足时的降级优先级

`large` 屏且页面内容超出可用高度时，终端按以下顺序将 Section 从 rich 逐一降为 compact，直到内容适配：

```
text → overlay → image → timer → hero → chart → progress → action → metric → list → toggle → nav
```

（左侧最先降级，`nav` 最后降级，即最优先保持 rich）

### 2.5 input_mode 对交互的影响

| 交互行为 | touch（AMOLED_175） | keys（LCD_085） |
|---------|-------------------|----------------|
| action 按钮 | 触屏点击 | 按键焦点 + 确认 |
| list 选择 | 触屏点击 | 按键上下 + 确认 |
| toggle 切换 | 触屏点击 | 按键焦点 + 确认 |
| nav tab 切换 | 触屏点击 | 按键左右 |
| horizontal_pages 翻页 | 触屏滑动 | 按键翻页 |

**平台适配建议**: `keys` 模式下避免下发密集的交互组件（如含 4 个 toggle 的 toggle_section），优先使用 nav_section + 简单 action_section 组合。

---

## 3. Scene JSON 结构（SECTION_SCENE）

### 3.1 完整格式

```json
{
  "page_id": "home_page",
  "layout": "vertical_scroll",
  "auto_scroll_ms": 0,
  "sections": [
    {
      "section_id": "sec_hero",
      "type": "hero_section",
      "data": { "value": "86%", "label": "完成率", "subtitle": "今日目标", "tone": "success" }
    },
    {
      "section_id": "sec_chart",
      "type": "chart_section",
      "data": { "title": "趋势", "points": [30, 55, 42, 68, 75, 60] }
    }
  ]
}
```

> 兼容 `"page": { ... }` 包装格式，终端自动解包。

### 3.2 顶层字段

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `page_id` | string | 否 | 页面标识（诊断用途） |
| `layout` | string | **是** | 布局模式：`vertical_scroll` / `horizontal_pages` / `fixed_single` / `overlay` |
| `auto_scroll_ms` | int | 否 | 自动翻页间隔（ms），范围 [800, 60000]，0 = 禁用 |
| `sections[]` | array | **是** | Section 列表，最多 12 个 |

### 3.3 Section 通用字段

每个 `sections[]` 元素必须包含以下字段：

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `section_id` | string | **是** | Section 唯一标识，Patch 时用于定位 |
| `type` | string | **是** | 12 种 section 类型之一，见 §5 |
| `data` | object | **是** | 类型特定的数据，各类型 schema 见 §5 |

---

## 4. 布局模式

| layout | 说明 | 适用场景 | 限制 |
|--------|------|---------|------|
| `vertical_scroll` | 垂直滚动列表 | 信息流、仪表盘（最通用） | 所有 section 排在一个可滚动容器中 |
| `horizontal_pages` | 水平翻页（带圆点指示器） | 引导页、分步表单 | 最多 12 页 |
| `fixed_single` | 固定单页（无滚动） | 简单卡片、确认页 | 超出屏幕的内容被裁剪 |
| `overlay` | 浮层弹窗 | overlay_section 父容器 | overlay 渲染到全屏层 |

---

## 5. Section 类型定义

以下定义每种 Section 的字段 schema，并给出 **AMOLED_175（large/rich）和 LCD_085（small/compact）两种屏幕上的完整渲染行为对比**。

> **约定**: 
> - ✅ = 渲染该字段
> - ❌ = 忽略该字段（字段可下发但不显示）
> - 字体格式: `字体名 字号`
> - 尺寸单位均为 px

---

### 5.1 `hero_section` — 大标题卡片

展示一个核心数值/标题，配合标签、副标题、图标和进度条。适合 KPI 仪表盘的首屏。

**字段 schema**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `value` | string | **是** | 主数值/标题，如 `"86%"`、`"在线"` |
| `label` | string | 否 | 标签文案 |
| `subtitle` | string | 否 | 副标题 |
| `icon_src` | string | 否 | 图标键名，见 [§9 内置素材库](#9-内置素材库icon_src--icon_symbol) |
| `icon_symbol` | string | 否 | 图标符号（`icon_src` 的回退） |
| `tone` | string | 否 | 色系：`"primary"`(默认) / `"success"` / `"warning"` / `"danger"` / `"secondary"` |
| `progress` | int | 否 | 0–100，底部进度条 |

**per-board 渲染行为**:

| 渲染项 | AMOLED_175（466×466, large, rich） | LCD_085（128×128, small, compact） |
|--------|-------------------------------------|-------------------------------------|
| `value` | ✅ montserrat **24** | ✅ montserrat **14**（w<120）/ **20**（其他） |
| `label` | ✅ montserrat **18** | ✅ montserrat **14** |
| `subtitle` | ✅ montserrat 14 | ❌ **忽略** |
| `icon_src` / `icon_symbol` | ✅ 渲染（42–76px 图标），见 §9 | ❌ **忽略** |
| `progress` | ✅ 渲染（进度条高 8） | ❌ **忽略** |
| 卡片圆角 | **26** | **10** |
| 行间距 | **9** | **4** |

**事件**: 无。

**示例**:

```json
{
  "section_id": "sec_hero",
  "type": "hero_section",
  "data": {
    "value": "86%",
    "label": "今日完成率",
    "subtitle": "目标 100 单",
    "tone": "success",
    "progress": 86
  }
}
```

---

### 5.2 `metric_section` — 指标网格

以网格布局展示多个指标（标签+数值），适合数据概览面板。

**字段 schema**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `metrics` | array | **是** | 指标数组，最多 4 个 |

每个 metric 对象：

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `label` | string | **是** | 指标名称 |
| `value` | string | **是** | 指标值 |

**per-board 渲染行为**:

| 渲染项 | AMOLED_175（466×466, large, rich） | LCD_085（128×128, small, compact） |
|--------|-------------------------------------|-------------------------------------|
| 列数 | **2**（≥3 items），<3 items 自适应 | **2**（cell 过窄时自动变 **1** 列） |
| 单元格圆角 | **14** | **8** |
| 行间距 | **8** | **3**（w<120）/ **4**（其他） |
| 列间距 | **8** | **4**（w<120）/ **6**（其他） |
| `value` 字体 | montserrat **20** | montserrat **14** |
| `label` 字体 | montserrat **14** | montserrat **14** |
| 单元格高度 | **66** | **36**（w<120）/ **44**（其他） |

**事件**: 无。

---

### 5.3 `chart_section` — 折线图

展示一条折线趋势图，适合时间序列数据。

**字段 schema**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `title` | string | 否 | 图表标题 |
| `points` | int[] | **是** | 数据点（0–100），rich 最多 32 点，compact 最多 8 点 |
| `progress` | int | 否 | 0–100，底部进度条 |

**per-board 渲染行为**:

| 渲染项 | AMOLED_175（466×466, large, rich） | LCD_085（128×128, small, compact） |
|--------|-------------------------------------|-------------------------------------|
| `title` | ✅ montserrat 14 | ✅ montserrat 14 |
| `points` | ✅ 最多 **32** 点 | ✅ 最多 **8** 点（自动下采样） |
| `progress` | ✅ 渲染（进度条高 8） | ❌ **忽略** |
| 图表高度（无 target_h） | **128** | **24**（w<120）/ **30**（其他） |
| 折线宽度 | 默认 | **2** |
| 卡片圆角 | **18** | **10** |
| 行间距 | **8** | **1** |

**事件**: 无。

---

### 5.4 `timer_section` — 计时器

显示运行中的计时器，终端本地计时（每秒刷新），最大显示 99:59。

**字段 schema**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `title` | string | 否 | 计时器标题 |
| `timer.elapsed_ms` | int | 否 | 初始经过时间（ms），默认 0 |
| `timer.running` | bool | 否 | 是否运行中，默认 true |
| `progress` | int | 否 | 0–100，底部进度条 |

**per-board 渲染行为**:

| 渲染项 | AMOLED_175（466×466, large, rich） | LCD_085（128×128, small, compact） |
|--------|-------------------------------------|-------------------------------------|
| `title` | ✅ montserrat 14 | ❌ **忽略** |
| `timer` 时间 | ✅ montserrat **24** | ✅ montserrat **20**（target_h≤75）/ **24**（其他） |
| `progress` | ✅ 渲染（进度条高 8） | ❌ **忽略** |
| 卡片圆角 | **18** | **10** |
| 行间距 | **9** | **2** |

**事件**: 无（本地计时，不上报）。

---

### 5.5 `image_section` — 图片/图标展示

展示一个居中图标，配合标题和副标题。适合空状态、功能入口、品牌展示。

**字段 schema**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `icon_src` | string | 否 | 图标键名（也读 `image_src`，最终回退 `"chat"`），见 [§9 内置素材库](#9-内置素材库icon_src--icon_symbol) |
| `title` | string | 否 | 标题 |
| `subtitle` | string | 否 | 副标题 |

**per-board 渲染行为**:

| 渲染项 | AMOLED_175（466×466, large, rich） | LCD_085（128×128, small, compact） |
|--------|-------------------------------------|-------------------------------------|
| `icon_src` | ✅ **84px** 图标 | ✅ **24**（w<120）/ **32**（其他） |
| `title` | ✅ montserrat 14 | ✅ montserrat 14 |
| `subtitle` | ✅ montserrat 14 | ❌ **忽略** |
| 卡片圆角 | **26** | **10** |
| 行间距 | **8** | **1** |

**事件**: 无。

---

### 5.6 `action_section` — 按钮组

一组操作按钮，每个按钮点击后产生上行事件。适合表单提交、功能入口。

**字段 schema**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `actions` | array | **是** | 按钮数组，最多 4 个 |

每个 action 对象：

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `id` | string | **是** | 按钮标识（上行事件 `node_id` 携带此值） |
| `label` | string | **是** | 按钮文案 |
| `tone` | string | 否 | 色系（同 hero） |
| `enabled` | bool | 否 | 默认 true |

**per-board 渲染行为**:

| 渲染项 | AMOLED_175（466×466, large, rich） | LCD_085（128×128, small, compact） |
|--------|-------------------------------------|-------------------------------------|
| 布局方向 | **横排（ROW）** | **竖排（COLUMN）** |
| 按钮宽度 | `(inner_w - (n-1)×8) / n`，最小 60 | 全宽 `inner_w` |
| 按钮高度 | **52** | **36** |
| 按钮圆角 | **18** | **10** |
| 字体 | montserrat 14 | montserrat 14 |
| 列间距 | **8** | 0 |
| 行间距 | 0 | **4** |

**事件**:

| 事件 | event_type | node_id | 说明 |
|------|-----------|---------|------|
| 按钮点击 | `"action.click"` | `action.id` | 触屏点击或按键确认 |

**上行 TLV**: `event_kind=1` (UI3_EVENT_UI_CLICK), `node_id=<action.id>`, `event_type="action.click"`。

**示例**:

```json
{
  "section_id": "sec_actions",
  "type": "action_section",
  "data": {
    "actions": [
      { "id": "btn_save", "label": "保存", "tone": "primary" },
      { "id": "btn_cancel", "label": "取消", "tone": "secondary" }
    ]
  }
}
```

---

### 5.7 `progress_section` — 进度条

展示一个带标题和百分比文本的进度条。适合任务进度、加载状态。

**字段 schema**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `title` | string | 否 | 标题 |
| `progress` | int | **是** | 0–100 |
| `progress_text` | string | 否 | 进度文本（默认 `"N%"`） |

**per-board 渲染行为**:

| 渲染项 | AMOLED_175（466×466, large, rich） | LCD_085（128×128, small, compact） |
|--------|-------------------------------------|-------------------------------------|
| `title` | ✅ montserrat 14 | ❌ **忽略** |
| `progress` 进度条 | ✅ 高 **8**（target_h>60 按比例增大，最大 16） | ✅ 高 **8**（target_h>60 按比例增大，最大 16） |
| `progress_text` | ✅ montserrat 14 | ✅ montserrat 14 |
| 卡片圆角 | **18** | **10** |
| 行间距 | **4** | **2** |

**事件**: 无。

---

### 5.8 `text_section` — 文本块

展示标题+正文文本。适合通知、消息、说明内容。

**字段 schema**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `title` | string | 否 | 标题 |
| `body` | string | 否 | 正文 |

**per-board 渲染行为**:

| 渲染项 | AMOLED_175（466×466, large, rich） | LCD_085（128×128, small, compact） |
|--------|-------------------------------------|-------------------------------------|
| `title` | ✅ montserrat 14 | ✅ montserrat 14 |
| `body` | ✅ 最多 **200** 字符 | ✅ 最多 **40** 字符 |
| body 显示模式 | **WRAP**（自动换行） | **DOT**（单行省略号截断） |
| 卡片圆角 | **18** | **10** |
| 行间距 | **4** | **2** |

**事件**: 无。

---

### 5.9 `list_section` — 列表

可滚动的选项列表，每项可点击并产生上行事件。适合菜单、选择器。

**字段 schema**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `items` | array | **是** | 列表项数组 |

每个 item 对象：

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `id` | string | **是** | 列表项标识（上行事件 `node_id` 携带） |
| `title` | string | **是** | 标题 |
| `subtitle` | string | 否 | 副标题 |
| `icon_src` | string | 否 | 图标键名（compact 模式下忽略），见 [§9 内置素材库](#9-内置素材库icon_src--icon_symbol) |
| `tone` | string | 否 | 标题色系 |

**per-board 渲染行为**:

| 渲染项 | AMOLED_175（466×466, large, rich） | LCD_085（128×128, small, compact） |
|--------|-------------------------------------|-------------------------------------|
| max_items | **20** | **6** |
| 列表项高度 | **56** | **28** |
| 容器内边距 | **12** | **6** |
| 容器行间距 | **2** | **1** |
| 列表项圆角 | **8** | **4** |
| 列表项内边距 | **4** | **2** |
| `title` | ✅ montserrat 14 | ✅ montserrat 14 |
| `subtitle` | ✅ montserrat 14（右对齐） | ❌ **忽略** |
| `icon_src` | ✅ **36px** 图标 | ❌ **忽略** |

**事件**:

| 事件 | event_type | node_id | 说明 |
|------|-----------|---------|------|
| 列表项选择 | `"list.select"` | `item.id` | 点击列表项 |

**上行 TLV**: `event_kind=1` (UI3_EVENT_UI_CLICK), `node_id=<item.id>`, `event_type="list.select"`。

---

### 5.10 `toggle_section` — 开关选项

一组开/关切换控件，每次切换产生上行事件（携带新值）。

**字段 schema**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `options` | array | **是** | 开关选项数组 |

每个 option 对象：

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `id` | string | **是** | 开关标识 |
| `label` | string | **是** | 开关标签文案 |
| `active` | bool | 否 | 初始状态，默认 false |

**per-board 渲染行为**:

| 渲染项 | AMOLED_175（466×466, large, rich） | LCD_085（128×128, small, compact） |
|--------|-------------------------------------|-------------------------------------|
| max_toggles | **8** | **4** |
| 行高度 | **44** | **32** |
| 容器内边距 | **12** | **6** |
| 容器行间距 | **4** | **2** |
| 行内边距 | **4** | **2** |
| 标签字体 | montserrat 14 | montserrat 14 |

**事件**:

| 事件 | event_type | node_id | 说明 |
|------|-----------|---------|------|
| 开关切换 | `"toggle.change"` | `"<id>:<0\|1>"` | 切换开关，`node_id` 携带新值 |

> `node_id` 格式: `"wifi:1"` = wifi 开关打开, `"wifi:0"` = 关闭。

**上行 TLV**: `event_kind=3` (UI3_EVENT_TOGGLE_CHANGE), `node_id="<id>:<0|1>"`, `event_type="toggle.change"`。

---

### 5.11 `overlay_section` — 弹窗

浮层弹窗，用于通知、确认、告警。渲染到全屏层，不占用 section 布局空间。

**字段 schema**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `title` | string | 否 | 弹窗标题 |
| `body` | string | 否 | 弹窗正文（也读取 `text` 字段） |
| `tone` | string | 否 | 色系：`"warning"`(默认) / `"success"` / `"danger"` / `"primary"` |
| `unread_count` | int | 否 | 未读计数（badge），≥ 0 |
| `auto_hide_ms` | int | 否 | 自动隐藏时间（ms，仅 compact 生效，默认 5000，0 = 不自动隐藏） |

**per-board 渲染行为**:

| 渲染项 | AMOLED_175（466×466, large, rich） | LCD_085（128×128, small, compact） |
|--------|-------------------------------------|-------------------------------------|
| 弹窗宽度 | **306** | **112** |
| 圆角 | **22** | **9** |
| 阴影宽度 | **10** | **4** |
| 内边距 | **12** | **5** |
| Y 偏移 | **76** | **4** |
| 行间距 | **4** | **2** |
| 关闭方式 | ✅ 显式 **"OK" 按钮**（90×28） | ✅ **点击弹窗任意位置**关闭 |
| `body` 独立标签 | ✅ 渲染（WRAP，body ≠ title 时） | ❌ **不渲染**（仅合并显示文本） |
| 未读 badge | ✅ 弹窗内联（20×18） | ✅ 浮于根容器**右上角**（18×16） |
| `auto_hide_ms` | ❌ 关闭（强制 0，仅手动关闭） | ✅ 启用（默认 5000ms） |
| 字体 | montserrat 14 | montserrat 14 |

**事件**:

| 事件 | event_type | node_id | 说明 |
|------|-----------|---------|------|
| 弹窗确认/关闭 | `"overlay.confirm"` | `""` | 点击 OK 按钮 / compact 点击弹窗 / 自动隐藏 |

**上行 TLV**: `event_kind=2` (UI3_EVENT_OVERLAY_CONFIRM), `event_type="overlay.confirm"`。

**特殊行为**: overlay 渲染到 `lv_scr_act()` 全屏层，在 `layout: "overlay"` 场景下可独立使用。

---

### 5.12 `nav_section` — 导航标签页

水平标签页栏，点击切换本地页面（不产生上行事件）。适合多视图切换。

**字段 schema**:

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `tabs` | array | **是** | 标签页数组 |
| `active_tab` | int | 否 | 默认激活 tab 索引，默认 0 |

每个 tab 对象：

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `id` | string | 否 | Tab 标识 |
| `label` | string | **是** | Tab 显示文案，无 id 时回退为标识 |

**per-board 渲染行为**:

| 渲染项 | AMOLED_175（466×466, large, rich） | LCD_085（128×128, small, compact） |
|--------|-------------------------------------|-------------------------------------|
| max_tabs | **6** | **4** |
| tab 高度 | **44** | **28** |
| tab 圆角 | **14** | **8** |
| 列间距 | **6** | **2** |
| 字体 | montserrat 14 | montserrat 14 |

**事件**: 无。点击 tab 触发终端本地页面切换（`ui3_section_runtime_switch_tile`），不上报事件。服务器通过 Patch 同步页面状态。

---

## 6. Patch 机制（SECTION_PATCH）

增量补丁用于对已存在的 Section 进行新增、修改或删除。终端根据 `section_id` 定位目标执行操作。

### 6.1 操作类型

#### update — 修改 Section 内容

```json
{
  "patches": [
    {
      "section_id": "sec_hero",
      "op": "update",
      "data": { "value": "92%" }
    }
  ]
}
```

- `data` 与已有数据**浅合并**（新 key 覆盖旧值，未出现的 key 保留）
- 合并后删除旧 LVGL 对象，用合并后的完整 data 重新渲染
- 多次 update 累积生效（每次在之前合并结果上继续）

#### remove — 删除 Section

```json
{
  "patches": [
    {
      "section_id": "sec_temp",
      "op": "remove"
    }
  ]
}
```

- 删除 LVGL 对象，清空 slot
- 后续 patch 对该 `section_id` 无效

#### add — 新增 Section

```json
{
  "patches": [
    {
      "section_id": "sec_new",
      "op": "add",
      "type": "text_section",
      "data": { "title": "新内容", "body": "动态追加的 section" }
    }
  ]
}
```

- `type` 必填，指定 section 类型（12 种之一）
- 新 section 追加到当前页末尾（vertical_scroll/fixed_single）或新增一页（horizontal_pages）
- compact/rich 判定与全量渲染一致
- 新 section 注册后可被后续 `update`/`remove` 操作
- `section_count` 达到 12 上限时拒绝新增

### 6.2 操作限制

| 操作 | 支持 | 说明 |
|------|------|------|
| update | ✅ | 修改已有 section 的 data |
| remove | ✅ | 删除已有 section |
| add | ✅ | 新增 section，须提供 `type` + `data` |
| 改 layout | ❌ | 布局切换需走全量 `SECTION_SCENE` |
| 改 type | ❌ | section 类型不可变 |

---

## 7. 交互事件汇总

所有 Section 交互事件通过 UI3 Binary `EVENT_INPUT`（msg_type=9）上行。

| 组件 | 事件 | event_kind | node_id 示例 | 说明 |
|------|------|-----------|-------------|------|
| action_section | `action.click` | 1 (UI_CLICK) | `"btn_save"` | 按钮点击 |
| list_section | `list.select` | 1 (UI_CLICK) | `"item_3"` | 列表项选择 |
| toggle_section | `toggle.change` | 3 (TOGGLE) | `"wifi:1"` | 开关切换（携带新值） |
| overlay_section | `overlay.confirm` | 2 (OVERLAY) | `""` | 弹窗确认/关闭 |

**TLV 字段**（所有事件通用）:

| TLV type | 字段 | 类型 | 说明 |
|----------|------|------|------|
| 120 | `event_kind` | u8 | 事件类别：1/2/3 |
| 121 | `node_id` | string | 组件标识 |
| 122 | `event_type` | string | 事件类型字符串 |
| 125 | `ts` | u32 | 毫秒时间戳 |

---

## 8. 快速参考：两板 Section 差异速查

以下表格汇总两种屏幕下每种 Section 的关键差异，供平台开发者快速查阅。

| Section 类型 | AMOLED_175 忽略的字段 | LCD_085 忽略的字段 | LCD_085 主要变化 |
|-------------|----------------------|-------------------|-----------------|
| hero_section | — | subtitle, icon_src, icon_symbol, progress | 字体缩小, 圆角减半 |
| metric_section | — | — | 单元格缩小, cell 窄时变 1 列 |
| chart_section | — | progress | 最多 8 点, 图表极矮 |
| timer_section | — | title, progress | 时间字体略小 |
| image_section | — | subtitle | 图标缩小到 24–32px |
| action_section | — | — | 横排→竖排, 按钮缩小 |
| progress_section | — | title | 布局更紧凑 |
| text_section | — | — | 40 字符截断, WRAP→DOT |
| list_section | — | subtitle, icon_src | 最多 6 项, 高度减半 |
| toggle_section | — | — | 最多 4 个, 行高减小 |
| overlay_section | auto_hide_ms | body 独立标签 | 无 OK 按钮, 全尺寸缩小 |
| nav_section | — | — | 最多 4 tab, 高度减小 |

---

## 9. 内置素材库（icon_src / icon_symbol）

以下 Section 类型支持通过 `icon_src`（图标键名）或 `icon_symbol`（图标符号）字段指定图标：

| 使用位置 | 字段名 | large (AMOLED_175) | small (LCD_085) | 回退值 |
|---------|--------|-------------------|-----------------|--------|
| `hero_section.data` | `icon_src` / `icon_symbol` | ✅ 渲染（42–76px） | ❌ **忽略** | — |
| `image_section.data` | `icon_src`（也读 `image_src`） | ✅ 渲染（84px） | ✅ 渲染（24–32px） | `"chat"` |
| `list_section.items[].data` | `icon_src` | ✅ 渲染（36px） | ❌ **忽略** | — |

### 9.1 匹配规则

图标匹配采用**子串匹配**：终端检查 `icon_src` / `icon_symbol` 的值是否**包含**下表中的键名。例如值为 `"chat"`、`"my-chat-icon"`、`"chat_bot"` 均能匹配到 `chat` 图标。

> 匹配顺序即下表顺序，命中第一个即停止。建议使用精确键名以避免误匹配。

### 9.2 完整图标列表

| 键名 | 图片资源 | 默认色彩 | 回退符号（icon_symbol 无匹配时） | 建议场景 |
|------|---------|---------|--------------------------------|---------|
| `start` | 播放图标 | `#2563EB` (蓝) | `LV_SYMBOL_PLAY` (▶) | 启动、开始 |
| `mail` | 信封图标 | `#2478FF` (蓝) | `LV_SYMBOL_ENVELOPE` (✉) | 消息、邮件 |
| `chat` | 对话图标 | `#17A673` (绿) | `LV_SYMBOL_CALL` (📞) | 对话、客服、**默认回退** |
| `file` | 文件图标 | `#F0A02A` (橙) | `LV_SYMBOL_FILE` (📄) | 文档、文件 |
| `love` | 心形图标 | `#EA4C89` (粉) | `*` | 收藏、喜欢 |
| `droplet` | 水滴图标 | `#18A7D6` (青) | `*` | 湿度、液体、环境 |
| `doubt` | 疑问图标 | `#7D63D9` (紫) | `LV_SYMBOL_WARNING` (⚠) | 疑问、未知、警告 |
| `veins` | 脉络图标 | `#EF5B5B` (红) | — | 健康、连接、网络 |
| `doller` / `dollar` | 货币图标 | `#21A65B` (绿) | `$` | 金钱、支付、价格 |

### 9.3 使用示例

```json
{
  "section_id": "sec_hero",
  "type": "hero_section",
  "data": {
    "value": "3 封新邮件",
    "label": "未读",
    "icon_src": "mail",
    "tone": "primary"
  }
}
```

```json
{
  "section_id": "sec_img",
  "type": "image_section",
  "data": {
    "icon_src": "chat",
    "title": "智能助手",
    "subtitle": "随时为您服务"
  }
}
```

---

## 10. 相关文档

| 文档 | 内容 |
|------|------|
| `SECTION_SCHEMA.md`（本文档） | 12 种 Section 类型定义、per-board 渲染行为、布局模式、Patch 机制 |
| [`PROTOCOL_AND_COMMANDS.md`](PROTOCOL_AND_COMMANDS.md) | 协议基础、能力上报、命令控制、输入事件、错误码 |
| `PROTOCOL.md` | WebSocket 信封、legacy topic 定义、兼容策略 |
| `TEMPLATE_RUNTIME.md` | Template payload schema、patch 字段、约束 |
| `ARCHITECTURE.md` | 终端架构、模块职责、设计哲学 |
