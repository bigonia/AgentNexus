# SDUI 事件查询接口问题分析与改进建议

> 分析对象: `GET /api/v1/sdui/board-types/{typeKey}/events`  
> 分析日期: 2026-06-18  
> 关联文档: [[STATE_MACHINE_ARCHITECTURE.md]]

---

## 一、当前接口响应结构

```
data
├── typeKey / board / label / exampleDeviceId / online   ← 元数据
├── inputEvents[]           ← 设备实际支持的 inbound 事件（基于 CapabilityRegistry 过滤）
├── eventOptions[]          ← 全局静态 inbound 事件选项（来自 EventRegistry.getFlatEventOptions()）
└── eventTree[]             ← 全局静态 inbound 事件树（来自 EventRegistry.getInboundEventTree()）
    ├── [0] SECTION 类别
    │   └── capabilities[]   ← 按 sourceCapability 分组，含完整 EventDefinition（含 transport, payloadSchema）
    └── [1] COMMAND 类别
        └── capabilities[]   ← 所有 lifecycle 事件平铺
```

## 二、核心问题

### 问题 1：事件分类混乱 —— 内部系统事件与用户交互事件混在一起

当前 `eventOptions` 和 `eventTree` 将三类完全不同性质的事件平铺在同一个列表里：

| 类别 | 事件示例 | 实际性质 |
|------|---------|---------|
| Section 交互 | `ui:action.click`, `ui:toggle.change`, `ui:list.select` | **用户主动行为** —— 应该作为状态机触发条件 |
| 命令生命周期 | `command.ack`, `command.failed`, `command.timeout` | **内部回调事件** —— 不应暴露为触发条件 |
| 音频/录音 | `audio.record.started`, `audio.record.chunk`, `audio.stt.processing` | **内部流式进度** —— 不应暴露为触发条件 |
| 定时器 | `timer.elapsed`, `system:cron` | **平台/系统事件** —— 可以作为触发条件但应在独立分类 |

**根因**：`EventRegistry.getInboundEventTree()` 将所有 `direction=INBOUND` 的事件硬编码为两个分组 —— `SECTION` 和 `COMMAND`，而 `COMMAND` 分组又把 `command.lifecycle`、`timer`、`audio.record`、`system` 不同来源的事件全塞进 `command.lifecycle` capability。

```java
// EventRegistry.java:174-179
public List<Map<String, Object>> getInboundEventTree() {
    return List.of(
        Map.of("category", "SECTION", "label", "Section 交互", "capabilities", groupedSectionEvents()),
        Map.of("category", "COMMAND", "label", "命令生命周期", "capabilities", groupedCommandLifecycleEvents())
    );
}
```

**影响**：前端编辑器展示 18 个事件选项给用户选择触发器，其中 13 个（command.* ×6 + audio.* ×5 + timer/sys: ×2）对状态机触发几乎无意义。用户需要在噪音中找到真正有效的触发事件。

### 问题 2：eventTree 暴露大量内部实现细节

每个事件的 `transport` 字段包含底层协议实现细节：

```json
"transport": {
    "protocol": "ui3_binary",   // ← 协议细节，不应对外暴露
    "msgType": 9,               // ← 二进制帧消息类型，内部实现
    "eventKind": 1,             // ← TLV 字段编号，内部实现
    "eventName": "action.click" // ← alias 注册用，不需前端感知
}
```

**这些信息的实际用途**：`transport` 字段唯一的作用是在 `EventRegistry.registerAlias()` 和 `resolvePayloadEventId()` 中做原始设备事件到平台事件 ID 的映射。这是**纯后端运行时逻辑**，完全不应出现在面向前端/编辑器的 API 响应中。

**同样的问题**适用于 `payloadSchema` 中的 `nodeId`、`ts` 等内部字段 —— 这些是协议层需要的信息，但状态机编辑器不需要全部展示。

### 问题 3：事件列表是静态全局的，不随部署状态变化

当前接口返回的 `eventOptions` 和 `eventTree` 是静态的 —— 来自 YAML 配置文件的全局注册表，与具体的状态机部署实例无关。

按照 [STATE_MACHINE_ARCHITECTURE.md](STATE_MACHINE_ARCHITECTURE.md) 的设计：

> **关键设计**：输入事件列表不是静态的，而是随流程定义的 section 变化而动态更新：
> - 静态输入：设备物理能力（按钮、传感器等），初始化时注册
> - 动态输入：section 携带的交互事件，随 section 新增/删除而变化
> - 删除一个 section 后，其关联的输入自动从可用列表移除

**当前实际**：接口返回了所有 5 种 section 交互事件（action.click, toggle.change, list.select, overlay.confirm, nav.change），无论当前状态机部署中是否使用了这些 section。

**结果**：如果用户的空闲状态页面上只有一个 `hero_section`（纯展示型，不产生事件），系统仍然告诉编辑器"你可以用 toggle.change 作为触发器"——这是虚假能力。

### 问题 4：device-level 物理事件完全缺失

从示例响应看，`inputEvents: []` 为空。这意味着设备的物理输入事件（按钮按下、运动传感器等）没有正确出现在接口中。

在 `CapabilityRegistry.getDeviceEventDefinitions()` 中：
```java
public List<EventDefinition> getDeviceEventDefinitions(String deviceId) {
    // ...
    for (EventDefinition def : eventRegistry.getAllInboundEvents()) {
        if (caps.supportsEvent(def.eventId()) || caps.inputEvents().contains(def.eventId())) {
            result.add(def);
        }
    }
    return result;
}
```

这个过滤逻辑有问题：
1. `EventRegistry.getAllInboundEvents()` 来自 YAML 配置文件 —— 包含 section 交互、command 生命周期、音频事件
2. **物理输入事件**（如 `input:buttons.pwr.press`、`input:motion.wake`）是在 `CapabilityCatalog` 中定义的，通过 `normalizeInputEventId()` 生成，**但不在 EventRegistry 的已知事件列表中**
3. 所以 `EventRegistry.getAllInboundEvents()` 根本不包含这些物理事件

**根因**：物理输入事件和 YAML 配置事件走的是两套注册体系，`getDeviceEventDefinitions` 只用 EventRegistry 过滤，漏掉了 CapabilityCatalog 中的物理输入事件。

### 问题 5：分类标签混乱

当前 `eventTree` 中 `COMMAND_LIFECYCLE` 分类的 label 是"命令生命周期"，但里面包含了：
- 命令 ACK/失败/超时 *(是 command lifecycle)*
- 定时器到期 *(不是 lifecycle)*
- 录音开始/上传/完成/STT处理/STT结果 *(不是 lifecycle)*
- 系统 cron *(不是 lifecycle)*

所有这些都被硬编码为 `category: COMMAND_LIFECYCLE`，因为它们都放在 `sdui-event-catalog.yml` 的 `lifecycle:` 节点下。分类完全由 YAML 位置决定，而非事件语义。

---

## 三、改进建议

### 3.1 重构事件分类体系

将事件按**对状态机的业务含义**重新分类为三类：

```
事件分类
├── USER_INTERACTION（用户交互事件）    ← 状态机触发条件
│   ├── section 交互: ui:action.click, ui:toggle.change, ui:list.select 等
│   └── 物理输入:    input:buttons.pwr.press, input:motion.wake 等
│
├── SYSTEM_TIMER（系统定时事件）        ← 状态机触发条件
│   ├── timer.elapsed（状态机内部定时器）
│   └── system:cron（系统 cron 触发）
│
└── PLATFORM_INTERNAL（平台内部事件）   ← 不应暴露为触发条件
    ├── 命令生命周期: command.dispatch, command.ack, command.rejected/failed/timeout/result
    └── 流式进度:     audio.record.*, audio.stt.*
```

**对应的后端改动**：

1. 在 `EventDefinition.EventCategory` 中新增 `USER_INTERACTION`、`SYSTEM_TIMER`、`PLATFORM_INTERNAL`
2. 修改 `sdui-event-catalog.yml`，将 section 交互事件和物理输入归类为 `USER_INTERACTION`
3. 将 `timer.elapsed` 和 `system:cron` 归类为 `SYSTEM_TIMER`
4. 将所有 `command.*` 和 `audio.*` 归类为 `PLATFORM_INTERNAL`
5. **API 层面**：状态机编辑器的事件选择器只展示 `USER_INTERACTION` + `SYSTEM_TIMER`

### 3.2 eventTree 去内部化 —— 移除 transport 和冗余字段

**transport 字段**：完全从对外 API 中移除。它的唯一用途是 alias 解析（后端逻辑），不应出现在响应中。

**具体做法**：在 `EventDefinition.toMap()` 中移除 `transport`，或创建一个新的 `toPublicMap()` 方法，只暴露前端需要的字段：

```java
public Map<String, Object> toPublicMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("eventId", eventId);
    map.put("displayName", displayName);
    map.put("description", description != null ? description : "");
    map.put("category", category.name());
    map.put("categoryLabel", category.label());
    map.put("sourceCapability", sourceCapability);
    // payloadSchema: 只保留状态机编辑器需要的字段，去掉 nodeId/ts 等内部字段
    map.put("payloadSchema", publicPayloadSchema());
    return map;
}
```

**payloadSchema 精简**：移除 `nodeId`、`ts`、`pageId` 等协议内部字段，只保留**对状态机转移条件有意义的字段**（如 `sectionId`、`buttonId`、`itemId`、`optionId`、`active`）。

### 3.3 实现动态事件列表

接口应该改为接收**状态机部署的当前 page 状态**作为输入，返回**在该状态下实际可用的事件**：

```
GET /api/v1/sdui/board-types/{typeKey}/events?deploymentId=xxx
或 POST /api/v1/sdui/board-types/{typeKey}/events/available
  body: { sections: [...], contextVariables: {...} }
```

返回结构：

```json
{
    "staticEvents": [     // 始终可用：物理按钮、传感器、系统定时器
        { "eventId": "input:buttons.pwr.press", "displayName": "电源键按下", "category": "USER_INTERACTION" }
    ],
    "dynamicEvents": [    // 随 page 上的 section 动态变化
        { "eventId": "ui:action.click", "displayName": "按钮点击", "boundSections": ["action1", "action2"] }
    ],
    "systemEvents": [
        { "eventId": "timer.elapsed", "displayName": "定时器到期" },
        { "eventId": "system:cron", "displayName": "定时触发" }
    ]
}
```

### 3.4 修复物理事件的注册路径

1. 将 `CapabilityCatalog` 中加载的物理输入事件（`input:buttons.*`、`input:motion.*`、`input:audio.*`）注册到 `EventRegistry`，使其有对应的 `EventDefinition`
2. 或者在 `CapabilityRegistry.getDeviceEventDefinitions()` 中增加对 CapabilityCatalog 物理输入的查询路径
3. 确保 `inputEvents` 字段不再为空

### 3.5 精简 eventOptions —— 区分触发事件和监听事件

当前 `eventOptions` 返回所有 inbound 事件（18个），但状态机编辑器实际只需要约 5-7 个：

**状态机触发事件（应展示）**：
- `ui:overlay.confirm` — 确认覆盖层
- `ui:action.click` — 按钮点击
- `ui:list.select` — 列表选择
- `ui:toggle.change` — 开关变化
- `ui:nav.change` — 标签切换
- `timer.elapsed` — 定时器到期
- `system:cron` — 定时触发
- `input:buttons.*` — 物理按钮（各设备不同）

**内部事件（不应在触发条件下拉中出现）**：
- `command.dispatch/ack/rejected/failed/timeout/result` — 命令生命周期
- `audio.record.started/chunk/data` — 录音进度
- `audio.stt.processing/result` — STT 进度

### 3.6 建议的精简 API 响应示例

```json
{
    "typeKey": "board:ESP32-S3-LCD-0.85:0",
    "board": "ESP32-S3-LCD-0.85",
    "label": "ESP32-S3-LCD-0.85",
    "exampleDeviceId": "1051DB398BD0",
    "online": true,

    "triggerEvents": [
        {
            "eventId": "input:buttons.pwr.press",
            "displayName": "电源键按下",
            "category": "USER_INTERACTION",
            "source": "物理按钮",
            "params": []
        },
        {
            "eventId": "ui:action.click",
            "displayName": "按钮点击",
            "category": "USER_INTERACTION",
            "source": "action_section",
            "params": [
                { "name": "sectionId", "type": "string", "description": "触发 Section ID" },
                { "name": "buttonId", "type": "string", "description": "被点击按钮 ID" }
            ]
        }
        // ... 只包含真正的触发级事件
    ],

    "dynamicCapabilities": {
        "sectionEvents": {
            "action_section": { "eventId": "ui:action.click", "displayName": "按钮点击" },
            "toggle_section": { "eventId": "ui:toggle.change", "displayName": "开关变化" }
        }
    }
}
```

---

## 四、改动优先级与影响范围

### Phase 1（低风险，高收益）

| 改动 | 影响文件 | 说明 |
|------|---------|------|
| 在 `EventDefinition.toMap()` 中移除 `transport` 字段 | `EventDefinition.java:131` | 后端仍保留 transport 用于 alias 解析，只从前端响应中隐藏 |
| 精简 `eventOptions` 返回值，过滤掉内部事件 | `EventRegistry.java:185-196` | 增加 category 过滤，只返回 `SECTION_INTERACTION` + 新增的 `SYSTEM_TIMER` |
| 修复 `eventTree` 分组逻辑，将非 lifecycle 事件从 COMMAND_LIFECYCLE 中分离 | `EventRegistry.java:330-342` | `groupedCommandLifecycleEvents()` 当前把所有 INBOUND command 都归入一个组 |

### Phase 2（中等风险，架构改进）

| 改动 | 影响文件 | 说明 |
|------|---------|------|
| 重构 `EventCategory` 枚举，新增分类 | `EventDefinition.java`, `sdui-event-catalog.yml` | 重新分类命令生命周期、定时器、音频流事件 |
| 修复物理事件注册路径 | `CapabilityRegistry.java`, `CapabilityCatalog.java`, `EventRegistry.java` | 让物理输入事件有 EventDefinition |
| 实现动态事件查询接口 | `BoardTypeController.java`, `CapabilityRegistry.java` | 需与前端协调新接口格式 |

### Phase 3（长期，配合状态机编辑器重构）

| 改动 | 影响文件 | 说明 |
|------|---------|------|
| 基于 page 状态的动态 event 去重/展示 | 新增或修改 Service | 根据部署的当前 page sections 返回真正可用的事件 |
| 事件 payload schema 的精简版本 | `EventDefinition.java` | 区分协议层 schema 和编辑器层 schema |

---

## 五、相关文件索引

| 文件 | 角色 |
|------|------|
| `src/main/java/.../sdui/controller/BoardTypeController.java` | `/events` 端点 (L118-146) |
| `src/main/java/.../sdui/event/EventRegistry.java` | 全局事件注册表，构建 eventTree/eventOptions |
| `src/main/java/.../sdui/event/EventDefinition.java` | 事件定义 record，含 transport, payloadSchema |
| `src/main/java/.../sdui/event/EventCatalogLoader.java` | 从 YAML 加载事件定义 |
| `src/main/resources/sdui-event-catalog.yml` | 静态事件配置 (417行) |
| `src/main/java/.../sdui/capability/CapabilityRegistry.java` | 设备能力注册表，过滤设备支持的事件 |
| `src/main/java/.../sdui/capability/CapabilityCatalog.java` | 加载 capability-catalog.yml，管理物理输入映射 |
| `src/main/java/.../sdui/section/SectionTypeCatalog.java` | Section 类型静态注册，定义交互事件 |
| `docs/sdui/STATE_MACHINE_ARCHITECTURE.md` | 状态机架构设计文档 |
