# 状态机端到端闭环流程

## 概述

状态机闭环：**增量式构建** → 绑定真实设备 → 注入事件触发转换 → 设备页面自动更新。

核心设计：用户只需手动定义初始状态，后续状态由后端根据 transition 的 actions **自动推导**生成，无需重复定义页面内容。

纯 HTTP 接口驱动，无需在平台内写任何测试代码。

## 关键概念：自动推导 (Auto-Derivation)

在旧设计中，用户需要预定义所有 states 的 sections AND 在 transitions 中编写 section.add/update/remove actions——这造成了两层定义互相覆盖的冗余。

新设计中，当添加一个 transition 并指定 `toStateId` 时：

```
fromState 的 pages → 深拷贝 → 应用 section.add/update/remove actions → 得到 derivedPages
                                                                         +
                                                                 context.set → initialContext
                                                                         ↓
                                                               创建/更新 toStateId state
```

- **section 类 actions** 在编辑时即应用，保留 `${ctx.xxx}` 模板变量不做运行时解析
- **context.set** 收集为 `initialContext`，部署时种子化 deployment 的 contextData
- **command.dispatch、http.request** 等非 section 类 action 不参与推导（运行时正常执行）
- 推导出的 state 带有 `derivedFromTransitionId` 标记，可通过 `?at={transitionId}` 查询

## 增量式 API 流程

### 1. 发现设备和板卡类型

```
GET /api/v1/sdui/devices          → 获取在线设备 ID
GET /api/v1/sdui/board-types      → 获取板卡类型 Key
```

### 2. 查询板卡能力

```
GET /board-types/{key}            → 板卡支持的 section、input event、command 清单
GET /board-types/{key}/events     → 可用的入站事件
GET /board-types/{key}/commands   → 可下发的命令及参数 schema
GET /board-types/{key}/sections   → 可用的 section 类型
```

### 3. 创建状态机空壳

```
POST /state-machines
Body: { "name": "...", "boardTypes": ["board:ESP32-S3-..."] }
Response: { "id": "main-xxx", "definition": { "states": [], "transitions": [] } }
```

只需传入 name 和 boardTypes，不需要 definition。后端自动初始化为空定义。

### 4. 添加初始状态

```
POST /state-machines/{id}/states
Body: {
  "id": "idle",
  "label": "Idle Standby",
  "pages": [{
    "pageId": "main",
    "sections": [
      { "sectionId": "hero-title", "sectionType": "hero_section", "fields": {...} },
      { "sectionId": "status-text", "sectionType": "text_section", "fields": {...} }
    ]
  }]
}
```

首个添加的 state 自动成为初始状态（部署时的起始点）。可以继续添加更多 state。

### 5. 添加 Transition（自动推导目标状态）

```
POST /state-machines/{id}/transitions
Body: {
  "fromStateId": "idle",           // 可选 — 见下方解析规则
  "fromTransitionId": "...",       // 可选 — 替代 fromStateId
  "toStateId": "running",          // 目标状态 — 由后端自动推导
  "event": { "eventId": "timer.elapsed" },
  "actions": [
    { "type": "section.update", "sectionId": "hero-title", "fields": { "title": "Processing..." } },
    { "type": "section.add", "sectionType": "progress_section", "sectionId": "progress-bar", "fields": {...} },
    { "type": "context.set", "name": "started", "value": true }
  ]
}

Response: {
  "id": "uuid-transition-id",
  "derivedState": {
    "id": "running",
    "pages": [...],                   // auto-derived: hero-title updated + progress-bar added
    "derivedFromTransitionId": "uuid-transition-id",
    "initialContext": { "started": true }
  }
}
```

此时后端自动：
1. 深拷贝 `idle` 的 pages
2. 应用 section.update（修改 hero-title 的 title）+ section.add（添加 progress-bar）
3. 收集 context.set 为 initialContext
4. 创建 "running" state 并存入定义中

**不需要手动定义 "running" state 的 sections。** 用户只需说"从 idle 收到 timer.elapsed 后做什么"，后端推算出结果页面。

### 6. 继续添加更多 Transition

```
POST /state-machines/{id}/transitions
Body: {
  "fromStateId": "running",
  "toStateId": "done",
  "event": { "eventId": "timer.elapsed" },
  "actions": [
    { "type": "section.update", "sectionId": "hero-title", "fields": { "title": "Done!" } },
    { "type": "section.remove", "sectionId": "progress-bar" },
    { "type": "context.set", "name": "completed", "value": true }
  ]
}
```

后端从 "running"（之前已推导）的 pages 出发，应用 update + remove，推导出 "done" state。

无 `toStateId` 的 transition 同样合法——表示纯命令执行或自循环。

### Transition 起始状态解析

`fromStateId` 现在是可选的。三种优先规则决定有效的源状态：

| 字段 | 行为 |
|------|------|
| `fromTransitionId` | 查找引用的 transition，使用其 `toStateId` 作为有效的 `fromStateId`。如果是自循环（toStateId 等于 fromStateId 或不存在），则使用其 `fromStateId` |
| `fromStateId`（无 `fromTransitionId`） | 直接使用（向后兼容） |
| 两者都不填 | 默认使用初始状态（states 列表中第一个） |

这支持真正的分支结构：
- **都不填**：从初始状态分支（多个 transition 可从同一初始状态分叉）
- **填入 `fromTransitionId`**：从另一个 transition 的结果状态分支
- **填入 `fromStateId`**：指向特定命名状态

示例 — 从初始状态分支（都不填）：

```json
{
  "toStateId": "timeout",
  "event": { "eventId": "timer.elapsed" },
  "actions": [
    { "type": "section.update", "sectionId": "hero-title", "fields": { "title": "Timeout!" } },
    { "type": "context.set", "name": "timedOut", "value": true }
  ]
}
```

示例 — 从另一个 transition 的结果分支：

```json
{
  "fromTransitionId": "uuid-of-prior-transition",
  "toStateId": "paused",
  "event": { "eventId": "ui:action.click" },
  "actions": [...]
}
```

### 7. 查询状态（增量查询）

```
GET /state-machines/{id}/states                    → 列出所有 states（idle, running, done）
GET /state-machines/{id}/states/running             → 获取 running 的完整定义（含 derivedFromTransitionId）
GET /state-machines/{id}/states?at={transitionId}  → 查询该 transition 之后的状态
GET /state-machines/{id}/transitions               → 顺序 transition 列表
```

`?at={transitionId}` 查询返回 "在此 transition 之后页面是什么样"——直接返回 toStateId 对应的 state。

### 8. 部署到设备

```
POST /state-machines/{id}/deployments
Body: { "devices": ["1051DB398BD0"] }
```

部署时自动：
- 设备在线检查、板卡类型匹配检查、能力交集对比
- 读取初始状态的 `initialContext`（由推导引擎设置）种子化 deployment 的 contextData
- 将初始 state 的页面推送到设备（scene push）

### 9. 触发状态转换

```
POST /state-machines/{id}/trigger
Body: { "event": { "eventId": "timer.elapsed", "deviceId": "..." } }
```

引擎自动完成：
- 查找匹配当前状态 + 事件的 transition
- 执行 actions（上下文设置、命令下发、页面修改等）
- 新旧页面 diff，生成 patch 或 scene 推送到设备
- 跳转到目标状态，更新部署记录

### 10. 验证

```
GET /state-machines/{id}/deployments    → 查看部署状态
GET /state-machines/{id}                → 查看完整定义
```

## 数据流

```
1. 创建空壳 (POST /)
   → PostgreSQL 插入记录 (states=[], transitions=[])

2. 添加初始状态 (POST /states)
   → definition.states 追加 idle state
   → 首个 state 标记为初始状态

3. 添加 transition (POST /transitions)
   → 解析 fromStateId (fromTransitionId > fromStateId > 初始状态)
   → 校验 event/action 合法性
   → 自动推导: fromState.pages + actions → derivedState
   → definition.states 追加/更新 target state
   → definition.transitions 追加 transition (自动生成 UUID)
   → 返回 transitionId + derivedState

4. 部署 (POST /deployments)
   → 预检 (在线/板卡/能力)
   → initialContext 种子化 contextData
   → 初始 scene 推送到设备 WebSocket

5. 事件触发 (POST /trigger 或 设备 WebSocket 上报)
   → findTransition() 匹配
   → executeActions() 执行动作链
   → projectionService.projectTransition() diff 页面
   → 推送 patch/scene 到设备
   → deploymentRepository.save() 更新状态
```

## Transition 与 State 的关系

| Transition 类型 | toStateId | 行为 |
|----------------|-----------|------|
| 状态转换 | 指定新的 stateId | 后端自动推导 target state；部署时 contextData 从 initialContext 种子化 |
| 自循环 (self-loop) | 不指定或等于 fromStateId | 纯命令/上下文更新，不跳转状态 |
| 纯命令执行 | 不指定 | 只下发命令、调 HTTP、设上下文，不改变页面 |

## 外置测试

```bash
./scripts/test-state-machine/e2e-test.sh
```

12 步自动化验证上述全流程：

1. 发现真实设备 & 板卡类型
2. 查询 events、commands、sections
3. 创建空壳（仅 name + boardTypes）
4. 增量添加初始 state
5. 添加 transition #1 → 后端自动推导 running state
6. 添加 transition #2 → 后端自动推导 done state
7. 分支测试：默认 fromStateId（初始状态）+ fromTransitionId 解析
8. 验证增量查询（states、?at=transitionId、transitions）
9. 部署到真实设备
10. 触发 idle → running
11. 触发 running → done
12. 清理

不依赖任何平台内测试代码，仅通过 curl 调用真实 API。

核心演示：
- **只手动定义了 idle state**，running、done、timeout、paused 由后端从 transition actions 自动推导生成
- **分支语法**：fromTransitionId 解析到运行中状态，fromStateId 默认到初始状态
