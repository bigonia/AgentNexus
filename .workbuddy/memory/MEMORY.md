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
- **不做灰度，v2 是唯一协议**：不要为新旧协议并存设计按设备分流的开关或双写路径。设备协议归属不由平台配置决定，旧协议的存废只取决于终端固件切换进度。
- 旧协议栈通过 `List<TopicHandler>` / `List<BinaryFrameHandler>` 由 Spring 收集装配，是整块存活的，没有可以单独删除的孤立死枝；用"零引用"判断 SDUI 的 Controller / Handler 是否死代码会误判。
- 判断某模块的旧代码是否可删，先看它是否仍被同协议的运行时依赖；"其他模块也有同样问题"通常是错的，要靠引用图验证而不是靠印象。

## 构建

- 本机 Maven 需绕过 Git Bash 的 `MAVEN_HOME` 反斜杠问题，且每次编译前删 `target/maven-status`。
  详见用户级 skill `agentnexus-build-verify`。
- 全量测试基线：323 项通过。

## 待办主线

- LCD_085 平台侧升级 **P5c**（准入=终端固件全量切换）：按 `12_DESIGN_NOTES.md` §7 清单删除旧协议路径。平台侧运行时已全部走 v2（P5b），旧协议栈不再有业务侧调用者，只剩同协议内部依赖。分流开关已删除（0.12.0），**回滚不能按设备进行**，只能一次性完成。旧协议簇传递闭包覆盖 `sdui` 非 v2 主代码 165 个文件中的约 79 个（48%）。
- P5b 遗留未闭环项：`rgb.effect` / `audio.record` 类平台步骤（能力 Schema 只声明 `usableIn=binding`）返回 `terminal_action_required`，能否闭环取决于终端确认 T15（平台可否为动态节点签发专用绑定 / 新 token）。
- 新协议对接前需要与终端确认的高风险项：二进制帧头布局（T3）、`capability_hash` 算法（T2）、业务配置 JSON 结构（T4）、调色板与索引矩阵位序（T7/T12）、本地响应动作名与参数集合（T13）、下行音频容器（T16）。
