# LCD_085 平台交付台账

> 基线：2026-09-20 的代码盘点。`已实现` 只表示平台代码与聚焦测试存在；只有真实终端通过才可改为 `已验收`。

## 1. 总体状态

| 范围 | 状态 | 结论 |
| --- | --- | --- |
| v2 协议内核 | 部分实现 | 请求生命周期和连接接管可复用；Binary 帧头与终端样例不兼容 |
| 能力域 | 部分实现 | 缓存和准入思路可复用；Schema/hash/协商流程需按 C1/C2 重做 |
| 业务配置 | 部分实现 | reset/trigger/token 思路可复用；配置 wire 模型受 C3 阻塞 |
| 显示 | 部分实现 | 三种视图和状态机已实现；Section 名称需改，图片编码缺 M1 |
| 音频 | 部分实现 | 上下行状态机和 WAV 拆解已实现；真实链路与上行消费未完成 |
| 系统命令 | 已实现 | 消息名称和 0–100 参数范围已有终端依据，待真机验收 |
| 工作流 | 部分实现 | 配置生成、回流、平台步骤已落地；需在 C3 后改成最终 wire 模型 |
| 管理与调试 | 已实现 | v2 HTTP 闭环、SSE 和调试页已存在 |
| 旧路径退出 | 延期清理 | 必须等真实终端验收和调用方迁移 |

当前没有任何范围应标记为 `已验收`。

## 2. 已实现能力与证据

| 能力 | 主要代码证据 | 验证现状 |
| --- | --- | --- |
| 三类信封和非法报文拒绝 | `v2/protocol/EnvelopeCodec` | 单元测试存在 |
| v2 Binary 帧与大小限制 | `v2/protocol/BinaryFrameCodecV2` | **实现不兼容**：当前 8 字节头必须改为样例的单类型字节 |
| 单连接接管与旧代次隔离 | `v2/session/DeviceConnectionRegistry` | 单元测试存在 |
| 请求登记、完成、超时 | `v2/session/PendingRequestRegistry` | 单元测试存在 |
| 能力 hash、缓存和同步 | `v2/capability/*`、`V2SessionBootstrapService` | 缓存可复用；hash 和同步 wire 与脚本不一致，受 C1/C2 阻塞 |
| 业务配置校验和全量下发 | `v2/business/BusinessConfigService` | 内部能力可复用；`business.update` 与脚本 scenes 模型冲突，受 C3 阻塞 |
| 两类 token 分离 | `v2/business/InteractionTokenService` | 单元测试存在 |
| Section/Image/Canvas | `v2/display/*` | Section wire 名称需对齐；图片/Canvas 待 M1 和真机验收 |
| 音频生命周期与 PCM 处理 | `v2/audio/*`、`transport/sink/Audio*` | 基础格式已有依据；异常结束受 C4 阻塞，Binary codec 需改造 |
| 系统命令与重连恢复 | `v2/system/SystemCommandService` | 聚焦测试存在，协议依据充分，待真机验收 |
| 工作流生成设备配置 | `WorkflowBusinessConfigAssembler`、`WorkflowActionMapper` | 内部编排可复用；输出需在 C3/M2 后适配 |
| 交互回流和平台续接 | `NodeWorkflowRuntimeService`、`WorkflowPlatformStepExecutor` | token wire 已明确；动态节点策略属于平台产品决策 |
| 管理 API 和调试闭环 | `sdui/controller/*`、`static/sdui-v2-console.html` | Controller 测试存在 |
| 无真机平台模拟 | `docs/sdui/platform/lcd085_platform_simulator.py`、测试模拟设备 | 可用于协议回归，不能替代真机验收 |

## 3. 待办清单

### P0：先冻结最小契约（正式开发门禁）

- [ ] 裁决 C1：最终协议版本和 hello hash 字段。
- [ ] 裁决 C2：最终 Schema 协商采用单 Event 还是双 Schema 分片。
- [ ] 裁决 C3：业务配置采用 bindings 还是 scenes，并在选择 scenes 时补 M2。
- [ ] 裁决 C4：固件是否支持 `audio.abort(reason)`。
- [ ] 把最终脚本作为契约测试输入；不再以当前 Java 临时模型验证自身。

### P1：完成分域联调

- [ ] 补 M1，完成图片和 Canvas 编码，并在真机验证颜色、方向、边界和丢帧。
- [ ] 部署最终业务配置，验证实体按键本地响应和 token 回流工作流。
- [ ] 验证上下行 `pcm_s16le/22050/mono`、异常和超时。
- [ ] 验证音量、亮度、重启、配网及重连补偿。
- [ ] 按终端五类 Section 及字段限制收敛平台目录和前端编辑器。

### P2：补齐平台缺口

- [ ] 将 `sdui-event-catalog.yml` 对能力 Schema 的投影收敛为单一出口。
- [ ] 补齐 action 的自然语言说明和参数速查，供前端和调试台使用。
- [ ] 为 `AudioUplinkConsumer` 接入 v2 artifact/STT 消费链，结束 TODO。
- [ ] 平台自行决定动态节点采用预置场景/绑定还是标记不支持，并约束工作流编辑器。
- [ ] 对终端上报的 Canvas/音频上限做生成前预检，不依赖设备事后拒绝。
- [ ] 处理配置版本回显与漂移可观测性；若终端不回显，明确替代证据。

### P3：清理与交付

- [ ] 盘点旧 HTTP/前端/运维调用方，完成迁移公告。
- [ ] 满足《平台重构指南》的全部删除门禁。
- [ ] 删除旧 `/ws/sdui`、`cmd/control`/ACK、TLV 输入和旧 16 字节帧头。
- [ ] 删除旧 Base64/录音处理路径和旧 Section 下行编排；保留仍被模板校验使用的模型。
- [ ] 运行全量测试、真机回归和故障注入，最后把相应范围改为 `已验收`。

## 4. 验收矩阵

| 场景 | 最小验收标准 | 当前 |
| --- | --- | --- |
| 连接 | 新连接接管旧连接，旧消息不生效，未完成请求可观测结束 | 待真机 |
| 能力 | hash 命中不重复上传；未命中上传并校验；非法 Schema 被拒绝 | 待真机 |
| 配置 | update 原子生效；reset 只清业务态；重连恢复同一完整配置 | 待真机 |
| 交互 | 实体输入执行本地序列；有效 token 续接工作流；无效 token 明确失败 | 待真机 |
| 显示 | 三类视图互斥；图片颜色/方向正确；Canvas 超载只保留最新 | 待真机 |
| 音频 | 上下行完整；并发被拒绝；abort/超时无泄漏；产物可消费 | 待实现 + 待真机 |
| 系统 | 参数校验、错误结果、幂等重发符合终端行为 | 待真机 |
| 工作流 | 部署预检、停止 reset、跨设备边界、平台步骤和产物记录正确 | 待真机 |
| 可观测性 | 请求、事件、会话、错误、超时和关键计数可由调试面定位 | 部分实现 |
| 兼容退出 | 无旧调用方，删除旧路径后全量回归通过 | 阻塞 |

## 5. 状态更新规则

- 合并平台实现：勾选待办，附类名和聚焦测试，状态最多到 `已实现`。
- 契约裁决或补充：先更新 `TERMINAL_CONTRACT.md` 的 C/M 编号，再更新本表依赖项。
- 真机通过：记录固件版本、平台提交、测试场景和证据位置后才标 `已验收`。
- 失败或变化：直接把状态回退并说明原因，不保留“看起来完成”的百分比。
