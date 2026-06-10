# SDUI 工作流 — 业务实现说明

本文档面向前端开发者，说明 SDUI 工作流模块的业务模型、流转方式和闭环实现路径。不追求 API 字段级完整度 — 字段细节请参阅 [`WORKFLOW_API_REFERENCE.md`](WORKFLOW_API_REFERENCE.md)。

---

## 1. 核心概念

### 1.1 什么是 SDUI 工作流

SDUI 工作流是一个**设备绑定的、事件驱动的自动化编排引擎**。它的本质是：

> 当「某件事发生」时，按「预先定义的方式」对「目标设备」执行一连串动作。

这句话拆成三个核心对象：

| 对象 | 对应概念 | 说明 |
|------|---------|------|
| **触发器 (Trigger)** | "某件事发生" | 定时、Webhook、设备按键、设备消息、手动触发 |
| **动作 (Action)** | "一连串动作" | 调用能力节点、发 HTTP 请求、条件分支、设置变量 |
| **工作流实例 (Instance)** | "对目标设备" | 每个设备独立运行，拥有自己的变量空间和执行状态 |

### 1.2 核心名词表

| 名词 | 说明 |
|------|------|
| **WorkflowDefinition** | 工作流定义模板，包含 triggers、actions、pages、edges。类比"蓝图"。 |
| **WorkflowInstance** | 定义绑定到具体设备后的运行实例。每个 (deviceId, definitionId) 最多一个。 |
| **Trigger** | 触发器，决定"什么时候执行"。有 6 种类型。 |
| **Action** | 动作，描述"执行什么"。有 5 种类型，其中 `node` 是主力。 |
| **Node (CapabilityNode)** | 统一能力节点，是动作的执行单元。分 device / platform / flow_control 三类。 |
| **PageDef** | 页面定义，描述推送到设备屏幕上的 UI 布局和 Section 列表。 |
| **SectionBindDef** | Section 绑定定义，声明一个 section 的类型和变量绑定关系。 |
| **EdgeDef** | DAG 边，定义动作节点之间的执行依赖（from → to）。 |
| **Variable** | 工作流变量，通过 `$data.xxx` 引用，在节点间流转。 |

### 1.3 架构分层

工作流处于 SDUI 四层架构中的**编排层**，上下各有一层：

```
能力层 (capabilities/*)
  ↓ 提供「设备支持什么」
编排层 (workflows/*)          ← 本文档范围
  ↓ 编排节点、调度触发器
终端运行时层 (SectionOrchestrationService, CommandService)
  ↓ 真正执行下发
设备
```

编排层**不定义**设备能力，只**消费**能力层提供的事实。

---

## 2. 业务流转全景

一个工作流从创建到产生实际效果，经历五个阶段：

```
 ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
 │ 1. 定义   │ → │ 2. 绑定   │ → │ 3. 触发   │ → │ 4. 执行   │ → │ 5. 反馈   │
 │ Definition│    │ Bind     │    │ Trigger  │    │ Execute  │    │ Feedback │
 └──────────┘    └──────────┘    └──────────┘    └──────────┘    └──────────┘
```

### 阶段 1 — 定义 (Definition)

在前端编辑器中设计工作流蓝图：

1. 调用 `GET /workflows/nodes?deviceId=xxx` 获取可用节点目录
2. 调用 `GET /workflows/triggers?deviceId=xxx` 获取可用触发器目录
3. 调用 `GET /workflows/page-editor?deviceId=xxx` 获取页面编辑器元数据
4. 用户拖拽编排后，调用 `POST /workflows/definitions/scaffold` 生成骨架，或直接 `POST /workflows/definitions` 保存
5. 可选：调用 `POST /workflows/definitions/validate` 做静态校验

### 阶段 2 — 绑定 (Bind)

将定义加载到具体设备上运行：

1. 前端调用 `POST /workflows/devices/{deviceId}/bind?definitionId=xxx&mode=strict`
2. 后端做三层检查：
   - **能力校验**：设备是否支持 workflow 引用的所有 nodeType / event / command
   - **冲突检测**：是否有其他已运行 workflow 占用了相同的 page/section 槽位
   - **模式决策**：strict（全匹配）/ adaptive（跳过 optionals）/ force（强制加载）
3. 校验通过后：触发器注册 → 首页渲染推送 → 实例持久化

绑定模式说明：

| mode | 行为 | 适用场景 |
|------|------|---------|
| `strict` | 任何能力不匹配即拒绝 | 正式部署 |
| `adaptive` | 跳过标记为 optional 的不匹配项 | 多机型适配 |
| `force` | 忽略所有不匹配，强制加载 | 调试/降级 |

### 阶段 3 — 触发 (Trigger)

六种触发器类型：

| 类型 | type 字段值 | 说明 | 典型场景 |
|------|-----------|------|---------|
| 手动触发 | `manual` | 前端/API 主动调用 | 调试、管理面板一键操作 |
| 定时触发 | `cron` | 按固定间隔或 cron 表达式 | 定时刷新、定时关屏 |
| Webhook | `webhook` | 外部 HTTP POST 触发 | 第三方系统集成 |
| 设备UI事件 | `device.ui.event` | 设备端按键、触摸等 | 按键翻页、长按关机 |
| 设备命令 | `device_command` | 匹配终端下发的指令 | 语音指令、终端输入 |
| 设备消息 | `device_message` | 其他系统/设备发来的消息 | 跨设备联动 |

### 阶段 4 — 执行 (Execute)

触发后，后端按以下路径执行：

```
Trigger 触发
  ↓
查找匹配的 actions（通过 triggerId 关联）
  ↓
┌─ 有 edges 定义? ─→ DAG 模式：按拓扑排序分层并行执行
│  无 edges 定义? ─→ Sequential 模式：按列表顺序串行执行
  ↓
每个 Action 分发：
  ├─ node → 查找 CapabilityNode → 解析变量 → 构建 NodeContext → 执行
  ├─ fetch → 发 HTTP 请求 → 结果存入 $data.xxx
  ├─ condition → 判断条件 → 走 then/else 分支
  ├─ sequence → 依次执行子步骤
  └─ set_variable → 直接设置变量值
  ↓
变量变更 → VariableWatcher 自动重绑受影响的 Section
  ↓
执行记录持久化到 ExecutionRecord
```

### 阶段 5 — 反馈 (Feedback)

执行结果通过以下渠道反馈：

- **前端轮询**：`GET /workflows/devices/{deviceId}/{definitionId}/status`
- **执行历史**：`GET /workflows/devices/{deviceId}/{definitionId}/executions`
- **变量变化**：`GET /workflows/devices/{deviceId}/{definitionId}/variables`
- **设备UI变化**：Device 通过 WebSocket 收到新的 SectionScene/SectionPatch，屏幕更新

---

## 3. 核心 API 速览

前端最常用的 API 按使用场景分组：

### 3.1 编辑器场景

| 方法 | 路径 | 用途 |
|------|------|------|
| `GET` | `/api/v1/sdui/workflows/nodes?deviceId=xxx` | 获取可用节点目录（含 schema、参数定义） |
| `GET` | `/api/v1/sdui/workflows/triggers?deviceId=xxx` | 获取可用触发器目录 |
| `GET` | `/api/v1/sdui/workflows/page-editor?deviceId=xxx` | 获取页面编辑器元数据（layouts、sectionTypes） |
| `POST` | `/api/v1/sdui/workflows/definitions/scaffold` | 将编辑器的 graph JSON 转为 WorkflowDefinition |
| `POST` | `/api/v1/sdui/workflows/definitions/validate` | 校验定义（静态检查 + DAG 检查） |
| `GET` | `/api/v1/sdui/workflows/definitions/{id}/editor-model` | 从已有定义反向构建编辑器模型 |

### 3.2 定义管理

| 方法 | 路径 | 用途 |
|------|------|------|
| `GET` | `/api/v1/sdui/workflows/definitions` | 列表 |
| `GET` | `/api/v1/sdui/workflows/definitions/{id}` | 详情 |
| `POST` | `/api/v1/sdui/workflows/definitions` | 创建 |
| `PUT` | `/api/v1/sdui/workflows/definitions/{id}` | 更新 |
| `DELETE` | `/api/v1/sdui/workflows/definitions/{id}` | 删除 |

### 3.3 设备绑定与运行

| 方法 | 路径 | 用途 |
|------|------|------|
| `POST` | `/api/v1/sdui/workflows/devices/{deviceId}/bind` | 绑定工作流到设备 |
| `DELETE` | `/api/v1/sdui/workflows/devices/{deviceId}/{definitionId}` | 解绑 |
| `POST` | `/api/v1/sdui/workflows/devices/{deviceId}/{definitionId}/pause` | 暂停 |
| `POST` | `/api/v1/sdui/workflows/devices/{deviceId}/{definitionId}/resume` | 恢复 |
| `POST` | `/api/v1/sdui/workflows/devices/{deviceId}/trigger/{triggerId}` | 手动触发 |

### 3.4 运行时监控

| 方法 | 路径 | 用途 |
|------|------|------|
| `GET` | `/api/v1/sdui/workflows/devices/{deviceId}` | 列出设备上所有运行的工作流 |
| `GET` | `/api/v1/sdui/workflows/devices/{deviceId}/status` | 设备整体工作流状态 |
| `GET` | `/api/v1/sdui/workflows/devices/{deviceId}/{definitionId}/status` | 单个工作流状态 |
| `GET` | `/api/v1/sdui/workflows/devices/{deviceId}/{definitionId}/variables` | 查看工作流变量 |
| `GET` | `/api/v1/sdui/workflows/devices/{deviceId}/{definitionId}/executions` | 执行记录 |
| `GET` | `/api/v1/sdui/workflows/devices/{deviceId}/triggers` | 触发器状态 |

### 3.5 Webhook 集成

| 方法 | 路径 | 用途 |
|------|------|------|
| `POST` | `/api/v1/sdui/workflows/webhook/{path}` | 外部系统调用，触达绑定的设备工作流 |

---

## 4. 业务闭环实现

### 4.1 场景：定时自动刷新仪表盘

**需求**：每 30 秒从后端 API 拉取数据，更新设备屏幕上的 metric section。

**实现步骤**：

1. 创建 WorkflowDefinition，包含：
   - Trigger: `{"type":"cron", "interval":30}`
   - Action (step 1): `{"type":"fetch", "url":"https://api.example.com/dashboard", "save":"dashboard"}`
   - Action (step 2): `{"type":"node", "nodeType":"device.section.patch", "params":{"pageId":"main","sectionId":"metric1","type":"metric_section","bind":{"value":"$data.dashboard.temp"}}}`

2. `POST /workflows/devices/{deviceId}/bind?definitionId=xxx` 绑定到设备

3. 系统自动：每 30 秒 → 拉取数据 → 变量变更 → 自动重绑 → SectionPatch 推送到设备 → 屏幕更新

**闭环要点**：`fetch` 结果存入 `$data.dashboard`，`section.patch` 的 `bind.value` 引用 `$data.dashboard.temp`，变量变化时 `VariableWatcher` 自动触发 section 重绑。

### 4.2 场景：设备按键翻页

**需求**：设备上的物理按键触发页面切换。

**实现步骤**：

1. 设备上报能力时声明支持 `ui:button.press` 事件
2. 创建 WorkflowDefinition：
   - Trigger: `{"type":"device.ui.event", "eventType":"ui:button.press", "nodeId":"btn_next"}`
   - Action: `{"type":"node", "nodeType":"device.page.switch", "params":{"pageId":"page2"}}`

3. 绑定到设备后：用户按设备按钮 → WebSocket 上报事件 → `EventInputHandler` → `WorkflowService.fireEvent()` → 匹配触发器 → 执行 `device.page.switch` → 设备收到新页面 Scene

**闭环要点**：事件上报、触发器匹配、页面切换、设备渲染形成完整闭环。前端可以通过 `GET /workflows/devices/{deviceId}/page-state` 查看当前页面状态。

### 4.3 场景：管理面板手动触发

**需求**：运维人员在管理面板点击按钮，向设备发送一条 TTS 语音。

**实现步骤**：

1. 创建 WorkflowDefinition：
   - Trigger: `{"type":"manual", "id":"tts_trigger"}`
   - Action: `{"type":"node", "nodeType":"platform.tts", "params":{"text":"$data.message"}}`

2. 前端先设置变量 `message`（通过 message injection 接口），然后调用手动触发

3. `POST /workflows/devices/{deviceId}/trigger/tts_trigger` → 执行 TTS → 设备播放语音

**闭环要点**：前端可以在触发后轮询 `GET /executions` 查看执行结果（成功/失败）。

### 4.4 场景：Webhook 集成外部系统

**需求**：外部监控系统通过 Webhook 触发设备报警。

**实现步骤**：

1. 创建 WorkflowDefinition：
   - Trigger: `{"type":"webhook", "id":"alert", "path":"alerts/sensor"}`
   - Action: `{"type":"node", "nodeType":"device.control", "params":{"command":"rgb.effect.set", "params":{"effect":"blink_red"}}}`

2. 绑定后，Webhook URL 为：`POST /api/v1/sdui/workflows/webhook/alerts/sensor`

3. 外部系统 POST 到该 URL → 工作流触发 → 设备红灯闪烁

**闭环要点**：Webhook 的请求 body 会进入 `$trigger` 变量空间，可在 action 中通过 `$trigger.temperature` 等方式引用。

---

## 5. 变量系统

### 5.1 三类变量空间

| 前缀 | 含义 | 生命周期 | 示例 |
|------|------|---------|------|
| `$data.xxx` | 工作流实例变量 | 绑定期间持久化，服务器重启可恢复 | `$data.temperature` |
| `$trigger.xxx` | 本次触发的上下文 | 单次触发有效 | `$trigger.payload.value` |
| `$env.xxx` | 环境变量 | 绑定期间有效 | `$env.API_BASE_URL` |

### 5.2 表达式语法

基于 Spring SpEL，支持：

- 简单引用：`$data.count`, `$trigger.event.type`
- 三元运算：`$data.count > 10 ? 'high' : 'low'`
- 数值运算：`$data.a + $data.b`
- 函数调用：`min($data.x, 100)`, `max($data.y, 0)`
- 数组索引：`$data.items[0].name`

### 5.3 变量在 Section 中的绑定

Section 的 `bind` 字段支持变量表达式：

```json
{
  "id": "temp_display",
  "type": "hero_section",
  "bind": {
    "value": "$data.temperature",
    "label": "'当前温度'",
    "progress": "$data.temperature / 100 * 100"
  }
}
```

绑定后，当 `$data.temperature` 变化时，`VariableWatcher` 自动重新计算 Section 数据并推送 `SectionPatch` 到设备。

---

## 6. 节点 (CapabilityNode) 体系

### 6.1 节点分类

| 类别 | 来源 | 示例 |
|------|------|------|
| **device** | 设备能力投影 | `device.control`, `device.page.render`, `device.page.switch`, `device.section.patch`, `device.audio.play` |
| **platform** | 平台托管能力 | `platform.tts`, `platform.stt`, `platform.llm.chat`, `platform.rag.query` |
| **flow_control** | 流程控制 | `flow.condition`, `flow.fetch`, `flow.loop`, `flow.parallel`, `flow.sequence`, `flow.set_variable` |

### 6.2 节点的设备可见性

- 不带 `deviceId` 调用 `GET /workflows/nodes` 返回 platform + flow_control 全局节点
- 带 `deviceId` 调用 `GET /workflows/nodes?deviceId=xxx` 额外返回该设备支持的 device 节点
- 每个节点 schema 包含 `deviceSupported` 标记：`true` = 该设备支持，`null` = 全局节点不适用

### 6.3 节点参数

节点的 `inputs` 描述了配置时需要填写的参数：

```json
{
  "name": "command",
  "type": "string",
  "required": true,
  "defaultValue": null,
  "description": "要执行的设备命令"
}
```

前端编辑器应根据 `inputs` 动态渲染参数表单。`constraints` 字段可能包含 `options`（下拉选项）、`min`/`max`（数值范围）等约束。

---

## 7. 前端接入路线

### 7.1 最小接入（只看不编）

如果前端只需要展示工作流运行状态，不需要编辑器：

1. `GET /api/v1/sdui/devices/{deviceId}` — 设备详情（含已绑定工作流）
2. `GET /api/v1/sdui/workflows/devices/{deviceId}` — 设备上所有工作流
3. `GET /api/v1/sdui/workflows/devices/{deviceId}/{definitionId}/status` — 单个工作流状态
4. `GET /api/v1/sdui/workflows/devices/{deviceId}/{definitionId}/executions` — 执行历史

### 7.2 编辑器接入

如果前端需要做可视化工作流编辑器：

1. **初始化编辑器**：
   - `GET /workflows/nodes?deviceId=xxx` → 节点面板
   - `GET /workflows/triggers?deviceId=xxx` → 触发器面板
   - `GET /workflows/page-editor?deviceId=xxx` → 页面编辑器

2. **保存工作流**：
   - 将编辑器 graph（nodes + edges + triggers）提交到 `POST /workflows/definitions/scaffold`
   - scaffold 将 graph 转为标准 WorkflowDefinition 并保存

3. **编辑已有工作流**：
   - `GET /workflows/definitions/{id}/editor-model` → 反向构建编辑器模型

4. **校验**：
   - `POST /workflows/definitions/validate` → 静态检查

### 7.3 完整接入路线

```
1. 设备列表 → GET /api/v1/sdui/devices
2. 设备详情 → GET /api/v1/sdui/devices/{deviceId}
3. 能力查看 → GET /api/v1/sdui/capabilities/{deviceId}
4. 编辑器   → GET /workflows/nodes, /triggers, /page-editor
5. 保存定义 → POST /workflows/definitions, /scaffold
6. 绑定设备 → POST /workflows/devices/{deviceId}/bind
7. 监控运行 → GET /workflows/devices/{deviceId}/status
8. 查看历史 → GET /workflows/devices/{deviceId}/executions
```

---

## 8. 常见问题

### Q: 一个设备可以绑定多个工作流吗？
可以。每个工作流独立运行，有自己的变量空间和触发器。但不同工作流不能占用相同的 page/section 槽位（冲突检测会拒绝）。

### Q: 修改定义后，已绑定的实例会自动更新吗？
不会。需要先 `unbind`，再重新 `bind`。这是为了保证运行中实例的稳定性。

### Q: 设备离线后工作流会怎样？
触发器停止调度（cron 停止、webhook 路由保留但无法下发命令）。设备重新上线后，工作流实例状态保留但不会自动恢复触发——当前需要重新 bind。

### Q: 如何调试工作流执行失败？
1. `GET /workflows/devices/{deviceId}/{definitionId}/executions` 查看执行记录中的 `failedNode` 和 `failureReason`
2. `GET /workflows/devices/{deviceId}/{definitionId}/variables` 查看当前变量状态
3. 使用 Debug API (`POST /debug/{deviceId}/command`) 单独测试节点对应的命令是否正常

### Q: DAG 模式和 Sequential 模式有什么区别？
- **Sequential**（默认）：当 `edges` 为空时，actions 按 `triggers` 下 actions 列表的顺序串行执行
- **DAG**：当 `edges` 非空时，按拓扑排序分层执行。同一层的节点并行执行，不同层串行。适合有依赖关系的复杂工作流。
