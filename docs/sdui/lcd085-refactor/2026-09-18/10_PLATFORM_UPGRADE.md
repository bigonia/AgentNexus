# 平台侧升级方案与阶段计划

> 建立日期：2026-09-18
> 基线版本：`v0.9.0-lcd085-pre-refactor`
> 依据文档：[交互模型](01_INTERACTION_MODEL.md)、[系统边界](02_SYSTEM_BOUNDARY.md)、[UI 模型](03_UI_MODEL.md)、[协议模型](04_PROTOCOL_MODEL.md)
> 性质：实施文档。描述平台侧如何对齐重构后的 LCD_085 终端，不是终端设计的复述。

## 1. 目标与范围

终端从"平台逐条遥控"改为"平台下发配置 + 终端本地执行事件对"。平台侧必须从**命令驱动**改为**配置驱动 + 事件驱动**。

本轮平台侧升级范围：

- 协议内核整体替换为统一 request / result / event 模型；
- 新增业务配置（Trigger → Response 绑定）模型与 token 管理；
- 能力上报从"名称列表"升级为"机器可校验 Schema + `capability_hash`"；
- 显示、音频、系统命令全部迁移到统一协议，旧入口在收尾阶段删除；
- 现有 Node Workflow 从"直接下发命令/Section"改为"产出业务配置 + 消费业务交互事件"。

不在本轮范围：音量、亮度、重启、配网的产品行为本身（仅迁移调用协议）；计费、订单等平台业务功能。

## 2. 端云职责映射

| 终端职责（02 §3） | 平台对应能力 | 当前实现 | 目标实现 |
| --- | --- | --- | --- |
| 呈现当前业务内容 | 下发单一主视图 | Section 场景 / Patch | `display.section` / `display.image` / `display.canvas` |
| 采集并解释物理输入 | 接收业务交互事件 | TLV 输入（msgType 9） | `platform.interaction` 事件 + token 反查 |
| 执行本地响应序列 | 预置 Trigger→Response 绑定 | 无（平台逐步下令） | `business.update` 全量配置 |
| 云端耗时处理完成后续驱动 | 以新输入驱动终端 | 命令派发 | `business.trigger(token)` + 新 `display.*` |
| 交换事件、命令、内容、流数据 | 统一控制面 + 数据面 | `cmd/control` + 多套帧类型 | 单一 WebSocket + JSON/Binary 分离 |
| 维护运行状态 | 平台侧不镜像终端微状态 | 大量设备微观状态 | 只保留业务态与连接态 |

关键变化：**平台不再维护"设备执行到第几步"**。平台只维护设备唯一业务配置、业务状态和未完成请求的超时。

## 3. 目标平台架构

```text
                        ┌──────────────────────────────────────┐
   LCD_085 终端  ──WS──▶│  接入层 sdui/v2/session              │
                        │  · 握手与单连接接管                    │
                        │  · 请求路由（by name）                 │
                        │  · 事件上行路由（by name）             │
                        │  · 二进制帧分发                        │
                        └───────────────┬──────────────────────┘
                                        │
        ┌───────────────────────────────┼───────────────────────────────┐
        ▼                               ▼                               ▼
┌───────────────┐               ┌───────────────┐             ┌───────────────┐
│ 能力域         │               │ 业务配置域     │             │ 业务编排域     │
│ capability    │               │ business      │             │ workflow      │
│ · Schema/hash │               │ · 绑定表       │             │ · Node Flow   │
│ · 校验         │               │ · token 注册   │             │ · 产出配置     │
│ · 目录投影     │               │ · reset/update│             │ · 消费交互事件 │
└───────────────┘               └───────┬───────┘             └───────┬───────┘
                                        │                             │
                                ┌───────▼─────────────────────────────▼───────┐
                                │ 请求结果与超时  request lifecycle             │
                                │ 设备唯一音频接收态 / 显示态 / 队列预算         │
                                └─────────────────────────────────────────────┘
```

分层原则：

1. **接入层只做编解码与路由**，不含业务语义，与旧 `MessageRouter` 职责一致但按 `name` 而非 `topic` 分发。
2. **能力域**决定"平台允许配置什么"，是配置校验的唯一依据。
3. **业务配置域**决定"当前设备允许执行什么"，是 token 的唯一权威来源。
4. **业务编排域**（Node Workflow）产出的只是配置与驱动请求，不直接操作设备连接。

## 4. 协议内核（`sdui/v2`）

新建独立包 `com.zwbd.agentnexus.sdui.v2`，与旧 `sdui/*` 在开发期并行，收尾阶段删除旧包。旧协议入口保留但标记 `deprecated`，只用于过渡回归。

### 4.1 三种信封

严格对应协议模型 §5，对 `name` 做显式白名单，不做通用 `type` 分发。

```text
Request  平台→终端  { id, name, body? }
Result   终端→平台  { id, ok, error? }
Event    终端→平台  { name, body? }
```

判定顺序（避免歧义）：含 `id` 且含 `ok` → Result；含 `id` 且含 `name` → Request；含 `name` 无 `id` → Event；否则拒绝。

### 4.2 请求生命周期

平台侧对每个出站请求分配唯一 `id`，登记为未完成请求：

```text
登记(id, deviceId, name, body, deadline)
  → 发送
  → 收到 Result(id, ok) → 完成 / 失败
  → 超时 → TIMEOUT（不自动重放）
  → 连接被新连接接管 → 旧连接未完成请求按超时语义处理
```

对应终端"整个请求只返回一次最终结果"的约定：平台侧不存在多步中间态。

### 4.3 二进制通道

`BinaryFrameCodecV2` 采用新的最小帧头，替代旧 16 字节头：

| 字段 | 字节 | 说明 |
| --- | ---: | --- |
| magic `0x414E`（"AN"） | 2 | 与旧 `0x5344` 区分，旧帧在新通道一律拒绝 |
| version | 1 | v2 = `2` |
| dataType | 1 | `1` 音频 / `2` 图片 / `3` Canvas / `4` Schema / `5` 资源 |
| payload length | 4 | 小端 |
| payload | N | 数据本体 |

首期**不使用** `stream_id`、分片序号、确认位点（与协议模型 §7、§8 一致），靠"同类数据不并发"约束时序。每个 `dataType` 在单设备单方向上同一时刻只允许一个活动生命周期；无活动生命周期时收到对应数据直接丢弃并计数告警。

### 4.4 大小与背压

- 出站单帧 payload 上限 `sdui.v2.binary.max-frame-bytes`，超限拒绝发送并返回 `frame_too_large`。
- 出站队列按设备维度有界，控制消息优先于 Canvas 与遥测；队列满时 `display.canvas.*` 直接丢帧保留最新，控制消息返回 `queue_full`。

## 5. 能力 Schema 与 hash

### 5.1 上报模型

终端上报机器可校验 Schema（协议模型 §4）。平台侧对应模型：

```text
CapabilitySchemaV2 {
  protocolVersion, schemaVersion, board,
  triggers[],        // 支持的 Trigger 源：button.* / platform.trigger.*
  actions[],         // name, params[], usableIn(binding|request|both), limits
  surface { screen, ui{}, image{}, canvas{} },
  audio { formats[], limits{} }
}
```

`capability_hash` 由终端在构建时生成并随握手携带；平台按 hash 缓存，未命中才请求完整 Schema 并二次校验。

> hash 算法与编码方式在文档中未定义，平台侧按 §[12_DESIGN_NOTES](12_DESIGN_NOTES.md) 的处理原则临时实现。

### 5.2 配置校验

`business.update` 的绑定表必须针对当前生效 Schema 校验：Trigger 存在且允许配置、Response 动作存在、参数类型与范围合法、序列长度有上限。校验不通过整体拒绝，不部分应用。

## 6. 业务配置模型

### 6.1 绑定表

```text
BusinessConfig {
  deviceId,
  configVersion,          // 平台自增，终端原样保存
  triggers: [
    {
      triggerId,          // 例如 button.ok / platform.trigger
      source,             // physical | platform
      token?,             // source=platform 时必填，平台生成
      responses: [ { name, params } ]   // 有限、有序、不可编程
    }
  ]
}
```

约束（对应交互模型 §2、§3）：

- 序列长度上限、动作白名单由平台配置项约束；
- 不提供绑定级 `add` / `modify` / `delete`，只有全量替换；
- `platform.trigger` 的 `token` 由平台生成并唯一指向一个绑定。

### 6.2 token 服务

平台维护 `token → (deviceId, bindingRef, businessContext)` 双向表：

```text
出站 token   platform.trigger(token)   平台生成，终端匹配后执行
入站 token   interaction report       终端原样回传，平台反查业务上下文
```

两类 token 结构可以相同但注册表分离，避免把上报 token 误当作触发器。

### 6.3 reset / update / trigger

| 语义 | 平台动作 |
| --- | --- |
| `business.reset` | 下发请求，成功后清理本地绑定的业务态；不清理连接、能力缓存、系统命令期望值 |
| `business.update` | 先对 Schema 校验全量配置，再下发；成功后替换本地绑定的业务态；不隐含 reset |
| `business.trigger(token)` | 按 token 查绑定存在性后下发；仅接收一次性最终结果 |
| 业务切换 | 平台按 `reset → update` 两步顺序编排 |

### 6.4 重连与接管

新连接接管后（协议模型 §2、§3）：

```text
接管成功
  → 旧连接标记失效，其后续上行消息全部忽略
  → 若设备当前有业务配置 → 幂等重发 business.update
  → 能力 hash 未知或校验失败 → 进入能力同步阶段，暂不下发业务配置
```

不使用 `boot_id`：平台不区分网络重连与终端重启，两者都幂等重发完整配置。

## 7. 显示、音频与系统命令

### 7.1 显示

| 请求 | 对应主视图 | 说明 |
| --- | --- | --- |
| `display.section` | 单 Section | 全量替换；不提供 Patch |
| `display.image.begin` → binary(type=2) → `display.image.end` | 全屏图片 | 调色板索引矩阵，RGB565 调色板 |
| `display.canvas.open` → binary(type=3)×N → `display.canvas.close` | Matrix Canvas | 会话内不增量改调色板 |

三种主视图互斥：平台侧在发出任一新主视图请求前，先关闭本地记录的上一主视图会话状态。

### 7.2 音频

```text
下行 audio.start → binary(type=1) → audio.stop | audio.abort
上行 audio.start → binary(type=1) → audio.stop | audio.abort(reason)
```

平台为每设备维护唯一音频接收态；`buffer_full` 上报按异常终止处理；平台超时后标记异常终止并决定部分数据取舍。不实现分片确认与重传。

### 7.3 系统命令

`system.volume.set` / `system.brightness.set` / `system.reboot` / `system.provisioning.start` 全部走统一 request/result。平台维护期望值，终端不持久化；是否需要重连后重新下发由平台决定。

## 8. 与 Node Workflow 的对接

工作流仍是平台的复杂业务编排层，但输出形态改变：

| 环节 | 现状 | 目标 |
| --- | --- | --- |
| 设备侧入口 | 工作流直接触发命令 / 下发 Section | 工作流产出 `BusinessConfig` 并下发 |
| 输入 | TLV 输入事件（含 pageId / sectionId） | `platform.interaction` 事件（token 反查） |
| 中间态 | 工作流运行时保有多步设备状态 | 终端只报交互结果，不报执行细节 |
| UI | Section 场景 + 模板 | 收敛为 5 类 Section + 图片 + Canvas |

映射方式：一个部署（deployment）对应设备当前的一份 `BusinessConfig`；工作流节点产出配置片段，部署时合并为全量配置；`platform.interaction` 事件按 token 反查回工作流运行上下文，作为新的输入驱动后续节点。

### 8.1 节点下沉判据（P5a 实现）

文档只规定了"终端执行本地响应序列"，没有定义工作流节点如何变成响应动作。P5a 的落法是：

一个输出节点成为 `ResponseStep` 的条件（三者同时满足）：

1. **参数全静态**：不含 `{"$ref": "nodeId"}` 形态的运行时绑定；
2. **不依赖平台产物**：不需要 TTS 文本、音频 artifact、Section / UI 模板渲染；
3. **终端声明支持**：目标动作在能力 Schema 的 `actions[]` 中，且 `usableIn` 含 `binding`。

响应序列的边界：

- 按执行计划逐条下沉；遇到第一个**同 slot 且不可下沉**的节点就停止，后续节点全部留给平台（它们的输入依赖平台算出来的结果）；
- **跨 slot** 节点不下沉，但**不截断**本 slot 的静态前缀——它属于另一台设备，不需要本设备提供输入；
- 只要存在平台步骤，就在序列尾部追加一个 `platform.interaction.report` 动作（token 由平台注入），让终端在本地序列执行完后告知平台继续云端流程；没有平台步骤时不追加，保持纯本地闭环。

**业务动态行为不进响应序列。** 响应动作全部静态，同一动作的业务差异由平台签发**不同 token** 表达。因此配置里不存在条件、分支或参数表达式，终端也不需要理解业务语义。

未下沉的节点被登记为 `platformSteps`，交由 P5b 在收到交互上报后执行。

### 8.2 交互回流与运行时接管（P5b 实现）

P5b 把工作流运行时从"听终端输入事件（TLV）后逐条下发设备命令"改成"听业务交互上报后执行平台步骤"。

**闭环落点。**

```text
终端本地执行静态响应序列
  → platform.interaction 事件（device_id + token）
  → BusinessConfigService 按 token 反查 contextRef，发出 BusinessInteraction 领域事件
  → NodeWorkflowRuntimeService.onInteraction：解析 contextRef → 定位活动部署与触发节点
  → 读出该设备、该触发节点下固化的 platformSteps，逐条执行
  → 结果作为**新的独立请求**下发（display.section / audio.*）
```

01§4 明确"下发结果是新的独立命令，不是原响应序列的继续"，因此平台步骤不是把响应序列接下去，而是一次全新的下行。

**上下文怎么恢复（`contextRef`）。** 终端只上报 `device_id + token`，平台必须自己找回"是哪一次部署的哪个触发节点"。做法是 token 在注册时携带 `contextRef`，形如 `wf:<workflowId>:<triggerNodeId>`：

- 为什么不只记 `triggerId`——同一工作流的不同部署可以把同一个物理按钮绑到不同 slot，只凭 `triggerId` 无法区分是哪一次部署在等；
- 为什么不用 `deviceId` 反查——同一设备可同时参与多个工作流的部署，反查结果不唯一；
- 解析失败（例如非工作流场景写入的 `binding:<triggerId>`）一律返回空、安静跳过，不让整条上报链失败。

`contextRef` 只存在于平台侧 token 注册表，不进 `BusinessConfig` 的下行报文——终端不感知也不需要理解它。

**平台步骤从哪来。** 来自部署时固化到 `NodeWorkflowDeploymentEntity.businessConfigs` 的 `platformSteps`，运行时读取而非按当前能力 Schema 重新组装。理由：设备侧配置在部署那一刻已经生效，平台侧必须与那一份严格对应；按当前定义重新组装会在工作流被编辑后引入漂移。

**平台步骤的两种出口。**

| 类别 | 节点 | 出口 |
| --- | --- | --- |
| 平台可作为请求下发 | `display.section`、`ui.update`、`audio.play`（TTS / artifact） | 走 v2 下行：`display.section` 或 `audio.start → binary → audio.stop` |
| 平台不可作为请求下发 | `rgb.effect`、`audio.record` | 能力 Schema 只声明 `usableIn=binding`，平台无请求可发；如实返回 `terminal_action_required`，**不**退化成旧协议遥控路径 |

第二类不是实现缺陷，而是模型的结果：动作所有权交给了终端的本地响应序列。要覆盖它需要"平台为一个动态节点生成专用绑定并签发 token"的能力，而终端设计文档没有定义这种绑定的形态，登记为缺口 G25 并列入待确认项 T15。

**单 Section 收敛点。** v2 没有 Section 级增量更新，而平台侧的业务写法里仍有 Section 场景与 Patch。收敛发生在唯一一处 `SectionViewResolver`：把"页面 → 多 Section"取首页单 Section 作为主视图；Patch 在平台侧合成完整 Section 后再下发，平台没有快照时回退为下发完整场景。业务侧统一经 `PrimaryViewPublisher` 出口，不再各自调用编排服务。

**v2 接入层的设备租户上下文（P1–P4 遗漏的真实缺陷）。** 平台数据隔离靠 `@TenantId`，租户来自线程上的 `GlobalContext`；v2 WebSocket 线程没有 HTTP 上下文，租户退化成 `default`。而设备表 `sdui_device` 是**全局表**（用 `ownerUserId` 列记归属），部署 / 运行 / UI 上下文才是租户表——于是同一线程上"读设备成功、读部署查不到"。新增 `DeviceTenantContext` 在路由分发点按设备归属建立租户，作为 v2 侧唯一的租户建立入口。

**旧执行器删除。** `CapabilityNodeExecutorService` 已删除，其职责由 `WorkflowPlatformStepExecutor` 承接。旧实现依赖的 `CommandService`（`cmd/control` + 等 ACK）、`AudioService`（Base64 内联音频）、`SectionOrchestrationService`（scene/patch）在 v2 中都没有对应概念；为等 ACK 而写的重试与状态轮询也不需要了——v2 的请求只接收一次最终结果。

## 9. 分阶段计划

| 阶段 | 内容 | 交付物 | 前置 | 状态 |
| --- | --- | --- | --- | --- |
| P0 | 基线冻结 | tag `v0.9.0-lcd085-pre-refactor`、本组过程文档 | — | 完成 |
| P1 | 协议内核 | `sdui/v2` 信封编解码、请求生命周期、连接接管、二进制帧头、新端点 | 无 | 完成 |
| P2 | 能力域 | `CapabilitySchemaV2`、hash 缓存与协商、配置校验器 | P1 | 完成 |
| P3 | 业务配置域 | 绑定表模型、token 服务、reset/update/trigger | P2 | 完成 |
| P4 | 业务面迁移 | display.*（Section/图片/Canvas）、audio.*、system.* | P3 | 完成 |
| P5a | 工作流产出业务配置 | 节点→动作映射与下沉判定、按设备组装 `BusinessConfig`、部署下发 | P4 | 完成 |
| P5b | 交互事件回流 | `platform.interaction` 驱动工作流运行、消费组装产出的 `platformSteps`、以新 token 驱动终端 | P5a | 完成 |
| P5c | 旧协议路径剪除 | 删除 §10 清单，平台侧只剩一套协议 | P5b + 终端全量切换 | 未开始 |

**P5 为何拆成三阶段**：原计划把"工作流对接"与"旧路径删除"放在同一步。但旧路径删除的准入条件不是代码就绪，而是**终端固件全量切到 v2**——在那之前删除会让在线设备立刻失联。同时工作流侧本身包含"产出配置"与"消费事件"两个方向，可独立验收。因此按"产出 → 回流 → 剪除"三道闸门拆分，详见 [12_DESIGN_NOTES.md](12_DESIGN_NOTES.md) §4.8。

**0.12.0：不做灰度，v2 是唯一协议。** 原 P5a 里的设备级分流开关（`DeviceProtocolRouter` + `sdui.routing`）已删除。本项目不做"新旧协议并存期按设备分流"的渐进切换，所有设备的业务配置一律按 v2 组装下发。代价是旧路径删除不再有"把某台设备按回旧协议"的回退手段，见 12_DESIGN_NOTES.md §4.12。

阶段准入原则：每阶段必须可编译、有针对性测试、并且不破坏上一阶段已验收行为。终端未就绪期间，`sdui/v2` 通过内置的**模拟终端**（测试用 stub）验证，不依赖真实设备。当前验证基线：`mvn test` 323 项通过。

各阶段的具体功能项与逐条状态见 [功能清单与实现台账](11_FEATURE_MATRIX.md)。

## 10. 旧路径剪除清单（P5c 执行）

**准入条件**：终端固件全量切换到 v2。分流开关已在 0.12.0 删除，因此**没有按设备回退的手段**——旧路径删除只能一次性完成，且必须在终端切换之后。在那之前删除任何一项都会让在线设备失联。

**当前状态**：清单内各类已统一加上 `@Deprecated(since = "0.10.0")` 与替代项说明，代码保持可用。P5b 完成后，平台侧运行时（工作流续接、主视图下发、音频下行）已全部走 v2。但**旧协议栈并非已无调用者**：控制层（`controller` / `debug`）仍按旧能力与事件模型暴露接口，这些端点间接"保护"着 87 个待删类。逐文件清单、执行顺序与必保边界见 [12_DESIGN_NOTES.md](12_DESIGN_NOTES.md) §7「待清除模块清单」与 §4.18 的引用图复核。

| 旧入口 | 位置 | 处置 |
| --- | --- | --- |
| `cmd/control` / `cmd/control_ack` | `SduiProtocolConstants`、`CommandDispatcher`、`SduiControlAckHandler` | 删除 |
| TLV 输入事件（msgType 9） | `BinaryProtocolCodec`、`EventInputHandler` | 删除 |
| 旧 16 字节帧头 | `BinaryProtocolCodec` | 由 v2 帧头替代 |
| `SECTION_SCENE` / `SECTION_PATCH` | `SectionOrchestrationService`、codec | 由 `display.*` 替代 |
| Base64 音频 | `AudioService` 相关分支 | 删除 |
| Section 14 类 | `sdui-event-catalog.yml`、`SectionTypeCatalog` | 收敛为 5 类 |
| 能力名称上报 | `CapabilitiesReportHandler`、`CapabilitySnapshotParser` | 由 Schema + hash 替代 |
| 旧 WS 端点 `/ws/sdui`、`/` | `WebSocketConfig`、`SduiWebSocketHandler`、`MessageRouter` | 删除，只留 `/ws/sdui/v2` |

**规模（0.13.0 引用图实测）**：`sdui` 主代码 217 个 = v2 51 + 非 v2 166，非 v2 中 72 个属保留包。以真实 import / 类型引用建图后判定：**v2 对非 v2 的硬依赖只有 6 个类**（`SectionScene` / `SectionPatch` / `SectionData` / `SectionEntry` / `SectionDataCodec` / `SduiDeviceRepository`）；**无需改动任何保留方即可整文件删除的只有 6 个 + 1 处部分删除**；另有 87 个类须先裁剪控制层端点才能删除。`sdui` 之外 58 个主代码文件对 sdui 非 v2 类的引用数为 0，剪除不会外溢。

这再次说明：P5c 不是"删几个文件"，而是**先裁掉控制层对旧模型的暴露，再自上而下移除整块旧协议簇**。执行清单与顺序见 12_DESIGN_NOTES.md §7，复核脚本为 `scripts/sdui-refgraph/refgraph.py`（每次裁剪后重跑即可得到当刻清单）。

## 11. 待确认

逐项清单独立维护于 `docs/sdui/terminal/TERMINAL_CONFIRMATIONS.md`（T1–T16，含平台侧当前假设、改动代价与建议确认批次）。本节只列与本方案直接相关的三类，不维护副本。

- 握手字段 `protocol_version` / `capability_hash` 的承载位置（连接参数或首条消息）→ **T1、T2**；
- 业务配置的准确 JSON 结构、上限值与错误码集合 → **T4、T5**；
- 二进制帧头布局与 `dataType` 取值 → **T3**（协议内核的入站前置，风险最高）。

**已定的取舍，不作为待确认项**：终端尚未实现能力时的降级策略。分流开关已于 0.12.0 删除，不保留"按设备走旧协议"这一降级路径——v2 是唯一协议，切换只能一次性完成（见 12_DESIGN_NOTES.md §4.12）。
