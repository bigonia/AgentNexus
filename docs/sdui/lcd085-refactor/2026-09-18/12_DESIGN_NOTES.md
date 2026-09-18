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

后续进入 P5b：`platform.interaction` 事件驱动工作流运行、消费组装产出的 `platformSteps`、以新 token 驱动终端。P5b 完成后才具备"把运行时换到 v2、整块移除旧协议栈"的条件。

**P5b 已完成**：平台侧运行时（工作流续接、主视图下发、音频下行）已全部走 v2，旧协议栈不再有业务侧调用者，只剩"同协议内部互相依赖"这一层。P5c 的准入门槛因此回落为单一条件——**终端固件全量切换**。

## 7. 待清除模块清单（P5c 用）

P5a 阶段把所有将被删除的旧协议路径统一标注为 `@Deprecated(since = "0.10.0")`，并在注释里写明替代项。这样：

- 现在删除会破坏在线设备，所以不删；
- 将来删除时不需要重新梳理"哪些能动"，照本表执行即可；
- 代码层面若需回滚，撤销 `@Deprecated` 标注即可，代码始终可用。

⚠️ **0.12.0 已删除设备级分流开关**（§4.12），因此**回滚不再能按设备进行**：撤销标注会让旧协议对所有设备同时恢复，无法只让某台设备回退排查。执行本表时请一次性完成，并确认终端已全量切换。

✅ **P5b 已按计划删除一项**：`sdui/workflow/CapabilityNodeExecutorService.java` 及其测试。它不属于旧协议消息入口，而是**工作流运行时里的旧协议调用者**——通过 `CommandService` / `AudioService` / `SectionOrchestrationService` 驱动设备。P5b 把运行时换到 v2 后它彻底失去调用方，遂直接删除（连同 `CapabilityNodeTestService` 的注入一并改为 `WorkflowPlatformStepExecutor`）。这是本清单外唯一在 P5b 删除的实现类，其余各项仍按"终端切换后一次性移除"执行。

**规模提示**：本表列的是入口文件，实际下游还有约 70 个 `sdui` 非 v2 主代码文件（旧协议簇传递闭包共约 79 个）与约 23 个测试文件。逐文件引用关系见 §4.12 的核查结论。

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
| `sdui/WebSocketConfig.java` 的 `/ws/sdui`、`/` 端点 | `/ws/sdui/v2` | 旧端点注册，连同 `SduiWebSocketHandler` / `MessageRouter` 一起移除 |
| `sdui/resources/static/sdui-node-test.html` 等三个调试页 | v2 调试页面 | 见 11_FEATURE_MATRIX 5.18 |

> 上表只列"删除类"改动。P5b 对 `NodeWorkflowRuntimeService` 的改造属于改写而非删除，单列在 [11_FEATURE_MATRIX.md](11_FEATURE_MATRIX.md) §5.2。

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
