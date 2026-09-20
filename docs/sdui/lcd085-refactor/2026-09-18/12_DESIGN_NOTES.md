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
| G25 | 平台为动态节点生成专用绑定并签发 token 的形态 | 01§3 | 未实现：遇到只能由终端执行的动作（`usableIn=binding`）时返回 `terminal_action_required` 而不自造绑定 | 中：`rgb.effect` / `audio.record` 类平台步骤无法闭环，需终端确认后再定 |

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
| G26 | 下行音频的容器约定 | 04§7 | 平台侧发送裸 PCM（拆掉 WAV 头），如实记录采样率 / 声道 / 位宽；不重采样 | 中：终端若期望 WAV 头则需对齐，见 T16 |

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

**平台侧处理**：拆成 P5a（产出配置）、P5b（交互事件回流）、P5c（旧路径剪除）。P5c 的准入条件写进 [11_FEATURE_MATRIX.md](11_FEATURE_MATRIX.md) §5.3；在那之前旧路径一律只标 `@Deprecated` 不删除，见本文 §7。（P5a 曾包含一个设备级分流开关，已在 0.12.0 按 §4.12 撤销。）

### 4.9 响应序列全静态，业务动态由 token 承载（2026-09-18）

01§2 说 Response 序列"有限、有序、不可编程"，但没划定"不可编程"的边界：参数里能不能用 `$ref` 引用上游节点输出？能不能留一个"等平台算完再继续"的占位？

**裁决（已与需求方确认）**：响应动作**全部静态**，不包含任何动态行为。业务上的动态差异由平台**为同一动作签发不同 token** 来表达。

推论与实现：

- `audio.play` 带 `text` / `artifact_id` / `audio_file` 不能下沉——需要平台先产出音频；
- 任何含 `$ref` 的节点不能下沉。

`WorkflowActionMapper` 为每种节点类型显式给出"下沉或拒绝 + 拒绝理由"。这条规则的价值在于：配置里没有某个节点时，永远能查到它为什么不在，而不是一个静默的省略。

> **修正（2026-09-18，P5b）**：本节原先还写了"`audio.record` 的 `control=toggle` 不能下沉——依赖终端运行时录音状态"。该判定把"运行时状态判断"误当成了"参数动态"。01§5 明确 `toggle` 是**音频模块根据真实状态解释的便利动作**，其动作名与参数（空）都是静态的，状态判断是终端自己的职责，因此可以下沉为 `audio.record.toggle`；终端未声明该便利动作时由能力门禁拦下。已改为下沉并补进 `WorkflowActionMapperTest`。判据因此收敛为一条：**只看动作名与参数是否静态、是否依赖平台产物，不看终端是否需要读自身状态。**

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

### 4.12 撤销设备级分流：不做灰度，v2 是唯一协议（2026-09-18）

P5a 引入了 `DeviceProtocolRouter` + `sdui.routing`（优先级 黑名单 > 白名单 > 缺省协议），目的是支持"新旧协议并存期按设备分流"。需求方明确否决：这是个人项目，不需要渐进切换，全量切到 v2 即可，不必为此背复杂度。

**裁决**：删除分流开关及其配置段。`NodeWorkflowDeploymentService` 对部署涉及的每台设备一律组装并下发 `BusinessConfig`；原先"旧协议设备跳过并记 `legacyDevices`"的分支消失。`AssembleResult` 从 `(configs, legacyDevices)` 收敛为 `(configs)`，部署结果里的 `protocol` 字段一并去掉——只剩一套协议时该字段没有信息量。

**代价（必须记下来）**：分流开关同时是旧路径的**回退手段**——某台设备切到 v2 后出问题，把它加进 `legacy-devices` 就能立刻按回旧协议排查。删除之后这个手段没有了：P5c 的旧路径删除只能一次性完成，且不能再按设备回退。这是主动接受的取舍，不是遗漏。相应地，P5c 的准入条件从"终端切换完成且不再有 legacy 设备"收紧为"终端切换完成"。

**顺带核查**：以旧协议为根做传递闭包，`sdui` 非 v2 的 165 个主代码文件中约 79 个（48%）仍在旧协议下游，另有约 23 个测试文件。旧协议栈是通过 `List<TopicHandler>` / `List<BinaryFrameHandler>` 由 Spring 收集装配的，**没有可以顺手删掉的孤立死枝**——它整块都是活的，只能等 P5b 把运行时换到 v2 之后再整块移除。

### 4.13 清理其他模块的死代码（2026-09-18）

按要求"其他模块也检查一下"，对非 `sdui` 包做了引用图扫描。以下三类完全无引用且配置已失效，属确定死代码，已删除：

| 文件 | 判定依据 |
| --- | --- |
| `ai/config/DynamicAiProviderRegistrar.java` | `@Deprecated` 且 `@Configuration` 已被注释掉，非 Spring bean；全仓零引用 |
| `ai/config/DynamicAiProvidersProperties.java` | 仅被上述 Registrar 引用，随其一起失效；任何 yml 中都无 `spring.ai.dynamic-providers` 配置 |
| `ai/enums/ServiceType.java` | `@Deprecated`，全仓零引用（枚举值 `DATABASE_METADATA` 亦无使用） |

其余扫描命中项**未改动**，理由记录如下，供后续判断：

- `common/web/GlobalContextInterceptor` 的 `X-Space-Id` 兜底与 `common/config/SwaggerConfig` 的对应说明——这是与旧前端约定的身份传递兼容层，涉及鉴权语义，与本次协议重构无关，贸然移除可能影响登录链路。
- `sdui/event/EventPayload`、`EventRegistry`、`service/audio/AudioRecordHandler` 内的 `backward compat` 分支——它们服务的是**同一协议内**的旧报文形态，属 P5c 剪除范围，已登记在 §7，本阶段不动。

### 4.14 交互回流：上下文的承载与平台步骤的来源（2026-09-18）

01§4 说"平台根据设备和 token 恢复业务上下文"，但没定义平台侧拿什么记住上下文。P5a 的做法是按上报里的 `triggerId` 去反查活动部署——这在同一工作流的多个部署把同一个物理按钮绑到不同 slot 时不成立，反查结果不唯一。

**裁决**：token 注册时携带 `contextRef`，形如 `wf:<workflowId>:<triggerNodeId>`（S10）。`workflowId` + `triggerNodeId` 在一次工作流定义内唯一，可直接定位到"哪一次部署的哪个触发节点"，不需要反查。`contextRef` 只在平台侧注册表内流转，不进 `BusinessConfig` 的下行报文——终端不感知也不回传。解析失败返回空并安静跳过，以兼容非工作流场景写入的 `binding:<triggerId>`。

另一个空缺是"平台步骤从哪来"（Q6）。P5b 的裁决是**随部署固化**：部署时把 `triggers` 与 `platformSteps` 一起写入 `NodeWorkflowDeploymentEntity.businessConfigs`，运行时读取。若改成运行时按当前能力 Schema 与工作流定义重新组装，工作流一旦被编辑，平台侧执行的就不再是设备侧已生效配置对应的那一份，形成静默漂移。代价是工作流定义变更后必须重新部署才生效——这与设备侧的行为一致，不是缺陷。

### 4.15 平台步骤中"只能由终端执行"的动作（2026-09-18）

平台步骤里混着两类节点。`display.section` / `ui.update` / `audio.play` 平台可以作为请求下发；而 `rgb.effect` / `audio.record` 在能力 Schema 里只声明 `usableIn=binding`——平台没有请求可发。

可以走的三条路：① 退回旧协议的遥控路径（与"v2 是唯一协议"直接冲突）；② 自造一套"平台动态生成绑定并签发 token"的形态（文档未定义，会先固化一个错的接口）；③ 如实返回 `terminal_action_required`。

**裁决**：选 ③。第 3 条路只说明模型的真实结果——动作所有权在终端的本地响应序列，需要终端的配合才能让平台发起。已登记为缺口 G25 / 待确认项 T15。选它的理由是：前两条都会**掩盖**这个缺口，而缺口的代价只是"这类节点暂时不闭环"，代价可控；自造接口的代价是终端按错形态实现后再改。

注意 `terminal_action_required` 在运行记录里是 **`ok=true`**（平台已尽到职责，只是这一步不由平台交付），不计入失败，后续平台步骤继续执行。

### 4.16 v2 接入层遗漏设备租户上下文（2026-09-18，P5b 修复）

P5b 把工作流运行时挂到 `platform.interaction` 上之后，暴露了一个 P1–P4 遗留的真实缺陷：**v2 的 WebSocket 线程上没有租户**。

平台数据隔离靠 Hibernate `@TenantId`，租户取自线程上的 `GlobalContext`（见 `UserIdResolver`）。HTTP 请求会设置它，WebSocket 线程不会，于是租户退化成 `default`。而设备表 `sdui_device` 是一张**全局表**（用 `ownerUserId` 列记归属），部署 / 运行 / UI 上下文 / artifact 才是租户表。结果是同一线程上"读设备成功、读该设备的部署记录却查不到"——旧协议栈的 `MessageRouter` 做了这一步，v2 接入层漏了。

**平台侧处理**：新增 `DeviceTenantContext`，在路由分发点（请求 / 事件 / 二进制三类）按设备归属包裹处理器，作为 v2 侧唯一的租户建立入口（S13）。读设备这一步刻意放在设租户之前：租户信息本身来自设备行，而 `sdui_device` 没有 `@TenantId`，所以这一步天然可行；若将来它被改成租户表，这里会立刻查不到设备——属于显式失败，而不是静默串租户。

这个缺陷的教训值得记下来：**P1–P4 的单测都是不落库的 mock 测试，租户不匹配在 mock 层完全不暴露**。跨租户的读写只有在真实持久层上才会现形。

### 4.17 单 Section 收敛点与下行音频容器（2026-09-18）

**单 Section 收敛。** v2 只有 `display.section` 全量替换，没有 Section 级增量更新，而平台侧的业务写法里仍有"场景"与"Patch"。若让每个下发点自己决定怎么转换，就会出现多处真值。收敛到唯一一处 `SectionViewResolver`（S12）：场景取首页 Section 作为主视图；Patch 在平台侧合成为完整 Section 后再下发；平台没有当前快照时**回退为下发完整场景**——比静默失败更接近调用方意图（把界面切过去）。业务侧统一经 `PrimaryViewPublisher` 出口。

**下行音频容器。** artifact 里存的是 WAV 文件，而 04§7 只说"二进制帧承载音频数据"。直接把 WAV 整段发出去会把 44 字节头当音频播出来。裁决：拆出裸 PCM 载荷发送（S14），不做重采样——采样率由终端按自身声明处理，平台如实记录 `declaredSampleRate` 便于比对。终端的容器期望尚未确认，登记为 T16。

### 4.18 P5c 删除清单经引用图复核（2026-09-18）

§7 原清单是"入口文件 + 规模估算"（约 79 个 / 48%），用来执行不够。本轮写 `scripts/sdui-refgraph/refgraph.py` 解析全工程 import 与类型引用、重建引用图后重新判定，结论如下。

**判据。** 一个类可整文件删，当且仅当**它的所有引用者也都可删**，且**它没有实现删除集之外的工程内契约**。第二条是必需的：Spring 按类型注入的实现类天然零显式引用者，只按"零引用即死代码"判定，会把 `FfmpegTtsProvider` 这类**正在被 P5b 使用**的实现一起删掉。

**裁决一：v2 对非 v2 包的硬依赖只有 6 个类**——这是 P5c 不可逾越的边界：`SectionScene`、`SectionPatch`、`SectionData`、`SectionEntry`、`SectionDataCodec`（经 `SectionViewResolver` / `PrimaryViewPublisher`）、`SduiDeviceRepository`（经 `DeviceTenantContext`）。

**裁决二：原清单有两条与代码矛盾，予以修正。**
- `SectionPatch`：原列为"删除，由 `display.section` 全量替换"。但 v2 的单 Section 收敛点正是靠它接收业务侧的 Patch 表达（§4.17），`SectionData` / `SectionEntry` / `SectionDataCodec` 同理。它们是**平台侧内部模型**，不是设备协议细节——要删的是 `SectionOrchestrationService` 里的下行编码路径，不是模型本身。
- `SectionTypeCatalog`：原列为"删除，14 类收敛为 5 类"。但它被 `SduiUiTemplateService` 用于模板 Section 类型校验。处置改为**收敛类型集合、保留服务**。

**裁决三：P5c 的难点不在"删文件"，而在控制层。** 在当时保留集下，无需任何改造即可整文件删除的只有 6 个，外加 1 个"部分删除"。另有 87 个类**必须先裁剪引用方才能删**，阻塞源集中在 `controller` / `debug`（§7.2 表）。**执行顺序因此是先裁剪这些接口，再删模型**，而不是反过来。

> 本节是 0.13.0 那一轮的判定记录。数字已随 0.14.0 的接口集落地而改变（A 类 12、候选池 93），且第三条判据（接线根）是 0.14.0 才补上的（§4.20）。**以 §7 的实测数字为准。**

**裁决四：`sdui` 包对外封闭。** `sdui` 之外 58 个主代码文件对 `sdui` 非 v2 类的引用数为 0——P5c 不会波及 `sdui` 之外。

**裁决五：上行音频链路在 v2 没有替代。** `AudioRecordHandler` / `AudioRecordChunkHandler` / `AudioRecordSessionManager` 是旧协议上行音频（二进制帧）的处理链，也是平台**唯一**的录音通路。v2 侧（§4.7 / T11）平台尚未实现，删除它们等于放弃录音能力——须待 T11 落地。

### 4.19 对外接口集按"闭环"重构（2026-09-18，0.14.0）

**问题。** P5b 把运行时切到了 v2，但控制层还按旧模型暴露接口：设备详情里挂着 `availableCommands` / `capabilityContract` / `recentCommands`，能力域提供 `tree` / `metadata` / `protocol` / `sections` 这类旧视图，调试域以 `command` / `section` / `section/patch` 为名。结果是**平台内部只有一套协议，对外却有两套词汇**——路径里出现旧词，就一定有人往回接旧模型。

**做法。** 先定契约（`docs/sdui/front/CLIENT_API.md`），再按域改写控制器。契约以闭环组织——设备上线 → 能力核验 → 编排 → 部署 → 终端本地执行 → 交互上报 → 平台续接 → 观测——每一步都问"前端要回答什么问题"，答不上的接口删掉。

**四条单一来源约束**（写入契约 §4，逐条在代码里落实）：

| 约束 | 唯一来源 | 之前的第二份来源 |
| --- | --- | --- |
| 在线态 | `DeviceConnectionRegistry` | `DeviceSessionManager`（旧协议） |
| 能力 | v2 `CapabilitySchemaV2` | `CapabilityRegistry` / `CapabilityContractService` |
| 校验器 | `BusinessConfigValidator` | 事件目录自带一套校验 |
| 出站路径 | `PlatformRequestService` | 调试域独立命令通道 |

保留模块（`NodeWorkflowDeploymentService`、`NodeWorkflowManagementService`、`CapabilityNodeTestService`、`NodeWorkflowDebugService`、`SduiDeviceService`）的 `DeviceSessionManager` 依赖因此一并换成 `DeviceConnectionRegistry`——**否则"唯一在线态"只是文档里的一句话**。

**关键判据：`usableIn` 决定平台能否发声。** 能力 Schema 对每个动作声明 `binding`（终端本地响应）或 `request`（平台可请求）。只声明 `binding` 的动作平台**发不出请求**，`/capabilities/{id}/actions` 如实标注，调试域按同一判据返回 `unsupported`——而不是伪造一条下行让前端以为能调。这让"哪些节点只能下沉"在前端可见，与 `WorkflowActionMapper` 的下沉判定同源。

**节点目录的真值收敛。** `NodeTypeRegistry` 同时被编辑器（列出节点）与运行时（`WorkflowActionMapper` 执行/映射）使用，不再是两份登记表。新增一致性用例断言两者一一对应，消除"编辑器里有、运行时没实现"的漂移。

**落地结果。** 控制层的旧模型阻塞量从约 59 降到 18（§7.2），`DebugController` 与 `CapabilityController` 已完全脱离旧模型。删除重复端点 `/debug/node-workflows/**`（与 `/node-workflows/**` 逐一重复，前端从未调用）。补上契约声明却缺失的 `GET /{workflowId}/deployments/{deploymentId}/config`（固化的业务配置出口）。

**修正一处错误注释。** `CapabilityNodeController` 原标注"前端未使用，预期移除"，实际编辑器与两个测试页都在调用。教训：端点是否在用要靠扫描前端调用点（`scripts/sdui-front-paths.py`）判定，不能靠印象——这正是 Q9 之前一直无法关闭的原因。

**复核并撤销一处定性（同日，0.14.1）。** `/board-types/{board}/section-triggers` 曾被记作"接口集里最后一处旧 Section 模型泄漏，应从 v2 Schema 的 `surface.ui` 派生"。复核后该定性不成立，理由三条：

1. **数据源不是旧协议。** 三级树的输入是平台页面定义（`PageService` / `SectionPageDefinition`）与平台 Section 类型目录（`SectionTypeCatalog`，由 `sdui-event-catalog.yml` 驱动）。二者都是平台侧产物，与终端固件版本无关。
2. **"从 `surface.ui` 派生"技术上不成立。** `CapabilitySchemaV2.UiSpec` 只有 `sectionTypes`（允许的类型名）与 `viewModes`，不含元素与事件定义；实例级三级树不可能由它生成。
3. **v2 已有相反裁决。** `v2.display.SectionViewResolver` 类注释明确"不做 Section 类型的能力门禁——终端声明的 `ui.sectionTypes` 属于 `CapabilitySchemaV2` 的校验职责，在这里再判断一次会形成第二处真值"。因此本端点也不应叠加门禁。

**结论**：本端点不阻塞 P5c。它依赖的 `SectionTypeCatalog`、`PageService`、`EventRegistry` 都被保留包（`ui` / `workflow`）使用着（`SduiUiTemplateService` 用 `SectionTypeCatalog` 做模板字段校验，`NodeWorkflowSupport` 用 `EventRegistry`），属活代码；随本端点存废的只有 `SectionTriggerCatalog` 与 `SectionTriggerCatalogService` 两个薄封装。前端当前未调用它（`scripts/sdui-front-paths.py`），属编排面板尚未接线。Q10 据此关闭。

### 4.20 引用图的第三类误判：接线根（2026-09-18，0.14.0 修正）

§4.18 给了两条判据（引用者全可删 + 未实现删除集外的**工程内**契约）。**0.14.0 发现两条都不足以覆盖接线类。**

`WebSocketConfig` 带 `@Configuration`、实现 Spring 的 `WebSocketConfigurer`，方法体里注册了 `/ws/sdui` 与 `/ws/sdui/v2` 两个端点。它的显式引用者数为零，实现的契约（`WebSocketConfigurer`）在工程外，于是被判定为 A 类"可整文件删"。照此执行会删掉 v2 的接入端点——**编译通过、测试通过、运行时不报错**，只是设备再也连不上。这是最危险的一类误判：没有任何自动化信号会提示它。

**修正**：脚本新增第三类——**接线根**。凡带 `@Configuration`，或实现框架回调接口（`WebSocketConfigurer` / `WebMvcConfigurer` / `CommandLineRunner` / `ApplicationListener` / `Filter` 等，见 `FRAMEWORK_CALLBACKS`）的类，一律**不作为可删候选**，并单独列为改造点。

判据的补充表述：

> f 可删 <=> 引用者全可删 **且** 未实现删除集外的工程内契约 **且** f 不是接线根。

接线根与页面配置类的区别值得记住：前者的作用是"把别人接上"，删除后果不体现在引用图上；后者（如 `@Component` 注解的 Handler）删除后其注册行会编译失败，引用图看得到。因此判据只保护 `@Configuration` 与框架回调，不保护普通 `@Component`——否则 `SduiWebSocketHandler` 这类真正该删的类会被永久保护。

**实测影响**：A 类从 15 降到 **12**。移出的 3 个是 `WebSocketConfig`（转为接线根）与 `SduiWebSocketHandler`、`MessageRouter`（转为"先删注册行再删"，见 §7.1b）。数量变小了，但清单从"照删会出事故"变成了"照删是安全的"。

## 4.21 引用图的两处口径修正：释放量与保留闭包（2026-09-18，0.14.1 复核）

复核 Q10 时发现引用图的两处输出会误导 P5c 的执行顺序，一并修正。

**一、"引用数"不等于"释放量"。** 原"释放容量"表统计的是"该控制层类引用了多少个滞留类"。但按判据 `f 可删 <=> 引用者全可删`，只有滞留类的引用者**全是候选方**时，裁掉它才能释放；若另有保留方引用，裁掉多少都不释放。于是 `CapabilityNodeTestService`（引用 5 个）被排在第一位，实际它**一个都释放不了**。

改为**逐个推演**：把该类视为已删除后重跑收敛，看 A 类增量。为此给 `converge()` 加了 `removed` 参数——少了它，"引用者已删"的类仍会被判为受阻。修正后的排序：`BoardTypeController` 2、`WebSocketConfig` 2、`DeviceController` 1，其余控制层类均为 0。

**二、"直接引用者是不是保留包"漏掉间接依赖。** 原判断只看被引用类的直接引用者。`event/EventRegistry` 的直接引用者全在候选池，看起来可删；实际上 `ui.SduiUiTemplateService → section.SectionTypeCatalog → event.EventRegistry` 是一条真实链（构造器注入，非 javadoc）。

改为计算**保留闭包**：从保留包出发（种子排除接线根与 `controller` / `debug` 两个待裁层），沿类型引用与继承关系求可达集。实测 **39 个**，全部属于"保留方不改动就删不掉"。§7.3 据此重写——原表声称可删的旧能力 / 事件模型，实际上全在闭包内。

**种子为什么必须排除接线根与待裁层**：`WebSocketConfig` 虽在保留集，但它要裁剪（只留 `/ws/sdui/v2` 注册）。把它当种子，会把 `SduiWebSocketHandler → MessageRouter → DeviceSessionManager` 整条旧链误判为保留。同理 `controller` / `debug` 层已在 §7.2 列为待裁，它们当前的引用是**改造点**，不是保护。

**附带的两个修正**：`section/SectionPatch` 与 `section/SectionTypeCatalog` 原先都带 `@Deprecated(since="0.10.0")` + "legacy protocol path, scheduled for removal"。前者被 v2 主视图收敛依赖，后者被 UI 模板域依赖——照删都会**编译通过但功能悄悄消失**（前者让 v2 下发失败，后者让模板字段校验失效）。两处标注已改为说明其真实依赖，`@Deprecated` 已移除。

## 4.22 T17 解耦：切两条边，保留闭包 39 → 22（2026-09-20，0.14.2）

T17 原写作"`ui` 包如何从旧能力 / 事件模型解耦，入口是三个 ui 服务"。逐类扫描依赖后发现**边界画错了**：旧簇的保留层入口只有两条边，都不需要动 `ui` 包整体。

**第 1 条边：`ui.SduiUiTemplateService → protocol.catalog.DeviceCapabilityProjection`。**
`DevicePrimaryUiService` 与 `WorkflowUiContextService` **并未**引用投影类——它们只依赖 `section/` 平台 UI 模型（合法保留）。`SduiUiTemplateService` 对投影类的全部使用只有一行：

```java
capabilityProjection.sections(deviceId).forEach(section -> supported.add(section.type()));
```

即"设备支持哪些 Section 类型"。该问题 v2 已有裁决归属：`CapabilitySchemaV2.surface.ui.sectionTypes`（`v2.display.SectionViewResolver` 明确不在渲染侧二次判断）。故改为经 `CapabilityQueryService` 新增的 `sectionTypes(deviceId)` 读取。

**为什么去掉与平台类型目录的求交**：原实现取设备声明与 `DeviceProtocolCatalog.sections()` 的交集。但类型本身是否存在，已由 `SectionTypeCatalog.isValidType` 在模板创建时（`normalizeDefinition`）校验过；在下发门禁里再求一次交，是同一事实的第二处判断。v2 的裁决同样是"门禁只认 Schema 声明"。

**第 2 条边：`section.SectionTypeCatalog → event.EventRegistry`。**
该类的 `init()` 里只有一句 `eventRegistry.getSectionTypes()`——它把运行时事件注册表当作 YAML 配置读取器使用。改为依赖 `EventCatalogLoader`：后者是 `sdui-event-catalog.yml` 的加载边界，自身无构造依赖（不会成环），且 `@PostConstruct` 顺序由依赖关系保证，比原先的 `@Lazy` 代理更可靠。

**实测效果**（`scripts/sdui-refgraph/refgraph.py`）：保留闭包 **39 → 22**。出闭包的 17 类：

| 簇 | 类 |
| --- | --- |
| 旧能力模型（14） | `CapabilityCatalog`、`CapabilityRegistry`、`protocol/CapabilitySchema`、`CapabilitySnapshotParser`、`SduiProtocolConstants`、`protocol/catalog/*` 6 类、`service/CommandSchemaRegistry`、`SduiCapabilityService` |
| 旧事件模型（3） | `event/EventRegistry`、`EventPayload`、`protocol/BinaryProtocolCodec` |

**A 类（可整文件删）仍为 12**——出闭包不等于立即可删，这些类仍被旧协议簇自身引用。释放它们与裁控制层是两件事。

**结论：`SectionTypeCatalog` 不是"ui 的东西"。** v2 侧经 `v2.display.SectionViewResolver → section.SectionDataCodec → SectionTypeCatalog` 同样依赖它，而 `SectionDataCodec` 是 v2 硬边界。它是 UI 模型与 v2 **共享的类型定义源**，必须保留；要切的是它的**上游**（不该是事件模型）。

### 4.22.1 引用图新增：阻塞源归因

原"推演后仍留在池中"一节的说明写作"引用方为 ui / v2，属真实依赖"。解耦后该口径失真：多数阻塞类并非被保留方直接引用，而是陷在旧协议簇的内部循环里，循环本身不构成保护，真正要处理的是把循环挂到保留层的**入口**。

新增输出：从每个滞留类沿反引用上溯（限定在候选池内），收口到保留层成员，按拖住的类数排序。实测（裁掉 controller / debug 后）：

| 保留层入口 | 拖住 | 性质 |
| --- | --- | --- |
| `WebSocketConfig` | **56** | 接线根的旧端点注册（§7.1b：只删两行）——**P5c 的主阻塞源** |
| `ui.SduiUiTemplateService` | 14 | 平台 UI 模型 + 配置边界（真依赖） |
| `ui.DevicePrimaryUiService` / `ui.WorkflowUiContextService` / `v2.display.SectionViewResolver` | 各 11 | 同上 |
| `v2.display.PrimaryViewPublisher` | 6 | v2 硬边界 |
| `workflow.NodeWorkflowDeploymentService` | 6 | 节点目录 |
| `workflow.WorkflowPlatformStepExecutor` | 1 | TTS |
| （无保留层入口） | 25 | 随内部循环一起消失，无需单独改造 |

这一节能直接回答"删这一个到底能解锁什么"，取代原先按引用数排序的粗筛。

### 4.23 前端调试页面对齐 v2：新建联调台，不改造旧三页（2026-09-20，0.14.3）

**背景**：0.14.0 重整接口集后，调试域按 v2 请求路径重写，但 `scripts/sdui-front-paths.py` 显示三个页面**只用到 `node-tests` 三个端点**；`/request`、`/view`、`/requests*`、`/events/stream` 全部无调用方。留下的空白是：P5c 需要"终端切换后逐条确认 v2 通道可用"，而当时没有任何工具能做这件事。

**先排除误判**：把三个页面的每一条调用路径与当前控制器端点逐一比对，**全部仍然有效**——0.14.0 删掉的是 `/debug/{id}/command`、`/debug/{id}/commands*`、`/debug/{id}/section*`、`/debug/node-workflows/**` 这些前端从未调用的路径。旧页面没有坏链，它们只是停在"节点目录驱动"的工作流测试视角，看不到 v2 的能力面与请求面。

**顺带修掉扫描器的一处漏报**。做上述比对时发现 `scripts/sdui-front-paths.py` 的路径提取有缺陷：正则 `[^\s"'`)|]+` 逐字符匹配到右括号即止，而 JS 模板里带参数的路径写作 `${encodeURIComponent(deviceId)}`，于是**所有带参数的路径都被截成半截**（`/api/v1/sdui/debug/${encodeURIComponent(deviceId`），占位符替换随之失效。后果不是显示难看，而是**截断后的路径无法与控制器端点对账**——"仍在使用的端点"会看起来像没人用。这与 §7.2 判断端点存废用的是同一份输出，所以先修它。

修正两处：① 把 `${...}` 整体作为一个匹配单元，带参数的路径现在能完整列出；② 新增"路径不是字面量"提示——若调用点写成 `api(devicePath('/requests'))`，路径由辅助函数拼出，扫描器看不见，这类静默漏报必须能被发现。本页因此改为直接写完整路径（模板字面量内部仍可插值），修正后扫描输出 0 告警。

**处置：新建 `static/sdui-v2-console.html`，不改造旧三页。** 三条理由：

1. **视角不同**。旧页从节点目录（`CapabilityNodeCatalog`）出发，是编排视角；新页从设备能力 Schema 出发，是协议联调视角。终端对接真正需要的是后者——先看设备声明了什么，再按 `usableIn` 挑一个平台发得出去的动作，确认它到底通不通。
2. **旧三页本身在 P5c 待删清单里**（§7.2 / §7.3）。改造一份即将删除的前端只会留下迁移债；新页与旧页并存，P5c 剪除旧协议簇时旧页随之消失。
3. **不引入第二处真值**。新页只经四类 v2 端点取数：能力四端点（单一来源 `CapabilityQueryService`）、出站两端点（与业务同走 `PlatformRequestService`）、请求目录与历史（`usableIn` 分级）、两条 SSE（平台内部已有事件的转发，不自造事件类型）。没有一处新增平台侧能力推断。

**页面覆盖**：

| 页签 | 端点 | 回答什么 |
| --- | --- | --- |
| 能力 | `GET /capabilities/{id}`、`/sync`、`/schema`、`/actions`、`/triggers` | 声明了什么、同步到哪一步、哪些动作平台发得出去 |
| 请求下发 | `GET /debug/{id}/requests`、`POST /request`、`GET /requests/history` | 按参数规格表单化下达，结果落历史 |
| 主视图 | `POST /view`（section / image / canvas）、`GET` / `DELETE /view/state` | 三种主视图互斥、显示会话字节数与拒帧数 |
| 实时流 | `GET /requests/stream`、`GET /events/stream` | 请求结果与 `platform.interaction` / `business.cleared` / `device.connection` 的实时到达 |

**对 P5c 的影响**：不改变可删集——新页不引用旧模型，也不释放旧类。它提供的是**验收手段**：终端固件切到 v2 后，5.10–5.16 的每一项删除都可先用本页确认对应通道已在 v2 上跑通，再动刀。

### 4.24 §7.2 的"释放量"是推演值，不等于该删（2026-09-20）

做 §4.23 的路径对账时发现一处文档间的不一致，需要指出。

§7.3.4 的执行顺序第 1 步写作"裁 §7.2 的三个控制层类 → 释放 3 个旧模型类"，即 `BoardTypeController`（`/section-triggers` → `SectionTriggerCatalog`、`SectionTriggerCatalogService`）、`DeviceController`（`/telemetry/trends` → `TelemetryTrendService`）、`WebSocketConfig`（旧端点两行 → `SduiWebSocketHandler`、`MessageRouter`）。

但前两个端点**都在 0.14.0 的接口契约里**：`/telemetry/trends` 是 §2.1 设备域的正式端点；`/section-triggers` 是 §2.3 中带长篇辩护的编排面板数据源，契约明确写它"不阻塞 P5c，也无需改写……前端当前未调用属编排面板尚未接线"。

**这与 §7.2 不矛盾，但容易被读错**：表格里的"真实释放量 2 / 2 / 1"回答的是"**假如**删掉这类能释放几个"，是**推演上限**，不是"应当删除"的结论。§7.2 自己的判据也只写"处理方式：删除端点，或改读 v2 的能力 Schema"——对这两个端点，契约给出的答案是**不处理**。

**结论：5.10.1（"裁剪控制层对旧能力 / 事件模型的暴露"）在当前状态下没有可安全执行的删除动作**，应重新裁定为"控制层现有暴露均在契约内，属活功能；真正的裁剪对象只有 `WebSocketConfig` 的旧端点注册两行，而它属于 P5c 准入（需终端切换）"。§7.3.4 执行顺序第 1 步因此实际只剩第三项，可释放 2 类而非 5 类。

这条裁决**收紧**了删除集（少删 3 个类，方向保守），但需在终端切换前与编排面板的接线计划一并确认：若 `/section-triggers` 最终被编排面板采用则必须保留；若编排改用别的方式取三级树，则它随 §7.2 处理。

## 5. 未决问题

| # | 问题 | 影响 | 状态 |
| --- | --- | --- | --- |
| Q1 | 终端侧确认 G2 / G10 / G13 / G20 四项高风险的临时假设 | 协议能否真正对接 | 待终端协议实现完成 |
| Q2 | 平台侧业务配置与 token 需要持久化到什么程度 | 平台重启后的恢复能力 | 待定；首期内存实现（G17），可由业务层重新 update 恢复 |
| Q3 | 灰度策略：新旧协议并存期如何按设备分流 | 上线切换 | **已关闭（0.12.0）**：不做灰度。分流开关已删除，v2 是唯一协议，见 §4.12 |
| Q4 | Node Workflow 产出配置的粒度（整份配置 / 片段合并） | 工作流编排模型 | **已解决（P5a）**：以 deployment 为粒度；节点产出片段，部署时按设备合并为全量配置 |
| Q5 | 是否需要一个统一的设备侧操作审计视图 | 可观测性 | 待定 |
| Q6 | 组装产出的 `platformSteps` 由谁持有（内存 / 随部署落库） | P5b 的交互回流能否跨平台重启 | **已解决（P5b）**：随部署固化进 `businessConfigs`，可跨重启，见 §4.14 |
| Q7 | 平台能否为动态节点签发专用绑定 / 新 token | `rgb.effect` / `audio.record` 类平台步骤能否闭环 | 待终端确认（G25 / T15） |
| Q8 | 平台步骤在连接线程上同步执行 | 一次含 TTS 与音频下行的工作流可能占用秒级消息线程时间 | 待定；移出线程需要一并传递租户上下文（`GlobalContext` 是 ThreadLocal），旧实现同样同步，无退化 |
| Q9 | §7.2 的控制层端点哪些仍被前端使用 | P5c 能否删除对应的旧能力 / 事件模型 | **已关闭（0.14.0）**：扫描 `static/*.html` 得到前端实际调用面（§7.2）。调试域只有 `node-tests` 三个端点被调用；`static/*.html` 本身在 §7.3 待删清单里 |
| Q10 | `/section-triggers` 的触发树数据源 | 原判"接口集里最后一处旧模式泄漏，阻塞 3 个待删类" | **已关闭（0.14.1）**：定性有误，已撤销。数据源是平台页面定义与类型目录（非旧协议），v2 Schema 的 `ui` 只有类型名名单、无法派生三级树，且 v2 已裁决门禁归 Schema 校验（§4.19）。不阻塞 P5c |

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
| 2026-09-18 | 撤销设备级分流开关（0.12.0） | 删除 `DeviceProtocolRouter` / `SduiRoutingProperties` / `sdui.routing`，v2 成为唯一协议（§4.12）；顺带清理 `ai` 模块三处死代码（§4.13） |
| 2026-09-18 | 验证 | `mvn test` 289 项通过（随分流开关移除 6 项，无回归） |
| 2026-09-18 | 交付 P5b 交互事件回流 | `contextRef` 承载上下文（§4.14）、`platformSteps` 随部署固化（§4.14）、`WorkflowPlatformStepExecutor` 替代 `CapabilityNodeExecutorService` 并将平台步骤按出口分级（§4.15）、单 Section 收敛点与下行 PCM 拆解（§4.17）、修复 v2 接入层缺失的设备租户上下文（§4.16） |
| 2026-09-18 | 修正 `audio.record` 的 `toggle` 下沉判定 | 按 01§5 `toggle` 是终端侧便利动作、参数静态，改为下沉为 `audio.record.toggle`（§4.9 修正） |
| 2026-09-18 | 验证 | `mvn test` 323 项通过（删除旧执行器测试、新增 40 项 P5b 用例，净增 34 项，无回归） |
| 2026-09-18 | P5c 前置：引用图复核删除清单 | 新增 `scripts/sdui-refgraph/refgraph.py`；查明 v2 硬依赖只有 6 个类、`sdui` 对外封闭、删除难点在控制层；修正原清单两处错误（§4.18），§7 重写为三步可执行清单 |
| 2026-09-18 | 交付 0.14.0 闭环接口集 | 新增契约 `docs/sdui/front/CLIENT_API.md`；按域改写全部 sdui 控制器（设备 / 能力 / 板型 / 事件 / 工作流 / 调试）；新建 `CapabilityQueryService`（能力唯一出口）、`PlatformRequestDispatcher`（调试下行唯一出口）、`DebugStreamHub`（调试流）；保留模块统一换用 `DeviceConnectionRegistry`（§4.19）；删除重复端点 `/debug/node-workflows/**`，补上缺失的 `/deployments/{deploymentId}/config` |
| 2026-09-18 | 关闭 Q9、新增 Q10 | 扫描前端调用点（`scripts/sdui-front-paths.py`）确定实际使用面；修正 `CapabilityNodeController` 的错误弃用注释；§7.2 阻塞量 59 → 18（§4.19） |
| 2026-09-18 | 撤销 Q10、修正引用图两处口径、纠正两处误标弃用 | 保留闭包 39 个，§7.3 重写为"控制层裁剪**不能**释放旧能力 / 事件模型，前提是先解耦 `ui` 包"；"引用数"口径改为"释放量"逐个推演（§7.2）；`SectionPatch` / `SectionTypeCatalog` 移除错误的 `@Deprecated`；新增 T17（§4.19 / §4.21） |
| 2026-09-18 | 修正引用图第三类误判：接线根 | `WebSocketConfig`（`@Configuration`，注册 `/ws/sdui/v2`）原被列为 A 类可删，照删会让 v2 端点在编译与测试全绿的情况下消失。脚本新增接线根判据，A 类 15 → 12（§4.20） |
| 2026-09-18 | 验证 | `mvn test` **350 项通过**（新增 `PlatformRequestDispatcher` / `DebugStreamHub` / `CapabilityQueryService` 等 29 项用例；删除重复控制器 `NodeWorkflowDebugController` 及其 2 项测试；无回归） |
| 2026-09-20 | T17 结项：切两条边解耦 `ui` / `v2` 与旧能力·事件模型 | 保留闭包 **39 → 22**；`CapabilityQueryService` 新增 `sectionTypes(deviceId)` 读 v2 Schema 的 `surface.ui.sectionTypes`；`SectionTypeCatalog` 改由 `EventCatalogLoader` 取 YAML 类型条目（原经 `@Lazy EventRegistry`）；引用图新增"阻塞源归因"取代粗筛（§4.22 / §7.3.2）。**A 类仍 12**——出闭包 ≠ 立即可删 |
| 2026-09-20 | 验证 | `mvn test` **356 项通过**（新增 `SectionTypeCatalogTest` 5 项、`CapabilityQueryServiceTest` 的 `sectionTypes` 1 项；无回归） |

后续进入 P5b：`platform.interaction` 事件驱动工作流运行、消费组装产出的 `platformSteps`、以新 token 驱动终端。P5b 完成后才具备"把运行时换到 v2、整块移除旧协议栈"的条件。

**P5b 已完成**：平台侧运行时（工作流续接、主视图下发、音频下行）已全部走 v2。但这**不等于**旧协议栈已无调用者——控制层（`controller` / `debug`）仍大量按旧能力与事件模型暴露接口，§4.18 的引用图复核给出了准确边界。P5c 的准入门槛仍是单一条件——**终端固件全量切换**。

## 7. 待清除模块清单（P5c 用）

**准入**：终端固件全量切换到 v2。分流开关已在 0.12.0 删除（§4.12），因此**没有按设备回退的手段**——删除只能一次性完成，且必须在终端切换之后。

**清单来源**：`scripts/sdui-refgraph/refgraph.py` 对全工程 import 与类型引用做的引用图分析（判据与结论见 §4.18，接线根修正见 §4.20）。**每次裁剪代码后重跑本脚本**，即可得到那一刻的准确清单：

```bash
python scripts/sdui-refgraph/refgraph.py
```

**前端使用面来源**：`scripts/sdui-front-paths.py` 扫描 `static/*.html`，列出前端实际调用的接口路径。引用图判断不了端点是否在用，这个脚本补上那一半：

```bash
python scripts/sdui-front-paths.py
```

脚本输出的每条路径都可直接与控制器端点对账——**前提是路径以字面量书写**（模板字面量内部仍可插值）。带参数的路径曾在 0.14.3 之前被正则截断，修正见 §4.23；脚本现在还会提示"路径不是字面量"的调用点，避免用辅助函数拼接导致静默漏报。

**规模（0.14.2 实测）**：`sdui` 主代码中 v2 54 个、非 v2 165 个，其中保留包（`ui` / `workflow` / `artifact` / `repo` / `model` / `dto` / `controller` / `debug` / `resources`）占 71 个，`sdui` 外 58 个。候选池 93 = **A 类 12（可整文件删）** + C 类 81（需先裁剪引用方）。C 类里有 **22 个属保留闭包**（§7.3，0.14.2 解耦后由 39 收敛而来）——在保留方改动前删不掉，真正可争取的是其余 59 个。

脚本输出另有三节供执行顺序使用（0.14.1 新增两节、0.14.2 新增一节，见 §4.21 / §4.22）：**释放容量**（逐个推演"裁掉它能释放几个"，与"引用几个"不是一回事）、**保留闭包**（保留方直接或间接依赖的池内类，即不可删清单）、**阻塞源归因**（把每一类拖住的保留层入口，取代原先"引用方为 ui / v2"的粗筛——解耦后该口径已失真）。

### 7.1 第一步：无需改动任何保留方即可删（12）

| 文件 | 替代项 / 说明 |
| --- | --- |
| `sdui/capability/CapabilityContract.java`、`CapabilityContractService.java` | v2 能力 Schema 与 `CapabilityQueryService` |
| `sdui/capability/CapabilityInvocationValidator.java`、`CapabilityValidator.java` | `BusinessConfigValidator`（单一校验器） |
| `sdui/capability/PlatformCapabilityRegistry.java` | `CapabilityRegistryV2` |
| `sdui/protocol/SduiRuntimeHandlers.java` | v2 运行时；旧 `List<TopicHandler>` / `List<BinaryFrameHandler>` 收集装配随之移除 |
| `sdui/protocol/SemanticCommand.java` | 全工程（含测试）零引用，真死代码 |
| `sdui/protocol/TlvBuilder.java` | TLV 编码，仅测试引用（`EventPayloadTest`） |
| `sdui/section/SectionEditorService.java` | 平台侧 Section 编辑已由 UI 模板域承担 |
| `sdui/service/PlatformCapabilityRuntimeService.java` | 旧能力运行时 |
| `sdui/service/RgbControlService.java` | `rgb.effect` 在能力 Schema 仅声明 `usableIn=binding`，平台无请求可发（§4.15） |
| `sdui/service/TelemetryRetentionService.java` | 零引用 |

### 7.1b 接线根：先裁剪注册行，再随之下沉（1 + 2）

`WebSocketConfig` 带 `@Configuration`、由框架在启动期回调注册端点，自身**零显式引用者**。它一度被判为 A 类——照删会把 `/ws/sdui/v2` 一起点掉，而且编译与测试都不会报错（见 §4.20）。现在它被脚本当作**接线根**单独标出：

| 文件 | 处置 |
| --- | --- |
| `sdui/WebSocketConfig.java` | **保留文件**，只删 `/ws/sdui` 与 `/` 两行旧端点注册，保留 `/ws/sdui/v2` |
| `sdui/SduiWebSocketHandler.java` | 删除上面两行后转为可删（旧端点处理器） |
| `sdui/MessageRouter.java` | 随 `SduiWebSocketHandler` 一并删除（v2 有自己的路由） |

### 7.2 第二步：先裁剪控制层

**执行顺序的依据是"释放量"，不是"引用数"（口径修正 2026-09-18，0.14.1）。** 原统计口径是"该控制层类引用了多少个滞留类"，把"碰到"当成了"阻塞"。按判据 `f 可删 <=> 引用者全可删`，只有当滞留类的引用者**全是候选方**时，裁掉它才能释放；若某类另有保留方（`ui` / `workflow`）引用，裁掉多少控制层都不释放。原引导句"不先处理它们，旧模型一个都删不掉"对 `CapabilityNodeTestService` 这类并不成立。

下表因此改为**逐个推演**：把该类视为已删除后重跑收敛，看 A 类增量（`scripts/sdui-refgraph/refgraph.py`）。0.14.1 实测：

| 控制层类 | 真实释放量 | 引用滞留类 | 说明 |
| --- | --- | --- | --- |
| `controller/BoardTypeController` | **2** | 4 | `/section-triggers` 的存废决定 `SectionTriggerCatalog` / `SectionTriggerCatalogService`；另 2 个引用项（`CapabilityNodeCatalog*`）被部署域共用，不随它释放 |
| `WebSocketConfig` | **2** | 1 | 删掉旧端点两行注册，连带释放 `SduiWebSocketHandler` → `MessageRouter` |
| `controller/DeviceController` | **1** | 2 | 遥测趋势（`TelemetryTrendService`）；`SduiDeviceService` 被 v2 与调试域共用，不释放 |
| `debug/node/CapabilityNodeTestService` | 0 | 5 | 引用最多但一个都不释放——它们都另有保留方引用 |
| `debug/workflow/NodeWorkflowDebugService` | 0 | 4 | 同上 |
| `controller/CapabilityNodeController` | 0 | 2 | 节点目录（`CapabilityNodeCatalog*`）被部署域共用 |
| `controller/EventCatalogController` | 0 | 1 | 事件目录被 `workflow.NodeWorkflowSupport` 共用 |
| `controller/DebugController` | 0 | 0 | **已脱离**：改走 v2 请求派发与调试流枢纽 |
| `controller/CapabilityController` | 0 | 0 | **已脱离**：改走 `CapabilityQueryService`（全部读 v2 Schema） |

**结论**：旧清单把 `CapabilityNodeTestService` 列为第一阻塞源（"阻塞 5 个"）是误导——它一个都释放不了。按真实释放量，P5c 的优先项是 `BoardTypeController`、`WebSocketConfig`（各 2）与 `DeviceController`（1），合计 5 个；其余控制层类处理与否不改变可删集。

处理方式：删除端点，或改读 v2 的能力 Schema（`sdui.v2.capability.CapabilitySchemaV2`）。

**"哪些端点仍被前端使用"已不再是未知数**（原 §7.5 的确认项已关闭）。用 `scripts/sdui-front-paths.py` 扫描 `static/*.html` 得到前端实际调用面：

```
/api/v1/sdui/devices?size=200                          三个页面
/api/v1/sdui/board-types                               节点测试页
/api/v1/sdui/board-types/{board}/capability-nodes      节点测试页
/api/v1/sdui/capability-nodes/{deviceId}               编辑器、节点测试页、工作流测试页
/api/v1/sdui/debug/{deviceId}/node-tests/**            节点测试页
/api/v1/sdui/node-workflows{,/management/**}           编辑器、工作流测试页
```

结论：调试域**只有 `node-tests` 三个端点**被旧三页调用；`/debug/{deviceId}/request`、`/view`、`/requests*`、`/events/stream` 的调用方由 0.14.3 新增的 `static/sdui-v2-console.html` 提供（§4.23）。旧三页的定性不变，裁剪控制层时随之改版——`static/*.html` 本身也在 §7.3 的待删清单里。

### 7.3 第三步：保留闭包——**不能**随控制层裁剪释放的旧协议簇

原 §7.3 假定"裁掉控制层后，旧能力 / 事件 / 下发模型会一并进入可删集"。0.14.1 用**保留闭包**复核，该假定**不成立**；0.14.2 完成解耦后重测，闭包从 **39 收敛到 22**。

保留闭包 = 从保留包出发（种子排除接线根与 `controller` / `debug` 两个待裁层），沿类型引用与继承关系可达的池内类。它们被保留方直接或间接依赖，**在保留方不改动的前提下删不掉**。

#### 7.3.1 解耦前（0.14.1）：39 个，由三个来源引入

| 来源 | 引入链 | 被拖住的类 |
| --- | --- | --- |
| UI 模板域 | `ui.SduiUiTemplateService` | `section/` 平台 UI 模型 11 类、`protocol/catalog/DeviceCapabilityProjection` |
| 旧能力模型（经上一行引入） | `DeviceCapabilityProjection` → `service.SduiCapabilityService` → `capability.CapabilityRegistry` | `capability/CapabilityRegistry`、`CapabilityCatalog`、`protocol/CapabilitySchema`、`protocol/CapabilitySnapshotParser`、`service/CommandSchemaRegistry`、`protocol/catalog/*` 6 类 |
| 旧事件模型（同上） | `DeviceCapabilityProjection` → `protocol/catalog/DeviceProtocolCatalog` → `event.EventRegistry` | `event/EventRegistry`、`EventDefinition`、`EventCatalogLoader`、`EventCatalogProperties`、`EventPayload`、`protocol/BinaryProtocolCodec` |

**归因修正**：上表原写作"UI 模板域（`SduiUiTemplateService`、`DevicePrimaryUiService`、`WorkflowUiContextService`）"，但依赖扫描显示后两者**并未**引用 `DeviceCapabilityProjection`——它们只依赖 `section/` 平台 UI 模型（合法保留，见 §7.4）。旧能力模型的保留层入口实际只有 `SduiUiTemplateService` **一个**。

**关键链**（它决定了 P5c 的实际工作量）：

```
ui.SduiUiTemplateService
  └─ protocol.catalog.DeviceCapabilityProjection      （构造器注入，非 javadoc）
       ├─ service.SduiCapabilityService               （@Lazy 注入）
       │    ├─ capability.CapabilityRegistry
       │    └─ service.CommandSchemaRegistry
       └─ protocol.catalog.DeviceProtocolCatalog
            └─ event.EventRegistry -> EventDefinition / EventCatalogLoader / EventCatalogProperties
```

#### 7.3.2 解耦（0.14.2，T17 结项）：切两条边，闭包 39 → 22

旧簇的保留层入口只有两条边。切掉它们即可，**不需要**把 `ui` 包整体重构：

| # | 边 | 处置 | 释放 |
| --- | --- | --- | --- |
| 1 | `ui.SduiUiTemplateService` → `DeviceCapabilityProjection` | 改注入 `CapabilityQueryService`，新增 `sectionTypes(deviceId)` 读 v2 Schema 的 `surface.ui.sectionTypes`。原实现读旧 `CapabilitySnapshot` 并与平台类型目录求交；而类型存在性已由 `SectionTypeCatalog.isValidType` 在模板创建时校验过，此处求交是第二处真值，去掉 | 旧能力模型 14 类 |
| 2 | `section.SectionTypeCatalog` → `EventRegistry` | 该类只在 `init()` 里调 `eventRegistry.getSectionTypes()`——把运行时事件注册表当 YAML 配置读取器用。改依赖 `EventCatalogLoader`（YAML 边界，自身无构造依赖，不会成环） | 旧事件模型 3 类 |

释放明细（共 17 类出闭包）：

- **旧能力模型**：`capability/CapabilityCatalog`、`CapabilityRegistry`、`protocol/CapabilitySchema`、`CapabilitySnapshotParser`、`SduiProtocolConstants`、`protocol/catalog/*` 6 类、`service/CommandSchemaRegistry`、`SduiCapabilityService`
- **旧事件模型**：`event/EventRegistry`、`EventPayload`、`protocol/BinaryProtocolCodec`

**发现：`SectionTypeCatalog` 不是"ui 的东西"，而是 UI 模型与 v2 共享的类型定义源。** v2 侧同样经 `v2.display.SectionViewResolver → section.SectionDataCodec → SectionTypeCatalog` 到达它，而 `SectionDataCodec` 是 v2 硬边界（§7.4）。因此该目录必须保留——但它的**上游**不该是事件模型，这正是第 2 条边要切的原因。

#### 7.3.3 解耦后仍是 22 个，且**不能**继续靠裁控制层减少

| 来源 | 被拖住的类 |
| --- | --- |
| 平台 UI / Section 模型（UI 模型与 v2 共享，必须保留） | `section/PageService`、`PageRepository`、`SduiPageEntity`、`SectionTypeCatalog`、`SectionPageDefinition`、`SectionData`、`SectionDataCodec`、`SectionEntry`、`SectionLayout`、`SectionPatch`、`SectionRenderMode`、`SectionScene` |
| 事件目录配置（YAML 边界，配置半部保留） | `event/EventCatalogLoader`、`EventCatalogProperties`、`EventDefinition` |
| 节点目录（部署组装读，必须保留） | `capability/node/*` 6 类 |
| TTS 链路 | `service/audio/TtsProvider` |

`EventDefinition` 仍被 `EventCatalogLoader` 拖着——该 loader 目前从同一份 YAML 同时构建**事件定义**与**类型条目**。要释放它，需把 YAML 的 `commands` 半部与 `sections.types` 半部拆开（事件定义半部随 P5c 消失）。这是 P5c 内的事，不影响 T17 结项。

**结论**：`ui` 与 `v2` 对旧能力 / 事件模型的依赖已在 0.14.2 全部切断。剩余闭包 22 个是平台 UI 模型与配置边界的**真依赖**，不是待清理对象。P5c 的对象因此收敛为**旧协议接入簇**——它现在只被接线根 `WebSocketConfig` 的旧注册与自身内部循环握着（见 §7.3.4）。

#### 7.3.4 阻塞源归因（0.14.2 新增脚本输出）

原输出写作"引用方为 ui / v2，属真实依赖"，在解耦后已失真——多数阻塞类是陷在旧协议簇的内部循环里。脚本改为给出把每一类拖住的**保留层入口**：

| 保留层入口 | 拖住的候选池类数 | 性质 |
| --- | --- | --- |
| `WebSocketConfig` | **56** | 接线根的旧端点注册（§7.1b：只删 `/ws/sdui`、`/` 两行）。这是 P5c 的主阻塞源 |
| `ui.SduiUiTemplateService` | 14 | 平台 UI 模型 + 配置边界（真依赖） |
| `ui.DevicePrimaryUiService`、`ui.WorkflowUiContextService`、`v2.display.SectionViewResolver` | 各 11 | 同上 |
| `v2.display.PrimaryViewPublisher` | 6 | Section 收敛点输入模型（v2 硬边界） |
| `workflow.NodeWorkflowDeploymentService` | 6 | 节点目录 |
| `workflow.WorkflowPlatformStepExecutor` | 1 | TTS |
| （无保留层入口） | 25 | 陷在旧协议簇内部循环，随循环一起消失，无需单独改造 |

控制层裁剪后**真正新增**的可删类只有 6 个：

| 来源 | 类 |
| --- | --- |
| `BoardTypeController`（`/section-triggers` 存废决定） | `section/SectionTriggerCatalog`、`SectionTriggerCatalogService` |
| `DeviceController` | `service/TelemetryTrendService` |
| `WebSocketConfig`（删旧端点两行注册） | `SduiWebSocketHandler` → `MessageRouter` |
| `debug` 层改写 | `debug/DebugSessionService` |

其余旧协议类（`CapabilitiesReportHandler`、`HeartbeatHandler`、`MotionEventHandler`、`SduiControlAckHandler`、`SduiPageChangedHandler`、`handler/EventInputHandler`、`service/SduiProtocolService`、`DeviceSessionManager`、`section/SectionOrchestrationService` 等）串成一条链，**逐个推演显示裁掉整个 `debug` 层释放 0 个类**——该链在 `debug` 之外另有入口，即上表的 `WebSocketConfig`。

**执行顺序因此修正为**：

1. 裁 §7.2 的三个控制层类 → 释放 3 个旧模型类，加接线根释放的 2 个；
2. ~~让 `ui` 层与 `debug` 层从旧能力 / 事件模型解耦~~ → **`ui` 侧已于 0.14.2 完成（T17 结项）**；`debug` 层随 P5c 改写；
3. 步骤 1–2 完成后重跑脚本，旧协议簇才真正进入可删集。

> **第 1 步的规模已按 §4.24 收窄**：上表"释放量"是推演上限而非删除结论。`/telemetry/trends` 与 `/section-triggers` 都在 0.14.0 的接口契约内，故这两个控制层类**不裁**；第 1 步实际只剩接线根 `WebSocketConfig` 一项（释放 2 类，且属 P5c 准入）。

`static/*.html` 旧三个调试页仍在待删清单（前端实际调用面见 §7.2）。0.14.3 新增的 `sdui-v2-console.html` **不在**待删清单内——它只读 v2 能力 Schema 与调试域端点，是终端切换后的验收工具（§4.23）。

### 7.4 必须保留（v2 硬依赖，P5c 不可动）

| 类 | 引用方 | 原因 |
| --- | --- | --- |
| `model/SduiDevice` | `v2.capability.CapabilityQueryService` | 设备台账（板型聚合、租户过滤）。**0.14.0 新增的硬边界**：能力查询必须落到设备实体上，不能只读能力缓存 |
| `section/SectionScene`、`SectionPatch`、`SectionData`、`SectionEntry`、`SectionDataCodec` | `v2.display.PrimaryViewPublisher`、`v2.display.SectionViewResolver` | 单 Section 收敛点的输入模型（§4.17 / §4.18 裁决二）。**0.14.1 修正误标**：`SectionPatch` 原先带 `@Deprecated` + "legacy protocol path, scheduled for removal"，照删会让 v2 的主视图收敛编译不过 |
| `repo/SduiDeviceRepository` | `v2.session.DeviceTenantContext` | 设备归属查询（§4.16） |
| `service/audio/TtsProvider` 及实现 `FfmpegTtsProvider`、`MacOsTtsEngine`、`AudioConversionService` | `workflow.WorkflowPlatformStepExecutor` | `audio.play` 的 TTS 链路（§4.15）。它们零显式引用者，最容易被误删 |
| `section/SectionTypeCatalog` | `ui.SduiUiTemplateService`、`v2.display.SectionViewResolver`（经 `SectionDataCodec`） | 模板类型与字段校验用的类型目录；收敛 YAML 内的类型集合，而非删除本类。**0.14.1 修正误标**：原先带 `@Deprecated` + "legacy protocol path, scheduled for removal"，实际是活代码。**0.14.2 补硬边界**：v2 侧同样经 `section/SectionDataCodec` 依赖它，所以它同时是 v2 硬依赖——不该被当作"ui 专属" |
| `section/SectionLayout`、`SectionRenderMode`、`PageService`、`PageRepository`、`SduiPageEntity`、`SectionPageDefinition` | `ui.DevicePrimaryUiService`、`ui.SduiUiTemplateService` | 平台侧 UI 模型 |
| `capability/node/CapabilityNodeCatalog`、`CapabilityNodeCatalogService` | `workflow.NodeWorkflowDeploymentService` | 部署组装读节点目录。**0.14.1 新增**：它们同时被 `BoardTypeController` / `CapabilityNodeController` 引用，容易被误当成"控制层专属而可删" |
| `event/EventCatalogLoader`、`EventCatalogProperties`、`EventDefinition` | `section.SectionTypeCatalog`（经 loader 取 YAML 的 `sections.types`） | YAML 配置边界。**0.14.2 变更**：原表述为"`DeviceCapabilityProjection` 及其下游…不是可删项，P5c 的前提是先解耦 `ui` 包"——解耦已完成，该链的上游已不在保留层内，`DeviceCapabilityProjection` / `SduiCapabilityService` / `CommandSchemaRegistry` / `CapabilityRegistry` / `EventRegistry` 均已移出保留闭包，成为 P5c 的可删对象。`EventDefinition` 仍被 loader 拖着（loader 目前从同一份 YAML 同时构建事件定义与类型条目），需 P5c 拆 YAML 时释放 |

### 7.5 删除前必须确认

| # | 事项 | 不确认的后果 |
| --- | --- | --- |
| T11 | 上行音频的 v2 承载方式 | 旧链路是平台唯一的录音通路，删了就没有替代 |
| T13 | 本地响应动作的名称与参数集合 | `WorkflowActionMapper` 的映射表无法定稿，直接影响"哪些节点该下沉" |
| ~~—~~ | ~~§7.2 控制层端点的实际使用情况~~ | **已关闭（0.14.0）**：`scripts/sdui-front-paths.py` 给出前端实际调用面，见 §7.2。调试域只有 `node-tests` 三个端点在用 |
| ~~Q10~~ | ~~`/section-triggers` 改由 v2 Schema 派生~~ | **已关闭（0.14.1）**：定性有误。数据源是平台页面定义与类型目录；v2 的 `UiSpec` 只有类型名名单，派生三级树不成立；v2 已裁决 Section 门禁归 Schema 校验。不阻塞 P5c（§4.19） |
| ~~T17~~ | ~~`ui` 包如何从旧能力 / 事件模型解耦~~ | **已关闭（0.14.2）**：入口不是"三个 ui 服务"，而是**两条边**——`ui.SduiUiTemplateService → DeviceCapabilityProjection`、`section.SectionTypeCatalog → EventRegistry`。均已切断，保留闭包 39 → 22（§7.3.2）。`DevicePrimaryUiService` / `WorkflowUiContextService` 本就不涉及旧能力模型，无需改造 |

### 7.6 测试与调试资产

受影响测试随对应实现一并处理。§7.1 涉及的测试只有 `sdui/event/EventPayloadTest`（引用 `TlvBuilder`）。P5b 已删除 `CapabilityNodeExecutorService` 及其测试。

> P5b 对 `NodeWorkflowRuntimeService` 的改造属于改写而非删除，单列在 [11_FEATURE_MATRIX.md](11_FEATURE_MATRIX.md) §5.2。

## 8. 已知限制

| # | 限制 | 原因 | 回收条件 |
| --- | --- | --- | --- |
| L1 | 业务配置与 token 只在内存（G17） | 平台重启后由业务层重新 `business.update` 即可恢复，首期不值得引入持久化 | 需要跨重启保证时 |
| L2 | ~~`platformSteps` 未持久化~~ | **已回收（P5b）**：随部署固化进 `NodeWorkflowDeploymentEntity.businessConfigs`，可跨平台重启（§4.14） | — |
| L3 | 组装产出的配置版本号不回填到部署响应 | 下发是异步的，回填会让部署接口等待设备 ACK | 需要"部署即拿到版本"的交互时 |
| L4 | 组装阶段的参数取值域校验依赖下发校验器的干跑 | 刻意不在组装器里复制一份参数校验规则——复制会形成两处真值，改动必然漂移（见 §4.11） | 无需回收，属于刻意的职责划分 |
| L5 | `rgb.effect` / `audio.record` 类平台步骤不闭环 | 能力 Schema 只声明 `usableIn=binding`，平台无请求可发，只能返回 `terminal_action_required`（G25） | 终端确认 T15 后 |
| L6 | 平台步骤在连接消息线程上同步执行 | TTS 合成与音频下行可能占用秒级时间；移出该线程需要一并传递租户上下文（`GlobalContext` 是 ThreadLocal） | 出现可观测的线程阻塞时（Q8） |
| L7 | 主视图快照只在内存 | v2 无增量更新，Patch 需要快照合成；平台重启后首次 Patch 会回退为下发完整场景 | 需要跨重启保持增量能力时 |
