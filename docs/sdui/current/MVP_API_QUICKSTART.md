# SDUI Frontend Integration Spec (MVP)

本文档是前端（Web/Vue3/移动端）联调 SDUI 后端的正式说明，覆盖：

1. REST 接口请求/响应 JSON Schema + 字段说明
2. 统一分页契约（当前实现状态 + 推荐契约）
3. 错误码与前端处理建议
4. 鉴权与 Header 契约
5. WebSocket 上下行协议正式版
6. Postman Collection 导入说明

---

## 1. 背景与范围

SDUI（Server-Driven UI）模式下：

- 管理端前端通过 REST API 管理“应用、设备、资源、发布”
- 终端通过 WebSocket 接收 `ui/layout` / `ui/update` 并上报事件

前端在 MVP 阶段不直接拼装终端 UI 树，而是通过后端 AI + 运行时生成并下发。

---

## 2. Base URL 与通用响应

## 2.1 Base URL

- REST: `http://<host>:8080/api/v1/sdui`
- WS: `ws://<host>:8080/ws/sdui`

## 2.2 通用响应包装

所有 SDUI REST 接口当前返回 `ApiResponse<T>`：

```json
{
  "code": 20000,
  "message": "Success",
  "data": {}
}
```

---

## 3. 鉴权与 Header 契约

## 3.1 当前实现状态（MVP）

- `Authorization`: 当前后端对 `/api/**` 处于放开状态，非强制
- `X-Space-Id`: 非强制；不传时后端使用默认 `space_id=default`

## 3.2 前端联调建议（推荐）

- 一律带：
  - `Content-Type: application/json`
  - `X-Space-Id: <tenant_or_space_id>`
- 若未来启用鉴权，补：
  - `Authorization: Bearer <token>`

---

## 4. 分页契约

## 4.1 当前实现状态

当前列表接口多数为“全量返回”，尚未统一分页参数。

## 4.2 推荐统一分页契约（前后端后续对齐）

请求 Query：

- `page`: `integer`, 从 `1` 开始，必填
- `pageSize`: `integer`, 建议默认 `20`，最大 `100`
- `keyword`: `string`, 可选

响应 `data` 建议：

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "total": 137,
  "totalPages": 7
}
```

说明：当前接口还未落这个契约，前端请先按全量模式接入；分页改造时建议整体切换。

---

## 5. 错误码与前端处理建议

## 5.1 已使用错误码

| code | message 示例 | 含义 | 前端建议 |
|---|---|---|---|
| 20000 | Success | 成功 | 正常渲染 |
| 50000 | xxx | 业务错误（参数/状态等） | toast + 保留当前页面状态 |

## 5.2 HTTP 层错误（可能出现）

| HTTP | 场景 | 前端建议 |
|---|---|---|
| 400 | 参数校验失败（`@Valid`） | 高亮表单字段，提示用户修正 |
| 401/403 | 鉴权开启后可能出现 | 跳转登录或提示无权限 |
| 500 | 未捕获异常 | 通用错误弹窗 + 重试按钮 |

---

## 6. REST API 规范（含 Schema）

说明：

- `required` 表示请求字段必填
- `nullable` 表示可为 `null`
- 未特别说明的响应都使用 `ApiResponse<T>`

## 6.1 应用生成

### POST `/apps/generate`

Request Schema:

```json
{
  "type": "object",
  "required": ["requirement"],
  "properties": {
    "requirement": {"type": "string", "minLength": 1},
    "sceneTags": {"type": "array", "items": {"type": "string"}, "nullable": true},
    "assetSetIds": {"type": "array", "items": {"type": "string"}, "nullable": true},
    "targetDeviceIds": {"type": "array", "items": {"type": "string"}, "nullable": true}
  }
}
```

Response `data` Schema:

```json
{
  "type": "object",
  "properties": {
    "appId": {"type": "string"},
    "versionId": {"type": "string"},
    "versionNo": {"type": "integer"},
    "templateName": {"type": "string"},
    "templateDescription": {"type": "string"},
    "selectedAssetSetIds": {"type": "array", "items": {"type": "string"}},
    "previewLayoutJson": {"type": "string"},
    "validationReport": {"type": "string"}
  }
}
```

## 6.2 应用修订

### POST `/apps/{appId}/revise`

Path Params:

- `appId: string` (required)

Request Schema:

```json
{
  "type": "object",
  "required": ["instruction"],
  "properties": {
    "instruction": {"type": "string", "minLength": 1}
  }
}
```

Response `data` 与 `generate` 一致。

## 6.3 应用列表

### GET `/apps`

Response `data` Schema（数组）:

```json
{
  "type": "array",
  "items": {
    "type": "object",
    "properties": {
      "id": {"type": "string"},
      "name": {"type": "string"},
      "description": {"type": "string"},
      "sceneTags": {"type": "array", "items": {"type": "string"}},
      "status": {"type": "string", "enum": ["DRAFT", "PUBLISHED", "DISABLED"]},
      "entryMode": {"type": "string", "enum": ["PYTHON_SCRIPT"]},
      "createdBy": {"type": "string", "nullable": true},
      "createdAt": {"type": "string", "format": "date-time", "nullable": true},
      "updatedAt": {"type": "string", "format": "date-time", "nullable": true}
    }
  }
}
```

## 6.4 应用版本列表

### GET `/apps/{appId}/versions`

Path Params:

- `appId: string` (required)

Response `data` Schema（数组）:

```json
{
  "type": "array",
  "items": {
    "type": "object",
    "properties": {
      "id": {"type": "string"},
      "versionNo": {"type": "integer"},
      "scriptContent": {"type": "string"},
      "llmPromptSnapshot": {"type": "string", "nullable": true},
      "validationReport": {"type": "string", "nullable": true},
      "published": {"type": "boolean"},
      "createdAt": {"type": "string", "format": "date-time", "nullable": true}
    }
  }
}
```

## 6.5 应用发布

### POST `/apps/{appId}/publish`

Path Params:

- `appId: string` (required)

Request Schema:

```json
{
  "type": "object",
  "required": ["deviceIds"],
  "properties": {
    "versionId": {"type": "string", "nullable": true},
    "deviceIds": {"type": "array", "minItems": 1, "items": {"type": "string"}}
  }
}
```

Response `data`:

```json
{
  "type": "object",
  "properties": {
    "sent": {"type": "integer"},
    "requested": {"type": "integer"}
  }
}
```

## 6.6 设备列表

### GET `/devices`

Response `data` Schema（数组）:

```json
{
  "type": "array",
  "items": {
    "type": "object",
    "properties": {
      "deviceId": {"type": "string"},
      "name": {"type": "string"},
      "status": {"type": "string", "enum": ["ONLINE", "OFFLINE"]},
      "lastSeenAt": {"type": "string", "format": "date-time", "nullable": true},
      "currentAppId": {"type": "string", "nullable": true},
      "currentPageId": {"type": "string", "nullable": true}
    }
  }
}
```

## 6.7 设备遥测

### GET `/devices/{deviceId}/telemetry`

Path Params:

- `deviceId: string` (required)

Response `data` Schema（数组）:

```json
{
  "type": "array",
  "items": {
    "type": "object",
    "properties": {
      "id": {"type": "integer"},
      "deviceId": {"type": "string"},
      "wifiRssi": {"type": "integer", "nullable": true},
      "temperature": {"type": "number", "nullable": true},
      "freeHeapInternal": {"type": "integer", "nullable": true},
      "freeHeapTotal": {"type": "integer", "nullable": true},
      "uptimeS": {"type": "integer", "nullable": true},
      "createdAt": {"type": "string", "format": "date-time", "nullable": true}
    }
  }
}
```

## 6.8 设备控制（含 cmd_id）

### POST `/devices/{deviceId}/control`

Path Params:

- `deviceId: string` (required)

Request Schema:

```json
{
  "type": "object",
  "required": ["command", "value"],
  "properties": {
    "command": {"type": "string", "enum": ["brightness", "volume"]},
    "value": {"type": "integer", "minimum": 0, "maximum": 100}
  }
}
```

Response `data` Schema:

```json
{
  "type": "object",
  "properties": {
    "sent": {"type": "boolean"},
    "deviceId": {"type": "string"},
    "command": {"type": "string"},
    "cmdId": {"type": "string"},
    "requestedValue": {"type": "integer"},
    "status": {"type": "string", "enum": ["SENT", "FAILED"]}
  }
}
```

## 6.9 Worker 状态

### GET `/runtime/workers`

Response `data` Schema（数组）:

```json
{
  "type": "array",
  "items": {
    "type": "object",
    "properties": {
      "workerId": {"type": "string"},
      "state": {"type": "string"},
      "healthy": {"type": "boolean"},
      "handledRequests": {"type": "integer"},
      "lastError": {"type": "string", "nullable": true}
    }
  }
}
```

## 6.10 运维概览

### GET `/ops/overview`

Response `data` Schema:

```json
{
  "type": "object",
  "properties": {
    "totalDevices": {"type": "integer"},
    "onlineDevices": {"type": "integer"},
    "offlineDevices": {"type": "integer"},
    "sentCommands": {"type": "integer"},
    "failedCommands": {"type": "integer"},
    "ackedCommands": {"type": "integer"},
    "rejectedCommands": {"type": "integer"},
    "errorCommands": {"type": "integer"},
    "timeoutCommands": {"type": "integer"},
    "workerCount": {"type": "integer"},
    "healthyWorkerCount": {"type": "integer"},
    "workers": {"type": "array"}
  }
}
```

## 6.11 资源注册

### POST `/assets`

Request Schema:

```json
{
  "type": "object",
  "required": ["fileId", "assetType", "name"],
  "properties": {
    "fileId": {"type": "integer"},
    "assetType": {"type": "string", "examples": ["IMAGE_COVER", "AUDIO_CLIP"]},
    "name": {"type": "string"},
    "tags": {"type": "array", "items": {"type": "string"}, "nullable": true}
  }
}
```

Response `data` Schema:

```json
{
  "type": "object",
  "properties": {
    "id": {"type": "string"},
    "fileId": {"type": "integer"},
    "assetType": {"type": "string"},
    "name": {"type": "string"},
    "tags": {"type": "array", "items": {"type": "string"}},
    "processedStatus": {"type": "string", "enum": ["READY", "FAILED", "PENDING"]},
    "processedPayload": {"type": "string"}
  }
}
```

## 6.12 资源列表

### GET `/assets`

Query Params:

- `keyword: string` (optional)

Response `data` 为资产数组，结构同 `6.11` 响应。

### GET `/assets/source-files`

用途：给前端“可选文件列表”，用于从已上传文件中选择并注册 SDUI 资产。

Query Params:

- `keyword: string` (optional, 按 originalFilename 模糊匹配)

Response `data` Schema:

```json
{
  "type": "array",
  "items": {
    "type": "object",
    "properties": {
      "fileId": {"type": "integer"},
      "originalFilename": {"type": "string"},
      "sourceSystem": {"type": "string", "nullable": true},
      "createdAt": {"type": "string", "format": "date-time"}
    }
  }
}
```

## 6.13 绑定资源到应用

### POST `/apps/{appId}/assets:bind`

Path Params:

- `appId: string` (required)

Request Schema:

```json
{
  "type": "object",
  "required": ["assetId", "usageType"],
  "properties": {
    "assetId": {"type": "string"},
    "usageType": {"type": "string", "examples": ["cover", "icon", "sound"]}
  }
}
```

## 6.13A 资源集合（Asset Set）

> 说明：应用生成阶段建议使用 `assetSetIds` 精确指定上下文，不再把全部资源注入 AI 上下文。

### POST `/asset-sets`

Request Schema:

```json
{
  "type": "object",
  "required": ["name"],
  "properties": {
    "name": {"type": "string"},
    "description": {"type": "string", "nullable": true},
    "tags": {"type": "array", "items": {"type": "string"}, "nullable": true}
  }
}
```

### GET `/asset-sets`

Query Params:

- `keyword: string` (optional)

### POST `/asset-sets/{assetSetId}/items`

Request Schema:

```json
{
  "type": "object",
  "required": ["assetIds"],
  "properties": {
    "assetIds": {"type": "array", "items": {"type": "string"}, "minItems": 1}
  }
}
```

### GET `/asset-sets/{assetSetId}/items`

返回该集合下的资源项（含 `asset` 引用与排序字段）。

### DELETE `/asset-sets/{assetSetId}/items/{itemId}`

删除集合内某一资源项。

## 6.14 查询应用资源绑定

### GET `/apps/{appId}/assets`

Path Params:

- `appId: string` (required)

Response `data`：绑定数组。

说明：运行时上下文中的 `assets` 现在包含 `processed_status` 和 `processed_payload`，
应用脚本可直接读取图片压缩/音频转码后的结果路径与参数。

### DELETE `/apps/{appId}/assets/{bindingId}`

用途：解绑应用和资源的关联关系。

Path Params:

- `appId: string` (required)
- `bindingId: string` (required)

---

## 7. WebSocket 协议正式版

统一信封：

```json
{
  "topic": "string",
  "device_id": "string",
  "payload": {}
}
```

## 7.1 上行 Topic（终端 -> 后端）

### `telemetry/heartbeat`

```json
{
  "type": "object",
  "properties": {
    "wifi_rssi": {"type": "integer"},
    "temperature": {"type": "number"},
    "free_heap_internal": {"type": "integer"},
    "free_heap_total": {"type": "integer"},
    "uptime_s": {"type": "integer"}
  }
}
```

### `ui/click`

```json
{
  "type": "object",
  "properties": {
    "id": {"type": "string"},
    "action": {"type": "string", "nullable": true}
  }
}
```

### `ui/page_changed`

```json
{
  "type": "object",
  "properties": {
    "viewport": {"type": "string", "nullable": true},
    "page": {"type": "string"},
    "index": {"type": "integer", "nullable": true}
  }
}
```

### `motion`

```json
{
  "type": "object",
  "properties": {
    "type": {"type": "string"},
    "value": {"nullable": true}
  }
}
```

### `audio/record`

```json
{
  "type": "object",
  "properties": {
    "phase": {"type": "string", "examples": ["start", "stream", "stop"]},
    "context": {"type": "string", "nullable": true}
  }
}
```

### `ui/change`

```json
{
  "type": "object",
  "properties": {
    "id": {"type": "string"},
    "value": {}
  }
}
```

### `cmd/control_ack`（正式）

```json
{
  "type": "object",
  "required": ["cmd_id", "action", "status"],
  "properties": {
    "cmd_id": {"type": "string"},
    "action": {"type": "string", "enum": ["brightness", "volume"]},
    "status": {"type": "string", "enum": ["ACKED", "REJECTED", "ERROR"]},
    "reason": {"type": "string", "nullable": true},
    "requested_value": {"type": "integer", "nullable": true},
    "applied_value": {"type": "integer", "nullable": true},
    "ts": {"type": "integer", "nullable": true}
  }
}
```

## 7.2 下行 Topic（后端 -> 终端）

### `ui/layout`

`payload` 为完整布局树对象。

### `ui/update`

支持两种 payload：

1. `ops[]` 事务模式（推荐）
2. 直接 patch 模式

`revision/transaction` 规则：

1. 同一 `device_id + page_id` 的 `revision` 必须单调递增。
2. 包含 `ops[]` 时，建议并默认设置 `transaction=true`。
3. 终端遇到旧 `revision` 应丢弃，防止乱序覆盖。

### `cmd/control`

```json
{
  "type": "object",
  "required": ["cmd_id", "action", "value"],
  "properties": {
    "cmd_id": {"type": "string"},
    "action": {"type": "string", "enum": ["brightness", "volume"]},
    "value": {"type": "integer", "minimum": 0, "maximum": 100}
  }
}
```

---

## 8. Postman Collection

已提供可导入集合：

- `docs/sdui/SDUI_MVP.postman_collection.json`

导入后请设置变量：

- `baseUrl`：例如 `http://localhost:8080`
- `spaceId`：例如 `default`
- `token`：若未启用鉴权可留空

---

## 9. 前端联调建议

1. 先跑通：`assets -> apps/generate -> apps/publish -> devices/control`
2. 设备页重点展示 `cmdId` 与 ACK 最终状态
3. 运维页直接消费 `/ops/overview`
4. 统一封装 API Client，内建 `X-Space-Id`


---

## 10. 设备两阶段注册（2026-03 更新）

为解决“终端无状态接入”和“多租户隔离”之间的冲突，SDUI 设备接入流程调整为两阶段：

1. 全局注册阶段（UNCLAIMED）
- 设备首次连接 ws://<host>:8080/ws/sdui 并发送 telemetry/heartbeat 后，后端自动创建设备记录。
- 设备状态为 registrationStatus=UNCLAIMED。
- 后端会下发内置注册页（ui/layout），页面展示 device_id 和 claimCode。

2. 租户认领阶段（CLAIMED）
- 管理端在目标空间下调用认领接口，将设备绑定到当前 X-Space-Id。
- 认领成功后设备状态切换为 registrationStatus=CLAIMED。
- 若尚未发布应用，后端会下发“Waiting for app publish”页面；发布应用后进入业务页面。

### 10.1 新增接口

### GET /devices/unclaimed
- 用途：获取待认领设备列表。
- 备注：该列表用于管理端执行设备认领操作。

### POST /devices/{deviceId}/claim

Request:
~~~json
{
  "claimCode": "ABC123",
  "deviceName": "前台屏-01"
}
~~~

说明：
- claimCode 必填（4~12 位）。
- deviceName 可选（最大 100 字符）。

### 10.2 前端联调顺序（推荐）

1. 设备上线后，调用 GET /devices/unclaimed 确认设备出现。
2. 使用设备显示的 claimCode 调用 POST /devices/{deviceId}/claim 认领到当前空间。
3. 认领完成后执行应用发布：POST /apps/{appId}/publish。
4. 最后联调控制链路：POST /devices/{deviceId}/control + cmd/control_ack。

### 10.3 兼容性说明

- 设备 WebSocket 上行消息中的 space_id 现在是可选兼容字段。
- 新流程不再依赖设备上报 space_id，由后端基于“设备认领关系”推导租户归属。
