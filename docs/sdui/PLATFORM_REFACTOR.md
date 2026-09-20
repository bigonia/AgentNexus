# LCD_085 平台重构指南

> 性质：平台目标架构与实施顺序。协议字段以 [端云契约](TERMINAL_CONTRACT.md) 为准，进度以 [交付台账](DELIVERY_CHECKLIST.md) 为准。

## 1. 重构目标与边界

平台从“逐条遥控终端”迁移为“下发完整业务配置，终端本地执行有限响应序列，必要时回到平台续接”。平台负责业务配置、能力校验、云端步骤和可观测性；终端负责物理输入、当前视图、本地动作与设备微状态。

平台不应镜像“终端执行到第几步”，也不应通过旧 `cmd/control` 拼出新的 v2 业务。v2 的唯一设备接入端点是 `/ws/sdui/v2`；旧 `/ws/sdui` 仅是迁移期兼容入口。

```text
终端 ── WebSocket ──> v2 接入与会话
                         │
             ┌───────────┼───────────┐
             ▼           ▼           ▼
          能力域      业务配置域    显示/音频/系统
             │           │           │
             └───────────┴─────┬─────┘
                               ▼
                       Node Workflow
                 生成配置 / 消费交互 / 执行云端步骤
```

不在本轮范围：重新定义终端硬件行为、计费等业务系统、在平台保留第二套长期协议，以及在终端契约未确认前增加猜测性兼容分支。

## 2. 稳定架构

### 2.1 接入与可靠性

- `SduiV2WebSocketHandler` 建立连接，`DeviceConnectionRegistry` 保证同设备只有一个有效代次。
- `EnvelopeCodec` 应只接受三种信封：Request `{id,name,body?}`、Result `{id,ok,error?}`、Event `{name,body?}`。
- `PendingRequestRegistry` 管理一次性结果和超时；普通请求不自动重放。
- 能力同步完成前禁止业务配置下发；重连或连接接管后幂等重发完整配置。
- Binary 只承载流或有界大对象，wire 形态为 `[1 byte 类型][payload]`；生命周期之外的帧拒绝或丢弃并计数。

### 2.2 能力与业务配置

终端上报的 command/ui Schema 是设备能力事实。平台目录只能投影或解释该事实，不能用当前平台自定义的 `CapabilitySchemaV2` 反向要求终端改变格式。

终端业务配置负责表达本地 Trigger → Response。最终采用单配置 `business.update` 还是多场景 `business.scenes.replace`，必须先完成契约 C3 裁决：

```text
trigger -> [有限、有序、不可编程的 response]
```

- 配置必须全量、原子替换；不提供增量增删改。
- `business.reset` 只清业务态，不清连接、能力缓存和系统期望值。
- 平台触发 token 与交互上报 token 使用分离注册表。
- 配置下发前必须按当前 Schema 校验 trigger、action、参数和上限。

### 2.3 显示、音频与系统

- 主视图只有三类：单 Section、全屏图片、Matrix Canvas，互斥切换；Section wire 名称为 `display.section.set`。
- v2 下发完整 Section；旧场景和 Patch 在平台边界处由 `SectionViewResolver` 收敛，不能进入设备协议。
- 图片和 Canvas 使用调色板索引数据；Canvas 队列只保留最新帧。
- 音频使用显式 start/stop/abort 生命周期与 Binary 数据；同设备同方向只允许一个活动流。
- 音量、亮度、重启和配网走 `system.*` Request/Result；可恢复设置必须幂等。

### 2.4 工作流闭环

工作流部署时由 `WorkflowBusinessConfigAssembler` 生成设备配置，并把无法下沉的步骤固化为 `platformSteps`。终端执行本地静态前缀，通过 `platform.interaction.report(token)` 回流；平台恢复租户与工作流上下文，再由 `WorkflowPlatformStepExecutor` 续接。

边界规则：

- 响应序列不能含 `$ref` 或运行时产物；动态上下文由不透明 token 关联。
- 跨设备步骤不下沉到当前设备。
- 遇到第一个平台步骤后截断本地后缀，防止顺序倒置。
- 只能由终端本地执行、平台又没有请求能力的动作返回 `terminal_action_required`，不得退化到旧协议遥控。

## 3. 管理面边界

HTTP 管理面统一位于 `/api/v1/sdui`，主要域为：

| 域 | 路径 | 用途 |
| --- | --- | --- |
| 设备 | `/devices` | 上线、认领、绑定、遥测和连接记录 |
| 能力 | `/capabilities` | Schema、action、trigger 与同步状态 |
| 板型/节点 | `/board-types`、`/capability-nodes` | 编辑器可用目录 |
| 事件目录 | `/events` | trigger/action/section 目录与校验 |
| 工作流 | `/node-workflows` | 定义、预检、部署、运行和产物 |
| UI 模板 | `/ui-templates` | 模板 CRUD 与预览 |
| 调试 | `/debug` | v2 request、view、SSE、节点测试与会话 |

字段与端点明细以 Swagger 和对应 Controller 为准。前端不得直接读取旧命令目录或构造旧 Section Patch。

## 4. 后续实施顺序

1. **冻结契约**：裁决 C1–C4；按需补 M1–M3，不再重复确认脚本已经明确的连接、Binary 和消息名称。
2. **真实设备联调**：依次打通显示、业务交互、音频、系统命令；每批结果回填契约和台账。
3. **完成平台缺口**：能力目录投影、动作说明、五类 Section 目录、动态节点策略、v2 上行音频消费者。
4. **完成产品回归**：工作流部署、重连恢复、错误与超时、调试台和管理 API 全链路验证。
5. **清理旧路径**：仅当门禁满足后删除旧 WS、Topic/TLV、16 字节帧头、旧音频和旧下行编排。

## 5. 旧路径删除门禁

以下条件必须全部满足：

- C1–C4 已有明确结论，M1–M3 已按交付范围补齐；
- 真实 LCD_085 完成连接、能力、显示、交互、上下行音频和系统命令回归；
- v2 工作流部署与停止、断线重连、连接接管、超时和异常路径通过；
- 旧管理端点调用方已盘点，前端和运维脚本已迁移；
- 删除清单有聚焦测试保护，并在同一变更中更新交付台账。

在门禁满足前，旧代码可以标记 deprecated，但不得把“终端基本升级完成”推断为“平台兼容路径可安全删除”。
