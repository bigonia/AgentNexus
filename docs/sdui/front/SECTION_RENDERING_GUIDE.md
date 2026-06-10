# SDUI Section 渲染语义说明

本文档是 section 接口的业务补充说明，面向前端实现者。

它回答的问题不是“接口字段怎么传”，而是：

- 每种 `sectionType` 在前端应该渲染成什么
- 不同 `layout` 应该如何组织页面
- `tone`、`progress`、`iconSrc` 这类字段的业务含义是什么
- 某些 section 交互后应该上报什么事件

字段结构和协议基础请结合：

- [`SECTION_FRONTEND_INTEGRATION.md`](SECTION_FRONTEND_INTEGRATION.md)
- [`SECTION_SCHEMA.md`](SECTION_SCHEMA.md)

本文档内容以当前后端定义为准，主要对应：

- `SectionTypeCatalog`
- `SectionData`
- `SectionLayout`

---

## 1. 总体原则

### 1.1 section 的基本认知

一个 section 可以理解为“页面里的一个内容块”。

前端需要把一个 page 渲染成：

- 一个页面布局 `layout`
- 按顺序排列的一组 `sections[]`
- 每个 section 由：
  - `sectionType`
  - `fields`
  - 可选交互能力

### 1.2 渲染优先级

建议前端按以下优先级实现：

1. 先保证结构正确
2. 再保证字段语义正确
3. 再做视觉风格细化

也就是说，初期即使样式不完全统一，也应优先保证：

- `hero_section` 不要被渲染成纯文本块
- `action_section` 不要被渲染成列表
- `chart_section` 至少要有趋势表达
- `overlay` 布局不要和普通滚动页混用

### 1.3 未知字段与容错

前端建议：

- 只消费已知字段
- 不要因为多余字段报错
- 不要因为缺少非关键字段整块不显示

关键字段缺失时的降级建议会写在每个 section 说明里。

---

## 2. Layout 语义

## 2.1 `vertical_scroll`

含义：

- 垂直滚动页面
- 所有 section 按顺序从上到下排列
- 适合仪表盘、信息流、列表型页面

前端建议：

- 页面容器可滚动
- section 间保留稳定间距
- 每个 section 视作独立卡片或块级区域

## 2.2 `horizontal_pages`

含义：

- 多页水平翻页容器
- 每个 section 更像单独一页或一个分页块
- 适合引导页、分步骤展示、轮播式内容

前端建议：

- 使用横向分页或 carousel
- 支持页码指示器
- 避免在同一屏同时展示太多 section

## 2.3 `fixed_single`

含义：

- 固定单页，无滚动
- 内容超出屏幕时可能被裁剪
- 适合确认页、简洁卡片页、聚焦展示页

前端建议：

- 强约束内容密度
- 尽量只放 1 到 3 个结构简单的 section
- 不要默认给页面加纵向滚动

## 2.4 `overlay`

含义：

- 浮层或全屏覆盖层
- 常用于通知、告警、确认弹窗
- 最典型的是 `overlay_section`

前端建议：

- 使用 modal / sheet / full-screen overlay 的表现
- 背后主页面应被弱化或遮罩
- 应支持显式关闭或确认动作

---

## 3. 通用字段语义

## 3.1 `tone`

`tone` 不是纯颜色值，而是语义样式枚举。

建议前端把它映射到设计系统中的语义色，而不是写死某个 hex 值。

常见含义：

- `primary`
  - 主强调
  - 默认重点信息、主按钮、主数据状态
- `secondary`
  - 次级强调
  - 相对弱于主色的补充样式
- `success`
  - 成功、完成、健康、正常
- `warning`
  - 警告、注意、待处理
- `danger`
  - 错误、风险、严重告警
- `neutral`
  - 中性信息、普通列表项、默认弱样式

推荐映射策略：

- 文本色
- 背景色
- 边框色
- 图标色

应来自统一的 `tone -> token` 映射。

## 3.2 `progress`

`progress` 一般表示 0 到 100 的百分比进度。

前端建议：

- 以进度条、环形图、面积填充或趋势辅助条表达
- 对越界值做裁剪显示
- 不要假设后端永远传整数范围内的完美值

## 3.3 `iconSrc` / `iconSymbol`

当前后端定义里，这两个字段更接近“图标名称”而不是真正的远程 URL。

前端应优先按“图标 key”理解，比如：

- `chat`
- `mail`
- `file`
- `love`
- `droplet`

建议：

- 建一层本地图标映射表
- 未识别时降级到默认图标或不显示

## 3.4 `updatedAt`

这是后端 workspace 状态更新时间戳。

前端通常不需要直接展示，但可以用于：

- 调试态变化提示
- 最近更新时间展示
- 本地缓存比较

---

## 4. Section 类型说明

## 4.1 `hero_section`

定位：

- 单个核心指标卡
- 用于展示一个最重要的数据点

建议视觉：

- 大号数值
- 下面有标签和副标题
- 可附带图标
- 可附带进度条或进度背景

关键字段：

- `value`
  - 主数值，最醒目
- `label`
  - 指标名称
- `subtitle`
  - 补充状态说明
- `tone`
  - 整体状态色
- `iconSrc` / `iconSymbol`
  - 图标提示
- `progress`
  - 百分比进度

渲染建议：

- `value` 使用最大字号
- `label` 作为标题或说明
- `subtitle` 用较弱文本展示
- `progress` 可用底部条或环形指示

降级策略：

- 没有 `subtitle` 时仍可正常显示
- 没有图标时不占位或弱占位

## 4.2 `metric_section`

定位：

- 一组紧凑指标网格
- 用于并列展示多个 KPI

字段：

- `metrics[]`
  - 每项包含：
    - `label`
    - `value`

建议视觉：

- 2 列或 3 列网格
- 每项由小标题 + 数值组成

适合内容：

- CPU / MEM / 温度 / 电量
- 今日订单 / 活跃用户 / 错误数

## 4.3 `chart_section`

定位：

- 趋势图或简单折线图

字段：

- `title`
- `points`
  - 一组整数点
- `progress`
  - 可作为辅助状态

建议视觉：

- 小型 sparkline、折线图或面积图
- `title` 放上方
- `progress` 可辅助显示总体完成度

最低实现要求：

- 即使没有正式图表库，也应至少将 `points` 渲染成趋势线或柱状序列

## 4.4 `progress_section`

定位：

- 显示一个明确的进度状态

字段：

- `title`
- `progress`
- `progressText`

建议视觉：

- 标题
- 大号进度条
- 文本进度说明，如 `42%` 或 `3 / 10`

适合场景：

- 上传进度
- 任务完成度
- 设备同步状态

## 4.5 `text_section`

定位：

- 纯文本说明块

字段：

- `title`
- `body`

建议视觉：

- 标题 + 正文
- 支持较长文案
- 不要渲染成按钮或列表

适合场景：

- 提示说明
- 状态解释
- 帮助文字

## 4.6 `timer_section`

定位：

- 展示计时或运行中的时间状态

字段：

- `title`
- `progress`
- `timer.elapsedMs`
- `timer.running`

建议视觉：

- 标题
- 已运行时长
- 可选进度展示
- `running=true` 时可显示动态状态点

时间建议：

- 前端显示时应把 `elapsedMs` 转为更可读格式
- 如 `00:32`、`02:14:08`

## 4.7 `image_section`

定位：

- 图像或图标主导的信息块

字段：

- `iconSrc`
- `title`
- `subtitle`

建议视觉：

- 顶部或左侧主图标
- 下方标题与说明

说明：

- 当前更偏“图标卡片”而不是真实远程图片组件
- 优先按图标 key 处理

## 4.8 `overlay_section`

定位：

- 浮层通知、告警、确认框

字段：

- `title`
- `body`
- `tone`
- `unreadCount`
- `autoHideMs`

建议视觉：

- 居中弹层、顶部通知条、全屏告警层均可
- `tone` 决定通知级别
- `unreadCount` 可显示角标
- `autoHideMs > 0` 时支持自动消失

交互：

- 关闭或确认时应触发事件 `overlay.confirm`

## 4.9 `action_section`

定位：

- 一组操作按钮

字段：

- `actions[]`
  - `id`
  - `label`
  - `tone`
  - `enabled`

建议视觉：

- 一排或多排按钮
- 每个按钮是明确的点击目标

交互：

- 用户点击按钮时，上报 `action.click`
- 事件中应包含对应按钮 ID

实现建议：

- `enabled=false` 时按钮应禁用
- 最好保证按钮点击区域足够大

## 4.10 `toggle_section`

定位：

- 一组开关项或二态选项

字段：

- `options[]`
  - `id`
  - `label`
  - `active`

建议视觉：

- 列表式开关
- 每项一个标签 + 开关控件

交互：

- 用户切换时上报 `toggle.change`
- 应包含：
  - `optionId`
  - `active`

## 4.11 `list_section`

定位：

- 可点击列表

字段：

- `items[]`
  - `id`
  - `title`
  - `subtitle`
  - `tone`
  - `iconSrc`

建议视觉：

- 标准列表项
- 可有 leading icon、标题、副标题、色调点缀

交互：

- 点击列表项时上报 `list.select`

适合场景：

- 消息列表
- 任务列表
- 菜单入口

## 4.12 `nav_section`

定位：

- 导航标签栏

字段：

- `tabs[]`
  - `id`
  - `label`
- `activeTab`

建议视觉：

- tab bar 或 segmented control
- 当前激活标签有明确高亮

说明：

- 目前后端定义里它偏展示型
- 如果前端提供点击切换能力，应确认是否已有配套事件协议再接入

---

## 5. 交互事件说明

当前后端已明确的 section 交互事件主要有：

- `action.click`
  - 来自 `action_section`
  - 含义：点击某个操作按钮
- `toggle.change`
  - 来自 `toggle_section`
  - 含义：切换某个开关项
- `list.select`
  - 来自 `list_section`
  - 含义：选择某个列表项
- `overlay.confirm`
  - 来自 `overlay_section`
  - 含义：关闭或确认弹层

前端建议：

- 事件上报应带 `sectionId`
- 有页面上下文时带 `pageId`
- 有具体子控件时带对应子项 ID，如 `buttonId`、`itemId`、`optionId`

---

## 6. Compact / Rich 渲染模式

后端有 `renderMode` 概念，常见值：

- `rich`
- `compact`

含义：

- `rich`
  - 完整展示字段
- `compact`
  - 针对小屏或紧凑设备裁剪部分字段

前端接入建议：

- 表单编辑时，以 `section-editor` 返回的 `displayFields` 为准
- 不要自己硬编码“某字段一定显示”
- 相同 sectionType 在不同设备上可展示字段可能不同

例如：

- `hero_section` 在 compact 下可能只保留：
  - `value`
  - `label`
  - `tone`

---

## 7. 推荐实现策略

建议前端建立两个映射层：

### 7.1 `sectionType -> renderer`

例如：

- `hero_section -> HeroSectionCard`
- `metric_section -> MetricGridSection`
- `action_section -> ActionButtonsSection`

### 7.2 `tone -> design token`

例如：

- `primary -> brand`
- `success -> green`
- `warning -> yellow`
- `danger -> red`
- `neutral -> gray`

推荐不要做的事：

- 直接在业务代码里到处写 `if tone === "danger" then #ff0000`
- 把所有 section 都渲染成统一的 key-value 列表

---

## 8. 最低视觉标准建议

如果前端需要一个最小可用版本，建议至少做到：

- `hero_section`：大数字卡片
- `metric_section`：网格指标
- `chart_section`：简单趋势线
- `text_section`：标题正文块
- `action_section`：按钮组
- `list_section`：可点击列表
- `overlay_section`：弹层告警

这几类做好后，已经能覆盖大部分 debug 和工作流展示场景。

---

## 9. 一句话总结

前端不要把 section 理解成“任意 JSON 块”，而应该把它理解成“有明确视觉语义和交互语义的协议化 UI 组件”。
