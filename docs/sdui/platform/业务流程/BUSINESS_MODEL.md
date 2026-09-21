# 业务流模型（设计讨论稿）

> **状态**：设计共识，**尚未实现**。用于沉淀"平台如何配置一个业务"的模型。
> **范围**：终端之上的业务编排层。终端协议（连接、Schema、主视图、音频、二进制数据面）见
> [协议总览](../protocol/PROTOCOL.md) 与 [平台接入指南](../platform-integration/lcd085/README.md)。
> **边界**：本文只定义模型与边界，不定义实现与 API。

## 1. 总览

```text
源（触发器） ──► 转换器 ──► 页面（槽位绑定）
                              ▲
                      事件（页面维度）──► 行为
                                        ├─ 终端原生
                                        ├─ 平台执行器
                                        └─ 页面跳转
```

一条业务 = **源产生数据 → 转换器处理 → 页面绑定渲染 → 页面事件触发行为**。
流程保持**线性无环**，不引入 DAG。

## 2. 类型（槽位契约）

类型不放在数据上，而是放在**页面槽位**上。数据是 JSON 值 + 资源引用，
**无 DTO**，直接字段引用（如 `.reply`、`.items[0].value`）。

| 契约 | 用途 |
|---|---|
| `text` | 文本 |
| `number` | 数值 |
| `series` | 数值数组（图表） |
| `image` | 图片资源 |
| `audio` | 音频资源 |

类型只服务两件事：**槽位校验**与**转换器选择**。

## 3. 源（触发器）

| 类别 | 例子 |
|---|---|
| 系统级 | 定时器、内部事件 |
| 外部 | 对外钩子（外部系统触发） |
| 设备 | 按钮、录音完成（终端原生触发） |

源产出带契约的数据。**除终端原生触发外，源本质是接口调用。**

## 4. 转换器

- 当"填入的契约"与"槽位需要的契约"**不匹配**时，插入一个转换节点；
- 手动选择与自动插入**无本质区别**，产出同一个节点；
- 插入的节点**必须可见、可替换**（审计底线）；
- 支持内置与外部实现；AI 相关（STT / TTS / 对话）为外部实现。

## 5. 页面

- 选择模板（终端支持的视图类型，见 [UI 模型](../protocol/UI_MODEL.md)）；
- 槽位用占位符绑定源 / 转换器的输出；
- 类型不匹配时提示，或按第 4 节插入转换器。

## 6. 事件与行为

- 事件按**页面维度**配置；
- 选择某个按钮 → 选择下一步行为；行为只有三类：

1. **终端原生**：录音、RGB、上报（本地闭环，不等云端）；
2. **平台执行器**：调用平台或外部能力；
3. **页面跳转**：平台内置规则。

## 7. 执行器

- 抽象为一次**平台侧接口调用**，带类型化返回值；
- 与"源"对称；由平台目录提供，作者只选择。

## 8. 作用域

v1 **只有 `invocation` 一档**：

- 一次触发处理内产生的值，处理结束即释放；**不做跨作用域值传递**；
- 平台只持有**路由状态**（当前页面、激活规则域），不对外暴露为会话变量；
- 跨调用上下文（如多轮对话）不由平台保存：平台向外部能力传递**会话键**
  （`deviceId + businessId`），内容由能力自行维护。

预留 `scope` 字段（`invocation` / `session`）；v1 遇到 `session` 报"不支持"，
不静默降级。

## 9. 能力契约

| 字段 | 说明 |
|---|---|
| `id` / `name` / `role` / `version` | 标识 |
| `in` / `out` | 输入 / 输出契约（`out` 带 `scope`） |
| `timeout_ms` | 超时（可选） |
| `errors` | 错误码集合（可选） |
| `idempotent` | 是否幂等（可选） |

`role`：`source`（无 in、有 out）/ `transform`（有 in、有 out）/ `action`（有 in、out 可空）。

示例：

```jsonc
{ "id":"ai.stt", "role":"transform",
  "in":{ "audio":{ "contract":"audio" } },
  "out":{ "text":{ "contract":"text", "scope":"invocation" } },
  "timeout_ms":20000, "errors":["timeout","stt_failed"] }

{ "id":"ai.dialog", "role":"transform",
  "in":{ "text":{ "contract":"text" }, "session":{ "contract":"text" } },
  "out":{ "reply":{ "contract":"text" } },
  "timeout_ms":30000, "errors":["timeout","llm_failed"] }

{ "id":"device.audio.play", "role":"action",
  "in":{ "audio":{ "contract":"audio" } },
  "errors":["device_offline"] }

{ "id":"source.timer", "role":"source", "config":{ "interval_s":60 },
  "out":{ "tick":{ "contract":"text" } } }
```

## 10. 外部 AI 边界

- 平台**不实现"与大模型交互"**（STT / LLM / TTS）；
- AI 作为**外部能力**，通过契约接入（HTTP / MCP 等），平台只依赖契约，不依赖实现；
- 平台仍负责**编排、超时、错误、会话键传递**；对话内容由外部能力持有。

明确接受的代价：

- 平台侧跨轮变量做不了（需外部能力持有，或暂不支持）；
- 多轮对话的平台审计变弱（平台只见调用，不见内容）；
- 外部依赖的可用性、延迟、成本与数据合规需要单独治理。

## 11. 设计原则与有意不做

原则：

- **选择优于定义**：能力目录可以增长，作者模型不增长；
- 流程**线性无环**；
- **单作用域**；
- 类型只用于槽位校验与转换器选择。

v1 有意不做：DTO / 结构类型、跨作用域传递、并行与分支循环、通用重试策略、
参数校验表达式。

## 12. 示例：AI 对话

```text
源:        capture.done(audio)          ← 终端本地录音后上报
转换(外部): ai.stt       audio → text
转换(外部): ai.dialog    text  → text    ← 显式，不依赖类型推断
页面:      reply 页槽位  ← text
转换(外部): ai.tts       text  → audio
执行器:    device.audio.play  audio → 终端
```

平台负责串接、校验、超时与错误；外部只实现 `ai.*`。

## 13. 落地顺序

| 阶段 | 内容 | 目的 |
|---|---|---|
| **M1** | 单页 + 按钮录音 + mock executor，跑通 `源→转换→页面→执行器` | 全程确定性、可测，先证明抽象 |
| **M2** | mock 换成真实外部 AI 能力，接口不变 | 验证能力契约抽象 |
| **M3** | 会话 / 多轮，再接 Agent 编写 | 引入状态与外部编排 |

## 14. 附录 A：模型正式字段（提案）

> 字段为提案，平台可在实现中调整；**结构、边界与语义不应变**。

### A.1 顶层

```jsonc
{
  "id": "biz_1",
  "version": 1,
  "entry": "idle",                      // 入口页面 key
  "sources": { "<key>": source },       // 外部触发器 / 系统定时器（设备事件隐含在页面按钮里）
  "pages":   { "<key>": page },
  "transitions": [ transition ],
  "automations": [ automation ]
}
```

### A.2 page

| 字段 | 说明 |
|---|---|
| `key` | 页面标识，业务内唯一 |
| `mode` | `template` / `image` / `canvas` |
| `template` | `mode=template` 时的模板类型（文本/状态/指标/菜单/图表） |
| `slots` | 槽位绑定：`{ 字段: 字面量 或 "@引用" }` |
| `buttons` | 页面维度事件：`[ { button, action } ]` |

### A.3 action（按钮行为）

```jsonc
{ "kind": "native",   "responses": [ { "name":"audio.record.start" }, { "name":"platform.interaction.report", "body":{ "token":"capture.done" } } ] }
{ "kind": "executor", "use": "stock.trade", "with": { "symbol": "@s.symbol" } }
{ "kind": "jump",     "to": "status" }
```

- `native`：终端本地执行，不必上报；
- `executor`：终端只上报，平台调用能力；
- `jump`：终端只上报，平台切换页面（等价于一条跳转规则）。

### A.4 source

```jsonc
{ "type": "external", "trigger_id": "stock.tick",
  "payload": { "symbol": { "contract": "text" }, "price": { "contract": "number" } } }

{ "type": "system", "schedule": { "interval_s": 60 },
  "out": { "tick": { "contract": "text" } } }
```

### A.5 transition

```jsonc
{ "on": "capture.started", "to": "talking" }
```

这是**事件驱动跳页**的通用形式；`jump` 是它在按钮场景的简写，编译后等价。

### A.6 automation

```jsonc
{
  "on": "capture.done",
  "pending": "thinking",                       // 可选，立即显示
  "steps": [
    { "use": "ai.stt",    "with": { "audio": "@capture.audio" }, "out": { "text": "@t" } },
    { "use": "ai.dialog", "with": { "text": "@t" },              "out": { "reply": "@r" } }
  ],
  "present": [
    { "to": "view",  "page": "reply", "bind": { "text": "@r" } },
    { "to": "audio", "source": "@a" }
  ],
  "on_error": { "present": [ { "to": "view", "page": "error" } ] }
}
```

`present` 的四种目标：`view`（模板页）/ `image` / `canvas` / `audio`。

### A.7 引用与绑定语义

- 引用写法：`@name`、`@name.field`、`@name[0]`；v1 只在 invocation 作用域内解析；
- 步骤 `out` 把能力输出命名为流程内引用（左为能力输出名，右为流程内引用名）；
- 设备事件可携带值，由平台在事件到达时提供（如"最近一次录音"）；
- `present.bind` 覆盖页面槽位默认值；未绑定的槽位用默认值。

### A.8 编译期约束

| 约束 | 值/说明 |
|---|---|
| scene 数 | ≤ 8 |
| 每 scene binding | ≤ 16 |
| 每 binding responses | 1~4 |
| 菜单页 scene | 不得绑定 `button.plus/pwr.short_press` |
| 槽位契约 | 必须可满足，否则插入转换器或编译失败 |
| 引用 | 必须能解析，否则编译失败 |

## 15. 待定

- 转换器目录与默认选择策略；
- 页面模板槽位的精确字段名（以 UI Schema 为准）；
- 错误与超时的默认策略；
- 外部能力接入协议的最终形态（见 [能力与交互契约](./CONTRACTS.md)）。

## 16. 相关文档

- [能力与交互契约](./CONTRACTS.md)
- [编译与运行期](./COMPILATION_AND_RUNTIME.md)
- [Agent 交互](./AGENT_INTERACTION.md)
- [实施计划](./IMPLEMENTATION_PLAN.md)

