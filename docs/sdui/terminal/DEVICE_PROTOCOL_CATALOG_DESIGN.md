# 设备协议目录设计

本文档描述 SDUI 的“设备协议单一来源”设计思路。目标不是建设前端表单中心，也不是重新抽象整个平台能力，而是将**与终端设备约定的协议定义**统一收口为一份后端事实数据源。

相关协议细节仍分别见：

- [`PROTOCOL_AND_COMMANDS.md`](PROTOCOL_AND_COMMANDS.md)
- [`SECTION_SCHEMA.md`](SECTION_SCHEMA.md)
- [`CAPABILITY_REPORTING.md`](CAPABILITY_REPORTING.md)

---

## 1. 设计目标

建立一份仅面向“设备协议”的统一目录，覆盖：

- 服务端下发到终端的控制命令
- 服务端下发到终端的 Section UI 协议
- 终端上报到服务端的事件协议

这份目录的职责是：

- 作为后端协议处理的单一事实源
- 输出稳定、标准化、瘦身后的协议定义
- 为调试接口、工作流设备节点、协议编解码提供统一输入
- 降低命令、Section、事件在不同链路中的重复定义和漂移

这份目录**不负责**：

- 为前端生成完整表单文档
- 承担页面展示文案中心
- 纳入平台能力如 TTS、STT、服务端音频播放
- 抽象工作流编排语义本身

---

## 2. 设计边界

### 2.1 纳入范围

设备协议目录只包含三类实体：

- `CommandSpec`
  - 设备控制命令，如 `display.brightness.set`、`rgb.effect.set`
- `SectionSpec`
  - 设备 UI Section 定义及其 scene/patch 交互语义
- `EventSpec`
  - 设备上报事件，如按钮事件、动作传感器事件、音频上传事件、Section 交互事件

这些实体的协议事实来源于终端现有协议约定，包括：

- `inputs[]` 等启动时能力上报
- `display.section_types / layouts / size_class` 等显示能力上报
- 运行时输入事件上报协议，如 `EVENT_INPUT`
- Section scene / patch 协议

### 2.2 明确不纳入范围

以下内容不进入设备协议目录：

- 平台能力
  - 如 `platform.tts`、`platform.stt`、服务端音频播放
- 工作流节点编排定义
  - 如节点生命周期、分支、变量绑定、调度语义
- 纯前端展示字段
  - 如 `label`、`placeholder`、说明性文案
- 页面编辑器辅助字段
  - 如 `sampleData`、`layoutHints`、编辑器专用约束描述

### 2.3 边界原则

判断一个定义是否进入目录，只看一条：

> 这个定义是否属于“服务端与终端之间已经约定好的协议事实”。

如果是平台内部运行时能力、工作流编排便利字段、前端展示辅助字段，则不进入该目录。

---

## 3. 核心原则

### 3.1 单一来源，但不做万能大对象

目录应当是统一入口，而不是单个超大类。建议采用“总目录 + 子目录 + 公共协议模型”的结构。

### 3.2 只保留必要字段

目录不追求保存所有文档信息，只保留真正影响协议处理和数据标准化的字段。

不作为核心长期保留的字段包括：

- `defaults`
- `required`
- `min/max`
- `displayName`
- `description`
- `sampleData`
- 编辑器专用约束

这些字段如确有需要，应位于文档层、展示层或附加扩展层，而不是设备协议事实层。

### 3.3 静态定义与运行时请求分离

目录定义的是“协议规则”，不是“某次请求数据”。

例如：

- `CommandSpec` 定义一个命令长什么样
- `CommandInvocation` 才表示一次命令调用

两者必须分开，避免把协议目录变成业务请求模型。

### 3.4 对外输出标准化

目录对外提供的是标准化、低噪声结构，而不是直接暴露内部实现细节。

目标是：

- 同一命令在调试接口、工作流设备节点、协议发送层看到的是同一份定义
- 同一 Section 在 scene 和 patch 路径下看到的是同一份结构定义
- 同一事件在能力查询、事件处理器、工作流事件节点看到的是同一份上报模型

### 3.5 平台负责统一投影，不重做终端协议解释

目录要做的是把终端已经约定好的协议能力统一组织成平台可消费的标准模型，而不是在平台侧重新推导终端已经封装好的协议语义。

例如：

- 终端通过 `inputs[]` 声明存在 `buttons.pwr`、`motion`、`audio.record`
- 终端运行时通过统一的输入事件协议上报封装后的按钮、motion、Section 交互事件
- 终端通过 `display` 能力声明支持的 Section 类型、布局和渲染档位

平台职责是：

- 读取这些协议事实
- 整理为统一目录输出
- 供调试、工作流、协议处理链路复用

平台不负责：

- 从底层原始采样重新发明按钮或 motion 事件语义
- 从 `shape`、`screen.w/h`、`size_class` 自行重算 rich/compact 渲染规则

---

## 4. 目录结构设计

建议采用如下逻辑结构：

- `DeviceProtocolCatalog`
  - `CommandCatalog`
  - `SectionCatalog`
  - `EventCatalog`

另外有两类公共基础模型：

- `FieldSpec`
- `TransportSpec`

含义如下：

- `DeviceProtocolCatalog`
  - 统一入口，只负责聚合查询
- `CommandCatalog`
  - 管理所有设备命令定义
- `SectionCatalog`
  - 管理所有 Section 及 scene/patch 定义
- `EventCatalog`
  - 管理所有设备上行事件定义
- `FieldSpec`
  - 统一描述协议字段结构
- `TransportSpec`
  - 统一描述协议传输标识

该结构避免出现一个过重的“全能协议类”，同时又能保证唯一入口。

---

## 5. 数据模型思路

### 5.1 CommandSpec

`CommandSpec` 表示一个设备控制命令的协议定义。

建议仅包含：

- `id`
- `group`
- `params`
- `transport`

说明：

- `id`
  - 语义命令标识，如 `display.brightness.set`
- `group`
  - 命令归属分组，如 `display`、`rgb`
- `params`
  - 命令参数结构，仅保留协议必要字段
- `transport`
  - 命令如何映射到终端协议通道

### 5.2 SectionSpec

`SectionSpec` 表示一个 Section 类型的协议定义。

建议仅包含：

- `type`
- `fields`
- `scene`
- `patch`

说明：

- `type`
  - Section 类型，如 `hero_section`
- `fields`
  - Section 数据结构
- `scene`
  - 该 Section 如何参与完整 scene 下发
- `patch`
  - 该 Section 如何参与增量 patch

Section 目录关注的是“结构和交互语义”，不承担编辑器属性说明职责。

### 5.3 EventSpec

`EventSpec` 表示一个终端上行事件的协议定义。

建议仅包含：

- `id`
- `source`
- `payload`
- `transport`

说明：

- `id`
  - 事件语义标识，如 `button.press`、`button.long_press`、`shake`、`audio.record`、`action.click`
- `source`
  - 事件来源域，如 `button`、`motion`、`audio`、`section`
- `payload`
  - 事件上报数据结构
- `transport`
  - 事件从终端上报时的协议标识

这类事件统一纳入 `EventCatalog`，避免只关注下行命令而忽略设备上行协议。

需要注意的是，当前终端上报的并不是“等待平台自行解释的原始底层采样”，而是已经封装进现有协议模型中的运行时事件。例如：

- 物理按键通过统一输入事件通道上报
- motion 通过统一输入事件通道上报
- Section 交互通过统一输入事件通道上报
- 音频录音通过 `audio/record` 三阶段 JSON topic 上报

因此 `EventCatalog` 的职责是统一事件定义和查询视图，而不是重建底层输入语义。

### 5.4 FieldSpec

`FieldSpec` 是命令参数、Section 字段、事件 payload 的公共结构定义。

建议仅包含：

- `name`
- `type`
- `children`

说明：

- `name`
  - 字段名
- `type`
  - 字段类型，如 `string`、`int`、`bool`、`object`、`array`
- `children`
  - 对象或数组子结构

`FieldSpec` 的目标是表达协议结构，不承载表单语义。

### 5.5 TransportSpec

`TransportSpec` 描述一个协议定义对应的传输标识。

建议仅包含：

- `topic`
- `action`
- `messageKind`

说明：

- `topic`
  - JSON topic 或逻辑通道名
- `action`
  - payload 中的协议动作标识
- `messageKind`
  - 二进制或特定消息种类，如 `SECTION_SCENE`、`SECTION_PATCH`、`EVENT_INPUT`

不是所有实体都必须同时使用这三个字段，但统一结构有利于标准化输出。

---

## 6. 终端能力投影

设备协议目录不应只理解为一张“静态协议表”，还应承担“把终端上报事实投影成标准能力视图”的职责。

这里的“投影”指：

- 依据终端能力上报决定哪些命令、Section、事件对当前设备有效
- 依据终端已定义好的协议语义，输出标准化能力结果
- 不在平台侧重算终端已经封装好的运行时规则

### 6.1 输入能力与事件

终端启动时通过 `inputs[]` 上报可用输入能力，例如：

- `buttons.pwr`
- `audio.record`
- `motion`

这些能力项表示该设备支持哪些输入源；真正运行时事件则按既定协议上报。

因此平台应做的是：

- 基于 `inputs[]` 判断当前设备支持哪些输入事件族
- 基于事件协议定义整理出统一事件目录

而不是要求终端在启动时上报所有运行时事件枚举。

### 6.2 Section 能力与事件

Section 相关能力由 `display` 能力上报驱动，而不是由 `inputs[]` 驱动。

关键事实包括：

- `display.section_types`
- `display.layouts`
- `display.size_class`
- 终端对 rich / compact 等渲染档位的既有封装

Section 交互事件本身也是终端协议的一部分，并非平台临时拼装：

- `action.click`
- `list.select`
- `toggle.change`
- `overlay.confirm`
- `nav.switch`

因此平台应将其建模为“设备 UI 交互事件”，并与按钮、motion、音频等设备输入事件共同纳入 `EventCatalog`。

### 6.3 rich / compact 处理原则

Section 的 rich / compact 能力解析需要支持，但平台不应自行推导终端渲染规则。

设计原则是：

- rich / compact 的判定与渲染降级逻辑由终端封装
- 平台只解析终端上报或终端能力暴露出的结果
- 设备协议目录输出当前设备可用的 Section 视图

换句话说，目录需要支持“按设备能力投影出有效 Section 定义”，但不需要在平台侧重新实现 `shape`、`screen.w/h`、`size_class` 到渲染模式的推理链路

---

## 7. 命令、Section、事件的关系

设备协议目录应覆盖一条完整的设备交互闭环：

- `commands`
  - 服务端到终端的控制下发
- `sections`
  - 服务端到终端的界面下发
- `events`
  - 终端到服务端的状态与交互上报

这三者缺一不可。

如果只定义 `commands` 和 `sections`，目录只能覆盖下行协议，无法统一：

- 按钮事件上报
- 动作传感器事件上报
- 音频上传/录音事件
- Section 交互事件

因此 `EventSpec` 必须作为一级实体存在，而不是零散附着在命令或 Section 文档中。

---

## 8. 与现有系统的关系

### 8.1 调试接口

调试接口应从设备协议目录读取标准定义，而不是自行拼装另一套编辑器模型。

建议方向：

- `GET /debug/{deviceId}/commands`
  - 输出 `CommandSpec` 列表
- `GET /debug/{deviceId}/sections`
  - 输出 `SectionSpec` 列表
- `GET /debug/{deviceId}/events`
  - 输出 `EventSpec` 列表

调试接口仍然可以基于这些定义做适配，但不应再成为能力事实源。

### 8.2 工作流设备节点

工作流不重新定义终端协议，只复用设备协议目录。

建议原则：

- 工作流节点只表达“什么时候执行”
- 设备协议目录表达“执行内容长什么样”
- Section 不直接以“裸 Section 集合”进入工作流，而是先收敛成页面控制层

例如：

- `device.command` 节点复用 `CommandSpec`
- `device.page.render`、`device.section.patch` 复用 `SectionSpec`
- `device.ui.event` 节点复用 `EventSpec`

### 8.3 Section 的页面控制层

Section 的协议能力虽然明确，但直接把每个 Section、每个交互节点、每个字段都暴露给工作流，会导致：

- 输入输出端口过多
- 页面状态难以维护
- 事件响应缺少稳定上下文
- 调试接口、工作流节点、运行时状态再次分叉

因此建议在工作流前置一个页面控制层，将 Section 统一收敛成 `page`。

页面控制层的基本约束是：

- 先形成明确的 `page`
- `page` 中明确包含哪些 `sections`
- 每个 `section` 具有稳定的 `sectionId / type / data`
- 页面一旦进入当前交互周期，即形成稳定上下文
- 后续渲染、patch、事件响应都围绕该 `page` 进行

这意味着工作流处理的不是零散控件，而是页面状态。

### 8.4 页面上下文模型

Section 进入工作流后，应统一挂载在页面上下文下管理。

页面上下文至少应包含：

- `pageId`
- `layout`
- `sections[]`
- `sectionsById`
- `updatedAt`
- `active`

其中每个 Section 至少包含：

- `sectionId`
- `sectionType`
- `data`

工作流无需保存终端内部渲染细节，例如：

- 焦点位置
- rich / compact 内部绘制状态
- 终端局部布局计算结果

这些属于终端 UI runtime，不属于平台工作流上下文。

### 8.5 固定输入与输出

Section 一旦通过页面控制层进入工作流，其输入、输出和上下文都应固定化。

建议的统一事件输入模型：

- `pageId`
- `sectionId`
- `sectionType`
- `eventType`
- `nodeId`
- `value`

建议的统一页面输出动作：

- `device.page.render`
- `device.section.patch`

不建议继续拆分出：

- 单独的 Section 渲染节点
- 单独的 Section 删除节点
- 子字段级 patch 节点
- 每种 Section 一套专有事件节点

这样可以保持工作流图的输入输出稳定，避免被 UI 细节拖垮。

### 8.6 页面事件响应

当用户与终端页面上的 Section 发生交互时，平台应按统一页面事件模型处理，而不是把某种 Section 行为直接绑定死到某个工作流动作上。

推荐流程：

1. 工作流下发某个 `page`
2. 平台记录当前页面状态
3. 终端上报 UI 交互事件
4. 平台解析出统一的页面事件上下文
5. 工作流根据 `pageId / sectionId / eventType / nodeId` 决定后续动作

后续动作可以包括：

- 更新当前页面中的某个 Section
- 切换到另一个页面
- 下发设备命令
- 调用平台能力

关键点是：Section 事件只负责表达“用户做了什么”，业务响应由工作流决定。

### 8.7 预定义与运行时生成

页面控制层不要求所有页面都必须在系统初始化阶段静态写死。

更准确的约束是：

- 工作流运行时必须存在明确的页面模型
- 页面模型可以预先配置
- 也可以由运行时节点生成或切换
- 但一旦页面被激活，在该交互周期内就应成为稳定上下文

因此工作流的核心不是“所有页面必须离线预定义”，而是“任何交互都必须发生在稳定的页面上下文中”。

### 8.8 协议编解码层

协议编解码层不自行维护另一套结构真相。

建议原则：

- 编码命令时读取 `CommandSpec`
- 构建 scene/patch 时读取 `SectionSpec`
- 解析事件时读取 `EventSpec`

这样可避免 controller、service、workflow、文档多处重复维护。

### 8.9 文档层

现有文档仍保留，但其角色应调整为：

- `PROTOCOL_AND_COMMANDS.md`
  - 解释传输协议与命令行为
- `SECTION_SCHEMA.md`
  - 解释 Section 结构和交互语义
- `CAPABILITY_REPORTING.md`
  - 解释终端能力上报格式

而设备协议目录负责成为后端协议事实源。文档是解释层，不是事实源。

---

## 9. 约束与取舍

### 9.1 不追求“一切都配置化”

命令通常天然适合 catalog 化；Section 和事件的复杂度更高，不必强行全部落入单一 YAML。

设计重点不是“放在哪里”，而是：

- 是否有单一来源
- 是否能被统一读取
- 是否避免重复定义

因此允许不同实体采用不同承载方式，只要最终都汇聚到统一目录即可。

### 9.2 不把前端表单需求反推为协议模型

前端如果需要表单增强字段，应在设备协议目录之外单独扩展。

协议目录的输出必须首先服务于：

- 后端一致性
- 协议处理标准化
- 多链路复用

而不是优先满足页面编辑便利性。

### 9.3 不让工作流成为第二事实源

工作流可以组合命令和 Section，但不应再次定义：

- 一套独立的命令 schema
- 一套独立的 Section 字段结构
- 一套独立的事件模型

否则调试链路和工作流链路仍会继续漂移。

### 9.4 事件必须与下行协议同等对待

设备交互不是单向命令调用，而是双向协议。

因此：

- 下行命令
- 下行 UI
- 上行事件

三者必须放入同一设计视角中统一考虑。

### 9.5 不把 Section 事件当作启动期原生能力枚举

Section 交互事件不应简单并入 `inputs[]` 语义中。

原因是：

- `inputs[]` 表示启动时声明的输入能力项
- Section 交互事件依赖 `display` 能力和 Section 协议
- 两者属于同一终端协议体系，但来源通道不同

因此统一目录应当在事件层汇总它们，而不是强行要求它们在能力声明层拥有相同来源模型。

### 9.6 不在平台侧重建终端渲染规则

即便 Section 存在 rich / compact 等渲染差异，平台也不应重做终端已经封装好的适配逻辑。

平台目录应做的是：

- 读取终端暴露出的能力结果
- 输出标准化的有效 Section 视图

而不是把终端 UI runtime 的判定细节重新搬到平台侧。

### 9.7 Section 在工作流中必须先收敛为页面上下文

工作流不应直接操作无归属的 Section 集合。

必须先形成页面上下文，再进行：

- 渲染
- patch
- 事件监听
- 状态维护

否则：

- `sectionId` 缺少稳定归属范围
- UI 事件无法确定所属页面
- patch 难以判断作用目标
- 工作流上下文会被零散 UI 状态污染

因此页面是 Section 进入工作流的最小稳定容器。

---

## 10. 推荐实施路径

### 10.1 第一步：建立统一目录入口

建立 `DeviceProtocolCatalog` 及三类子目录：

- `CommandCatalog`
- `SectionCatalog`
- `EventCatalog`

先完成统一查询入口和标准输出模型，不要求一次性替换所有旧实现。

### 10.2 第二步：先收命令

命令通常最接近现有 catalog 形态，适合作为第一阶段落地对象。

目标：

- 调试命令查询
- 命令发送归一化
- 协议动作映射

都从同一命令目录读取。

### 10.3 第三步：再收 Section

将 Section 的结构定义、scene、patch 语义统一纳入 `SectionCatalog`。

目标：

- 调试端 Section 查询
- scene 构建
- patch 构建
- 工作流设备页面节点

全部从同一 Section 目录读取。

### 10.4 第四步：补齐 Event

将现有分散的上行事件统一映射为 `EventSpec`。

优先收口：

- 按钮事件
- 动作传感器事件
- 音频上传事件
- Section 交互事件

这样设备交互闭环才算完整建立。

### 10.5 第五步：文档与接口对齐

在目录稳定后，再逐步调整：

- 调试接口输出结构
- 工作流设备节点引用方式
- 现有协议文档中的事实来源说明

避免先大面积改接口，再反向补协议定义。

---

## 11. 最终结论

设备协议单一来源应当聚焦在“终端协议事实”本身，而不是扩展成平台能力中心或前端表单中心。

推荐的最终形态是：

- 一个统一入口：`DeviceProtocolCatalog`
- 三类一级实体：`CommandSpec`、`SectionSpec`、`EventSpec`
- 两类公共基础模型：`FieldSpec`、`TransportSpec`
- 平台能力、工作流编排、前端展示信息全部留在目录之外

这样可以在不引入过度复杂度的前提下，统一：

- 命令定义
- Section 定义
- 设备上行事件定义

并为调试接口、工作流设备节点、协议处理链路提供稳定的一致性基础。
