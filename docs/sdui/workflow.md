# SDUI 后端整体设计

本文档定义 SDUI 后端在**终端能力管理、调试接入、工作流接入**三部分的统一设计思路。

目标不是围绕前端编辑器方便性做建模，而是先保证后端结构清晰、终端能力表述一致、运行时职责稳定。前端、调试工具、工作流编排器都应建立在同一份终端能力事实上，而不是各自维护一套平行模型。

协议基础请参阅 [`PROTOCOL_AND_COMMANDS.md`](PROTOCOL_AND_COMMANDS.md)，Section 渲染结构请参阅 [`SECTION_SCHEMA.md`](SECTION_SCHEMA.md)，能力上报格式请参阅 [`CAPABILITY_REPORTING.md`](CAPABILITY_REPORTING.md)。

前端接入方式请参阅 [`FRONTEND_API_INTEGRATION.md`](FRONTEND_API_INTEGRATION.md)。

模块化 API 文档入口：

- 设备管理查看：[`DEVICE_VIEW_API.md`](DEVICE_VIEW_API.md)
- 设备调试：[`DEBUG_API.md`](DEBUG_API.md)
- Section / Page 调试：[`DEBUG_SECTION_API.md`](DEBUG_SECTION_API.md)
- 工作流 API：[`WORKFLOW_API.md`](WORKFLOW_API.md)
- 设备能力：以本文为总体设计入口，后续继续拆分独立文档

---

## 1. 设计原则

### 1.1 单一能力事实源

终端能力只能定义一次，来源只能是：

- 设备上报的 `device/capabilities`
- 平台内置能力目录
- 平台内置 Section 类型目录

后端不允许在调试接口、工作流接口、编辑器元数据里再重复发明另一套能力描述。

### 1.2 三层对外语义分离

SDUI 对外接口分成三层：

- `capabilities/*`：回答“设备支持什么”
- `debug/*`：回答“我现在直接对设备做什么”
- `workflows/*`：回答“我如何基于统一能力编排自动化”

这三层共享同一份能力事实，但职责不同，不能相互替代。

补充约束：

- `capabilities/{deviceId}` 与 `capabilities/{deviceId}/tree` 都应返回 resolved contract 视图
- 原始快照、旧式归一化能力树只允许出现在 metadata/debug 语义接口中
- debug-only 视图用于排障，不得再作为工作流或调试主模型输入

### 1.3 工作流不是协议层

工作流不负责重新定义终端协议，也不负责定义另一套动作语言。工作流只是：

- 触发器调度器
- 变量与状态容器
- 统一能力节点执行器

也就是说，工作流是“能力消费者”，不是“能力定义者”。

### 1.4 UI 运行时独立

页面渲染、Section patch、active page 切换属于终端 UI 运行时问题，不应散落在工作流执行器和控制器里。它们应由独立运行时服务承担，避免：

- 页面状态和设备显示不一致
- Section patch 语义在多个模块各写一遍
- 节点执行器反向依赖工作流主服务形成循环依赖

---

## 2. 整体分层

SDUI 后端推荐按以下四层组织：

### 2.1 能力层

职责：

- 接收和持久化终端能力上报
- 归一化输入、输出、显示能力
- 为调试和工作流提供统一查询接口

核心对象：

- `CapabilitySchema.CapabilitySnapshot`
- `CapabilityRegistry`
- `DeviceProtocolCatalog`
- `DeviceCapabilityProjection`
- `SduiCapabilityService`
- `CommandSchemaRegistry`

能力层只描述事实，不做调度，不直接发工作流动作。

### 2.2 调试层

职责：

- 对真实设备执行一次命令或一次 UI 下发
- 实时观测事件和命令结果
- 暴露面向运维/联调的直接控制接口

核心接口：

- `GET /api/v1/sdui/debug/{deviceId}/commands`
- `POST /api/v1/sdui/debug/{deviceId}/command`
- `POST /api/v1/sdui/debug/{deviceId}/section`
- `GET /api/v1/sdui/debug/{deviceId}/events/stream`

调试层直接消费能力层，不新增独立能力模型。

### 2.3 编排层

职责：

- 维护工作流定义
- 管理设备绑定、触发器、变量、执行记录
- 调度统一能力节点

核心对象：

- `WorkflowDefinition`
- `WorkflowService`
- `TriggerScheduler`
- `ActionExecutor`
- `CapabilityNodeRegistry`

编排层不定义终端协议，只编排节点。

### 2.4 终端运行时层

职责：

- 执行面向终端的具体运行时操作
- 将页面定义转换为 `SectionScene`
- 将 Section 更新转换为 `SectionPatch`
- 协调命令、页面、音频等真实下发行为

当前重点对象：

- `SectionOrchestrationService`
- `WorkflowPageRuntimeService`
- `CommandService`
- `AudioService`

终端运行时层是工作流节点和调试接口的共同下游。

---

## 3. 终端能力管理

### 3.1 能力上报是唯一入口

终端通过 `device/capabilities` 一次性上报：

- 物理特征：板卡、屏幕、输入模式
- 输入能力：按钮、录音、IMU 等
- 输出能力：亮度、重启、音频、RGB 等
- UI 能力：Section 类型、布局、size class

平台收到后必须完成三件事：

1. 持久化原始快照
2. 归一化为统一能力图
3. 派生命令 schema、节点可用性、显示限制

### 3.2 能力层内部模型

建议内部统一为三类能力：

- `inputs`
- `outputs`
- `display`

其中：

- `inputs` 对应事件和输入源
- `outputs` 对应设备控制和媒体输出
- `display` 对应 Section/UI 渲染能力

不要在工作流侧再引入另一套 `actions`、`node-types`、`sectionTypes` 作为能力主模型。

### 3.3 命令能力的派生规则

设备命令不是独立人工配置，而是由 `outputs` 派生：

- `outputs[]` 决定设备支持哪些能力组
- 统一设备协议目录决定这些能力组下有哪些命令
- `CommandSchemaRegistry` 负责产生命令参数 schema

因此：

- 调试命令目录来自能力层
- 工作流里的 `device.control` 节点也来自能力层
- 两者底层必须共用同一命令定义和校验链路

### 3.4 UI 能力的派生规则

Section/UI 能力由以下两部分组成：

- 终端实际上报的 `display.section_types / layouts / size_class`
- 平台统一设备协议目录中的 Section 定义

前者决定“设备支持哪些类型”，后者决定“这些类型的字段和交互语义是什么”。

因此：

- `capabilities/{deviceId}/sections` 是设备 UI 能力事实接口
- `capabilities/{deviceId}/commands` 是设备命令事实接口
- `capabilities/{deviceId}/protocol` 是统一设备协议目录接口
- `debug/*` 与 `workflows/*` 都必须建立在它之上

---

## 4. 调试接入设计

调试层的目标是“直达真实设备行为”，而不是抽象出新的模型。

### 4.1 命令调试

流程：

1. 通过 `GET /debug/{deviceId}/commands` 获取设备可执行命令及参数 schema
2. 通过 `POST /debug/{deviceId}/command` 直接执行命令
3. 通过命令回执和统计接口查看结果

这里的命令目录、默认值、校验规则必须来自同一 `CommandSchemaRegistry`。

### 4.2 Section 调试

流程：

1. 通过 `GET /capabilities/{deviceId}/sections` 或 `GET /capabilities/{deviceId}/protocol` 获取设备支持的 Section 类型与字段
2. 通过 [`DEBUG_SECTION_API.md`](DEBUG_SECTION_API.md) 中的 page-first 调试接口直接下发 page scene / patch
3. 通过 `GET /debug/{deviceId}/section/state` 读取当前 page state，并结合终端实际渲染结果验证行为

Section 调试不允许维护另一套“工作流 UI 动作定义”，并且必须与工作流复用同一套 `pageId + sectionId` 语义。

### 4.3 事件调试

事件调试拆成两类：

- 元数据查询：`GET /capabilities/{deviceId}/events`
- 实时观测：`GET /debug/{deviceId}/events/stream`

前者回答“支持什么事件”，后者回答“刚刚发生了什么事件”。

### 4.4 平台能力调试

平台托管能力允许暴露为独立调试能力，但必须满足：

- 内部建模为 `platform.*`
- 响应中显式带 `source=platform`
- 运行时路由由平台 runtime handler 决定

不要再通过控制器中的命令分支把它们伪装成设备原生命令。

---

## 5. 工作流接入设计

### 5.1 工作流只保留统一动作骨架

工作流定义中的动作类型应收敛为：

- `node`
- `fetch`
- `condition`
- `sequence`
- `set_variable`

所有设备行为，包括命令、页面渲染、Section patch、音频播放，都通过 `node` 进入。

Section 相关节点应复用与调试接口一致的运行时语义：

- `device.page.render`
  - 对应调试侧 `POST /api/v1/sdui/debug/{deviceId}/section`
  - 负责发送完整 `SectionScene`
- `device.section.patch`
  - 对应调试侧 `POST /api/v1/sdui/debug/{deviceId}/section/patch`
  - 负责发送 `SectionPatch`
- 两者共同落到同一个 `SectionOrchestrationService`
  - 因而工作流、调试页、设备实时页面应共享同一套页面状态与 patch 语义

### 5.2 节点是能力投影，不是第二套真相源

`CapabilityNodeRegistry` 的职责是：

- 暴露平台节点
- 暴露设备节点
- 提供节点 schema
- 提供设备维度的可用性过滤

但它不负责发明新的能力语义。

节点类型示例：

- `device.control`
- `device.page.render`
- `device.section.patch`
- `device.page.switch`
- `platform.audio.play`
- `platform.tts`
- `platform.stt`

这些节点本质上都是统一能力事实在工作流中的执行投影。

设备输出节点收敛原则：

- 设备命令统一走 `device.control`
- 不再保留与设备命令语义重复的 helper node 作为正式能力入口
- 如 RGB、重启、亮度等设备输出，仅通过命令 schema 和 contract 派生

### 5.3 工作流节点目录

工作流编辑器唯一节点目录接口为：

- `GET /api/v1/sdui/workflows/nodes`

该接口应只返回统一节点 schema，不再返回另一套手工拼装的：

- `node-types`
- `actions`
- `sectionTypes`
- 编辑器私有能力树

如果节点需要展示设备支持状态，应通过节点 schema 的设备支持标记表达，而不是再定义独立目录。

### 5.4 工作流定义校验

工作流定义校验分两步：

1. 静态校验
2. 设备绑定校验

静态校验检查：

- JSON 结构是否合法
- `nodeType` 是否存在
- 必填参数是否存在
- DAG/触发器引用是否完整

设备绑定校验检查：

- 当前设备是否支持该 `nodeType`
- `device.control` 引用的命令是否受支持
- 页面/Section 节点引用的类型与页面是否有效

### 5.5 触发器模型

触发器仍可保留：

- `manual`
- `cron`
- `webhook`
- `device.ui.event`
- `device_command`
- `device_message`

但与终端相关的触发器元数据必须来自统一能力层：

- `device.ui.event` 事件选择来源于 `capabilities/{deviceId}/events`
- `device_command` 命令选择来源于设备命令能力

---

## 6. 终端运行时设计

### 6.1 为什么需要独立运行时

如果页面渲染和 Section patch 逻辑散落在：

- `WorkflowService`
- `ActionExecutor`
- 各个 device node

会带来三个问题：

- 代码重复
- 页面状态和实际显示容易分叉
- 容易形成 Bean 循环依赖

因此需要独立运行时服务。

### 6.2 页面运行时

页面运行时负责：

- 根据 `WorkflowDefinition` 查找目标页面
- 将 `PageDef + variables + triggerPayload` 转成 `SectionScene`
- 维护 `activePage`
- 通过 `SectionOrchestrationService` 下发页面

当前对应：

- `WorkflowPageRuntimeService`

工作流主服务和页面节点都应调用它，而不是彼此直接依赖。

同一套 page-first 语义在调试侧也通过 [`DEBUG_SECTION_API.md`](DEBUG_SECTION_API.md) 对外暴露，不再单独维护一套“仅调试用的 section 模型”。

### 6.3 Section Patch 运行时

Section patch 运行时负责：

- 解析 `pageId + sectionId`
- 校验 patch 目标 section 是否存在，必要时解析其 `sectionType`
- 将结构化数据转为 `SectionPatch`
- 调用 `sendPatch`

关键约束：

- `sectionType` 在 `add` 操作时必须显式提供；`update/remove` 可由已知页面状态推断
- 不允许根据 payload 猜 Section 类型

### 6.4 命令运行时

命令运行时负责：

- 命令默认值填充
- 命令参数校验
- 协议路由解析
- 真实下发和 ACK 处理

当前对应：

- `CommandService`
- `CommandSchemaRegistry`
- `SduiCapabilityService.resolveRoute()`

---

## 7. 当前落地状态

截至当前代码状态，已经完成的收敛包括：

- 工作流旧专用动作已移除，设备行为统一经 `node` 进入
- `/api/v1/sdui/workflows/node-types` 已删除
- `device.control` 已成为统一设备命令节点
- 页面能力已进入 `device.page.render`
- 页面切换已统一为 `device.page.switch`
- Section 更新已进入 `device.section.patch`
- `device.section.patch` 已与调试 patch 语义对齐：显式给 `pageId + sectionId`，`add` 显式给类型，`update/remove` 可复用已有 section 类型
- 页面渲染运行时已从工作流主服务中独立出 `WorkflowPageRuntimeService`
- `capabilities/{deviceId}` 与 `capabilities/{deviceId}/tree` 已统一为 resolved contract 语义
- 旧能力树已降级为 debug-only 内部视图
- 设备输出入口已收敛到 `device.control`

仍需继续完善的点包括：

- 将更多 UI 构建逻辑从 `ActionExecutor` 下沉到终端运行时层
- 让节点 schema 更彻底地复用统一能力元数据
- 继续收紧设备节点可用性判断和参数约束

---

## 8. 后续演进方向

后续演进优先级建议如下：

### 8.1 第一优先级

- 继续拆分 UI 运行时，减少 `ActionExecutor` 中的页面和 Section 构建逻辑
- 收紧节点 schema 与能力层的映射关系

### 8.2 第二优先级

- 将工作流设备节点的参数元数据完全建立在统一能力目录之上
- 让 `workflows/nodes` 成为能力投影后的唯一编排入口

### 8.3 第三优先级

- 进一步统一命令、页面、Section 的运行时审计与执行记录
- 为终端运行时增加更清晰的失败分类与可观测性输出

---

## 9. 结论

SDUI 后端的核心不是“前端怎么方便接”，而是“终端能力如何只定义一次，并在调试、编排、运行时中保持一致”。

因此本设计的最终目标是：

- 能力层统一描述终端事实
- 调试层直接操作真实设备
- 编排层只编排统一节点
- 终端运行时层负责真实页面、Section、命令执行

只要这四层边界稳定，前端、调试工具、自动化工作流都会自然建立在同一套终端能力语义之上，不再出现重复建模和职责交叉。
