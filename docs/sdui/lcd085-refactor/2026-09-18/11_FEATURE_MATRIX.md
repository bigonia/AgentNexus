# 功能清单与实现台账

> 建立日期：2026-09-18
> 用途：平台侧（AgentNexus）为对齐 LCD_085 重构所需的全部功能项、来源、阶段、状态与终端依赖。
> 维护规则：每完成一项在此更新状态。状态取值：`TODO` / `DOING` / `DONE` / `BLOCKED` / `DEFERRED`。
> 与 [10_PLATFORM_UPGRADE.md](10_PLATFORM_UPGRADE.md) 的分工：本文只做台账，设计理由写在方案文档，未定义项的处理原则写在 [12_DESIGN_NOTES.md](12_DESIGN_NOTES.md)。

## 图例

- **来源**：`01§2` 表示 [01_INTERACTION_MODEL.md](01_INTERACTION_MODEL.md) 第 2 节，其余同理。
- **自实现** 列标记为 `是` 的条目，是设计文档中没有直接规定、但平台侧功能开发必须补齐的内容。
- **终端依赖**：`-` 表示平台侧可独立完成；其余表示需要终端契约确认。

## 1. 协议内核（P1）

| # | 功能项 | 来源 | 自实现 | 阶段 | 状态 | 终端依赖 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1.1 | Request / Result / Event 三种 JSON 信封编解码 | 04§5 | 否 | P1 | DONE | - | `sdui/v2/protocol` |
| 1.2 | 信封判定规则与非法报文拒绝 | 04§5 | 否 | P1 | DONE | - | 顺序判定，避免 `name`+`id` 歧义 |
| 1.3 | 请求生命周期登记与一次性结果匹配 | 04§5、04§9 | 是 | P1 | DONE | - | 文档未定义超时登记结构 |
| 1.4 | 普通请求超时处理（不自动重放） | 04§6 | 否 | P1 | DONE | - | 定时扫描 |
| 1.5 | 新二进制帧头与 dataType 识别 | 04§8.1 | 是 | P1 | DONE | 需终端确认字段序 | 文档未定义帧头 |
| 1.6 | 二进制帧大小上限与出站队列上限 | 04§8 | 是 | P1 | DONE | 需终端声明上限 | 文档只给原则 |
| 1.7 | 无活动生命周期时二进制数据丢弃 | 04§8.1 | 否 | P1 | DONE | - | 计数告警 |
| 1.8 | 单连接接管（新连接替换旧连接） | 04§2 | 否 | P1 | DONE | - | 独立于旧 `DeviceSessionManager` |
| 1.9 | 旧连接迟到消息全部忽略 | 04§2、04§9 | 否 | P1 | DONE | - | 连接代次校验 |
| 1.10 | 握手：protocol_version / capability_hash | 04§3 | 否 | P1 | DONE | 承载位置待确认 | 参数与首条消息双支持 |
| 1.11 | v2 WebSocket 端点与旧端点并存 | 04§1 | 是 | P1 | DONE | - | `/ws/sdui/v2` |
| 1.12 | 模拟终端（测试桩） | - | 是 | P1 | DONE | - | 无真实设备时的验收手段 |

## 2. 能力域（P2）

| # | 功能项 | 来源 | 自实现 | 阶段 | 状态 | 终端依赖 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2.1 | `CapabilitySchemaV2` 模型（trigger/action/surface/audio） | 04§4 | 否 | P2 | DONE | 需终端按此结构上报 | 准确语法待定 |
| 2.2 | `capability_hash` 生成与校验 | 04§4 | 是 | P2 | DONE | 算法需双方一致 | 算法文档未定义 |
| 2.3 | hash 缓存：已知 hash 直接用 | 04§4 | 否 | P2 | DONE | - | 按 hash 全局共享 |
| 2.4 | 未知 hash → 请求完整 Schema → 校验 | 04§4 | 否 | P2 | DONE | 需 `capability.get` 名称确认 | 控制流程文档未定义 |
| 2.5 | Schema 校验失败进入能力同步阶段 | 04§4 | 是 | P2 | DONE | - | 暂不接受业务下发 |
| 2.6 | 绑定表配置校验器（Trigger/Action/参数/长度） | 04§4、01§2 | 是 | P2 | DONE | - | 校验规则来自平台配置 |
| 2.7 | 能力目录投影到新模型 | 04§4 | 是 | P2 | TODO | - | 与 `sdui-event-catalog.yml` 收敛同步，尚未开始 |
| 2.8 | 动作自然语言解释与速查表留在平台 | 04§4 | 是 | P2 | TODO | - | 不随连接下发 |

## 3. 业务配置域（P3）

| # | 功能项 | 来源 | 自实现 | 阶段 | 状态 | 终端依赖 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 3.1 | `BusinessConfig` 绑定表模型（Trigger → Response 序列） | 01§2、01§3 | 否 | P3 | DONE | 结构需终端确认 | 字段名待对接 |
| 3.2 | 序列约束：有限、有序、不可编程 | 01§2 | 否 | P3 | DONE | - | 长度与白名单可配 |
| 3.3 | 全量原子替换下发 `business.update` | 01§6.2 | 否 | P3 | DONE | - | 无增量命令 |
| 3.4 | `business.reset` 语义与平台侧业务态清理 | 01§6.1 | 否 | P3 | DONE | - | 不含连接与系统态 |
| 3.5 | 平台 Trigger token 生成与注册表 | 01§3 | 是 | P3 | DONE | token 长度上限待定 | 文档只说不透明 |
| 3.6 | 上报 token 注册表与业务上下文反查 | 01§4 | 是 | P3 | DONE | - | 与 Trigger token 分离 |
| 3.7 | `business.trigger(token)` 下发与一次性结果 | 01§3 | 否 | P3 | DONE | - | - |
| 3.8 | 业务切换编排（reset → update） | 01§6.3 | 是 | P3 | DONE | - | 文档未给平台侧编排细节 |
| 3.9 | 重连后幂等重发完整配置 | 04§3 | 否 | P3 | DONE | - | 不使用 boot_id |
| 3.10 | 配置版本号与漂移检测 | 01§6.2 | 是 | P3 | DONE | 需终端回显版本 | 文档未定义回显 |

## 4. 业务面迁移（P4）

| # | 功能项 | 来源 | 自实现 | 阶段 | 状态 | 终端依赖 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 4.1 | `display.section` 单 Section 全量替换 | 03§2 | 否 | P4 | DONE | 需确认名称 | - |
| 4.2 | Section 类型收敛为 5 类 | 03§2.1 | 否 | P4 | DOING | - | 新模型已按 5 类声明；YAML 目录尚未裁剪 |
| 4.3 | `display.image.begin/end` + binary 全屏图片 | 03§3 | 否 | P4 | DONE | 调色板格式待定 | - |
| 4.4 | RGB565 调色板模型与校验 | 03§3 | 是 | P4 | DONE | - | - |
| 4.5 | `display.canvas.open/close` + binary 帧 | 03§4 | 否 | P4 | DONE | 分辨率/色数上限待定 | - |
| 4.6 | Canvas 丢帧保留最新策略 | 03§4.3 | 是 | P4 | DONE | - | 文档只给原则 |
| 4.7 | 三种主视图互斥管理 | 03§1 | 是 | P4 | DONE | - | 平台侧需记录当前视图 |
| 4.8 | 音频下行 `audio.start` → binary → `audio.stop/abort` | 04§7 | 否 | P4 | DONE | 名称待确认 | - |
| 4.9 | 音频上行接收态与唯一性约束 | 04§7 | 否 | P4 | DONE | - | 每设备唯一 |
| 4.10 | `buffer_full` 异常终止与部分数据取舍 | 04§7 | 否 | P4 | DONE | - | - |
| 4.11 | 音频平台侧超时终止 | 04§9 | 否 | P4 | DONE | 超时值待定 | - |
| 4.12 | 系统命令 `system.*` 统一 request/result | 02§4、04§5 | 否 | P4 | DONE | - | 复用硬件实现 |
| 4.13 | 系统命令期望值由平台维护 | 02§4 | 否 | P4 | DONE | - | 终端不持久化 |
| 4.14 | 平台交互上报事件入库 | 01§4 | 否 | P4 | DONE | - | `platform.interaction` |
| 4.15 | 上报 token 无效/找不到绑定的错误处理 | 01§3 | 是 | P4 | DONE | - | 错误名待定 |

## 5. 工作流对接与收尾（P5）

P5 按三道闸门拆成三个子阶段，拆分理由见 [12_DESIGN_NOTES.md](12_DESIGN_NOTES.md) §4.8。

### 5.1 P5a 工作流产出业务配置

| # | 功能项 | 来源 | 自实现 | 阶段 | 状态 | 终端依赖 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 5.1 | 工作流部署产出 `BusinessConfig` | 02§2 | 是 | P5a | DONE | - | `WorkflowBusinessConfigAssembler` |
| 5.1.1 | 输出节点 → 终端动作映射与下沉判定 | - | 是 | P5a | DONE | 需确认动作名（T13） | `WorkflowActionMapper`，见 S8 |
| 5.1.2 | 静态前缀截断 / 跨 slot 不截断规则 | - | 是 | P5a | DONE | - | 见 S9 |
| 5.1.3 | 序列尾部追加 `platform.interaction.report` | 01§4 | 是 | P5a | DONE | token 名称待确认 | token 由 `prepare` 注入 |
| 5.1.4 | 响应动作全静态约束（禁 `$ref` / 平台产物） | 01§2 | 是 | P5a | DONE | - | 业务动态改由 token 区分 |
| 5.1.5 | 组装结束跑下发校验干跑，消除静默失败 | 01§6.2 | 是 | P5a | DONE | - | 见 12_DESIGN_NOTES §4.11 |
| 5.2 | 部署时下发配置、停止时 `business.reset` | 01§6 | 是 | P5a | DONE | - | `NodeWorkflowDeploymentService` |
| 5.3 | ~~设备级新旧协议分流开关~~ | - | 是 | P5a | **已移除** | - | 0.12.0 删除：不做灰度，v2 为唯一协议 |
| 5.4 | 组装问题分级（ERROR 阻断 / WARN 提示） | - | 是 | P5a | DONE | - | 部署前预检，不部分应用 |

### 5.2 P5b 交互事件回流

| # | 功能项 | 来源 | 自实现 | 阶段 | 状态 | 终端依赖 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 5.5 | `platform.interaction` 事件驱动工作流运行 | 01§4 | 是 | P5b | DONE | token 字段名待确认（T14） | `NodeWorkflowRuntimeService.onInteraction` |
| 5.5.1 | `contextRef` 承载工作流上下文，替代按 triggerId 反查 | 01§4 | 是 | P5b | DONE | - | 见 S10；`WorkflowContextRef` |
| 5.5.2 | v2 接入层按设备归属建立租户上下文 | 02§4 | 是 | P5b | DONE | - | 见 S13 / 12§4.16 |
| 5.6 | 消费组装结果中的 `platformSteps` 并执行 | - | 是 | P5b | DONE | - | 从部署记录固化值读取，见 S11 |
| 5.6.1 | 平台步骤出口分级（可请求下发 / 只能终端执行） | 04§4 | 是 | P5b | DONE | 需 T15 才能闭环 | `terminal_action_required`，缺口 G25 |
| 5.7 | 平台步骤执行后以新 token / 新请求驱动终端 | 01§3 | 是 | P5b | DOING | 需 T15 | UI / 音频走新的独立请求已实现；动态签发新 token 的路径未实现，见 5.7.1 |
| 5.7.1 | 平台为动态节点生成专用绑定并签发新 token | 01§3 | 是 | P5b | BLOCKED | 需 T15 | 文档未定义该绑定形态（G25），暂返回 `terminal_action_required` |
| 5.8 | 交互结果作为工作流新输入写入 run context | 01§4 | 是 | P5b | DONE | - | `triggerEvent` 记录上报原文 |
| 5.8.1 | 单 Section 收敛点（场景 / Patch → 完整 Section） | 03§2 | 是 | P5b | DONE | - | 见 S12；`SectionViewResolver` + `PrimaryViewPublisher` |
| 5.8.2 | WAV → 裸 PCM 下行拆解 | 04§7 | 是 | P5b | DONE | - | 见 S14；`WavPcm`，不做重采样 |
| 5.9 | 旧工作流运行时的设备命令直发路径下线 | 02§4 | 否 | P5b | DONE | - | `CapabilityNodeExecutorService` 及其测试已删除 |

### 5.3 P5c 旧协议路径剪除（已开工）

准入条件：**终端固件全量切换到 v2**。分流开关已在 0.12.0 删除——本项目不做灰度，
v2 是唯一协议，因此旧路径的删除不再有"把设备按回旧协议"的回退手段，只能等终端切换完成。
以下各项当前已标注 `@Deprecated(since = "0.10.0")` 并保留可用。删除清单、执行顺序与必保边界经过引用图复核，见
[12_DESIGN_NOTES.md](12_DESIGN_NOTES.md) §7 与 §4.18；复核脚本 `scripts/sdui-refgraph/refgraph.py`，每次裁剪后重跑即可得到当刻清单。

| # | 功能项 | 来源 | 自实现 | 阶段 | 状态 | 终端依赖 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 5.9.1 | 引用图复核删除清单，产出可执行顺序与必保边界 | - | 是 | P5c | **DONE** | - | 见 12§4.18；发现 v2 硬依赖仅 6 类、原清单 2 处错误已修正 |
| 5.10 | 删除旧 `cmd/control` 与 ACK 路径 | 02§4、04§5 | 否 | P5c | DEFERRED | 终端切换完成 | 见方案 §10，已 deprecated |
| 5.10.1 | 裁剪控制层对旧能力 / 事件模型的暴露 | - | 是 | P5c | TODO | 需业务确认端点用途（Q9） | **前置**：阻塞 87 个待删类中的大多数，8 个端点见 12§7.2 |
| 5.10.2 | 删除无需改造即可移除的 6 类 + 旧 WS 端点 | - | 是 | P5c | DEFERRED | 终端切换完成 | 12§7.1；含真死代码 `SemanticCommand` |
| 5.11 | 删除 TLV 输入路径 | 04§1 | 否 | P5c | DEFERRED | 终端切换完成 | 已 deprecated |
| 5.12 | 删除旧 Section 编排与多 Section 拼接 | 03§2 | 否 | P5c | DEFERRED | 终端切换完成 | **修正**：`SectionPatch` 等模型被 v2 收敛点复用，保留；只删 `SectionOrchestrationService` 的下行编码路径（12§4.18 裁决二） |
| 5.13 | 删除 Base64 音频路径 | 04§1 | 否 | P5c | DEFERRED | 终端切换完成 | 已 deprecated |
| 5.14 | 删除旧 16 字节二进制帧头 | 04§8 | 否 | P5c | DEFERRED | 终端切换完成 | 已 deprecated |
| 5.15 | Section 14 类目录收敛为 5 类 | 03§2.1 | 否 | P5c | DEFERRED | 需 T6 定稿 | **修正**：`SectionTypeCatalog` 服务须保留（ui 层用于模板校验），只收敛类型集合 |
| 5.16 | 删除能力名称上报路径 | 04§4 | 否 | P5c | DEFERRED | 终端切换完成 | 已 deprecated |
| 5.16.1 | 删除旧上行音频处理链 | 04§7 | 否 | P5c | **BLOCKED** | 需 T11 先落地 | `AudioRecordHandler` / `AudioRecordChunkHandler` / `AudioRecordSessionManager` 是平台唯一录音通路 |
| 5.17 | 删除 `DeviceProtocolRouter` 与 `sdui.routing` | - | 是 | P5a | **DONE** | - | 0.12.0 已删除 |
| 5.18 | 前端调试页面对齐 v2 | - | 是 | P5c | TODO | - | `static/sdui-*.html` 三个页面 |

> 5.18 不依赖终端切换，可随时提前做；当前排在 P5c 只是因为与调试链路一起验证成本更低。

## 6. 自实现补充功能汇总

以下功能在设计文档中没有直接规定，但平台侧实现必须补齐，已在 [12_DESIGN_NOTES.md](12_DESIGN_NOTES.md) 中记录理由与风险。

| 编号 | 功能 | 为什么必须 |
| --- | --- | --- |
| 1.3 | 请求生命周期登记结构 | 文档定义了"只返回一次结果"，但未定义平台如何登记与匹配 |
| 1.5 | 二进制帧头 | 文档要求"最小数据类型标识"，未定义字节布局 |
| 1.6 | 帧大小与队列上限 | 文档只给"必须有上限"的原则，需要可配置的具体值 |
| 1.9 | 连接代次校验 | 文档要求忽略旧连接消息，未给判定手段 |
| 1.11 | 新端点和过渡期并存 | 文档要求最终只有一套协议，但开发期必须可回归 |
| 1.12 | 模拟终端 | 终端未就绪时无法验证平台侧，必须自建测试桩 |
| 2.2 | hash 算法 | 文档明确"实现阶段确定" |
| 2.5 | 能力同步阶段的准入拦截 | 文档定义了该阶段，未定义平台如何拦截业务下发 |
| 2.6 | 配置校验器 | 文档要求校验，未定义校验规则来源 |
| 3.5 / 3.6 | token 注册表结构 | 文档只说不透明 token，未定义平台存储 |
| 3.8 | 切换编排 | 文档给了两步语义，未给平台侧执行与失败处理 |
| 3.10 | 配置版本回显 | 全量替换需要可观测的版本，否则无法判断漂移 |
| 4.4 | RGB565 调色板模型 | 文档给了语义，未给平台侧数据模型 |
| 4.6 | 丢帧策略实现 | 文档只给原则 |
| 4.7 | 主视图互斥状态机 | 文档给定互斥，未定义平台侧跟踪方式 |
| 4.15 | 无效 token 错误路径 | 文档提到返回错误，未给错误名 |

首版实现过程中额外补齐的自实现项（详见 [12_DESIGN_NOTES.md](12_DESIGN_NOTES.md) §4）：

| 编号 | 功能 | 为什么必须 |
| --- | --- | --- |
| S1 | 接管时立即结束旧连接未完成请求 | 文档只说"忽略旧连接消息"与"超时处理"，两者之间存在悬挂窗口 |
| S2 | 业务清理的跨域协调者 | 01§6.1 列出的清理项分散在音频域与显示域，需要一个集中表达范围的位置 |
| S3 | 干跑校验的 token 回收 | 预检会签发 token，若不回收会在注册表留下永不使用的条目 |
| S4 | 上行音频流的开始宣告约定 | 04§7 只描述生命周期形状，未定义终端如何告知平台上行流已开始 |
| S5 | 调色板随会话开始放入 JSON body | 03§3/§4 只要求"完整提供调色板"，未定义承载通道；首期色数上限下体积可忽略 |
| S6 | 连接 attach 的幂等保护 | 避免重复建立连接对象或误自增代次，导致代次校验失效 |
| S7 | 出站"在途消息"上限而非排队 | 旧实现无上限；文档只要求"队列有固定上限"，这里选择同步有界发送而非内存排队 |
| S8 | 工作流节点 → 终端动作的显式映射表 | 两套命名空间不同（`rgb.effect` vs `rgb.effect.set`），文档未定义换算关系；且必须能逐条回答"为什么不下沉" |
| S9 | 静态前缀截断与跨 slot 不截断 | 01§2 只要求序列"有限、有序、不可编程"，未定义当链条中间出现平台步骤或跨设备步骤时序列到哪里为止 |

P5b 追加的自实现项：

| 编号 | 功能 | 为什么必须 |
| --- | --- | --- |
| S10 | `contextRef` 承载工作流上下文 | 01§4 只说"按设备和 token 恢复业务上下文"，未定义平台侧用什么记住上下文；仅凭 `triggerId` 无法区分同一按钮被不同部署绑到不同 slot |
| S11 | 平台步骤随部署固化（而非运行时重新组装） | 组装阶段已产出 `platformSteps`，但文档未说它存在哪里；每次运行时按当前定义重组会在工作流被编辑后与设备侧已生效配置发生漂移 |
| S12 | 单 Section 收敛点 | v2 只有 `display.section` 全量替换，而业务侧仍在产出场景与 Patch；文档未定义转换位置，散落在各调用点会形成多处真值 |
| S13 | v2 接入层的设备租户上下文 | 01§4 要求平台按设备恢复上下文，但未意识到 v2 的 WebSocket 线程没有租户；设备表是全局表而部署表是租户表，不建立租户则查不到部署记录 |
| S14 | WAV → 裸 PCM 下行拆解 | 04§7 只说下行发二进制音频数据，未说容器形态；artifact 里存的是 WAV 文件，直接整段下发会把 44 字节头当音频播出去 |

## 7. 首版交付的代码资产

首版（P1–P4 核心）落地的实现文件，均在 `src/main/java/com/zwbd/agentnexus/sdui/v2/` 下，与旧实现物理隔离。

| 包 | 类型 | 职责 |
| --- | --- | --- |
| `v2/protocol` | `Envelope` / `EnvelopeCodec` | 三种信封及其判定规则 |
| `v2/protocol` | `BinaryFrameCodecV2` / `BinaryDataType` | 新二进制帧头与数据类型 |
| `v2/protocol` | `ProtocolErrors` / `ProtocolException` / `V2Names` | 错误名与消息名常量 |
| `v2/session` | `DeviceConnectionRegistry` / `DeviceConnection` / `DeviceHandshake` | 单连接接管与代次校验 |
| `v2/session` | `PendingRequestRegistry` | 未完成请求登记、超时与接管清理 |
| `v2/capability` | `CapabilitySchemaV2` / `CapabilityHash` / `CapabilityRegistryV2` | Schema 模型、hash、缓存与协商状态 |
| `v2/business` | `BusinessConfig` / `TriggerBinding` / `ResponseStep` / `TriggerSource` | 绑定表模型 |
| `v2/business` | `BusinessConfigValidator` | 配置校验（结构 + 能力约束） |
| `v2/business` | `InteractionTokenService` | 双域不透明 token 注册表 |
| `v2/business` | `BusinessConfigService` | reset / update / trigger / 上报受理 / 接管重发 |
| `v2/business` | `BusinessInteraction` / `BusinessClearedEvent` / `BusinessCleanupCoordinator` | 业务领域事件与跨域清理 |
| `v2/display` | `DisplaySessionService` / `DisplayCommandService` | 主视图互斥、图片与 Canvas 会话 |
| `v2/display` | `PaletteImageCodec` | 调色板与索引矩阵编解码校验 |
| `v2/audio` | `AudioStreamService` / `AudioCommandService` | 音频方向互斥、异常终止与超时 |
| `v2/system` | `SystemCommandService` | 系统命令与期望值维护 |
| `v2/transport` | `SduiV2WebSocketHandler` / `SduiV2MessageRouter` | v2 端点与按名称路由 |
| `v2/transport` | `DeviceSender` / `PlatformRequestService` | 有界出站与请求生命周期 |
| `v2/transport` | `V2SessionBootstrapService` | 接管后的能力确认与配置重发编排 |
| `v2/transport/sink` | `PlatformInteractionEventSink` / `AudioLifecycleEventSink` / `AudioUplinkBinarySink` / `CanvasFrameBinarySink` / `ImageChunkBinarySink` / `CapabilitySchemaBinarySink` | 上行事件与二进制接收器 |
| `v2` | `V2ProtocolProperties` / `V2Config` / `OperationResult` | 可配置上限与装配 |

P5a 追加的实现文件（工作流侧）：

| 包 | 类型 | 职责 |
| --- | --- | --- |
| `sdui/workflow` | `WorkflowActionMapper` | 输出节点 → 终端静态动作的映射与下沉判定 |
| `sdui/workflow` | `WorkflowBusinessConfigAssembler` | 按设备组装 `BusinessConfig`，产出 `platformSteps` 与分级 `Issue` |
| `sdui/workflow` | `NodeWorkflowDeploymentService`（改造） | 部署前组装预检、部署时下发、停止时 reset |

P5b 追加 / 改写的实现文件：

| 包 | 类型 | 职责 |
| --- | --- | --- |
| `sdui/workflow` | `NodeWorkflowRuntimeService`（重写） | 由 `BusinessInteraction` 事件驱动，按 `contextRef` 恢复上下文，只跑固化的 `platformSteps` |
| `sdui/workflow` | `WorkflowContextRef`（新） | `wf:<workflowId>:<triggerNodeId>` 编解码，解析失败返回空 |
| `sdui/workflow` | `WorkflowPlatformStepExecutor`（新） | 平台步骤执行出口：UI / 音频下发，终端本地动作返回 `terminal_action_required` |
| `sdui/workflow` | `NodeWorkflowDeploymentEntity`（改造） | 新增 `businessConfigs` JSON 列，固化 `triggers` 与 `platformSteps` |
| `sdui/workflow` | `CapabilityNodeExecutorService`（**已删除**） | 旧设备命令直发执行器，被 `WorkflowPlatformStepExecutor` 取代 |
| `sdui/v2/display` | `SectionViewResolver`（新） | 单 Section 收敛点：场景取首页、Patch 合成完整 Section、主视图快照 |
| `sdui/v2/display` | `PrimaryViewPublisher`（新） | 业务 UI 下发的唯一出口，接 `SectionViewResolver` 与 `DisplayCommandService` |
| `sdui/v2/audio` | `WavPcm`（新） | WAV 容器拆解，取裸 PCM 载荷；非 WAVE 按裸 PCM 处理 |
| `sdui/v2/session` | `DeviceTenantContext`（新） | 按设备归属在连接线程上建立租户上下文 |
| `sdui/v2/transport` | `SduiV2MessageRouter`（改造） | 分发前用 `DeviceTenantContext` 包裹请求 / 事件 / 二进制处理器 |
| `sdui/ui` | `WorkflowUiContextService` / `DevicePrimaryUiService` / `SduiUiTemplateService`（改造） | 移除 `SectionOrchestrationService` 依赖，改走 `PrimaryViewPublisher` |

测试资产（`src/test/java/com/zwbd/agentnexus/sdui/v2/`）：


| 类型 | 说明 |
| --- | --- |
| `sim/SimulatedLcd085Device` | LCD_085 模拟终端测试桩：典型能力 Schema、握手、上下行报文构造与解析 |
| 单元测试 14 个类 / 134 个用例 | 覆盖信封判定、帧编解码、hash 性质、配置校验、token 隔离、接管代次、请求超时、主视图互斥、位打包、音频互斥、路由分发、初始化编排 |

P5a 追加的测试资产：

| 类型 | 说明 |
| --- | --- |
| `sdui/workflow/WorkflowActionMapperTest` | 17 项：各节点类型的下沉与拒绝理由，含 `$ref`、平台产物、能力门禁等反面用例 |
| `sdui/workflow/WorkflowBusinessConfigAssemblerTest` | 13 项：静态前缀截断、跨 slot 不截断、上报动作追加、下发校验干跑、各类 ERROR/WARN 路径 |

P5b 追加的测试资产：

| 类型 | 说明 |
| --- | --- |
| `sdui/workflow/NodeWorkflowRuntimeServiceTest`（重写） | 7 项：交互上报驱动、按固化快照执行、单步失败中止、终端本地动作不算失败、非工作流引用与失效部署的丢弃路径 |
| `sdui/workflow/WorkflowPlatformStepExecutorTest` | 15 项：UI scene/patch 下发、TTS 与 artifact 播放、PCM 分片、音频启停失败中止、RGB 与录音的 `terminal_action_required` |
| `sdui/workflow/WorkflowContextRefTest` | 3 项：编解码回环、节点 id 含冒号、非工作流引用返回空 |
| `sdui/v2/display/SectionViewResolverTest` | 12 项：单 Section 收敛、Patch 合成、无基底返回空、快照维护 |
| `sdui/v2/audio/WavPcmTest` | 6 项：RIFF 头解析、非 WAVE 回落、截断与空载荷 |

验证命令与结果：`mvn test` → 323 项通过（0.12.0 为 289 项；P5b 删除旧执行器测试、新增 40 项用例，净增 34 项，无回归）。

## 8. 待终端确认项汇总

| 编号 | 事项 | 阻塞的功能 |
| --- | --- | --- |
| T1 | 握手字段承载位置（连接参数 / 首条消息） | 1.10 |
| T2 | `capability_hash` 算法与编码 | 2.2、2.4 |
| T3 | 二进制帧头字节布局与 dataType 取值 | 1.5、4.3、4.5、4.8 |
| T4 | 业务配置 JSON 结构与上限 | 3.1、3.3 |
| T5 | 错误码最小集合 | 3.4、4.15、全部失败路径 |
| T6 | 五类 Section 的字段与限制 | 4.2 |
| T7 | 图片调色板与索引矩阵编码 | 4.3 |
| T8 | Canvas 分辨率、色数、帧率上限 | 4.5 |
| T9 | 音频开始/结束/异常消息名称与超时值 | 4.8、4.11 |
| T10 | 系统命令参数与错误语义 | 4.12 |
| T11 | 上行音频流的开始宣告方式（平台侧暂约定为 `audio.start` 事件） | 4.9、4.11 |
| T12 | 调色板与索引矩阵的承载通道及位序（平台侧暂假设随 begin 的 JSON 下发、字节内低位在前） | 4.3、4.5 |
| T13 | 本地响应动作的名称与参数集合（决定工作流节点映射表能否定稿） | 5.1.1 |
| T14 | 终端上报交互结果时携带的 token 字段名与位置 | 5.1.3、5.5 |
| T15 | 平台能否为动态节点签发专用绑定 / 新 token 以驱动终端本地动作（G25） | 5.6.1、5.7、5.7.1 |
| T16 | 下行音频的容器约定：终端是否需要 WAV 头，还是只接裸 PCM（平台侧暂按裸 PCM 发送） | 4.8、5.8.2 |
