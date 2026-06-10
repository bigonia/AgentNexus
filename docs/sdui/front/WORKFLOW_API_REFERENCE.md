# SDUI 工作流 API 详细说明

本文档是 SDUI 工作流模块的完整 API 参考，包含所有端点、请求/响应格式和参数说明。

业务概念和流转方式请参阅 [`WORKFLOW_BUSINESS_GUIDE.md`](WORKFLOW_BUSINESS_GUIDE.md)。

---

## 目录

- [1. 通用约定](#1-通用约定)
- [2. 节点与触发器目录](#2-节点与触发器目录)
- [3. 工作流定义 CRUD](#3-工作流定义-crud)
- [4. 编辑器辅助](#4-编辑器辅助)
- [5. 设备绑定与运行控制](#5-设备绑定与运行控制)
- [6. 运行时状态查询](#6-运行时状态查询)
- [7. 消息注入与 Webhook](#7-消息注入与-webhook)
- [8. 数据模型参考](#8-数据模型参考)
- [9. 错误码](#9-错误码)

---

## 1. 通用约定

### 1.1 Base Path

```
/api/v1/sdui/workflows
```

### 1.2 通用响应格式

所有接口统一返回 `ApiResponse<T>`：

```json
{
  "code": 20000,
  "message": "Success",
  "data": { }
}
```

- `code: 20000` — 成功
- `code: 40000` — 业务错误（详见 data 中的具体信息）
- `code: 40400` — 资源不存在

### 1.3 认证

所有接口需要 JWT Bearer Token（`Authorization: Bearer <token>`），并通过 `X-Space-Id` 头做多租户隔离。

### 1.4 时间格式

所有时间字段使用 ISO 8601 格式，例如 `"2026-06-07T09:15:00"`。

---

## 2. 节点与触发器目录

这些接口是工作流编辑器的数据源，返回"用户可以选择什么"。

### 2.1 获取节点目录

- **方法**：`GET`
- **路径**：`/api/v1/sdui/workflows/nodes`

#### Query 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceId` | string | 否 | 不传返回全局节点（platform + flow_control）；传入额外返回该设备支持的 device 节点 |

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "groups": [
      {
        "group": "device",
        "nodes": [
          {
            "type": "device.control",
            "displayName": "设备控制",
            "description": "向设备发送控制命令",
            "category": "device",
            "icon": "settings",
            "source": "device",
            "deviceSupported": true,
            "protocol": "command.control",
            "runtimeHandler": "device.command",
            "inputs": [
              {
                "name": "command",
                "type": "string",
                "required": true,
                "defaultValue": null,
                "description": "选择要执行的设备命令",
                "constraints": {
                  "source": "device.commands",
                  "options": [
                    {
                      "value": "display.brightness.set",
                      "params": [
                        { "name": "value", "type": "int" }
                      ]
                    },
                    {
                      "value": "rgb.effect.set",
                      "params": [
                        { "name": "effect", "type": "string" },
                        { "name": "speed", "type": "int" }
                      ]
                    }
                  ]
                }
              },
              {
                "name": "params",
                "type": "map",
                "required": false,
                "defaultValue": null,
                "description": "命令参数，根据所选命令动态填充",
                "constraints": {}
              }
            ],
            "outputs": [],
            "constraints": { "commandSource": "resolved_contract.outputs" },
            "defaultConfig": {}
          }
        ]
      },
      {
        "group": "platform",
        "nodes": [ /* ... */ ]
      },
      {
        "group": "flow_control",
        "nodes": [ /* ... */ ]
      }
    ],
    "nodes": [ /* 扁平化的所有节点列表 */ ]
  }
}
```

#### 节点对象字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | string | 节点类型标识，如 `device.control` |
| `displayName` | string | 中文显示名 |
| `description` | string | 功能说明 |
| `category` | string | `device` / `platform` / `flow_control` |
| `icon` | string | 图标标识 |
| `source` | string | 能力来源：`device` / `platform` / `workflow` |
| `deviceSupported` | boolean\|null | 当前设备是否支持（`null` 表示全局节点，不适用设备维度） |
| `runtimeHandler` | string\|null | 运行时处理器标识 |
| `inputs` | ParamDef[] | 输入参数定义 |
| `outputs` | ParamDef[] | 输出参数定义 |
| `constraints` | object | 约束条件 |
| `defaultConfig` | object | 默认参数值 |

#### ParamDef 对象

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | string | 参数名 |
| `type` | string | 参数类型：`string` / `int` / `float` / `boolean` / `object` / `array` |
| `required` | boolean | 是否必填 |
| `defaultValue` | any | 默认值 |
| `description` | string | 参数说明 |
| `constraints` | object | 约束（可能含 `options`、`min`、`max` 等）。对于 `device.control` 节点的 `command` 参数，`constraints.options` 包含设备支持的命令列表及每个命令的参数schema，与 `GET /debug/{deviceId}/commands` 同源。 |

#### 命令选项（constraints.options）格式

当参数为命令选择器时，`constraints.options` 中包含命令列表：

```json
{
  "source": "device.commands",
  "options": [
    {
      "value": "display.brightness.set",
      "params": [
        { "name": "value", "type": "int" }
      ]
    }
  ]
}
```

- `value` — 命令标识，传给 `device.control` 的 `command` 参数
- `params` — 该命令的参数 schema，字段格式为 `{name, type}`，与 debug 命令 API 一致
- `source: "device.commands"` — 标识数据来源为设备命令目录

> **与 debug API 的关系**：`constraints.options` 中的命令列表与 `GET /api/v1/sdui/debug/{deviceId}/commands` 返回的 `deviceCommands` 数组来自同一数据源（`DeviceCapabilityProjection.commands()`），字段格式一致。工作流编辑器可以用 `constraints.options` 渲染命令下拉框，用户选择命令后根据 `params` 动态生成参数表单。

---

### 2.2 获取触发器目录

- **方法**：`GET`
- **路径**：`/api/v1/sdui/workflows/triggers`

#### Query 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceId` | string | 否 | 不传只返回通用触发器；传入额外返回该设备的 UI 事件和命令触发器 |

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "triggers": [
      {
        "type": "manual",
        "displayName": "手动触发",
        "description": "由前端或调试端手动触发",
        "params": []
      },
      {
        "type": "cron",
        "displayName": "定时触发",
        "description": "按 cron 或固定间隔执行",
        "params": [
          { "name": "interval", "type": "int", "required": false, "description": "固定间隔秒数" },
          { "name": "cron", "type": "string", "required": false, "description": "Cron 表达式" }
        ]
      },
      {
        "type": "webhook",
        "displayName": "Webhook",
        "description": "外部 HTTP 请求触发",
        "params": [
          { "name": "path", "type": "string", "required": true, "description": "Webhook 路径" }
        ]
      },
      {
        "type": "device_message",
        "displayName": "设备消息",
        "description": "来自其他系统或设备的消息",
        "params": [
          { "name": "messageType", "type": "string", "required": false, "description": "消息类型" },
          { "name": "sourceType", "type": "string", "required": false, "description": "来源类型" },
          { "name": "sourceId", "type": "string", "required": false, "description": "来源 ID" }
        ]
      },
      {
        "type": "device.ui.event",
        "displayName": "ui:button.press",
        "description": "",
        "eventType": "ui:button.press",
        "source": "device",
        "capability": "inputs",
        "params": [
          { "name": "eventType", "type": "string", "required": true, "default": "ui:button.press", "description": "UI 事件类型" },
          { "name": "pageId", "type": "string", "required": false, "description": "页面过滤" },
          { "name": "sectionId", "type": "string", "required": false, "description": "Section 过滤" },
          { "name": "nodeId", "type": "string", "required": false, "description": "Node 过滤" }
        ]
      },
      {
        "type": "device_command",
        "displayName": "display.brightness.set",
        "description": "",
        "command": "display.brightness.set",
        "source": "device",
        "capability": "outputs",
        "params": [
          { "name": "command", "type": "string", "required": true, "default": "display.brightness.set", "description": "命令名" }
        ]
      }
    ]
  }
}
```

#### 触发器类型汇总

| type 值 | 说明 | 特有参数 |
|---------|------|---------|
| `manual` | 手动触发 | 无 |
| `cron` | 定时触发 | `interval`（秒）, `cron`（cron 表达式） |
| `webhook` | Webhook | `path`（URL 路径） |
| `device_message` | 设备消息 | `messageType`, `sourceType`, `sourceId` |
| `device.ui.event` | 设备 UI 事件 | `eventType`, `pageId`（可选过滤）, `sectionId`（可选过滤）, `nodeId`（可选过滤） |
| `device_command` | 设备命令 | `command` |

---

### 2.3 获取页面编辑器元数据

- **方法**：`GET`
- **路径**：`/api/v1/sdui/workflows/page-editor`

#### Query 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceId` | string | 是 | 目标设备 ID |

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "layouts": ["vertical_scroll", "fixed_single"],
    "renderMode": "rich",
    "sectionTypes": [
      {
        "type": "hero_section",
        "displayFields": [
          { "name": "value", "type": "string", "required": true },
          { "name": "label", "type": "string", "required": false }
        ],
        "events": ["click", "long_press"],
        "patchOps": ["add", "update", "remove"]
      }
    ]
  }
}
```

---

## 3. 工作流定义 CRUD

### 3.1 列表定义

- **方法**：`GET`
- **路径**：`/api/v1/sdui/workflows/definitions`

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": [
    {
      "id": "wf-dashboard-v1",
      "name": "仪表盘自动刷新",
      "icon": "dashboard",
      "definitionJson": "{...}",
      "createdAt": "2026-06-01T10:00:00",
      "updatedAt": "2026-06-07T09:00:00"
    }
  ]
}
```

#### WorkflowDefinitionEntity 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string(64) | 唯一标识，创建时由前端指定 |
| `name` | string(100) | 名称，必填 |
| `icon` | string(32) | 图标标识 |
| `definitionJson` | string (TEXT) | 核心：WorkflowDefinition 的 JSON |
| `createdAt` | datetime | 创建时间 |
| `updatedAt` | datetime | 更新时间 |

---

### 3.2 获取定义详情

- **方法**：`GET`
- **路径**：`/api/v1/sdui/workflows/definitions/{id}`

响应格式同列表项。

---

### 3.3 创建定义

- **方法**：`POST`
- **路径**：`/api/v1/sdui/workflows/definitions`

#### Request Body

```json
{
  "id": "wf-alert-v1",
  "name": "报警工作流",
  "icon": "warning",
  "definitionJson": "{...WorkflowDefinition JSON...}"
}
```

- `definitionJson` 必须是合法的 `WorkflowDefinition` JSON 字符串（会被 normalize 处理）

#### 成功响应

返回保存后的完整 `WorkflowDefinitionEntity`。

---

### 3.4 更新定义

- **方法**：`PUT`
- **路径**：`/api/v1/sdui/workflows/definitions/{id}`

Body 同创建。`id` 从路径获取，body 中的 `id` 会被覆盖。

---

### 3.5 删除定义

- **方法**：`DELETE`
- **路径**：`/api/v1/sdui/workflows/definitions/{id}`

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": { "deleted": "wf-alert-v1" }
}
```

---

### 3.6 校验定义

- **方法**：`POST`
- **路径**：`/api/v1/sdui/workflows/definitions/validate`

#### Request Body

完整的 `WorkflowDefinitionEntity` JSON（同创建接口）。

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "valid": true,
    "errors": [],
    "warnings": ["Workflow has no pages defined. No UI will be shown on load."],
    "triggerIssues": [],
    "nodeIssues": [],
    "pageIssues": [{ "issue": "Workflow has no pages defined." }],
    "bindingIssues": [],
    "graphIssues": []
  }
}
```

#### 校验维度

| 校验项 | 检查内容 |
|--------|---------|
| 结构校验 | `definitionJson` 是否为合法 JSON |
| 名称校验 | `name` 不能为空 |
| 触发器校验 | 是否至少有一个触发器；触发器引用是否存在 |
| 节点校验 | `nodeType` 是否为空；参数是否完整 |
| DAG 校验 | 边引用的 from/to 节点是否存在；是否有环 |
| 页面校验 | 页面 section 的 type 是否为空；pageId/sectionId 是否重复 |
| 引用校验 | actions map 的 key 是否都有对应 trigger 引用 |

---

## 4. 编辑器辅助

### 4.1 Scaffold 定义（编辑器快速保存）

- **方法**：`POST`
- **路径**：`/api/v1/sdui/workflows/definitions/scaffold`

将前端编辑器的 graph 结构（nodes + edges + triggers）转换为标准 `WorkflowDefinition` 并保存。

#### Request Body

```json
{
  "id": "wf-demo",
  "name": "Demo Workflow",
  "icon": "play",
  "pages": [
    {
      "id": "main",
      "layout": "vertical_scroll",
      "autoScroll": true,
      "autoScrollMs": 0,
      "sections": [
        {
          "id": "hero1",
          "type": "hero_section",
          "bind": {
            "value": "$data.temperature",
            "label": "'温度'"
          }
        }
      ]
    }
  ],
  "graph": {
    "triggers": [
      { "type": "cron", "id": "t1", "interval": 30 }
    ],
    "nodes": [
      {
        "id": "n1",
        "nodeType": "flow.fetch",
        "params": {
          "url": "https://api.example.com/data",
          "method": "GET",
          "save": "apiData"
        }
      }
    ],
    "edges": []
  },
  "virtualInputs": [],
  "virtualOutputs": []
}
```

#### Body 字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | 是 | workflow 唯一 ID |
| `name` | string | 是 | 名称 |
| `icon` | string | 否 | 图标 |
| `pages` | PageDef[] | 否 | 页面定义列表 |
| `graph.triggers` | TriggerDef[] | 是 | 触发器列表 |
| `graph.nodes` | GraphNode[] | 是 | 节点列表 |
| `graph.edges` | EdgeDef[] | 否 | DAG 边列表 |
| `virtualInputs` | VirtualIO[] | 否 | 虚拟输入 |
| `virtualOutputs` | VirtualIO[] | 否 | 虚拟输出 |

#### GraphNode 结构

```json
{
  "id": "n1",
  "nodeType": "device.control",
  "params": {
    "command": "display.brightness.set",
    "params": { "value": 80 }
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | 是 | 节点 ID，对应 actions map 的 key |
| `nodeType` | string | 是 | 节点类型，如 `device.control` |
| `params` | object | 否 | 节点参数 |

#### 成功响应

返回保存后的完整 `WorkflowDefinitionEntity`。

---

### 4.2 反向构建编辑器模型

- **方法**：`GET`
- **路径**：`/api/v1/sdui/workflows/definitions/{id}/editor-model`

将已保存的定义反序列化为前端编辑器可用的 graph 模型。

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "id": "wf-demo",
    "name": "Demo Workflow",
    "icon": "play",
    "pages": [ /* PageDef[] */ ],
    "virtualInputs": [],
    "virtualOutputs": [],
    "graph": {
      "triggers": [ /* TriggerDef[] */ ],
      "nodes": [
        {
          "id": "n1",
          "nodeType": "device.control",
          "params": { "command": "display.brightness.set" }
        }
      ],
      "edges": [ /* EdgeDef[] */ ]
    },
    "unsupportedActionGroups": []
  }
}
```

`unsupportedActionGroups` 列表包含无法反向转换为 graph node 的 action group ID（例如使用了 condition/sequence 等非 node 类型的 action）。

---

## 5. 设备绑定与运行控制

### 5.1 列出设备上的工作流

- **方法**：`GET`
- **路径**：`/api/v1/sdui/workflows/devices/{deviceId}`

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": [
    {
      "definitionId": "wf-dashboard-v1",
      "name": "仪表盘自动刷新",
      "status": "RUNNING",
      "activePage": "main",
      "installedAt": "2026-06-07T08:00:00",
      "variables": {
        "temperature": 25.6,
        "humidity": 68
      },
      "pageState": { /* 当前页面状态 */ },
      "lastExecution": {
        "id": "exec-uuid",
        "deviceId": "esp32-A1B2C3",
        "definitionId": "wf-dashboard-v1",
        "definitionName": "仪表盘自动刷新",
        "triggerId": "t1",
        "startedAt": "2026-06-07T09:00:00",
        "endedAt": "2026-06-07T09:00:01",
        "status": "SUCCESS",
        "failedNode": null,
        "failureReason": null,
        "nodeResults": [ /* ... */ ]
      }
    }
  ]
}
```

---

### 5.2 绑定工作流到设备

- **方法**：`POST`
- **路径**：`/api/v1/sdui/workflows/devices/{deviceId}/bind`

#### Query 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `definitionId` | string | 是 | 要绑定的工作流定义 ID |
| `mode` | string | 否 | 部署模式：`strict`（默认）/ `adaptive` / `force` |

#### 成功响应（正常加载）

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "status": "loaded",
    "deviceId": "esp32-A1B2C3",
    "definitionId": "wf-dashboard-v1",
    "definitionName": "仪表盘自动刷新",
    "activePage": "main",
    "mode": "strict"
  }
}
```

#### 成功响应（adaptive/force 模式有跳过项）

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "status": "loaded_adaptive",
    "deviceId": "esp32-A1B2C3",
    "definitionId": "wf-dashboard-v1",
    "definitionName": "仪表盘自动刷新",
    "activePage": "main",
    "mode": "adaptive",
    "warnings": [
      {
        "element": "action",
        "actionType": "node",
        "actionGroup": "n3",
        "capability": "device.audio.play",
        "issue": "Device does not support audio output",
        "severity": "warning",
        "suggestions": []
      }
    ],
    "skippedCount": 1
  }
}
```

#### 能力不匹配响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "status": "capability_mismatch",
    "deviceId": "esp32-A1B2C3",
    "definitionId": "wf-dashboard-v1",
    "mode": "strict",
    "issues": [
      {
        "element": "trigger",
        "triggerType": "device.ui.event",
        "triggerId": "t_btn",
        "capability": "ui:long_press",
        "issue": "Device does not support event: ui:long_press",
        "severity": "error",
        "optional": false,
        "suggestions": ["ui:button.press"]
      }
    ],
    "hint": "工作流引用了设备不支持的能力。可调用 GET /api/v1/sdui/capabilities/esp32-A1B2C3 查看设备能力。"
  }
}
```

#### Section 冲突响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "status": "conflict",
    "deviceId": "esp32-A1B2C3",
    "definitionId": "wf-dashboard-v2",
    "conflicts": [
      {
        "slot": "main/hero1",
        "sectionType": "hero_section",
        "ownedBy": "wf-dashboard-v1",
        "ownedByName": "仪表盘自动刷新"
      }
    ]
  }
}
```

---

### 5.3 解绑工作流

- **方法**：`DELETE`
- **路径**：`/api/v1/sdui/workflows/devices/{deviceId}/{definitionId}`

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "status": "unloaded",
    "deviceId": "esp32-A1B2C3",
    "definitionId": "wf-dashboard-v1"
  }
}
```

---

### 5.4 暂停工作流

- **方法**：`POST`
- **路径**：`/api/v1/sdui/workflows/devices/{deviceId}/{definitionId}/pause`

暂停后：所有触发器停止调度（cron 取消、webhook 路由移除），但实例和变量保留。

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "status": "paused",
    "deviceId": "esp32-A1B2C3",
    "definitionId": "wf-dashboard-v1"
  }
}
```

---

### 5.5 恢复工作流

- **方法**：`POST`
- **路径**：`/api/v1/sdui/workflows/devices/{deviceId}/{definitionId}/resume`

恢复后重新注册所有触发器。

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "status": "resumed",
    "deviceId": "esp32-A1B2C3",
    "definitionId": "wf-dashboard-v1"
  }
}
```

---

### 5.6 手动触发

- **方法**：`POST`
- **路径**：`/api/v1/sdui/workflows/devices/{deviceId}/trigger/{triggerId}`

`triggerId` 是工作流定义中 trigger 的 `id` 字段。后端自动查找该设备上包含此 trigger 的运行中工作流并执行。

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "sent": true,
    "deviceId": "esp32-A1B2C3",
    "triggerId": "t1"
  }
}
```

未找到匹配工作流时 `sent` 为 `false`。

---

## 6. 运行时状态查询

### 6.1 设备整体状态

- **方法**：`GET`
- **路径**：`/api/v1/sdui/workflows/devices/{deviceId}/status`

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "status": "active",
    "workflows": [ /* 同 5.1 的设备工作流列表 */ ],
    "pageState": { /* 当前页面状态 */ },
    "recentExecutions": [ /* 最近 20 条执行记录 */ ]
  }
}
```

设备无工作流时 `status` 为 `"no_workflow"`。

---

### 6.2 单个工作流状态

- **方法**：`GET`
- **路径**：`/api/v1/sdui/workflows/devices/{deviceId}/{definitionId}/status`

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "definitionId": "wf-dashboard-v1",
    "name": "仪表盘自动刷新",
    "status": "RUNNING",
    "activePage": "main",
    "installedAt": "2026-06-07T08:00:00",
    "triggers": 1,
    "variables": {
      "temperature": 25.6,
      "humidity": 68
    },
    "pageState": { /* 当前页面状态 */ },
    "recentExecutions": [ /* 最近 10 条执行记录 */ ]
  }
}
```

#### 状态值说明

| status | 说明 |
|--------|------|
| `RUNNING` | 正常运行 |
| `PAUSED` | 已暂停 |
| `STOPPED` | 已停止 |
| `not_found` | 设备上未找到该工作流实例 |

---

### 6.3 查看工作流变量

- **方法**：`GET`
- **路径**：`/api/v1/sdui/workflows/devices/{deviceId}/{definitionId}/variables`

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "definitionId": "wf-dashboard-v1",
    "name": "仪表盘自动刷新",
    "variables": {
      "temperature": 25.6,
      "humidity": 68,
      "apiData": { "status": "ok", "items": [] }
    }
  }
}
```

---

### 6.4 执行记录

- **方法**：`GET`
- **路径**：`/api/v1/sdui/workflows/devices/{deviceId}/{definitionId}/executions`
- **方法**：`GET`
- **路径**：`/api/v1/sdui/workflows/devices/{deviceId}/executions`

#### Query 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `limit` | int | 否 | 返回条数，默认 50 |

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": [
    {
      "id": "exec-uuid-123",
      "deviceId": "esp32-A1B2C3",
      "definitionId": "wf-dashboard-v1",
      "definitionName": "仪表盘自动刷新",
      "triggerId": "t1",
      "startedAt": "2026-06-07T09:00:00",
      "endedAt": "2026-06-07T09:00:01",
      "status": "SUCCESS",
      "failedNode": null,
      "failureReason": null,
      "nodeResults": [
        {
          "nodeType": "flow.fetch",
          "status": "COMPLETED",
          "outputs": { "save": "apiData" }
        }
      ]
    }
  ]
}
```

#### 执行记录字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 记录 UUID |
| `deviceId` | string | 设备 ID |
| `definitionId` | string | 工作流定义 ID |
| `definitionName` | string | 工作流名称 |
| `triggerId` | string | 触发的 trigger ID |
| `startedAt` | datetime | 开始时间 |
| `endedAt` | datetime | 结束时间 |
| `status` | string | `RUNNING` / `SUCCESS` / `ERROR` |
| `failedNode` | string\|null | 失败的节点类型（仅 ERROR 时） |
| `failureReason` | string\|null | 失败原因（仅 ERROR 时） |
| `nodeResults` | array | 各节点的执行结果 |

---

### 6.5 触发器状态

- **方法**：`GET`
- **路径**：`/api/v1/sdui/workflows/devices/{deviceId}/triggers`

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": [
    {
      "triggerId": "t1",
      "definitionId": "wf-dashboard-v1",
      "definitionName": "仪表盘自动刷新",
      "type": "cron",
      "interval": 30,
      "cron": null,
      "active": true
    },
    {
      "triggerId": "t_webhook",
      "definitionId": "wf-alert-v1",
      "definitionName": "报警工作流",
      "type": "webhook",
      "path": "alerts/sensor",
      "url": "/api/v1/sdui/webhook/alerts/sensor",
      "registered": true
    },
    {
      "triggerId": "t_btn",
      "definitionId": "wf-nav-v1",
      "definitionName": "导航工作流",
      "type": "device.ui.event",
      "eventType": "ui:button.press",
      "pageId": "main",
      "sectionId": null,
      "nodeId": "btn_next",
      "registered": true
    }
  ]
}
```

---

### 6.6 设备页面状态

- **方法**：`GET`
- **路径**：`/api/v1/sdui/workflows/devices/{deviceId}/page-state`

返回当前推送到设备的页面结构快照（由 `DebugSectionWorkspaceService` 维护）。

---

## 7. 消息注入与 Webhook

### 7.1 注入设备消息

- **方法**：`POST`
- **路径**：`/api/v1/sdui/workflows/devices/{deviceId}/message`

模拟一条来自其他系统或设备的消息，触发匹配的 `DeviceMessageTrigger`。

#### Request Body

```json
{
  "messageType": "sensor_reading",
  "sourceType": "external",
  "sourceId": "temp-sensor-01",
  "payload": {
    "temperature": 25.6,
    "humidity": 68
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `messageType` | string | 否 | 消息类型，默认 `"text"` |
| `sourceType` | string | 否 | 来源类型，默认 `"external"` |
| `sourceId` | string | 否 | 来源 ID，默认 `"unknown"` |
| `payload` | object | 否 | 消息负载，默认 `{}` |

#### 成功响应

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "status": "delivered",
    "deviceId": "esp32-A1B2C3",
    "messageId": "uuid-xxx",
    "matchedWorkflows": ["wf-alert-v1"]
  }
}
```

无匹配触发器时 `status` 为 `"no_matching_trigger"`。

---

### 7.2 Webhook 入口

- **方法**：`POST`
- **路径**：`/api/v1/sdui/workflows/webhook/{path}`

外部系统调用此 URL 触发工作流。`{path}` 对应 `WebhookTrigger` 的 `path` 字段。

**注意**：此接口不经过 `ApiResponse` 包装，直接返回纯 JSON。

#### Request Body（可选）

任意 JSON 对象，会进入 `$trigger` 变量空间。

#### 成功响应

```json
{
  "status": "ok",
  "deviceId": "esp32-A1B2C3",
  "path": "alerts/sensor"
}
```

无匹配设备时：

```json
{
  "status": "no_device",
  "path": "unknown/path"
}
```

---

## 8. 数据模型参考

### 8.1 WorkflowDefinition（definitionJson 内部结构）

```json
{
  "id": "wf-demo",
  "name": "Demo Workflow",
  "icon": "play",
  "pages": [
    {
      "id": "main",
      "layout": "vertical_scroll",
      "autoScroll": true,
      "autoScrollMs": 0,
      "sections": [
        {
          "id": "hero1",
          "type": "hero_section",
          "bind": { "value": "$data.temp", "label": "'温度'" }
        }
      ]
    }
  ],
  "triggers": [
    { "type": "cron", "id": "t1", "interval": 30 }
  ],
  "actions": {
    "t1": [
      { "type": "node", "nodeType": "flow.fetch", "params": { "url": "https://...", "save": "data" } }
    ]
  },
  "edges": [],
  "virtualInputs": [],
  "virtualOutputs": []
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 定义 ID |
| `name` | string | 名称 |
| `icon` | string | 图标 |
| `pages` | PageDef[] | 页面定义列表 |
| `triggers` | TriggerDef[] | 触发器列表 |
| `actions` | Map&lt;string, ActionDef[]&gt; | key = trigger 的 id，value = 该 trigger 对应的动作列表 |
| `edges` | EdgeDef[] | DAG 边，`{"from":"n1","to":"n2"}` |
| `virtualInputs` | VirtualIO[] | 虚拟输入 |
| `virtualOutputs` | VirtualIO[] | 虚拟输出 |

### 8.2 TriggerDef 完整类型

```json
// 手动触发
{ "type": "manual", "id": "t1" }

// 定时触发
{ "type": "cron", "id": "t1", "interval": 30 }
{ "type": "cron", "id": "t1", "cron": "0 */5 * * *" }

// Webhook
{ "type": "webhook", "id": "t1", "path": "alerts/sensor" }

// 设备 UI 事件
{
  "type": "device.ui.event",
  "id": "t1",
  "eventType": "ui:button.press",
  "pageId": "main",
  "sectionId": null,
  "nodeId": "btn_next",
  "optional": false
}

// 设备命令
{
  "type": "device_command",
  "id": "t1",
  "command": "display.brightness.set",
  "params": [],
  "optional": false
}

// 设备消息
{
  "type": "device_message",
  "id": "t1",
  "messageType": "sensor_reading",
  "sourceType": "external",
  "sourceId": "temp-sensor-01"
}
```

### 8.3 ActionDef 完整类型

```json
// 能力节点（主力类型）
{
  "type": "node",
  "nodeType": "device.control",
  "params": {
    "command": "display.brightness.set",
    "params": { "value": 80 }
  }
}

// HTTP 请求
{
  "type": "fetch",
  "url": "https://api.example.com/data",
  "method": "GET",
  "body": null,
  "save": "apiData"
}

// 条件分支
{
  "type": "condition",
  "variable": "$data.temp",
  "operator": "gt",
  "value": "30",
  "thenActions": [ /* ... */ ],
  "elseActions": [ /* ... */ ]
}

// 顺序执行
{
  "type": "sequence",
  "steps": [
    { "type": "node", "nodeType": "...", "params": {} },
    { "type": "node", "nodeType": "...", "params": {} }
  ]
}

// 设置变量
{
  "type": "set_variable",
  "variable": "threshold",
  "value": "100"
}
```

#### 条件运算符

| operator | 含义 |
|----------|------|
| `eq` | 等于 |
| `neq` | 不等于 |
| `gt` | 大于 |
| `gte` | 大于等于 |
| `lt` | 小于 |
| `lte` | 小于等于 |
| `contains` | 字符串包含 |
| `isEmpty` | 为空（字符串/数组/Map） |

### 8.4 PageDef

```json
{
  "id": "main",
  "layout": "vertical_scroll",
  "autoScroll": true,
  "autoScrollMs": 0,
  "sections": [
    {
      "id": "hero1",
      "type": "hero_section",
      "bind": {
        "value": "$data.temperature",
        "label": "'当前温度'"
      }
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 页面 ID |
| `layout` | string | 布局：`vertical_scroll` / `horizontal_pages` / `fixed_single` / `overlay` |
| `autoScroll` | boolean | 是否自动滚动 |
| `autoScrollMs` | int | 自动滚动间隔（毫秒），0 表示不自动 |
| `sections` | SectionBindDef[] | Section 绑定列表 |

### 8.5 SectionBindDef

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | Section ID，在页面内唯一 |
| `type` | string | Section 类型，如 `hero_section`、`metric_section` |
| `bind` | Map&lt;string, string&gt; | 字段绑定，value 支持 `$data.xxx`、`$trigger.xxx`、`$env.xxx` 表达式 |

---

## 9. 错误码

| code | 含义 | 常见场景 |
|------|------|---------|
| `20000` | 成功 | — |
| `40000` | 业务错误 | 参数校验失败、设备离线、能力不匹配、冲突 |
| `40400` | 资源不存在 | definition 未找到、设备未找到 |

业务错误时，`data` 中包含具体错误信息。常见模式：

```json
{
  "code": 40000,
  "message": "Success",
  "data": {
    "status": "capability_mismatch",
    "issues": [ /* 详细问题列表 */ ]
  }
}
```

> **注意**：即使业务失败，HTTP 状态码仍为 200，需要通过 `data.status` 或 `data` 中的具体字段判断结果。
