# 能力与交互契约（提案）

> **状态**：提案，待平台侧确认。字段可在实现中调整，但**契约分层与语义不应变**。
> **关系**：模型结构见[业务流模型](./BUSINESS_MODEL.md)；
> 编译与运行见[编译与运行期](./COMPILATION_AND_RUNTIME.md)。

本文定义四类机器接口：

1. **能力契约**：源 / 转换器 / 执行器的输入输出声明；
2. **外部 provider 调用约定**：平台如何调用外部能力；
3. **触发器注册契约**：Agent/外部系统如何注册并调用触发器；
4. **编辑 API（MCP 工具）**：Agent/GUI 如何操作业务模型。

## 1. 能力契约

### 1.1 字段

```jsonc
{
  "id": "ai.stt",                 // 稳定标识，全局唯一
  "name": "语音转文字",
  "role": "transform",            // source | transform | action
  "version": 1,                   // 契约版本，签名变化时递增
  "in":  { "audio": { "contract": "audio", "required": true } },
  "out": { "text":  { "contract": "text", "scope": "invocation" } },
  "timeout_ms": 20000,            // 可选，默认由平台策略决定
  "errors": ["timeout", "stt_failed"],
  "idempotent": true              // 可选，默认 false
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 能力标识，被模型引用 |
| `name` | 是 | 显示名 |
| `role` | 是 | `source` / `transform` / `action` |
| `version` | 是 | 契约版本 |
| `in` | 否 | 输入参数；`source` 为空；参数默认必填，可选须显式标 `required:false` |
| `out` | 否 | 输出结果；`source` / `transform` 必填，`action` 可空 |
| `config` | 否 | 静态配置（如定时间隔），仅 `source` 使用 |
| `timeout_ms` | 否 | 超时 |
| `errors` | 否 | 该能力可能返回的错误码 |
| `idempotent` | 否 | 是否可安全重试 |

### 1.2 role 语义

| role | in | out | 触发方式 |
|---|---|---|---|
| `source` | 无（可有 `config`） | 有 | 设备事件 / 外部触发 / 系统调度 |
| `transform` | 有 | 有 | 流程内按序调用，或类型不匹配时插入 |
| `action` | 有 | 可空 | 页面事件或流程步骤 |

### 1.3 槽位契约（type）

`text` / `number` / `series` / `image` / `audio`。
`image` / `audio` 为资源引用，处理完释放（v1 仅 `invocation`）。

### 1.4 来源

| 来源 | 说明 |
|---|---|
| 平台内置 | 如 `device.audio.play`、`device.view.render`、`source.timer` |
| 外部实现 | 如 `ai.*`，平台只依赖契约，通过 provider 调用 |

## 2. 外部 provider 调用约定（HTTP 优先）

平台调用外部能力时，使用统一请求/响应信封。

**请求**

```jsonc
POST {provider_endpoint}
{
  "capability": "ai.stt",
  "version": 1,
  "invocation_id": "uuid",
  "input": { "audio": { "ref": "res_123", "url": "..." } },
  "context": { "device_id": "...", "business_id": "...", "session_key": "..." }
}
```

**响应**

```jsonc
{ "ok": true, "output": { "text": "你好" } }
{ "ok": false, "error": "stt_failed", "message": "..." }
```

| 约定 | 说明 |
|---|---|
| 鉴权 | provider 侧凭证由平台配置持有，**不进业务模型** |
| 超时 | 以能力契约 `timeout_ms` 为准 |
| 重试 | 仅当 `idempotent=true`；重试沿用同一 `invocation_id` |
| 错误映射 | provider `error` 直接映射为流程错误 |
| 资源 | 大对象用 `ref/url` 传递，不内联 |

> 不可达的 provider 采用"服务主动连接平台"的持久通道，见
> [Agent 交互 §6](./AGENT_INTERACTION.md#6-运行期出站平台调用-agent-写的服务)。

## 3. 触发器注册契约

### 3.1 注册

```jsonc
{
  "id": "stock.tick",
  "business_id": "biz_1",
  "name": "股票行情更新",
  "payload": {
    "symbol": { "contract": "text" },
    "price":  { "contract": "number" }
  },
  "idempotency_key": true
}
```

### 3.2 调用

```text
POST /trigger/{business_id}/{trigger_id}
{
  "idempotency_key": "客户提供，可选但强烈建议",
  "payload": { "symbol": "AAPL", "price": 189.5 }
}
```

### 3.3 响应与错误

```jsonc
{ "ok": true, "accepted": true }                       // 已受理
{ "ok": false, "error": "duplicate" }                  // 幂等键重复
{ "ok": false, "error": "invalid_payload", "detail": "price must be number" }
{ "ok": false, "error": "trigger_not_found" }
{ "ok": false, "error": "not_ready" }                  // 业务未部署/设备未就绪
```

**调度、重试、更新由注册方负责，平台不调度外部触发器。**

## 4. 编辑 API（MCP 工具）

所有工具都是**意图级**操作，GUI 与 Agent 共用同一套。

| 工具 | 参数 | 返回 | 错误 |
|---|---|---|---|
| `capabilities.list` | `{}` | 能力契约数组 | — |
| `templates.list` | `{}` | 模板 + 槽位契约 | — |
| `business.create` | `{name}` | `{id}` | `name_taken` |
| `business.get` | `{id}` | 完整模型 | `not_found` |
| `page.add` | `{id, key, mode, template?, slots?, buttons?}` | `{pageKey}` | `invalid_template`、`slot_contract_mismatch` |
| `page.update` | `{id, key, patch}` | `{ok}` | `not_found` |
| `button.bind` | `{id, pageKey, button, action}` | `{ok}` | `ui_rule_conflict`、`invalid_action` |
| `business.validate` | `{id}` | `{issues:[...]}` | — |
| `business.preview` | `{id, pageKey}` | `{image}` | 可选 |
| `business.deploy` | `{id, deviceId}` | `{ok}` | `device_offline`、`compile_failed` |

**校验错误结构（机器可读）**

```jsonc
{
  "code": "slot_contract_mismatch",
  "where": "pages.reply.slots.text",
  "expected": "text",
  "actual": "audio",
  "suggestion": "insert converter ai.tts"
}
```

**约束**

- 工具不暴露烧录、Recovery、整片擦写；
- 按租户/业务作用域隔离；
- `save` 与 `deploy` 分离，保存不自动生效。

## 5. 版本与兼容

- 能力契约 `version` 变化时，平台需保留旧版本以支持已部署业务；
- 业务模型 `version` 用于回滚与审计；
- 触发器契约变化需重新注册，不做隐式迁移。

## 6. 统一错误码（首批）

`not_found`、`invalid_template`、`slot_contract_mismatch`、`ui_rule_conflict`、
`invalid_action`、`timeout`、`duplicate`、`invalid_payload`、`trigger_not_found`、
`not_ready`、`device_offline`、`compile_failed`、`provider_failed`、`unsupported`。
