# SDUI 设备管理查看 API

本文档定义 SDUI 的“设备管理查看”接口，面向前端设备列表页、设备详情页、遥测图表页、连接状态页的直接接入。

本文档只覆盖设备查看与基础管理动作，不覆盖能力事实、设备调试、状态机编排：

- 设备能力查看：参阅能力目录和事件 catalog 文档
- 设备调试：参阅 [`DEBUG_API.md`](DEBUG_API.md)
- 状态机编排：参阅 [`STATE_MACHINE_FRONTEND_INTEGRATION.md`](STATE_MACHINE_FRONTEND_INTEGRATION.md)

---

## 1. 概览

### 1.1 模块目标

设备管理查看模块回答的问题是：

- 当前空间下有哪些设备
- 每台设备当前是否在线、最近状态如何
- 某台设备详情页首屏需要展示哪些聚合信息
- 设备最近的遥测、趋势、连接记录是什么
- 对设备执行哪些基础管理动作

### 1.2 Base Path

所有接口基于：

`/api/v1/sdui/devices`

### 1.3 通用响应包装

除特殊说明外，接口都返回 `ApiResponse<T>`：

```json
{
  "code": 20000,
  "message": "Success",
  "data": {}
}
```

### 1.4 与其他模块的边界

本模块包含：

- 设备列表与筛选
- 设备详情聚合读取
- 遥测历史与趋势
- 连接状态与断连原因
- 设备类型查询
- 设备命令目录查看
- 基础管理动作：认领、编辑、删除、未认领设备列表

本模块不包含：

- `GET /api/v1/sdui/capabilities/{deviceId}`
- `GET /api/v1/sdui/capabilities/{deviceId}/tree`
- `GET /api/v1/sdui/capabilities/{deviceId}/metadata`
- `GET /api/v1/sdui/capabilities/{deviceId}/events`
- `GET /api/v1/sdui/capabilities/{deviceId}/sections`
- 全部 `debug/*`
- 全部 `state-machines/*`

---

## 2. 前端页面映射

### 2.1 设备列表页

建议使用：

- `GET /api/v1/sdui/devices`
- 如需未认领设备入口，再调用 `GET /api/v1/sdui/devices/unclaimed`

主要渲染字段：

- `deviceId`
- `name`
- `status`
- `registrationStatus`
- `board`
- `screenShape`
- `inputMode`
- `sizeClass`
- `lastTelemetry`
- `currentAppId`
- `lastSeenAt`
- `connectedAt`
- `connectionCount`
- `claimedAt`

### 2.2 设备详情页首屏

建议首屏直接使用：

- `GET /api/v1/sdui/devices/{deviceId}`

该接口是聚合详情接口，适合一次性拿到：

- 基础信息
- 屏幕与输入特征
- 能力摘要
- 最近命令
- 最近遥测
- 当前连接会话信息

正式查看模型建议优先使用：

- `deviceId`
- `name`
- `notes`
- `status`
- `registrationStatus`
- `board`
- `screenShape`
- `screenWidth`
- `screenHeight`
- `inputMode`
- `sizeClass`
- `capabilitiesSummary`
- `lastTelemetry`
- `recentCommands`
- `connectedAt`
- `sessionId`
- `connectionCount`
- `totalUptimeS`
- `lastSeenAt`
- `claimedAt`
- `createdAt`

以下字段属于能力扩展或协议投影，可用于详情页扩展展示，但不建议替代正式查看字段：

- `availableCommands`
- `capabilityContract`

以下字段仅用于排障或诊断，不应作为设备查看页主数据源：

- `capabilitiesSnapshot`
- `capabilityDebugMetadata`

### 2.3 遥测与趋势页

建议使用：

- `GET /api/v1/sdui/devices/{deviceId}/telemetry`
- `GET /api/v1/sdui/devices/{deviceId}/telemetry/trends`
- 如需展示可执行命令与参数结构，再调用 `GET /api/v1/sdui/devices/{deviceId}/commands`

分页接口适合表格明细，趋势接口适合折线图、面积图、概览卡片。

### 2.4 连接记录页

建议使用：

- `GET /api/v1/sdui/devices/{deviceId}/connection-log`

适合展示：

- 当前 session
- 总连接/断连次数
- 断连原因分布
- 最近连接事件时间线

---

## 3. API 明细

## 3.1 获取设备列表

- 方法：`GET`
- 路径：`/api/v1/sdui/devices`

### Query 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `status` | string | 否 | 按在线状态筛选，常见值：`ONLINE`、`OFFLINE` |
| `registrationStatus` | string | 否 | 按认领状态筛选 |
| `board` | string | 否 | 按板卡筛选 |
| `search` | string | 否 | 按设备名或 `deviceId` 模糊搜索 |
| `sortBy` | string | 否 | 当前支持 `lastSeenAt`、`name`、`status`，默认 `lastSeenAt` |
| `sortDir` | string | 否 | `desc` 或 `asc`，默认 `desc` |
| `page` | int | 否 | 页码，从 `0` 开始，默认 `0` |
| `size` | int | 否 | 每页条数，默认 `20` |

### 成功响应示例

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "totalCount": 2,
    "onlineCount": 1,
    "offlineCount": 1,
    "page": 0,
    "size": 20,
    "items": [
      {
        "deviceId": "esp32-A1B2C3",
        "name": "客厅屏幕",
        "status": "ONLINE",
        "registrationStatus": "CLAIMED",
        "board": "ESP32-S3-Touch-AMOLED-1.75C",
        "screenShape": "round",
        "inputMode": "touch",
        "sizeClass": "large",
        "lastTelemetry": {
          "wifi": {
            "rssi": -52,
            "ip": "192.168.1.30"
          },
          "memory": {
            "freeHeapInternal": 231112,
            "largestHeapInternal": 180224,
            "freeHeapDma": 92544,
            "largestHeapDma": 65536,
            "freeHeapPsram": 0,
            "largestHeapPsram": 0,
            "freeHeapTotal": 323656,
            "fragInternalPct": 12,
            "fragDmaPct": 8,
            "fragPsramPct": 0
          },
          "temperature": {
            "celsius": 37.6
          },
          "power": {
            "supported": true,
            "batteryMv": 4060,
            "batteryPct": 91,
            "charging": false,
            "extPowerPresent": true,
            "extPowerCtrl": false,
            "extPowerOn": false
          },
          "uptimeS": 86400,
          "lastHeartbeatAt": "2026-06-07T09:15:00"
        },
        "currentAppId": "default-dashboard",
        "lastSeenAt": "2026-06-07T09:15:00",
        "connectedAt": "2026-06-07T08:00:00",
        "connectionCount": 14,
        "claimedAt": "2026-05-20T11:30:00",
        "createdAt": "2026-05-18T10:00:00"
      }
    ]
  }
}
```

### 字段说明

- `totalCount`：筛选后的设备总数
- `onlineCount`：当前结果集中在线设备数量
- `offlineCount`：当前结果集中离线设备数量
- `items`：当前页设备列表
- `lastTelemetry`：最近一条遥测的摘要投影，不是完整遥测实体

### 前端使用建议

- 列表页主卡片优先使用列表接口内联字段，不需要为每个设备再请求详情。
- 若只做总览，不建议在列表页直接读取 `capabilities/*`。

### 备注与限制

- 当前实现先拉取设备列表，再在内存中做筛选、排序、分页。
- `board`、`screenShape`、`inputMode`、`sizeClass` 来自设备能力快照；设备离线或未上报能力时可能为空。
- 当前排序实现对 `lastSeenAt` 的方向处理有实现细节，前端应以实际返回顺序为准，不要假设后端已做严格数据库级排序。

## 3.2 获取未认领设备列表

- 方法：`GET`
- 路径：`/api/v1/sdui/devices/unclaimed`

### 成功响应

- 返回：`ApiResponse<List<SduiDevice>>`

### 前端使用建议

- 用于认领页面或设备接入引导页。
- 该接口返回原始设备实体风格数据，不适合作为正式设备详情页模型。

## 3.3 获取设备详情

- 方法：`GET`
- 路径：`/api/v1/sdui/devices/{deviceId}`

### Path 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceId` | string | 是 | 设备 ID |

### 用途

这是设备详情页首屏聚合接口。

### 成功响应示例

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "name": "客厅屏幕",
    "notes": "靠近门口",
    "status": "ONLINE",
    "registrationStatus": "CLAIMED",
    "board": "ESP32-S3-Touch-AMOLED-1.75C",
    "screenShape": "round",
    "screenWidth": 466,
    "screenHeight": 466,
    "inputMode": "touch",
    "sizeClass": "large",
    "availableCommands": [
      "display.brightness.set",
      "device.reboot",
      "audio.volume.set"
    ],
    "capabilitiesSnapshot": "{...raw device capabilities json...}",
    "capabilitiesSummary": {
      "board": "ESP32-S3-Touch-AMOLED-1.75C",
      "screen": {
        "w": 466,
        "h": 466,
        "shape": "round"
      },
      "inputMode": "touch",
      "sizeClass": "large",
      "renderMode": "rich"
    },
    "capabilityContract": {
      "deviceId": "esp32-A1B2C3",
      "status": "READY"
    },
    "capabilityDebugMetadata": {
      "view": "debug"
    },
    "recentCommands": [
      {
        "cmdId": "cmd-001",
        "action": "display.brightness.set",
        "status": "ACKED",
        "reason": null,
        "createdAt": "2026-06-07T09:00:00"
      }
    ],
    "lastTelemetry": {
      "wifi": {
        "rssi": -52,
        "ip": "192.168.1.30"
      },
      "memory": {
        "freeHeapInternal": 231112,
        "largestHeapInternal": 180224,
        "freeHeapDma": 92544,
        "largestHeapDma": 65536,
        "freeHeapPsram": 0,
        "largestHeapPsram": 0,
        "freeHeapTotal": 323656,
        "fragInternalPct": 12,
        "fragDmaPct": 8,
        "fragPsramPct": 0
      },
      "temperature": {
        "celsius": 37.6
      },
      "power": {
        "supported": true,
        "batteryMv": 4060,
        "batteryPct": 91,
        "charging": false,
        "extPowerPresent": true,
        "extPowerCtrl": false,
        "extPowerOn": false
      },
      "uptimeS": 86400,
      "lastHeartbeatAt": "2026-06-07T09:15:00"
    },
    "connectedAt": "2026-06-07T08:00:00",
    "sessionId": "ws-session-123",
    "connectionCount": 14,
    "totalUptimeS": 432000,
    "lastSeenAt": "2026-06-07T09:15:00",
    "claimedAt": "2026-05-20T11:30:00",
    "createdAt": "2026-05-18T10:00:00"
  }
}
```

### 字段说明

正式查看字段：

- `deviceId`
- `name`
- `notes`
- `status`
- `registrationStatus`
- `board`
- `screenShape`
- `screenWidth`
- `screenHeight`
- `inputMode`
- `sizeClass`
- `capabilitiesSummary`
- `lastTelemetry`
- `recentCommands`
- `connectedAt`
- `sessionId`
- `connectionCount`
- `totalUptimeS`
- `lastSeenAt`
- `claimedAt`
- `createdAt`

能力扩展字段：

- `availableCommands`
- `capabilityContract`

debug-only 字段：

- `capabilitiesSnapshot`
- `capabilityDebugMetadata`

### 前端使用建议

- 详情页首屏应优先使用正式查看字段。
- 如需展示“该设备支持哪些命令”，可先读 `availableCommands`；如需渲染命令参数表单，应调用 `GET /api/v1/sdui/devices/{deviceId}/commands`，不要自行解析 `capabilitiesSnapshot`。
- 不要把 `capabilityDebugMetadata` 作为正式业务模型缓存。

### 备注与限制

- 若设备不存在，返回 `ApiResponse.error(40400, "device not found")`。
- `status` 以当前 WebSocket 在线状态为准，不完全等同于数据库内状态字段。

## 3.4 认领设备

- 方法：`POST`
- 路径：`/api/v1/sdui/devices/{deviceId}/claim`

### Body

`SduiClaimDeviceRequest`

```json
{
  "claimCode": "123456",
  "deviceName": "客厅屏幕"
}
```

### 字段约束

| 字段 | 类型 | 必填 | 约束 |
|------|------|------|------|
| `claimCode` | string | 是 | 长度 `4-12` |
| `deviceName` | string | 否 | 最大长度 `100` |

### 成功响应

- 返回：`ApiResponse<SduiDevice>`

### 前端使用建议

- 认领成功后，建议刷新设备列表或设备详情，而不是完全依赖返回实体进行页面拼装。

## 3.5 删除设备

- 方法：`DELETE`
- 路径：`/api/v1/sdui/devices/{deviceId}`

### 成功响应

- 返回：`ApiResponse<Void>`

### 备注

- 当前行为是将设备重置为未认领并断开连接，而不是简单物理删除记录。

## 3.6 更新设备信息

- 方法：`PATCH`
- 路径：`/api/v1/sdui/devices/{deviceId}`

### Body

```json
{
  "name": "新的设备名称",
  "notes": "放在书房"
}
```

### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 否 | 设备名 |
| `notes` | string | 否 | 备注 |

### 成功响应

- 返回：`ApiResponse<SduiDevice>`

### 备注与限制

- 至少要传 `name` 或 `notes` 其中一个。
- 两者都不传时，返回 `ApiResponse.error(40000, "at least one of 'name' or 'notes' is required")`。

## 3.7 获取遥测分页历史

- 方法：`GET`
- 路径：`/api/v1/sdui/devices/{deviceId}/telemetry`

### Query 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `page` | int | 否 | 页码，从 `0` 开始，默认 `0` |
| `size` | int | 否 | 每页条数，默认 `20`，最小 `1`，最大 `200` |

### 成功响应结构

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "content": [
      {
        "deviceId": "esp32-A1B2C3",
        "wifiRssi": -52,
        "ip": "192.168.1.30",
        "temperature": 37.6,
        "batteryPct": 91,
        "uptimeS": 86400,
        "createdAt": "2026-06-07T09:15:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 128,
    "totalPages": 7,
    "first": true,
    "last": false,
    "empty": false
  }
}
```

### 前端使用建议

- 用于表格、明细抽屉、历史记录下载前的预览。
- `content` 是 `SduiDeviceTelemetry` 实体列表，字段以真实实体为准。

## 3.8 获取遥测趋势

- 方法：`GET`
- 路径：`/api/v1/sdui/devices/{deviceId}/telemetry/trends`

### Query 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `range` | string | 否 | `1h` / `6h` / `24h` / `7d` / `30d`，默认 `24h` |
| `bucket` | string | 否 | `auto` / `1m` / `5m` / `10m` / `1h` / `6h`，默认 `auto` |
| `metrics` | string | 否 | `all` / `memory` / `temperature` / `wifi` / `battery`，默认 `all` |

### 自动 bucket 规则

| range | auto bucket |
|------|-------------|
| `1h` | `1m` |
| `6h` | `5m` |
| `24h` | `10m` |
| `7d` | `1h` |
| `30d` | `6h` |

### 成功响应示例

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "range": "24h",
    "bucket": "10m",
    "pointCount": 144,
    "series": {
      "wifi": {
        "rssi": [
          {
            "ts": "2026-06-07T08:00:00",
            "avg": -51.2,
            "min": -55.0,
            "max": -48.0
          }
        ]
      },
      "temperature": {
        "celsius": [
          {
            "ts": "2026-06-07T08:00:00",
            "avg": 37.5,
            "min": 36.9,
            "max": 38.1
          }
        ]
      },
      "memory": {
        "freeHeapTotal": [
          {
            "ts": "2026-06-07T08:00:00",
            "avg": 323656
          }
        ]
      },
      "battery": {
        "batteryPct": [
          {
            "ts": "2026-06-07T08:00:00",
            "avg": 91.0,
            "min": 90.0,
            "max": 91.0
          }
        ]
      }
    }
  }
}
```

### 前端使用建议

- 图表页建议一次只请求当前 tab 所需 `metrics`，避免拉取无关序列。
- `pointCount` 可用于判断图表是否需要抽样或空状态处理。

### 备注与限制

- `bucket=auto` 时由后端根据 `range` 自动计算聚合粒度。
- 各序列点对象的 `min`、`max` 字段是否存在，取决于该指标是否提供区间聚合。

## 3.9 获取连接日志

- 方法：`GET`
- 路径：`/api/v1/sdui/devices/{deviceId}/connection-log`

### Query 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `page` | int | 否 | 页码，从 `0` 开始，默认 `0` |
| `size` | int | 否 | 每页条数，默认 `20` |

### 成功响应示例

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "currentSession": {
      "connectedAt": "2026-06-07T08:00:00",
      "sessionId": "ws-session-123",
      "durationS": 4500
    },
    "stats": {
      "totalConnections": 14,
      "totalDisconnections": 13,
      "disconnectReasons": {
        "session_closed": 8,
        "duplicate_login": 3,
        "server_initiated": 2
      }
    },
    "events": [
      {
        "eventType": "CONNECTED",
        "sessionId": "ws-session-123",
        "ipAddress": "192.168.1.30",
        "disconnectReason": null,
        "eventAt": "2026-06-07T08:00:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalEvents": 14
  }
}
```

### 前端使用建议

- `currentSession` 适合详情页状态卡片。
- `stats.disconnectReasons` 适合饼图或统计徽标。
- `events` 适合时间线或表格。

### 备注与限制

- 当前实现只先取最近 `50` 条连接日志，再对这 `50` 条做分页，不是全量历史分页。
- `durationS` 只有设备当前在线且存在 `connectedAt` 时才会返回。

## 3.10 获取设备类型列表

- 方法：`GET`
- 路径：`/api/v1/sdui/devices/types`

### 成功响应结构

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "types": [
      {
        "typeId": "touch-large-round",
        "displayName": "大屏圆形触摸设备"
      }
    ],
    "totalCount": 1
  }
}
```

### 前端使用建议

- 用于分类筛选、设备分组、接入统计。
- `types` 的具体字段结构依赖 `CapabilityRegistry#getDeviceTypesAsList()`，前端应按实际返回解析。

## 3.11 获取设备命令目录

- 方法：`GET`
- 路径：`/api/v1/sdui/devices/{deviceId}/commands`

### 用途

返回设备详情页、运维面板或轻量命令测试面板可直接使用的命令目录。

### 成功响应示例

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "online": true,
    "commands": [
      {
        "command": "display.brightness.set",
        "displayName": "display.brightness.set",
        "description": null,
        "params": [
          {
            "name": "value",
            "type": "int",
            "label": "value"
          }
        ],
        "source": "device",
        "runtimeHandler": "device.command"
      },
      {
        "command": "audio.tts.speak",
        "displayName": "文本转语音",
        "description": "调用平台 TTS 能力播放文本",
        "params": [
          {
            "name": "text",
            "type": "string"
          }
        ],
        "source": "platform",
        "runtimeHandler": "platform.audio.tts"
      }
    ]
  }
}
```

### 字段说明

顶层字段：

- `deviceId`
- `online`
- `commands`

命令项字段：

- `command`
- `displayName`
- `description`
- `params`
- `source`
- `runtimeHandler`

参数项字段：

- `name`
- `type`
- `label`
- `required` 可选
- `default` 可选
- `min` 可选
- `max` 可选
- `options` 可选

### 前端使用建议

- 用于设备详情页“命令面板”、轻量运维面板或测试入口。
- `source=device` 表示来自设备命令目录；`source=platform` 表示来自平台托管能力。
- 若只需要展示“是否支持某命令”，优先读详情接口中的 `availableCommands`；若要渲染参数结构，应使用本接口。

---

## 4. 数据模型

### 4.1 设备列表项模型

核心字段：

- `deviceId`
- `name`
- `status`
- `registrationStatus`
- `board`
- `screenShape`
- `inputMode`
- `sizeClass`
- `lastTelemetry`
- `currentAppId`
- `lastSeenAt`
- `connectedAt`
- `connectionCount`
- `claimedAt`
- `createdAt`

### 4.2 设备详情模型

核心字段：

- `deviceId`
- `name`
- `notes`
- `status`
- `registrationStatus`
- `board`
- `screenShape`
- `screenWidth`
- `screenHeight`
- `inputMode`
- `sizeClass`
- `capabilitiesSummary`
- `recentCommands`
- `lastTelemetry`
- `connectedAt`
- `sessionId`
- `connectionCount`
- `totalUptimeS`
- `lastSeenAt`
- `claimedAt`
- `createdAt`

扩展字段：

- `availableCommands`
- `capabilityContract`
- `capabilitiesSnapshot`
- `capabilityDebugMetadata`

### 4.3 最近命令模型

字段：

- `cmdId`
- `action`
- `status`
- `reason`
- `createdAt`

### 4.4 遥测摘要模型

字段：

- `wifi.rssi`
- `wifi.ip`
- `memory.freeHeapInternal`
- `memory.largestHeapInternal`
- `memory.freeHeapDma`
- `memory.largestHeapDma`
- `memory.freeHeapPsram`
- `memory.largestHeapPsram`
- `memory.freeHeapTotal`
- `memory.fragInternalPct`
- `memory.fragDmaPct`
- `memory.fragPsramPct`
- `temperature.celsius`
- `power.supported`
- `power.batteryMv`
- `power.batteryPct`
- `power.charging`
- `power.extPowerPresent`
- `power.extPowerCtrl`
- `power.extPowerOn`
- `uptimeS`
- `lastHeartbeatAt`

### 4.5 遥测趋势模型

顶层字段：

- `deviceId`
- `range`
- `bucket`
- `pointCount`
- `series`

序列点字段：

- `ts`
- `avg`
- `min`
- `max`

### 4.6 连接日志模型

顶层字段：

- `deviceId`
- `currentSession`
- `stats`
- `events`
- `page`
- `size`
- `totalEvents`

事件字段：

- `eventType`
- `sessionId`
- `ipAddress`
- `disconnectReason`
- `eventAt`

### 4.7 设备命令目录模型

顶层字段：

- `deviceId`
- `online`
- `commands`

命令项字段：

- `command`
- `displayName`
- `description`
- `params`
- `source`
- `runtimeHandler`

---

## 5. 已知问题与前端约束

### 5.1 详情接口混入 debug-only 字段

当前 `GET /api/v1/sdui/devices/{deviceId}` 同时返回：

- 正式查看字段
- 能力投影字段
- debug-only 字段

前端设备查看页应优先依赖正式查看字段，不应把以下字段作为主渲染模型：

- `capabilitiesSnapshot`
- `capabilityDebugMetadata`

### 5.2 列表字段部分来自能力快照

以下字段来自设备能力快照而不是设备主表：

- `board`
- `screenShape`
- `inputMode`
- `sizeClass`

因此设备离线、未完成能力上报、缓存缺失时，这些字段可能为空。

### 5.3 列表分页是内存分页

当前列表接口不是数据库级筛选分页，而是：

1. 先取设备集合
2. 再做筛选
3. 再做排序
4. 最后做分页

前端应把它视为当前实现事实，而不是稳定性能契约。

### 5.4 连接日志不是全量分页

`GET /connection-log` 当前只基于最近 `50` 条连接事件做分页展示，不适合当作长期全量审计查询接口。

### 5.5 设备类型字段结构应按实际返回解析

`GET /types` 的 `types` 数组来自 `CapabilityRegistry#getDeviceTypesAsList()`，其对象字段应以前端实测返回为准，不要在前端写死为某个未文档化的固定 schema。

### 5.6 命令目录同时包含设备命令与平台托管能力

`GET /api/v1/sdui/devices/{deviceId}/commands` 当前会同时返回：

- `source=device` 的设备命令
- `source=platform` 的平台托管能力

因此如果页面只关心设备原生命令，不要直接把整个 `commands` 数组当作“纯设备协议命令列表”。

---

## 6. 接入建议

推荐的设备查看接入顺序：

1. 列表页接 `GET /api/v1/sdui/devices`
2. 详情页首屏接 `GET /api/v1/sdui/devices/{deviceId}`
3. 命令面板接 `GET /api/v1/sdui/devices/{deviceId}/commands`
4. 遥测 tab 接 `GET /telemetry` 与 `GET /telemetry/trends`
5. 连接 tab 接 `GET /connection-log`
6. 管理动作接 `claim / patch / delete`

如果只做“设备查看”而不做“设备调试”，不需要接入 `capabilities/*` 或 `debug/*`。
