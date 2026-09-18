# 客户端接口契约（闭环接口集）

本文定义平台对外暴露的**唯一一套**接口，按"闭环"组织：从一个设备上线到一次交互被消费，每一步都有确定的接口可调，不存在只服务于内部调试的旁路。

- **权威契约**是运行后的 OpenAPI（`/swagger-ui.html`）。本文定义**接口集合与语义边界**——哪些接口应当存在、各自回答闭环里的哪个问题。
- 设备侧协议面（终端 ↔ 平台）不在本文，见 `docs/sdui/lcd085-refactor/2026-09-18/04_PROTOCOL_MODEL.md`。本文只覆盖**前端与管理端**使用的 HTTP 面。
- 接口集的取舍判据只有一条：**它是否在闭环的某一步上承担不可替代的职责**。不承担的，删除；与其他接口重复的，合并。

## 1. 闭环

平台侧只有一套协议（v2），闭环如下：

```
① 设备上线        WS /ws/sdui/v2 + 握手(protocol_version, capability_hash)
② 能力核验        平台比对 hash：已知 → 复用；未知 → 要求终端上传 Schema
③ 工作流编排      前端读取板型/设备能力目录 → 组装工作流定义
④ 部署            平台把节点产出组装成「本地响应序列 + token 表 + 平台步骤」
                  → business.config 下发设备 → 设备 ACK
⑤ 终端本地执行    终端按静态响应序列就地响应（不打扰平台）
⑥ 交互上报        终端上报 platform.interaction(token)
⑦ 平台续接        平台按 token 恢复上下文 → 执行平台步骤 → 以新 token 下发新命令
⑧ 观测            运行记录、请求记录、事件流
```

闭环上"平台必须能回答的问题"与接口的对应关系：

| 步骤 | 前端要回答的问题 | 接口 |
| --- | --- | --- |
| ①② | 设备在线吗？能力是什么？同步到哪一步了？ | `/devices`、`/capabilities` |
| ③ | 这块板子/这台设备能做哪些动作、有哪些触发源？ | `/capabilities/{id}/actions`、`/board-types/{board}` |
| ④ | 这份工作流能不能部署？会下沉什么、拒绝什么？ | `/node-workflows/{id}/deployments/validate`、`.../inspect` |
| ⑤⑥ | 设备上此刻挂着哪些 token？ | `/devices/{id}/bindings` |
| ⑦ | 这次交互平台做了什么？ | `/node-workflows/{id}/runs/{runId}` |
| ⑧ | 现在正在发生什么？ | `.../events/stream`、`.../requests/stream` |
| 调试 | 我要绕过工作流直接让设备做一件事 | `/debug/{id}/request` |

## 2. 接口集

约定：全部返回 `ApiResponse<T>`（SSE 除外）；`deviceId` 为设备 id；`board` 为板型标识。

### 2.1 设备域 `/api/v1/sdui/devices`

设备台账、认领、遥测、连接历史。**能力只读投影，不做命令派发。**

| 方法 | 路径 | 职责 |
| --- | --- | --- |
| GET | `` | 设备列表，支持 `status` / `registrationStatus` / `board` / `search` / 排序 / 分页 |
| GET | `/unclaimed` | 未认领设备 |
| GET | `/{deviceId}` | 设备详情：在线态、能力摘要、最近请求、最新遥测 |
| POST | `/{deviceId}/claim` | 认领 |
| PATCH | `/{deviceId}` | 改名 / 备注 |
| DELETE | `/{deviceId}` | 删除 |
| GET | `/{deviceId}/telemetry` | 遥测分页 |
| GET | `/{deviceId}/telemetry/trends` | 遥测趋势 |
| GET | `/{deviceId}/connection-log` | 连接历史与断连原因统计 |
| GET | `/{deviceId}/bindings` | **当前生效的业务配置**：触发绑定、token 表、平台步骤（步骤 ⑤⑥ 的唯一出口） |

**在线态的唯一来源**是 v2 连接注册表（`DeviceConnectionRegistry`）。设备详情里的能力摘要来自 v2 Schema，不再有第二套能力快照。

### 2.2 能力域 `/api/v1/sdui/capabilities`

回答"这台设备现在能做什么"。全部由 v2 能力 Schema 与 hash 同步状态驱动。

| 方法 | 路径 | 职责 |
| --- | --- | --- |
| GET | `/catalog` | 全平台能力概览（已缓存 Schema 数、板型聚合） |
| GET | `/{deviceId}` | 能力摘要：同步状态、Schema hash、板型、动作数、触发源数、能力面 |
| GET | `/{deviceId}/schema` | **完整 Schema**（协议/Schema 版本、triggers、actions、surface） |
| GET | `/{deviceId}/actions` | 动作目录，含 `usableIn`（`binding` / `request`）与参数约束 |
| GET | `/{deviceId}/triggers` | 可配置触发源目录，含 `maxResponses` |
| GET | `/{deviceId}/sync` | 同步状态：hash 是否已知、是否等待终端上传 Schema、失败原因 |

`usableIn` 是前端必须尊重的分界线：只声明 `binding` 的动作**平台发不出请求**，只能出现在工作流的本地响应序列里。想在调试面板手动触发它，接口会如实返回"需终端本地执行"，而不是伪造一条下行。

### 2.3 板型域与节点目录 `/api/v1/sdui/board-types`

未绑定具体设备时，按板型回答同一组问题。实现方式：解析出一个该板型的代表设备，复用能力域。

| 方法 | 路径 | 职责 |
| --- | --- | --- |
| GET | `` | 板型列表（设备数、在线数） |
| GET | `/{board}` | 板型详情 |
| GET | `/{board}/actions` | 动作目录（= 代表设备的 `/actions`） |
| GET | `/{board}/triggers` | 触发源目录 |
| GET | `/{board}/capability-nodes` | 工作流编辑器的节点目录（按当前页面解析可用节点） |
| GET | `/{board}/section-triggers` | 工作流编辑器的三级触发树：Section → 元素 → 事件 |

节点目录另有一个设备级同构入口：

| 方法 | 路径 | 职责 |
| --- | --- | --- |
| GET | `/api/v1/sdui/capability-nodes/{deviceId}` | 设备级节点目录（支持 `pageId` / `pageJson`） |

两者的分工是**可用性精度**：板型级回答"这个型号理论上能做哪些节点"，设备级回答"这台设备此刻能做哪些节点"。节点是否可用最终取决于该设备已上报的能力 Schema，所以**设备已绑定时前端必须走设备级**——板型级只是没有具体设备时的近似，用它编排出的工作流可能包含该设备实际不支持的动作，部署时会被组装校验拒绝。

`capability-nodes` 与 `section-triggers`（含设备级同构入口）服务于步骤 ③ 的可视化编排，不是设备协议的一部分。

`/section-triggers` 的数据源与设备协议版本**无关**（2026-09-18 复核，原判断已修正）：三级树的输入是平台页面定义（`PageService` / `SectionPageDefinition`）与平台 Section 类型目录（`SectionTypeCatalog`，由 `sdui-event-catalog.yml` 驱动），两者都是平台侧产物。它不可能"改从 v2 能力 Schema 的 `surface.ui` 派生"——`UiSpec` 只声明允许的 Section 类型名，不含元素与事件定义；同理，Section 类型的能力门禁归 `CapabilitySchemaV2` 的校验职责，本端点不再判断第二处（见 `v2.display.SectionViewResolver` 类注释）。因此它**不阻塞 P5c，也无需改写**。前端当前未调用它，属编排面板尚未接线。

### 2.4 事件目录与校验 `/api/v1/sdui/events`

| 方法 | 路径 | 职责 |
| --- | --- | --- |
| GET | `/catalog` | 全局目录：触发器、动作、Section 类型、物理输入 |
| GET | `/triggers` | 触发源目录 |
| GET | `/actions` | 动作目录 |
| GET | `/sections` | Section 类型目录 |
| POST | `/validate` | 校验一段绑定/响应序列能否被终端接受（干跑） |

`/validate` 与部署接口共用**同一个校验器**。不复制校验规则：两处真值必然漂移。

### 2.5 工作流域 `/api/v1/sdui/node-workflows`

编排与部署，覆盖步骤 ③④⑦。

| 方法 | 路径 | 职责 |
| --- | --- | --- |
| POST | `` | 新建工作流 |
| GET | `` | 列表 |
| GET | `/{workflowId}` | 定义详情 |
| PUT | `/{workflowId}` | 更新定义 |
| DELETE | `/{workflowId}` | 删除 |
| POST | `/validate` | 校验定义本身（结构、必填、悬空引用） |
| GET | `/management/overview` | 运维总览 |
| GET | `/management/deployments` | 全部部署 |
| GET | `/management/devices/{deviceId}/deployments` | 某设备的部署 |
| POST | `/management/devices/{deviceId}/primary-ui` | 指派主视图 |
| GET | `/management/devices/{deviceId}/primary-ui` | 读主视图指派 |
| DELETE | `/management/devices/{deviceId}/primary-ui` | 取消指派 |
| POST | `/management/deployments/{deploymentId}/devices/{deviceId}/primary-ui` | 按部署指派主视图 |
| GET | `/management/conflicts` | 同 slot 冲突 |
| GET | `/management/runs/recent` | 最近运行 |
| POST | `/{workflowId}/deployments` | 部署 |
| POST | `/{workflowId}/deployments/validate` | 部署前校验（合成配置能否被终端接受） |
| POST | `/{workflowId}/deployments/inspect` | 组装巡检：逐节点给出"下沉 / 拒绝 + 理由" |
| GET | `/{workflowId}/deployments` | 部署列表 |
| GET | `/{workflowId}/deployments/{deploymentId}` | 部署详情 |
| GET | `/{workflowId}/deployments/{deploymentId}/config` | **固化的业务配置**（triggers + token 表 + platformSteps） |
| DELETE | `/{workflowId}/deployments/{deploymentId}` | 停止部署 |
| POST | `/{workflowId}/deployments/{deploymentId}/test-trigger` | 模拟一次交互上报，跑通步骤 ⑥⑦ |
| GET | `/{workflowId}/deployments/{deploymentId}/runs` | 该部署的运行记录 |
| GET | `/{workflowId}/runs/{runId}` | 运行详情 |
| GET | `/{workflowId}/runs/{runId}/artifacts` | 该次运行产出的资源 |

`inspect` 的存在理由：配置里没有某个节点时，必须能查到**为什么不在**。静默省略是不可接受的。

### 2.6 UI 模板域 `/api/v1/sdui/ui-templates`

| 方法 | 路径 | 职责 |
| --- | --- | --- |
| POST | `` | 新建模板 |
| GET | `` | 列表 |
| GET | `/{templateId}` | 详情 |
| PUT | `/{templateId}` | 更新 |
| DELETE | `/{templateId}` | 删除 |
| POST | `/{templateId}/preview` | 预览（可选推送到设备） |

### 2.7 资源域 `/api/v1/sdui`

| 方法 | 路径 | 职责 |
| --- | --- | --- |
| GET | `/artifacts/{artifactId}` | 产物元数据 |
| GET | `/artifacts/{artifactId}/blob` | 产物二进制 |
| GET | `/devices/{deviceId}/artifacts/latest` | 某设备最新产物 |

### 2.8 调试域 `/api/v1/sdui/debug`

绕过工作流，直接对设备下达 v2 请求。**这是调试入口，不是第二条业务通道**：它走的仍是 `PlatformRequestService`，与工作流续接同一路径。

| 方法 | 路径 | 职责 |
| --- | --- | --- |
| POST | `/{deviceId}/request` | 下达 v2 请求：`{name, params}`。按 `name` 前缀分派到 display / audio / system / business |
| POST | `/{deviceId}/view` | 下发主视图（单 Section 完整替换，或 image / canvas） |
| GET | `/{deviceId}/view/state` | 当前显示会话状态（模式、已收字节、拒帧数） |
| DELETE | `/{deviceId}/view/state` | 清空显示会话 |
| GET | `/{deviceId}/requests` | 请求目录（动作 + 参数约束，含 `usableIn=request` 过滤） |
| GET | `/{deviceId}/requests/history` | 请求历史 |
| GET | `/{deviceId}/requests/{requestId}` | 单条请求详情 |
| GET | `/{deviceId}/requests/stream` | 请求结果 SSE |
| GET | `/{deviceId}/events/stream` | 终端事件 SSE |
| POST | `/{deviceId}/node-tests/input` | 输入节点测试 |
| POST | `/{deviceId}/node-tests/output` | 输出节点测试 |
| GET | `/{deviceId}/node-tests/{testId}` | 测试结果 |
| GET | `/{deviceId}/sessions` | 活动调试会话 |
| GET | `/{deviceId}/sessions/{sessionId}` | 单个会话 |
| GET | `/{deviceId}/artifacts/{artifactId}` | 调试产物元数据 |
| GET | `/{deviceId}/artifacts/{artifactId}/blob` | 调试产物二进制 |

`/request` 与业务路径的差别只有"谁决定动作"：调试是人选，业务是工作流算出来的。动作可达性、参数校验、结果语义完全一致——否则调试通过而业务失败，调试就没有意义。

## 3. 与旧接口集的差异

| 旧接口 | 处置 | 理由 |
| --- | --- | --- |
| `/devices/{id}/commands` | 删除 | 与能力域 `/actions` 重复，且混装了旧命令表与平台能力两个来源 |
| `/capabilities/{id}/tree` | 删除 | 与 `/capabilities/{id}` 内容重复 |
| `/capabilities/{id}/metadata` | 删除 | 旧调试视图，闭环无此步 |
| `/capabilities/{id}/events` | 改为 `/triggers` | 语义从"终端事件名"变为"可绑定触发源" |
| `/capabilities/{id}/sections` | 删除 | 旧 Section 协商已废弃；UI 能力在 Schema 的 `surface.ui` |
| `/capabilities/{id}/commands` | 改为 `/actions` | 以 `usableIn` 明确可达性 |
| `/capabilities/{id}/protocol` | 删除 | 协议版本属握手语义，不在设备详情里重复 |
| `/board-types/{board}/commands` | 改为 `/actions` | 同上 |
| `/board-types/{board}/sections` | 删除 | 同上 |
| `/board-types/{board}/events` | 改为 `/triggers` | 同上 |
| `/events/commands`、`/events/sections` 校验器 | 合并进 `/events/validate` | 校验规则只存一处 |
| `/events/inbound-events` | 删除 | 旧 TLV 入站事件目录，v2 无此概念 |
| `/debug/{id}/command` | 改为 `/request` | 走 v2 请求路径 |
| `/debug/{id}/commands*` | 改为 `/requests*` | 命令表已不是闭环的一环 |
| `/debug/{id}/section` | 改为 `/view` | v2 无 Section 增量 Patch，只有完整替换 |
| `/debug/{id}/section/patch` | 删除 | v2 模型不存在 Patch |
| `/debug/{id}/section/state` | 改为 `/view/state` | 语义对齐显示会话 |
| `/debug/node-workflows/**` | 删除 | 与 `/node-workflows/**` 逐一重复（create / deploy / 详情 / 停止 / test-trigger / runs），无独立职责；前端从未调用 |
| `/capability-nodes/{deviceId}` | **保留** | 曾被标注为"前端未使用"，实际编辑器与节点测试页都在调用。设备级节点目录是步骤 ③ 的必要入口，已补入 §2.3 |

**命名原则**：路径用 v2 词汇（`actions` / `triggers` / `view` / `requests` / `bindings`），不复用旧协议的 `commands` / `sections`。路径里出现旧词，就一定有人往回接旧模型。

## 4. 约束

- **单一在线态来源**：`DeviceConnectionRegistry`。
- **单一能力来源**：v2 能力 Schema。不存在第二份能力快照。
- **单一校验器**：`BusinessConfigValidator`。
- **单一出站路径**：`PlatformRequestService`。业务下发与调试下发同路。
- **前端不感知 token**：token 只在"设备当前挂载什么"这一可观测视角里出现，不进入任何请求参数。
- 二进制产物一律经 `/blob` 出口，不内联进 JSON。
