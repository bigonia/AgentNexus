# 设计问题与决策记录

> 建立日期：2026-09-18
> 用途：记录平台侧实现过程中遇到的文档缺口、临时实现、设计冲突与裁决结论。
> 维护规则：结论确定后写入正文并标注日期；被推翻的结论直接改写，只在确有追溯价值时保留说明。不放 backlog，未决问题写在第 5 节。

## 1. 使用约定

本文记录三类内容：

1. **文档缺口**：终端设计文档未定义、但平台侧实现必需的定义或约束。
2. **自实现事项**：文档未提但必须实现的功能，记明理由、临时形态和回收条件。
3. **设计问题**：实现中发现的两侧不一致、文档内部冲突或无法落地之处。

处理策略统一为：**先按最小合理假设实现，代码中标注 `// TODO(lcd085-refactor):`，本文登记假设与待确认项，待终端升级完成后回头对齐并回收临时实现。**

## 2. 实现环境问题

### 2.1 Maven Wrapper 在 Git Bash 下不可用（2026-09-18）

现象：`./mvnw` 与 `mvn` 均报 `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`。

原因：环境变量 `MAVEN_HOME` 为 Windows 反斜杠路径，Git Bash 解析时把 `\` 当作转义符，导致启动器 classpath 失效。Maven 安装本身完整（`boot/plexus-classworlds-2.7.0.jar` 存在）。

临时做法：直接用 JDK 调用 Maven 启动器：

```bash
MVN=/d/software/maven/apache-maven-3.9.6-bin/apache-maven-3.9.6
java -cp "$(cygpath -w $MVN/boot/plexus-classworlds-2.7.0.jar)" \
     -Dclassworlds.conf="$(cygpath -w $MVN/bin/m2.conf)" \
     -Dmaven.home="$(cygpath -w $MVN)" \
     -Dmaven.multiModuleProjectDirectory="$(cygpath -w $PWD)" \
     org.codehaus.plexus.classworlds.launcher.Launcher -B test
```

影响：仅开发环境，未修改仓库内容。`AGENTS.md` 中 `./mvnw test` 的说明在 Windows + Git Bash 组合下暂不成立。

### 2.2 maven-compiler-plugin 增量编译会跳过新文件（2026-09-18）

现象：一次编译失败后，再次执行 `compile` 报 `Nothing to compile - all classes are up to date`，即使存在新源文件与编译错误；`mvn test` 也会复用上一次的旧测试类，表现为 `Unresolved compilation problem` 这种与真实原因无关的报错。

原因：编译器插件依赖 `target/maven-status/.../inputFiles.lst` 判断增量，失败或中断后该文件与实际源文件不一致。

临时做法：每次编译前删除该目录，强制重新编译：

```bash
rm -rf target/maven-status
```

影响：仅开发环境。这是排查"改了代码但报错没变"这类假象时必须先排除的一项。

## 3. 文档缺口与临时假设

以下条目在终端设计文档中没有定义。平台侧按表中"临时假设"实现，终端升级完成后需要对齐。

### 3.1 协议层

| # | 缺口 | 出处 | 临时假设 | 风险 |
| --- | --- | --- | --- | --- |
| G1 | 握手字段承载位置 | 04§3 | 优先取连接参数，其次取首条 `hello` 请求；两者都缺失则拒绝连接 | 中：终端实现方式未定，双支持可覆盖 |
| G2 | 二进制帧头布局 | 04§8 | 8 字节头：magic `0x414E` + version + dataType + 4 字节长度（小端） | 高：字节序与字段序必须与终端一致 |
| G3 | `dataType` 取值域 | 04§8.1 | `1` 音频 `2` 图片 `3` Canvas `4` 能力 Schema `5` 资源 | 中 |
| G4 | 单帧与队列上限具体值 | 04§8 | 帧 ≤ 8192 字节、控制队列 256 条、Canvas 待发帧 1 条（只留最新） | 低：可配置，需终端确认 |
| G5 | 错误名称最小集合 | 04§10 | 见 `ProtocolErrors`：`invalid_envelope` `unknown_name` `not_connected` `business_not_active` `binding_not_found` `config_invalid` `resource_exhausted` `queue_full` `frame_too_large` `audio_busy` `no_active_stream` `unsupported` `timeout` | 中：终端错误名需对齐 |
| G6 | 请求超时具体值 | 04§10 | 默认 10s，按请求名可覆写 | 低 |
| G7 | 平台请求完整 Schema 的控制流程 | 04§10 | 新增请求 `capability.get`；终端以 Binary(type=4) 上传，随后返回 `{id, ok:true}` | 中 |
| G8 | 能力同步阶段的准入拦截方式 | 04§4 | 未完成同步的设备，平台拒绝下发 `business.update`，返回 `resource_exhausted` 语义错误 | 低 |
| G9 | Ping/Pong 与重连退避参数 | 04§10 | 首期沿用现有 WebSocket 心跳，不新增参数 | 低 |

### 3.2 能力域

| # | 缺口 | 出处 | 临时假设 | 风险 |
| --- | --- | --- | --- | --- |
| G10 | `capability_hash` 算法与编码 | 04§10 | 对 Schema 做键排序后的规范 JSON 序列化，取 SHA-256 前 16 个十六进制字符 | 高：必须与终端实现完全一致，否则每次连接都会触发全量同步 |
| G11 | Schema 的准确语法 | 04§4 | 采用平台自定义的扁平记录模型，非标准 JSON Schema 子集 | 中 |
| G12 | 动作自然语言说明的存放 | 04§4 | 平台侧独立维护，不进入 Schema | 低 |

### 3.3 业务配置域

| # | 缺口 | 出处 | 临时假设 | 风险 |
| --- | --- | --- | --- | --- |
| G13 | `business.update` 的 JSON 结构 | 01§6.2 | 见 `BusinessConfig`：`deviceId` + `configVersion` + `triggers[].responses[]` | 高：字段名与嵌套需终端确认 |
| G14 | 绑定表与序列的规模上限 | 01§2 | 绑定数 ≤ 32，单序列 Response 数 ≤ 8 | 低 |
| G15 | token 格式与长度上限 | 01§3、01§4 | 前缀 `pt_`（触发器）/ `rt_`（上报）+ 24 字符随机串，总长 ≤ 64；上限 48 字节 | 中 |
| G16 | `configVersion` 是否回显 | 01§6.2 | 假设终端原样保存但不回显；平台侧只用它做本地漂移检测 | 中：若无回显则无法确认终端实际生效版本 |
| G17 | 平台侧业务态存储位置 | 02§4 | 首期内存实现，标注 TODO 待接持久化 | 低：重启丢失，可由平台重发配置恢复 |

### 3.4 业务面

| # | 缺口 | 出处 | 临时假设 | 风险 |
| --- | --- | --- | --- | --- |
| G18 | 消息名称的准确拼写 | 04§5、04§7 | `business.reset/update/trigger`、`display.section`、`display.image.begin/end`、`display.canvas.open/close`、`audio.start/stop/abort`、`system.volume.set` 等 | 中 |
| G19 | 五类 Section 的字段与限制 | 03§7 | 平台侧暂沿用现有字段模型，只按类型收敛，不裁剪字段 | 中：需在 Section Schema 定稿后收敛 |
| G20 | 图片调色板与索引矩阵编码 | 03§3 | 调色板为 RGB565 `short[]`（大端序），索引矩阵按 `ceil(log2(paletteSize))` 位打包 | 高 |
| G21 | Canvas 分辨率、色数、帧率上限 | 03§7 | 首期声明 16/32/64/128 方形、1/2/4 bit、上限 10 fps | 中 |
| G22 | 音频消息名称与超时值 | 04§10 | 下行 `audio.start/stop/abort`，上行同名事件带 `reason`；平台侧接收超时 15s | 中 |
| G23 | `buffer_full` 之后平台如何使用部分数据 | 04§7 | 平台默认保留已收部分并标记异常终止，由业务层决定取舍 | 低 |
| G24 | 系统命令的参数与错误语义 | 04§5 | `system.volume.set{value}`、`system.brightness.set{value}`、`system.reboot{}`、`system.provisioning.start{}` | 低 |

## 4. 实现中发现的设计问题

### 4.1 「连接接管」与「请求结果」的语义冲突（2026-09-18）

协议模型 §2 规定新连接接管后旧连接后续消息全部忽略；§6 规定断线中的普通请求由平台超时处理、不自动重放。若某请求在旧连接上已发出但结果尚未返回，接管后结果会随旧连接作废，此时该请求既不会收到结果也不会立即超时。

**平台侧处理**：接管发生时，把该设备在旧连接上所有未完成请求立即按失败结束，错误名 `not_connected`，不等待超时。这样调用方无需感知连接代次。已写入 `DeviceConnectionRegistry` 的接管回调。

### 4.2 旧实现把设备微观执行状态当作平台状态（2026-09-18）

`SduiDeviceCommand` 用 `SENT / ACKED / REJECTED / TIMEOUT / ERROR` 跟踪每条命令，`DevicePrimaryUiService` 与工作流运行时也各自保存设备侧进度。这与 02§4「终端不是完全无状态，但不提升为统一业务状态」以及 01§1 的"平台不再需要理解大量设备微观执行状态"直接冲突。

**平台侧处理**：v2 只在请求生命周期内保留 `PENDING / OK / FAILED / TIMEOUT`，请求结束后不再保留设备微观状态。旧表在 P5 剪除阶段一并处理，不迁移。

### 4.3 `display.canvas` 丢帧与"每设备唯一活动生命周期"的一致性问题（2026-09-18）

04§8 规定同类数据不并发创建多个活动传输，03§4.3 又要求 Canvas 消费不足时优先保留最新帧。若严格串行，平台在上一帧未消费完时不能发下一帧，会退化成"阻塞"而不是"丢帧"。

**平台侧处理**：区分两层。传输层保证单设备单方向同一时刻只有一个 `canvas` 生命周期（会话）；会话内的帧允许覆盖式入队，队列深度固定为 1，新帧替换尚未发出的旧帧，并累计 `droppedFrames` 计数供观测。这样既满足"不并发多生命周期"，也满足"优先保留最新帧"。

### 4.4 系统命令迁移与 `business.reset` 的边界（2026-09-18）

02§4 要求系统命令不被 `business.reset` 清除，且终端不持久化期望值。但平台若在 reset 后不重发系统期望值，终端在重启后就会回到默认音量/亮度。

**平台侧处理**：期望值全部由平台维护；平台在连接接管成功且业务配置下发完成后，按需重发系统期望值。终端重启与网络重连在平台看来不可区分，因此两种情况下都重发，符合 02§4「平台认为有必要时在连接建立后重新下发」。

### 4.5 上报 token 与触发器 token 的注册表必须分离（2026-09-18）

01§4 结尾指出两类 token "可以采用相同的字段形式，但分别属于上报 Response 和云端 Trigger，不隐含递归关系"。若用单张表存放，一个上报 token 被误用于 `business.trigger` 会造成语义错位的递归。

**平台侧处理**：`InteractionTokenService` 内部分两张表，查询接口分开，且 token 生成时使用不同前缀，任一方向查不到即返回错误。

### 4.6 配置预检会泄漏 token（2026-09-18，已修复）

`BusinessConfigService.validate()` 是干跑预检，但准备阶段本身有副作用：为云端 Trigger 和上报 Response 签发 token。首版实现只在预检**失败**时撤销，导致成功预检会在注册表里留下永远不会被使用的 token。若预检在部署流程中被反复调用，注册表会持续增长。

**平台侧处理**：`validate()` 改为在 `finally` 中撤销本次签发的全部 token，干跑不再有任何持久副作用。已补测试 `dryRunValidationLeavesNoTokens` 固定该行为。

### 4.7 上行音频流的开始宣告方式未定义（2026-09-18）

04_PROTOCOL_MODEL.md §7 给了生命周期形状（`JSON audio.start → Binary audio data → JSON audio.stop / audio.abort`），但只描述了**平台发起**下行播放的情况。新交互模型下，录音由终端在本地响应序列中自行开始（例如 `button.down → audio.record.start`），此时终端需要让平台知道上行流已开始，否则平台会把上行 PCM 当作"无活动生命周期数据"丢弃。

**平台侧处理**：约定终端在上行流开始时发送 `audio.start` 事件，body 可带 `direction`（缺省按 `uplink` 解释，因为下行由平台自己发起）。`AudioLifecycleEventSink` 同时注册 `audio.start`、`audio.stop`、`audio.abort` 三个名称。该项为缺口 G22 的扩展，待终端确认后收敛。

### 4.8 P5 必须拆成三道闸门（2026-09-18）

原计划把"工作流对接"与"旧协议路径删除"合成 P5 一步。动手前评估发现两件事的准入条件根本不同：

- 工作流改造的准入条件是**代码就绪**；
- 旧路径删除的准入条件是**终端固件全量切换**。

绑在一起的结果只有两种："要么永远不删所以 P5 永远不完成"，或者"删了但线上设备失联"。同时工作流侧自身含"产出配置"与"消费事件"两个可以独立验收的方向。

**平台侧处理**：拆成 P5a（产出配置 + 分流开关）、P5b（交互事件回流）、P5c（旧路径剪除）。P5c 的准入条件写进 [11_FEATURE_MATRIX.md](11_FEATURE_MATRIX.md) §5.3；在那之前旧路径一律只标 `@Deprecated` 不删除，见本文 §7。

### 4.9 响应序列全静态，业务动态由 token 承载（2026-09-18）

01§2 说 Response 序列"有限、有序、不可编程"，但没划定"不可编程"的边界：参数里能不能用 `$ref` 引用上游节点输出？能不能留一个"等平台算完再继续"的占位？

**裁决（已与需求方确认）**：响应动作**全部静态**，不包含任何动态行为。业务上的动态差异由平台**为同一动作签发不同 token** 来表达。

推论与实现：

- `audio.record` 的 `control=toggle` 不能下沉——依赖终端运行时录音状态；
- `audio.play` 带 `text` / `artifact_id` / `audio_file` 不能下沉——需要平台先产出音频；
- 任何含 `$ref` 的节点不能下沉。

`WorkflowActionMapper` 为每种节点类型显式给出"下沉或拒绝 + 拒绝理由"。这条规则的价值在于：配置里没有某个节点时，永远能查到它为什么不在，而不是一个静默的省略。

### 4.10 静态前缀截断与跨 slot 不截断（2026-09-18）

01§2 要求序列有序，但没说一条链中间夹着平台步骤时序列到哪为止。

**平台侧处理**：

- 同 slot 遇到首个不可下沉节点即**截断**，其后节点全部留平台——它们的输入依赖平台算出的结果，预先下发没有意义；
- 跨 slot 节点**不截断**本 slot 的前缀——它属于另一台设备，不需要本设备提供输入，只是需要平台在收到上报后协调；
- 只要产生了平台步骤，就在序列尾部追加一次 `platform.interaction.report`，让平台知道"本地部分做完了，继续云端流程"；没有平台步骤时不追加，避免让平台接收纯本地闭环的交互（02§4：平台不镜像终端微观状态）。

### 4.11 组装成功不等于下发成功（2026-09-18，已修复）

P5a 首版把"组装"和"校验"分在了两处：组装器只判断节点能否下沉，参数取值域等交给下发路径的 `BusinessConfigValidator`。结果是 `audio.play{preset:"ding"}` 这种配置——动作名合法、参数静态、下沉判定放行——会在部署接口返回成功、部署记录落库**之后**，才在 `business.update` 的校验里被拒绝。终端没收到配置，平台却已经认为这次部署生效了。

根因不是某条校验缺失，而是**存在一个"部署成功但未生效"的窗口**。部署是用户可见的动作，它返回成功就必须意味着配置一定能被终端接受。

**平台侧处理**：组装结束时用 `BusinessConfigService.validate` 对草稿配置做一次干跑，把校验错误转成部署 ERROR。选择干跑而不是把校验规则复制一份到组装器：复制会形成两处真值，将来规则改动必然漂移。干跑本身无持久副作用（§4.6 已修复 token 泄漏）。已补测试 `downstreamValidationFailureBecomesAssemblyError` 固定该不变量。

## 5. 未决问题

| # | 问题 | 影响 | 状态 |
| --- | --- | --- | --- |
| Q1 | 终端侧确认 G2 / G10 / G13 / G20 四项高风险的临时假设 | 协议能否真正对接 | 待终端协议实现完成 |
| Q2 | 平台侧业务配置与 token 需要持久化到什么程度 | 平台重启后的恢复能力 | 待定；首期内存实现（G17），可由业务层重新 update 恢复 |
| Q3 | 灰度策略：新旧协议并存期如何按设备分流 | 上线切换 | **已解决（P5a）**：`DeviceProtocolRouter` + `sdui.routing`，优先级 黑名单 > 白名单 > 缺省协议 |
| Q4 | Node Workflow 产出配置的粒度（整份配置 / 片段合并） | 工作流编排模型 | **已解决（P5a）**：以 deployment 为粒度；节点产出片段，部署时按设备合并为全量配置 |
| Q5 | 是否需要一个统一的设备侧操作审计视图 | 可观测性 | 待定 |
| Q6 | 组装产出的 `platformSteps` 由谁持有（内存 / 随部署落库） | P5b 的交互回流能否跨平台重启 | P5b 设计时 |

## 6. 开发过程记录

| 日期 | 动作 | 结果 |
| --- | --- | --- |
| 2026-09-18 | 冻结基线 | tag `v0.9.0-lcd085-pre-refactor`；确认基线可编译 |
| 2026-09-18 | 建立过程文档骨架 | 本文件 + `10_PLATFORM_UPGRADE.md` + `11_FEATURE_MATRIX.md` |
| 2026-09-18 | 交付 P1 协议内核 | 信封编解码、请求生命周期、连接接管与代次、新二进制帧头、`/ws/sdui/v2` 端点、模拟终端测试桩 |
| 2026-09-18 | 交付 P2 能力域 | `CapabilitySchemaV2`、`capability_hash` 计算与校验、按 hash 共享缓存、能力同步准入拦截、配置校验器 |
| 2026-09-18 | 交付 P3 业务配置域 | 绑定表模型、双域 token 注册表、`business.reset/update/trigger`、接管幂等重发、切换编排、跨域清理协调 |
| 2026-09-18 | 交付 P4 业务面 | `display.section/image/canvas`、`audio.*`、`system.*` 统一走 request/result；主视图互斥与丢帧策略 |
| 2026-09-18 | 验证 | `mvn test` 259 项通过（新增 134 项 v2 用例，无既有回归） |
| 2026-09-18 | 交付 P5a 工作流产出配置 | `WorkflowActionMapper` 节点下沉判定、`WorkflowBusinessConfigAssembler` 按设备组装、部署下发与停止清理、`DeviceProtocolRouter` 设备分流开关；旧协议路径统一标注 `@Deprecated` |
| 2026-09-18 | 修复组装与下发之间的静默失败窗口 | 组装结束增加下发校验干跑（§4.11），部署成功即等价于配置可被终端接受 |
| 2026-09-18 | 验证 | `mvn test` 295 项通过（新增 36 项 P5a 用例，无回归） |

后续进入 P5b：`platform.interaction` 事件驱动工作流运行、消费组装产出的 `platformSteps`、以新 token 驱动终端。

## 7. 待清除模块清单（P5c 用）

P5a 阶段把所有将被删除的旧协议路径统一标注为 `@Deprecated(since = "0.10.0")`，并在注释里写明替代项。这样：

- 现在删除会破坏在线设备，所以不删；
- 将来删除时不需要重新梳理"哪些能动"，照本表执行即可；
- 若需回滚到旧协议，只需撤销 `@Deprecated` 标注，代码始终可用。

| 文件 | 被替代项 | 备注 |
| --- | --- | --- |
| `sdui/SduiControlAckHandler.java` | v2 统一 request/result 信封 | `cmd/control_ack` 入口 |
| `sdui/service/CommandDispatcher.java` | v2 `business.*` / `display.*` / `audio.*` / `system.*` | `cmd/control` 派发 |
| `sdui/protocol/SduiProtocolConstants.java` | `sdui.v2.protocol.V2Names` | 旧 topic 与协议常量 |
| `sdui/handler/EventInputHandler.java` | v2 `platform.interaction` + token 反查 | TLV 输入（msgType 9） |
| `sdui/protocol/BinaryProtocolCodec.java` | `sdui.v2.protocol.BinaryFrameCodecV2` | 旧 16 字节帧头 |
| `sdui/protocol/CapabilitySnapshotParser.java` | `sdui.v2.capability.CapabilitySchemaV2` | 能力名称快照解析 |
| `sdui/CapabilitiesReportHandler.java` | v2 能力 Schema + `capability_hash` | 能力名称上报 |
| `sdui/section/SectionOrchestrationService.java` | `sdui.v2.display.DisplayCommandService` | Section 场景 / Patch 编排 |
| `sdui/section/SectionPatch.java` | `display.section` 全量替换 | 增量 Patch 下发 |
| `sdui/section/SectionTypeCatalog.java` | 03_UI_MODEL.md §2.1 的五类 | 14 类 Section 目录 |
| `sdui/service/AudioService.java` | `sdui.v2.audio.AudioCommandService` + 二进制帧 | Base64 内联音频 |
| `sdui/routing/DeviceProtocolRouter.java`<br>`sdui/routing/SduiRoutingProperties.java`<br>`application.yml` 的 `sdui.routing` | 无（过渡期专用） | P5a 新增，仅为并存期存在 |
| `sdui/resources/static/sdui-node-test.html` 等三个调试页 | v2 调试页面 | 见 11_FEATURE_MATRIX 5.18 |

> 上表只列"删除类"改动。P5b 对 `NodeWorkflowRuntimeService` 的改造属于改写而非删除，单列在 [11_FEATURE_MATRIX.md](11_FEATURE_MATRIX.md) §5.2。

## 8. 已知限制

| # | 限制 | 原因 | 回收条件 |
| --- | --- | --- | --- |
| L1 | 业务配置与 token 只在内存（G17） | 平台重启后由业务层重新 `business.update` 即可恢复，首期不值得引入持久化 | 需要跨重启保证时 |
| L2 | `platformSteps` 目前只回传给部署接口，未持久化 | P5b 才有消费方；提前落库会固化尚未定型的结构 | P5b 设计定稿（Q6） |
| L3 | 组装产出的配置版本号不回填到部署响应 | 下发是异步的，回填会让部署接口等待设备 ACK | 需要"部署即拿到版本"的交互时 |
| L4 | 组装阶段的参数取值域校验依赖下发校验器的干跑 | 刻意不在组装器里复制一份参数校验规则——复制会形成两处真值，改动必然漂移（见 §4.11） | 无需回收，属于刻意的职责划分 |
