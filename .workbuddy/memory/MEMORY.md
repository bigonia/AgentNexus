# AgentNexus 项目长期记录

## 文档约定

- `docs/` 是权威来源；设计与实现说明变更时必须同步更新对应 Markdown。
- LCD_085 重构相关文档统一放在 `docs/sdui/lcd085-refactor/<日期>/`：
  - `01_`–`04_` 是终端侧设计专题（交互模型、系统边界、UI 模型、协议模型），由终端方向维护。
  - `10_`–`12_` 是平台侧实施文档（升级方案、功能清单、设计问题与决策），**固定为三份，不再新增**。
- 平台侧实施文档的分工：设计理由写 `10_`，逐条状态写 `11_`，缺口与裁决写 `12_`。不要把状态表复制到 `10_`。
- 设计文档未定义但必须实现的内容，一律在代码中标注 `// TODO(lcd085-refactor):` 并在 `12_DESIGN_NOTES.md` 登记缺口编号（G*）、自实现编号（S*）或未决问题（Q*）。

## 代码约定

- 重构期采用"新通道并行、旧路径不动"策略：新代码放独立包（如 `sdui/v2/`），旧实现保持原样，收尾阶段一次性剪除旧路径。避免在旧文件里做渐进式改写。
- 协议常量集中声明，不在业务代码里散落字符串字面量（参考 `sdui.v2.protocol.V2Names`、`ProtocolErrors`）。
- 可配置上限与超时集中到 `@ConfigurationProperties`，并在 `application.yml` 写出默认值便于发现。
- 平台侧不镜像终端微观执行状态：设备动作过程状态不进入平台持久模型，只保留业务态与连接态。
- 终端本地响应序列的动作**必须全静态**（无 `$ref`、不依赖平台产物）。业务动态由平台为同一动作签发不同 token 表达，配置里不存在条件/分支/参数表达式。
- 下沉判据只有一条：动作名与参数是否静态、是否依赖平台产物。**不看终端是否需要读自身状态**（`audio.record.toggle` 属于可下沉）。
- 「部署成功」必须等价于「配置能被终端接受」：部署接口在落库前完成组装校验，且组装结束时跑一次下发校验器的干跑（`BusinessConfigService.validate`），不复制校验规则。
- 工作流运行时由 `platform.interaction` 事件驱动，不监听终端输入事件。上下文靠 token 携带的 `contextRef`（`wf:<workflowId>:<triggerNodeId>`）恢复，不按 triggerId 反查。
- 平台步骤在部署时固化进 `NodeWorkflowDeploymentEntity.businessConfigs`，运行时读取而不是按当前定义重新组装 —— 否则工作流被编辑后会与设备侧已生效配置漂移。
- 平台不可请求的动作（能力 Schema `usableIn` 只含 `binding`，如 RGB 灯效、录音）在运行记录里返回 `terminal_action_required` 且 `ok=true`，不退化成旧协议遥控路径。
- 业务 UI 只有一个下发出口 `PrimaryViewPublisher`，单 Section 收敛只在 `SectionViewResolver`。v2 无 Section 级增量更新，Patch 必须在平台侧合成完整 Section。
- **v2 的 WebSocket 线程没有租户**：`sdui_device` 是全局表（`ownerUserId` 列），部署/运行/UI 上下文才是租户表。任何在设备线程上读写租户表的代码都必须经 `DeviceTenantContext` 建立租户。mock 测试不落库，这类缺陷不会暴露。
- 待删除的旧实现统一标注 `@Deprecated(since = "0.10.0")` + 替代项注释，并在 `12_DESIGN_NOTES.md` §7「待清除模块清单」登记，便于按表删除与回滚。
- **但批量标注有假阳性**：重构期统一打标的类里，有被 v2 或 UI 模板域实际依赖的（`SectionPatch` 被 v2 主视图收敛依赖、`SectionTypeCatalog` 被 `SduiUiTemplateService` 依赖）。照 `@Deprecated` 删会**编译通过、测试全绿、功能悄悄消失**。以引用图结论为准逐个核对，假阳性改回"保留 + 写明依赖方"。
- **不做灰度，v2 是唯一协议**：不要为新旧协议并存设计按设备分流的开关或双写路径。设备协议归属不由平台配置决定，旧协议的存废只取决于终端固件切换进度。
- 旧协议栈通过 `List<TopicHandler>` / `List<BinaryFrameHandler>` 由 Spring 收集装配，是整块存活的，没有可以单独删除的孤立死枝；用"零引用"判断 SDUI 的 Controller / Handler 是否死代码会误判。
- 判断某模块的旧代码是否可删，先看它是否仍被同协议的运行时依赖；"其他模块也有同样问题"通常是错的，要靠引用图验证而不是靠印象。
  可删判据三条，缺一不可：①它的**所有引用者**也都可删；②它没有实现"删除集之外的工程内契约"；③它**不是接线根**（`@Configuration` 或实现框架回调接口的类）。第②条必需 —— Spring 按类型注入的实现类天然零显式引用者。第③条必需 —— 接线根（如注册 `/ws/sdui/v2` 的 `WebSocketConfig`）删除后果**不体现在引用图上**，照删会让端点在编译与测试全绿的情况下消失。
  工具：`scripts/sdui-refgraph/refgraph.py`。改代码后重跑即可得到当刻清单；解析时注意 `import a.b.*;` 的通配形式，正则漏了会让整批引用丢失。
  两个配套口径（0.14.1 修正）：**"引用数"≠"释放量"** —— 判断"裁掉 X 能释放几个"必须把 X 视为已删重跑收敛，不是数它引用了几个滞留类（`CapabilityNodeTestService` 引用 5 个、实际释放 **0** 个）；**保留闭包** —— 从保留包求可达集（种子排除接线根与 `controller` / `debug` 待裁层），只筛直接引用者会漏掉间接依赖（直接筛 14 个，闭包 **39** 个）。

## 对外接口集

- 契约文档 `docs/sdui/front/CLIENT_API.md` 是前端/管理端 HTTP 面的权威定义，按闭环组织：上线 → 能力核验 → 编排 → 部署 → 终端本地执行 → 交互上报 → 平台续接 → 观测。接口取舍判据只有一条：**它在闭环某一步上是否承担不可替代的职责**。
- 四条单一来源约束（改接口时必须守住）：在线态 = `DeviceConnectionRegistry`；能力 = v2 `CapabilitySchemaV2`（经 `CapabilityQueryService`）；校验器 = `BusinessConfigValidator`；出站路径 = `PlatformRequestService`（经 `PlatformRequestDispatcher` 供调试域）。出现第二份来源即视为回归。
- 路径一律用 v2 词汇（`actions` / `triggers` / `view` / `requests` / `bindings`），**不复用旧协议词**（`commands` / `sections`）。路径里出现旧词，就一定有人往回接旧模型。
- 能力的 `usableIn` 是前端必须尊重的分界线：只声明 `binding` 的动作平台发不出请求，接口如实返回 `unsupported`，不伪造下行。
- "端点是否仍被前端使用"不要靠印象：跑 `scripts/sdui-front-paths.py` 扫 `static/*.html` 得到调用面。曾据此推翻一处错误的 `@Deprecated "前端未使用"` 注释。
- 节点目录真值在 `NodeTypeRegistry`（编辑器与运行时共用），新增节点类型必须同时更新 `WorkflowActionMapper` 的映射并跑登记表一致性用例。
- `/board-types/{board}/section-triggers` 曾记为"接口集里最后一处旧模型泄漏、待改由 v2 Schema 派生"——**该定性已撤销（0.14.1）**。它的输入是平台页面定义与 Section 类型目录（`sdui-event-catalog.yml`），与终端固件无关；`UiSpec` 只有类型名名单，派生三级树不成立；且 v2 已裁决 Section 类型门禁归 `CapabilitySchemaV2` 校验。不阻塞 P5c，无需改写。

## 构建

- 本机 Maven 需绕过 Git Bash 的 `MAVEN_HOME` 反斜杠问题，且每次编译前清掉 `target/maven-status`。
  注意：该目录文件数会触发批量删除保护，`rm` 被拒后 `&&` 链会短路成"构建 1 秒结束"的假象，用 `mv` 改名代替。
  详见用户级 skill `agentnexus-build-verify`。
- 全量测试基线：350 项通过（0.14.1）。

## 待办主线

- **0.14.0 已完成**：闭环接口集（契约 `CLIENT_API.md` + 全部控制器改写 + `CapabilityQueryService` / `PlatformRequestDispatcher` / `DebugStreamHub`）、在线态单一来源落地。
- **0.14.1 已完成**：撤销 Q10、修正引用图两处口径（释放量逐个推演、保留闭包）、纠正两处误标弃用。代码仅注释变更。
- LCD_085 平台侧升级 **P5c**（准入=终端固件全量切换）：按 `12_DESIGN_NOTES.md` §7 清单删除旧协议路径。分流开关已删除（0.12.0），**回滚不能按设备进行**，只能一次性完成。
  引用图实测（0.14.1）：`sdui` 主代码 = v2 54 + 非 v2 165，保留包 71，接线根 1，候选池 93 = **A 类可整文件删 12** + C 类 81（其中 **39 个属保留闭包，保留方不改动就删不掉**）。
  v2 对非 v2 的硬依赖 **7 个类**；`sdui` 外零引用。
  **执行顺序（口径已修正）**：① 裁 `BoardTypeController` / `WebSocketConfig` / `DeviceController`（真实释放量各 2 / 2 / 1，其余控制层类释放 0）；② 让 `ui` 包与 `debug` 层从旧能力 / 事件模型解耦 —— 闭包 39 个里的绝大多数靠这一步（新增待确认项 **T17**）；③ 重跑脚本，旧协议簇才真正进入可删集。
  **关键否定结论**：裁控制层**不能**释放旧能力 / 事件模型。关键链是 `ui.SduiUiTemplateService → protocol.catalog.DeviceCapabilityProjection → service.SduiCapabilityService → capability.CapabilityRegistry`（另有 `→ DeviceProtocolCatalog → event.EventRegistry`）。控制层裁剪真正新增的可删类只有 6 个。
- P5b 遗留未闭环项：`rgb.effect` / `audio.record` 类平台步骤（能力 Schema 只声明 `usableIn=binding`）返回 `terminal_action_required`，能否闭环取决于终端确认 T15。
- 新协议对接前需要与终端确认的高风险项：二进制帧头布局（T3）、`capability_hash` 算法（T2）、业务配置 JSON 结构（T4）、调色板与索引矩阵位序（T7/T12）、本地响应动作名与参数集合（T13）、下行音频容器（T16）。
