# Node Workflow 上下文、产物与参数引用

> 面向前端编辑器集成。本文档描述工作流运行时的上下文结构、节点产物机制、参数引用语法，以及节点跳过（skipped）语义。

---

## 1. 运行时上下文 (Run Context)

每次工作流被触发时，运行时创建一个 **Run** (`NodeWorkflowRunEntity`)，并初始化一个结构化上下文。上下文在整个 Run 执行期间持续更新，所有节点共享。

### 1.1 上下文顶层结构

```json
{
  "event": { … },
  "slots": { … },
  "nodes": { … },
  "artifacts": { … },
  "run": { … }
}
```

### 1.2 `event` — 触发事件

触发本次 Run 的原始事件载荷，由 `EventInputHandler` 标准化后注入。

```json
{
  "eventId": "input:buttons.pwr.short_press",
  "deviceId": "dev-001",
  "nodeId": "pwr",
  "sectionId": "",
  "pageId": "",
  "ts": 1719000000000
}
```

### 1.3 `slots` — 槽位绑定

部署时确定的 slot → device 映射。

```json
{
  "main_device": {
    "slotId": "main_device",
    "deviceId": "dev-001"
  }
}
```

### 1.4 `nodes` — 节点执行结果

每个输出节点执行后，其返回结果写入 `nodes.<nodeId>`。key 为节点 ID，value 为 executor 返回的完整结果 Map。

**示例 — audio.record 停止录音后：**

```json
{
  "node_1782894986527_8b6swp": {
    "nodeId": "node_1782894986527_8b6swp",
    "nodeType": "audio.record",
    "slotId": "main_device",
    "deviceId": "dev-001",
    "command": "audio.record.stop",
    "control": "toggle",
    "recording": false,
    "sent": true,
    "status": "sent",
    "artifact": {
      "artifactId": "a1b2c3d4-…",
      "artifactRef": "artifact:a1b2c3d4-…",
      "blobUrl": "/api/v1/sdui/artifacts/a1b2c3d4-…/blob",
      "deviceId": "dev-001",
      "type": "audio/recording",
      "mimeType": "audio/wav",
      "blobBytes": 44100,
      "durationMs": 2000,
      "sampleRate": 22050
    }
  }
}
```

**示例 — audio.record 开始录音时（无产物）：**

```json
{
  "node_1782894986527_8b6swp": {
    "nodeId": "node_1782894986527_8b6swp",
    "nodeType": "audio.record",
    "slotId": "main_device",
    "deviceId": "dev-001",
    "command": "audio.record.start",
    "control": "toggle",
    "recording": true,
    "sent": true,
    "status": "sent"
  }
}
```

注意：开始录音时 **没有 `artifact` 字段**。

### 1.5 `artifacts` — 产物摘要

当节点结果包含 `artifact` 字段时，上下文自动提取一份摘要到 `artifacts.<nodeId>`。

结构与 `nodes.<nodeId>.artifact` 相同，方便前端按节点 ID 直接查找产物。

### 1.6 `run` — Run 元信息

```json
{
  "runId": "uuid",
  "workflowId": "uuid",
  "deploymentId": "uuid",
  "triggerNodeId": "node_xxx"
}
```

---

## 2. 参数引用语法

节点 `params` 中的字符串值可以使用 `$` 前缀引用上下文中的数据。

### 2.1 语法规则

| 语法 | 说明 | 示例 |
|------|------|------|
| `$path.to.field` | 完整引用 — 整个值被替换为解析结果 | `$nodes.n1.artifact.artifactId` |
| `text $path text` | 字符串插值 — `$ref` 嵌入到文本中 | `播放时长 $nodes.n1.artifact.durationMs ms` |

引用路径用 `.` 分隔，从上下文根开始逐级访问 Map key。

### 2.2 常用引用路径

| 路径 | 说明 |
|------|------|
| `$event.eventId` | 触发事件 ID |
| `$event.deviceId` | 触发事件的设备 ID |
| `$event.nodeId` | 触发事件的物理节点 ID（如 `"pwr"`） |
| `$slots.<slotId>.deviceId` | 槽位绑定的真实设备 ID |
| `$nodes.<nodeId>.<field>` | 某节点的执行结果字段 |
| `$nodes.<nodeId>.artifact.artifactId` | 某节点产物的 artifactId |
| `$nodes.<nodeId>.artifact.artifactRef` | 某节点产物的引用标识 |
| `$nodes.<nodeId>.artifact.durationMs` | 音频产物时长 |
| `$artifacts.<nodeId>.<field>` | 产物摘要字段 |
| `$run.runId` | 当前 Run ID |

### 2.3 实际配置示例

**audio.play 播放上游录音产物：**

```json
{
  "nodeId": "play_1",
  "slotId": "main_device",
  "nodeType": "audio.play",
  "params": {
    "artifact_id": "$nodes.record_1.artifact.artifactId"
  }
}
```

**ui.update 引用事件中的值：**

```json
{
  "nodeId": "ui_1",
  "slotId": "main_device",
  "nodeType": "ui.update",
  "params": {
    "slotId": "main_device",
    "templateKey": "tpl_xxx",
    "variableKey": "status_text",
    "value": "事件来自 $event.deviceId"
  }
}
```

### 2.4 解析模式：Lenient vs Strict

- **运行时 (lenient)**：未解析到的路径返回空字符串，不中断执行。用于支持"产物尚未生成时跳过下游节点"的场景。
- **校验时 (strict)**：未解析到的路径报校验错误，帮助用户在编辑阶段发现配置问题。

---

## 3. 节点产物 (Artifact)

### 3.1 产物生命周期

```
audio.record 节点 (control=toggle)
  │
  ├─ 第一次触发 (开始录音)
  │   └─ 返回 { recording: true, status: "sent" }
  │      → context.nodes[nodeId] 不包含 artifact 字段
  │      → 下游引用 $nodes.nodeId.artifact.xxx → 解析为空
  │
  └─ 第二次触发 (停止录音)
      └─ 返回 { recording: false, artifact: { artifactId, … }, status: "sent" }
         → context.nodes[nodeId].artifact 存在
         → context.artifacts[nodeId] 自动提取
         → SduiArtifactEntity 持久化（blob 存入数据库）
         → 下游引用 $nodes.nodeId.artifact.artifactId → 正常解析
```

### 3.2 产物数据格式

`artifact` 对象字段（来自 `SduiArtifactService.toMap()`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `artifactId` | string | 唯一标识 |
| `artifactRef` | string | 引用标识，格式 `artifact:<artifactId>` |
| `blobUrl` | string | 二进制下载 URL |
| `deviceId` | string | 产生产物的设备 |
| `type` | string | 产物类型，如 `audio/recording` |
| `mimeType` | string | MIME 类型，如 `audio/wav` |
| `blobBytes` | number | 二进制大小（字节） |
| `durationMs` | number | 音频时长（毫秒） |
| `sampleRate` | number | 采样率 |
| `metadata` | object | 自定义元数据 |
| `createdAt` | string | 创建时间 |

### 3.3 产物查询 API

```
GET /api/v1/sdui/node-workflows/{workflowId}/runs/{runId}/artifacts
```

返回该 Run 关联的所有产物列表。

---

## 4. 节点跳过 (Skipped) 语义

### 4.1 触发条件

当节点 params 中显式配置了引用 key，但该引用在 lenient 模式下解析为空时，节点返回 `skipped` 状态。

**当前适用的节点类型：**

| 节点类型 | 跳过条件 |
|----------|----------|
| `audio.play` | `artifact_id` 或 `audio_file` key 存在但值为空 |

### 4.2 行为

- 节点返回 `{ status: "skipped", reason: "…" }`
- Step 状态记录为 `"skipped"`
- **Run 不失败**，后续节点继续执行
- context 中正常记录该节点的结果

### 4.3 与 Failed 的区别

| | skipped | failed |
|------|---------|--------|
| Run 状态 | passed（如无其他失败） | failed |
| 后续节点 | 继续执行 | 停止执行 |
| 含义 | 条件不满足，有意跳过 | 执行出错 |

---

## 5. 完整示例：录音 + 播放工作流

### 5.1 工作流定义

```json
{
  "name": "音频录制和播放",
  "slots": [
    { "slotId": "main_device", "board": "ESP32-S3-Touch-AMOLED-1.75C", "displayName": "主设备" }
  ],
  "nodes": [
    {
      "nodeId": "btn",
      "slotId": "main_device",
      "nodeType": "button.trigger",
      "params": { "eventId": "input:buttons.pwr.short_press", "nodeId": "pwr" }
    },
    {
      "nodeId": "rec",
      "slotId": "main_device",
      "nodeType": "audio.record",
      "params": { "control": "toggle" }
    },
    {
      "nodeId": "play",
      "slotId": "main_device",
      "nodeType": "audio.play",
      "params": { "artifact_id": "$nodes.rec.artifact.artifactId" }
    }
  ],
  "edges": [
    { "from": "btn", "to": "rec" },
    { "from": "rec", "to": "play" }
  ],
  "uiTemplates": []
}
```

### 5.2 第一次按下按钮（开始录音）

```
Run 开始
  ├─ btn    → 匹配事件，触发 Run
  ├─ rec    → audio.record (toggle)
  │           设备未在录音 → 发送 start 命令
  │           返回 { recording: true, status: "sent" }
  │           → context.nodes.rec = { … }  (无 artifact)
  │           → passed
  └─ play   → audio.play (artifact_id: "$nodes.rec.artifact.artifactId")
              解析 $nodes.rec.artifact → 路径不存在 → artifact_id = ""
              → 返回 { status: "skipped", reason: "artifact not yet available" }
              → skipped
Run 结束: passed
```

### 5.3 第二次按下按钮（停止录音 + 播放）

```
Run 开始
  ├─ btn    → 匹配事件，触发 Run
  ├─ rec    → audio.record (toggle)
  │           设备正在录音 → 发送 stop 命令
  │           等待 ACK → 等待录音结束 → WAV 编码 → 持久化
  │           返回 { recording: false, artifact: { artifactId: "abc123", … }, status: "sent" }
  │           → context.nodes.rec.artifact 存在
  │           → context.artifacts.rec 自动提取
  │           → passed
  └─ play   → audio.play (artifact_id: "$nodes.rec.artifact.artifactId")
              解析 $nodes.rec.artifact.artifactId → "abc123"
              → playArtifact() 播放 WAV
              → 返回 { status: "sent", artifact: { … } }
              → passed
Run 结束: passed
```

---

## 6. 前端编辑器集成要点

### 6.1 产物引用选择器

编辑 `audio.play` 节点时，编辑器应：

1. 查找工作流中所有上游 `audio.record` 节点
2. 提供下拉选项让用户选择"播放哪个节点的录音产物"
3. 自动生成引用：`$nodes.<上游节点ID>.artifact.artifactId`

### 6.2 引用语法校验

提交前，编辑器可以调用 `POST /api/v1/sdui/node-workflows/validate` 进行校验。后端会检查：

- `$ref` 语法是否合法
- `ui.update` 节点引用的 `templateKey` 是否在 `uiTemplates` 中存在
- DAG 是否有环、节点是否可达

### 6.3 Run 详情展示

Run 详情 API (`GET …/runs/{runId}`) 返回完整 context 和 steps，前端可以：

- 展示每个 step 的状态（passed / skipped / failed）
- 展示 artifact 信息（时长、大小、播放/下载链接）
- 用 `artifacts` 字段快速定位产物
