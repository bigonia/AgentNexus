# SDUI API 变更说明

> 用于前端同步修改。所有改动向后兼容，无破坏性变更。

---

## 1. 设备详情接口 — 新增字段

**`GET /api/v1/sdui/devices/{deviceId}`**

响应新增 `recentCommands` 字段，返回最近 10 条下发命令及其 ACK 状态：

```json
{
  "code": 20000,
  "data": {
    "deviceId": "1051DB398BD0",
    "availableCommands": ["rgb.effect.set", "rgb.off", "display.brightness.set", ...],
    "recentCommands": [
      {
        "cmdId": "3dc5210d-27a6-4cf8-b927-e5ab8a4e3162",
        "action": "rgb_off",
        "status": "ACKED",
        "reason": "",
        "createdAt": "2026-05-25T09:50:02"
      },
      {
        "cmdId": "...",
        "action": "rgb_set",
        "status": "TIMEOUT",
        "reason": "ack_timeout",
        "createdAt": "..."
      }
    ],
    "...": "其余字段不变"
  }
}
```

**前端建议**：在设备详情面板底部添加"最近命令"时间线，按 status 显示不同颜色（ACKED=绿, REJECTED=红, TIMEOUT=橙, SENT=灰）。

---

## 2. RGB 控制接口 — 响应新增 action 字段

**`POST /api/v1/sdui/devices/{deviceId}/console/rgb/apply`**

响应新增 `action` 字段，表示实际发送的固件 action 名：

```json
{
  "code": 20000,
  "data": {
    "sent": true,
    "deviceId": "1051DB398BD0",
    "cmdId": "...",
    "action": "rgb_policy",
    "mode": "blink",
    "color": "#ff0000",
    "periodMs": 2000
  }
}
```

无破坏性变更，不影响现有前端逻辑。

---

## 3. 调试端点（新增）

### 3.1 查看命令参数 Schema

**`GET /api/v1/sdui/devices/{deviceId}/console/debug/commands`**

返回终端支持的所有命令及其参数定义（类型、范围、默认值、是否必填）：

```json
{
  "code": 20000,
  "data": {
    "deviceId": "1051DB398BD0",
    "online": true,
    "availableCommands": ["rgb.effect.set", "rgb.off", "display.brightness.set", "device.reboot"],
    "schemas": {
      "rgb.effect.set": {
        "command": "rgb.effect.set",
        "description": "RGB灯效控制",
        "fields": {
          "mode":       {"type":"enum","values":["solid","blink","breathe","rainbow","chase"],"required":true,"label":"效果模式"},
          "r":          {"type":"int","min":0,"max":255,"default":255,"label":"红色"},
          "g":          {"type":"int","min":0,"max":255,"default":255,"label":"绿色"},
          "b":          {"type":"int","min":0,"max":255,"default":255,"label":"蓝色"},
          "brightness": {"type":"int","min":0,"max":255,"default":128,"label":"亮度"},
          "period_ms":  {"type":"int","min":100,"max":20000,"default":2000,"label":"周期(ms)"}
        }
      },
      "rgb.off": {
        "command": "rgb.off",
        "description": "关闭RGB",
        "fields": {}
      }
    }
  }
}
```

**FieldDef 类型说明**：

| 字段 | 含义 |
|------|------|
| `type` | `"int"` / `"enum"` / `"string"` / `"bool"` |
| `min`, `max` | 数值范围（仅 type=int 时有值） |
| `values` | 枚举可选值（仅 type=enum 时有值） |
| `default` | 默认值（null 表示无默认值） |
| `required` | 是否必填 |
| `label` | 中文标签 |

### 3.2 发送调试命令

**`POST /api/v1/sdui/devices/{deviceId}/console/debug/command`**

发送命令并返回完整 round-trip 信息。后端自动：填充默认值 → 校验参数 → 发送 → 返回结果。

请求体：
```json
{
  "command": "rgb.effect.set",
  "params": {
    "mode": "blink",
    "r": 255
  },
  "action": null
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `command` | 是 | 语义命令名，如 `rgb.effect.set` |
| `params` | 否 | 命令参数，未填的字段自动使用默认值 |
| `action` | 否 | 覆盖自动解析的 action 名（调试用，通常不填） |

响应：
```json
{
  "code": 20000,
  "data": {
    "status": "SENT",
    "sent": true,
    "deviceId": "1051DB398BD0",
    "online": true,
    "command": "rgb.effect.set",
    "resolvedTopic": "cmd/control",
    "resolvedAction": "rgb_set",
    "effectiveAction": "rgb_policy",
    "appliedDefaults": true,
    "validationErrors": [],
    "paramsSent": {"mode":"blink","r":255,"g":255,"b":255,"brightness":128,"period_ms":2000},
    "payloadSent": "{\"cmd_id\":\"...\",\"action\":\"rgb_policy\",\"mode\":\"blink\",\"r\":255,...}",
    "cmdId": "3dc5210d-...",
    "schema": { ... },
    "availableCommands": ["rgb.effect.set", "rgb.off", ...]
  }
}
```

校验不通过时（`status: "VALIDATION_FAILED"`）：
```json
{
  "status": "VALIDATION_FAILED",
  "sent": false,
  "validationErrors": ["r: expected int, got String", "mode: expected one of [solid,blink,breathe,rainbow,chase], got invalid"],
  "paramsSent": {"mode":"invalid","r":"not_a_number"},
  ...
}
```

---

## 4. 前端可利用的新能力

### 4.1 动态表单生成

根据 `GET /debug/commands` 返回的 schema，前端可以为每个命令动态生成参数表单控件：

```
type=int    → <input type="number" :min="min" :max="max" />
type=enum   → <select><option v-for="v in values">{{ v }}</option></select>
type=bool   → <input type="checkbox" />
type=string → <input type="text" />
```

### 4.2 命令测试面板

建议在设备详情页添加一个"命令测试"面板：

```
┌─ 命令测试 ─────────────────────────────┐
│ 命令: [rgb.effect.set ▼]              │
│                                        │
│ 效果模式: [blink ▼]                   │
│ 红色:     [255] ───●───               │
│ 绿色:     [0]   ──●────               │
│ 蓝色:     [0]   ──●────               │
│ 亮度:     [128] ───●───               │
│ 周期(ms): [2000] ───●───              │
│                                        │
│ [发送]  [重置默认值]                   │
│                                        │
│ ── 最近命令 ────────────────────────── │
│ 09:50:02  rgb_off      ACKED          │
│ 09:49:55  rgb_policy   ACKED          │
│ 09:49:30  rgb_set      TIMEOUT        │
└────────────────────────────────────────┘
```

### 4.3 设备详情中的命令历史

利用 `recentCommands` 字段，显示每条命令的状态和原因，帮助快速定位终端不响应的问题。

---

## 变更影响总结

| 接口 | 变更类型 | 前端需改？ |
|------|---------|----------|
| `GET /devices/{id}` | 新增字段 `recentCommands` | 可选 |
| `POST .../rgb/apply` | 新增字段 `action` | 否 |
| `GET .../debug/commands` | 新接口 | 可选（调试面板用） |
| `POST .../debug/command` | 新接口 | 可选（调试面板用） |
