# Node Workflow 后端完整交互流程

> 本文档描述当前已实现的 **NodeWorkflow（DAG 能力节点工作流）** 系统的完整闭环交互、核心 API 及数据流。
> 状态机（StateMachine）层在设计文档中有规划但尚未实现，当前工作流引擎基于 DAG 拓扑排序的事件驱动模型。

---

## 1. 系统分层总览

```
┌─────────────────────────────────────────────────────────┐
│  L5  REST API 层                                        │
│  NodeWorkflowController / DebugController /             │
│  DeviceController / BoardTypeController / ...           │
├─────────────────────────────────────────────────────────┤
│  L4  工作流运行时                                        │
│  NodeWorkflowRuntimeService (事件驱动 DAG 执行引擎)       │
│  CapabilityNodeExecutorService (节点命令分发)             │
├─────────────────────────────────────────────────────────┤
│  L3  工作流管理                                          │
│  NodeWorkflowService (CRUD)                             │
│  NodeWorkflowDeploymentService (部署/槽位绑定)            │
│  NodeWorkflowManagementService (运维概览)                 │
├─────────────────────────────────────────────────────────┤
│  L2  协议 & 路由层                                       │
│  MessageRouter → TopicHandler / BinaryFrameHandler       │
│  EventInputHandler → EventPayload 标准化                  │
│  EventRegistry → 事件 ID 解析 & 校验                      │
│  CapabilityRegistry → 设备能力动态学习                     │
├─────────────────────────────────────────────────────────┤
│  L1  传输层                                              │
│  WebSocket (ws://host:8080/ws/sdui)                     │
│  BinaryProtocolCodec (UI3 二进制帧)                       │
│  DeviceSessionManager (会话管理)                          │
└─────────────────────────────────────────────────────────┘
```

---

## 2. 核心 API 一览

### 2.1 工作流定义 CRUD — `NodeWorkflowController`

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/v1/sdui/node-workflows` | 创建工作流定义 |
| `GET` | `/api/v1/sdui/node-workflows` | 列表查询 |
| `GET` | `/api/v1/sdui/node-workflows/{id}` | 获取单个定义 |
| `PUT` | `/api/v1/sdui/node-workflows/{id}` | 更新定义 |
| `DELETE` | `/api/v1/sdui/node-workflows/{id}` | 删除定义 |
| `POST` | `/api/v1/sdui/node-workflows/validate` | 校验定义（不保存） |

### 2.2 工作流部署 & 运行 — `NodeWorkflowController`

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/v1/sdui/node-workflows/{id}/deployments` | **部署工作流**：将槽位绑定到真实设备 |
| `POST` | `/api/v1/sdui/node-workflows/{id}/deployments/validate` | 部署前校验 |
| `POST` | `/api/v1/sdui/node-workflows/{id}/deployments/inspect` | 部署前预览（dry-run） |
| `GET` | `/api/v1/sdui/node-workflows/{id}/deployments` | 查询某工作流的所有部署 |
| `GET` | `/api/v1/sdui/node-workflows/{id}/deployments/{depId}` | 获取部署详情 |
| `DELETE` | `/api/v1/sdui/node-workflows/{id}/deployments/{depId}` | **停止/卸载部署** |
| `POST` | `/api/v1/sdui/node-workflows/{id}/deployments/{depId}/test-trigger` | **手动触发**（调试用） |
| `GET` | `/api/v1/sdui/node-workflows/{id}/deployments/{depId}/runs` | 查询部署的运行历史 |
| `GET` | `/api/v1/sdui/node-workflows/{id}/runs/{runId}` | 获取某次运行详情 |
| `GET` | `/api/v1/sdui/node-workflows/{id}/runs/{runId}/artifacts` | 获取运行产物（音频等） |

### 2.3 运维管理 — `NodeWorkflowController`

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/v1/sdui/node-workflows/management/overview` | 全局概览（统计、冲突检测） |
| `GET` | `/api/v1/sdui/node-workflows/management/deployments` | 所有活跃部署 |
| `GET` | `/api/v1/sdui/node-workflows/management/devices/{deviceId}/deployments` | 某设备的所有部署 |
| `GET` | `/api/v1/sdui/node-workflows/management/conflicts` | 冲突检测（同设备同事件的多个部署） |
| `GET` | `/api/v1/sdui/node-workflows/management/runs/recent` | 最近运行记录 |

#### Overview 返回字段

`overview` 返回聚合统计：`workflowCount`、`deploymentCount`、`activeDeploymentCount`、`runCount`、`failedRunCount`、`conflictCount`，以及 `latestRun` / `latestFailedRun` 摘要。

单个部署详情返回：`workflowName`、`deviceStatuses`（各槽位绑定设备的在线状态）、`lastRun`、`lastStatus`、`lastError`、`runCount`、`failedRunCount`。

冲突检测规则：两个活跃部署在同一个 `deviceId + eventId + (重叠或空的 nodeId)` 上同时监听，即判定为 `blocking_conflict`。

### 2.4 调试 API — `DebugController`

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/v1/sdui/debug/{deviceId}/command` | 直接向设备发送命令 |
| `POST` | `/api/v1/sdui/debug/{deviceId}/section` | 推送 UI Scene |
| `POST` | `/api/v1/sdui/debug/{deviceId}/section/patch` | 推送 UI Patch |
| `GET` | `/api/v1/sdui/debug/{deviceId}/events/stream` | **SSE 订阅设备事件流** |
| `GET` | `/api/v1/sdui/debug/{deviceId}/commands/stream` | **SSE 订阅命令生命周期流** |
| `POST` | `/api/v1/sdui/debug/{deviceId}/node-tests/input` | 输入能力节点测试 |
| `POST` | `/api/v1/sdui/debug/{deviceId}/node-tests/output` | 输出能力节点测试 |

### 2.5 设备 & 能力 API

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/v1/sdui/devices` | 设备列表（分页、筛选、排序） |
| `GET` | `/api/v1/sdui/devices/{deviceId}` | 设备详情（能力、命令、遥测） |
| `POST` | `/api/v1/sdui/devices/{deviceId}/claim` | 认领设备 |
| `GET` | `/api/v1/sdui/board-types` | 板卡类型列表 |
| `GET` | `/api/v1/sdui/board-types/{board}/events` | 某板卡的可用事件（触发器候选） |
| `GET` | `/api/v1/sdui/board-types/{board}/commands` | 某板卡的可用命令 |
| `GET` | `/api/v1/sdui/board-types/{board}/sections` | 某板卡的可用 Section 类型 |
| `GET` | `/api/v1/sdui/events/catalog` | 全局事件目录（编辑器用） |
| `GET` | `/api/v1/sdui/capability-nodes/{deviceId}` | 设备能力节点目录 |

---

## 3. 核心数据模型

### 3.1 工作流定义 (NodeWorkflowDefinition)

```
workflow
├── id, name
├── slots[]           ← 虚拟终端角色槽位
│   ├── slotId
│   ├── board         ← 要求的板卡类型
│   ├── displayName
│   └── requiredCapabilities[]
├── nodes[]           ← DAG 节点
│   ├── nodeId
│   ├── slotId        ← 归属哪个槽位
│   ├── nodeType      ← button.trigger | section.trigger | rgb.effect | audio.play | audio.record | ui.update | display.section
│   └── params        ← 节点配置参数
├── edges[]           ← 有向边 (from → to)
│   ├── from           ← 源节点 ID
│   └── to             ← 目标节点 ID
└── uiTemplates[]     ← 初始 UI 模板
```

### 3.2 部署实例 (NodeWorkflowDeploymentEntity)

```
deployment
├── workflowId
├── slotBindings{}    ← { "slotId": "real-device-001" }
├── status            ← active | stopped | replaced
└── createdAt
```

### 3.3 运行记录 (NodeWorkflowRunEntity)

```
run
├── workflowId, deploymentId
├── triggerEvent      ← 触发事件载荷
├── status            ← running | completed | failed
├── context           ← 运行时上下文（JSON）
└── steps[]           ← 各节点执行结果
```

---

## 4. 完整闭环流程

### 4.1 闭环一：设备按钮 → 触发工作流 → 执行动作 → 反馈到设备

```
                        ┌──────────────────────┐
                        │   1. 用户编排工作流     │
                        │   POST /node-workflows │
                        │   { slots, nodes,      │
                        │     edges }            │
                        └──────────┬─────────────┘
                                   │
                        ┌──────────▼─────────────┐
                        │   2. 部署工作流          │
                        │   POST /{id}/deployments│
                        │   { slotBindings:       │
                        │     {btn1:"dev-001"} }  │
                        └──────────┬─────────────┘
                                   │
    ═══════════════════════════════╪═══════════════════════════════
    ▎ 运行时闭环                   │                              ▎
    ═══════════════════════════════╪═══════════════════════════════
                                   │
    ┌──────────┐                   │
    │  ESP32   │  buttonA 短按      │
    │ 终端设备  │───────────────────┼──────────────────────────────┐
    └──────────┘   WebSocket        │                              │
                   Binary Frame     │                              │
                   (msgType=9)      │                              │
                                    │                              │
                        ┌───────────▼──────────────┐               │
                        │ SduiWebSocketHandler      │               │
                        │ handleBinaryMessage()     │               │
                        └───────────┬──────────────┘               │
                                    │                              │
                        ┌───────────▼──────────────┐               │
                        │ MessageRouter             │               │
                        │ routeBinaryMessage()      │               │
                        │ → BinaryProtocolCodec     │               │
                        │   .decode(rawFrame)       │               │
                        └───────────┬──────────────┘               │
                                    │                              │
                        ┌───────────▼──────────────┐               │
                        │ EventInputHandler         │  msgType=9    │
                        │ .handle(session, frame)   │               │
                        │                           │               │
                        │ ① 解析 TLV → EventPayload │               │
                        │   eventName="short_press" │               │
                        │   eventKind=4 (button)    │               │
                        │   nodeId="pwr"            │               │
                        │                           │               │
                        │ ② EventRegistry           │               │
                        │   .normalizePayload()     │               │
                        │   "short_press"           │               │
                        │   → "input:buttons.pwr.   │               │
                        │      short_press"         │               │
                        │                           │               │
                        │ ③ 通知 PayloadEventListener│              │
                        └───────────┬──────────────┘               │
                                    │                              │
                    ┌───────────────┼───────────────┐              │
                    │               │               │              │
            ┌───────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐      │
            │EventStream   │ │NodeWorkflow │ │(未来)       │      │
            │Service       │ │RuntimeSvc   │ │StateMachine │      │
            │(SSE→浏览器)   │ │.onEvent()   │ │EventBridge  │      │
            └──────────────┘ └──────┬──────┘ └─────────────┘      │
                                    │                              │
                        ┌───────────▼──────────────┐               │
                        │ NodeWorkflowRuntimeService│              │
                        │                           │               │
                        │ ④ 遍历活跃部署             │               │
                        │   triggerMatches()        │               │
                        │   匹配: deviceId + eventId │               │
                        │         + nodeId          │               │
                        │                           │               │
                        │ ⑤ executionPlan()         │               │
                        │   拓扑排序：从触发节点      │               │
                        │   BFS 可达的输出节点       │               │
                        │                           │               │
                        │ ⑥ 按拓扑序执行每个节点     │               │
                        │   → CapabilityNodeExecutor │              │
                        └───────────┬──────────────┘               │
                                    │                              │
                        ┌───────────▼──────────────┐               │
                        │ CapabilityNodeExecutor    │               │
                        │                           │               │
                        │ 按 nodeType 分发：         │               │
                        │ • rgb.effect →             │               │
                        │   CommandService           │               │
                        │   .dispatchCommand()       │               │
                        │ • audio.play →             │               │
                        │   TTS / WAV 播放           │               │
                        │ • ui.update →              │               │
                        │   SectionOrchestration     │               │
                        │   .sendScene/Patch()       │               │
                        └───────────┬──────────────┘               │
                                    │                              │
                        ┌───────────▼──────────────┐               │
                        │ SduiProtocolService       │               │
                        │ .sendControlCommand()     │               │
                        │ .sendSectionScene()       │               │
                        └───────────┬──────────────┘               │
                                    │                              │
                        ┌───────────▼──────────────┐               │
                        │ DeviceSessionManager      │               │
                        │ .sendMessage()            │               │
                        │ .sendBinaryFrame()        │               │
                        └───────────┬──────────────┘               │
                                    │                              │
    ┌──────────┐                    │   WebSocket                  │
    │  ESP32   │ ◄──────────────────┘   Text/Binary Frame          │
    │ 终端设备  │  收到命令 (rgb/section/audio)                      │
    │          │  执行 → 渲染 UI / 亮灯 / 播放音频                  │
    │          │───────────────────┐                               │
    └──────────┘   ACK 回执        │                               │
                   (控制确认/二进制ACK)                              │
                                    │                              │
                        ┌───────────▼──────────────┐               │
                        │ SduiControlAckHandler     │               │
                        │ or AckBinaryHandler       │               │
                        │ → CommandService          │               │
                        │   .handleControlAck()     │               │
                        │   更新命令状态 ACKED       │               │
                        │ → CommandResultStreamSvc  │               │
                        │   推送 SSE 给浏览器        │               │
                        └──────────────────────────┘               │
    ═══════════════════════════════════════════════════════════════
```

### 4.2 闭环二：命令调度生命周期

```
浏览器/API 发起命令
        │
        ▼
CommandService.dispatchCommand(deviceId, action, value)
        │
        ├─ ① 判断命令类型
        │   ├── 平台命令 (audio.prompt.play, audio.tts.speak)
        │   │   → 本地处理，直接标记 ACKED
        │   ├── 特殊路由命令 (rgb.off, rgb.effect.set)
        │   │   → 显式 action 映射
        │   └── 通用设备命令
        │       → CommandDispatcher.dispatch()
        │
        ├─ ② 持久化 SduiDeviceCommand (status=SENT)
        │
        ├─ ③ 发布 CommandLifecycleEvent (command.dispatch)
        │   → CommandResultStreamService
        │       ├── SSE → 浏览器订阅者
        │       └── CommandResultListener → (未来)StateMachineCommandEventBridge
        │
        ▼
CommandDispatcher.dispatch()
        │
        ├─ SduiCapabilityService.resolveRoute()
        │   语义命令 → 传输 topic + action
        │
        ├─ 生成 cmdId (UUID)
        │
        ▼
SduiProtocolService.sendControlCommand()
        │
        ▼
DeviceSessionManager.sendMessage()
        │
        ▼  WebSocket TextMessage
   ┌─────────┐
   │  ESP32  │  执行命令...
   └─────────┘
        │
        │  ACK (异步回执)
        ▼
SduiControlAckHandler (topic: "control/ack")
        │
        ▼
CommandService.handleControlAck(cmdId, status, reason)
        │
        ├─ 更新 SduiDeviceCommand → ACKED / REJECTED
        │
        └─ 发布 CommandLifecycleEvent (command.ack / command.rejected)
            → CommandResultStreamService → SSE 推送

定时任务 (每 3 秒):
CommandService.markTimedOutCommands()
  超过 10 秒仍为 SENT 状态的命令 → TIMEOUT
  → 发布 command.timeout 事件
```

### 4.3 闭环三：UI 场景推送

```
SectionOrchestrationService.sendScene(deviceId, scene)
        │
        ├─ ① SduiCapabilityService 适配
        │   过滤设备不支持的 Section 类型
        │   根据 sizeClass 选择 RICH / COMPACT 模式
        │
        ├─ ② SectionSceneBuilder 序列化
        │   SectionScene → JSON (含 12 种 SectionData 类型)
        │
        ├─ ③ 记住页面状态 (pageStatesByDevice)
        │
        ├─ ④ 通知 SectionTriggerHook（预留状态机集成点）
        │
        ▼
SduiProtocolService.sendSectionScene()
        │
        ├─ ProtocolMapper.mapSectionScene()
        │   JSON → msgType=15 二进制帧
        │
        ▼
DeviceSessionManager.sendBinaryFrame()
        │
        ▼  WebSocket BinaryMessage
   ┌─────────┐
   │  ESP32  │  渲染 UI 页面
   └─────────┘

Patch 流程类似 (msgType=16)，支持增量更新单个 section。
```

---

## 5. 事件标准化链路

设备原始输入到标准化事件 ID 的完整解析链：

```
设备固件发送:
  TLV 120 (eventKind)  = 4       (button)
  TLV 121 (nodeId)     = "pwr"   (电源按钮)
  TLV 122 (eventName)  = "short_press"
  TLV 123 (sectionId)  = "action_1"
  TLV 124 (pageId)     = "main"
  TLV 125 (ts)         = 1719000000000
  TLV 126 (value)      = ""

        │
        ▼
EventInputHandler.handle()
  → EventPayload.fromBinaryInput()
    解析为标准 EventPayload 记录

        │
        ▼
EventRegistry.normalizePayload()
  查找 sdui-event-catalog.yml 中匹配的 transport 信息:
    - protocol: ui3_binary
    - eventKind: 4
    - eventName: short_press
  → 解析为: "ui:action.click" (section 交互事件)

        │
        ├─ 同时
        ▼
CapabilityRegistry.resolveInputEventId()
  查找 capability-catalog.yml 中匹配的物理输入:
    - input: buttons.pwr
    - event: short_press
    - eventKind: 4
  → 解析为: "input:buttons.pwr.short_press" (物理输入事件)

        │
        ▼
两份事件 ID 都注入 EventPayload，供下游消费者选择匹配:
  - NodeWorkflowRuntimeService 用 eventId 匹配触发器
  - EventStreamService 将两份 ID 都推送给浏览器
```

---

## 6. 工作流运行时执行模型

```
NodeWorkflowRuntimeService.onEvent(payload)
        │
        ├─ ① 遍历所有 active 部署
        │     deploymentRepository.findByStatus(ACTIVE)
        │
        ├─ ② 对每个部署，检查触发器匹配
        │     triggerBindings = extractTriggers(workflow, deployment)
        │     for each triggerBinding:
        │       if eventMatches(binding.eventId, payload.eventId)
        │       && binding.deviceId == payload.deviceId
        │       && (binding.nodeId == null || binding.nodeId == payload.nodeId)
        │         → MATCH!
        │
        ├─ ③ 冲突检测
        │     同一设备 + 同一事件 + 重叠 nodeId
        │     有多个部署监听 → 记录冲突警告
        │
        ├─ ④ 创建 NodeWorkflowRunEntity
        │     初始化运行上下文 context = { $event, $nodes:{}, $sessions:{}, $ui:{} }
        │
        ├─ ⑤ 拓扑排序：executionPlan(workflow, triggerNodeId)
        │     BFS 从触发节点出发，沿边遍历所有可达输出节点
        │     返回有序列表 (保证依赖在前)
        │
        ├─ ⑥ 按序执行每个节点
        │     for node in executionPlan:
        │       params = NodeWorkflowParameterResolver.resolve(node.params, context)
        │       result = CapabilityNodeExecutorService.execute(node, params, deployment)
        │       context.$nodes[node.nodeId] = result
        │       if result affects UI:
        │         context.$ui[slot.deviceId] = updatedUIState
        │
        └─ ⑦ 运行结束
              更新 NodeWorkflowRunEntity (completed/failed)
              持久化各 NodeWorkflowRunStepEntity
```

---

## 7. 关键设计决策

### 7.1 Slot 抽象：定义引用槽位，部署绑定真实设备

```
定义时:  slot "btn_device" → board "ESP32-S3-BUTTON"
         slot "screen_device" → board "ESP32-S3-LCD"

部署时:  { "btn_device": "dev-001", "screen_device": "dev-002" }

同一个工作流定义可以部署到不同设备组合。
```

### 7.2 双目录设计：EventRegistry + CapabilityRegistry

| | EventRegistry | CapabilityRegistry |
|---|---|---|
| 数据来源 | `sdui-event-catalog.yml` (静态) | `capability-catalog.yml` + 设备上报 (动态) |
| 内容 | 所有已知事件的定义、schema、分类 | 设备实际支持的能力、命令、物理输入 |
| 职责 | 事件 ID 解析、别名映射、校验 | 设备能力查询、物理输入→事件 ID 解析 |
| 更新方式 | 启动时加载 | 运行时动态学习 |

### 7.3 观察者模式连接工作流与事件系统

```
EventInputHandler (被观察者)
  └── List<PayloadEventListener>
        ├── EventStreamService      ← SSE 推送给浏览器
        ├── NodeWorkflowRuntimeService ← 触发工作流 DAG 执行
        └── (未来) StateMachineEventBridge

CommandResultStreamService (被观察者)
  └── List<CommandResultListener>
        └── (未来) StateMachineCommandEventBridge
```

### 7.4 节点类型体系

| 节点类型 | 类别 | 说明 |
|----------|------|------|
| `button.trigger` | Trigger | 物理按钮/体感事件触发器 |
| `section.trigger` | Trigger | Section 交互事件触发器（action/toggle/list/nav/overlay 点击） |
| `rgb.effect` | Action | 设置 LED 灯光效果 |
| `audio.play` | Action | TTS 播放 / WAV 播放 |
| `audio.record` | Session | 录音（有生命周期和内部状态机） |
| `ui.update` | UI | 修改 UI 上下文 |
| `display.section` | UI | 直接控制 section 渲染 |

**section.trigger 匹配规则：** 三层匹配 — `eventId`（suffix 匹配，如 `action.click` 匹配 `ui:action.click`）→ `nodeId`（可选，空则通配）→ `sectionId`（可选，空则匹配任意同类 section）。`section.trigger` 节点由 `CapabilityNodeCatalogService` 根据设备支持的交互式 section 类型自动生成，前端编辑器从能力目录中拉取。

---

## 8. 与设计文档的差异

当前实现 (`NodeWorkflow`) vs 设计文档 (`CAPABILITY_NODE_WORKFLOW_DESIGN.md`) 的差异：

| 方面 | 设计文档 | 当前实现 |
|------|----------|----------|
| 节点类型 | 6 类（Trigger/Action/Transform/Session/UI/Control） | 简化版 6 种具体类型 |
| 上下文引用 | `$nodes.x.y` 表达式 | 支持 `$reference` 语法，NodeWorkflowParameterResolver 解析 |
| UI 上下文 | 每个角色独立 `$ui`，自动 diff | 通过 SectionOrchestrationService 直接发送 scene/patch |
| 会话节点 | 完整生命周期状态机 | audio.record 有内部状态管理 |
| Transform 节点 | STT、HTTP、数据映射 | 未实现 |
| Control 节点 | 条件、延迟、分支 | 未实现 |
| 状态机集成 | 状态机作为底层能力 | 状态机未实现，工作流直接调用 SectionOrchestrationService |

---

## 9. 快速参考：curl 示例

```bash
# 创建工作流定义
curl -X POST http://localhost:8080/api/v1/sdui/node-workflows \
  -H "Content-Type: application/json" \
  -H "X-Space-Id: default" \
  -d '{
    "name": "按钮控制RGB",
    "slots": [{"slotId":"btn","board":"ESP32-S3-BUTTON","displayName":"按钮终端"}],
    "nodes": [
      {"nodeId":"t1","slotId":"btn","nodeType":"button.trigger","params":{"event":"short_press","nodeId":"pwr"}},
      {"nodeId":"a1","slotId":"btn","nodeType":"rgb.effect","params":{"mode":"blink","r":255,"g":0,"b":0}}
    ],
    "edges": [{"from":"t1","to":"a1"}]
  }'

# 部署（绑定真实设备）
curl -X POST http://localhost:8080/api/v1/sdui/node-workflows/{workflowId}/deployments \
  -H "Content-Type: application/json" \
  -H "X-Space-Id: default" \
  -d '{"slotBindings":{"btn":"dev-001"}}'

# 查看运维概览
curl http://localhost:8080/api/v1/sdui/node-workflows/management/overview \
  -H "X-Space-Id: default"

# 订阅设备事件流 (SSE)
curl -N http://localhost:8080/api/v1/sdui/debug/{deviceId}/events/stream \
  -H "X-Space-Id: default"

# 手动触发测试
curl -X POST http://localhost:8080/api/v1/sdui/node-workflows/{workflowId}/deployments/{deploymentId}/test-trigger \
  -H "Content-Type: application/json" \
  -H "X-Space-Id: default" \
  -d '{"eventId":"input:buttons.pwr.short_press","deviceId":"dev-001"}'

# 使用 section.trigger 的工作流定义（点击 action 按钮 → 触发 RGB 灯光）
curl -X POST http://localhost:8080/api/v1/sdui/node-workflows \
  -H "Content-Type: application/json" \
  -H "X-Space-Id: default" \
  -d '{
    "name": "Section点击控制RGB",
    "slots": [{"slotId":"screen","board":"ESP32-S3-LCD","displayName":"屏幕终端"}],
    "nodes": [
      {"nodeId":"t1","slotId":"screen","nodeType":"section.trigger","params":{"eventId":"action.click","sectionId":"action_1"}},
      {"nodeId":"a1","slotId":"screen","nodeType":"rgb.effect","params":{"mode":"blink","r":0,"g":255,"b":0}}
    ],
    "edges": [{"from":"t1","to":"a1"}]
  }'
```
