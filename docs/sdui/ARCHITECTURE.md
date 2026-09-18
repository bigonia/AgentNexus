# SDUI 当前架构

## 接入与设备

终端连接 `ws://{host}:8080/ws/sdui`。`SduiWebSocketHandler` 将文本帧交给 Topic handler，将二进制帧交给 Binary frame handler；设备生命周期、能力快照、命令生命周期和遥测数据由 SDUI 服务层持久化或发布。

HTTP 管理面以设备、板型、能力与调试接口为中心，具体端点以 Swagger 为准。

## 能力与目录

`src/main/resources/sdui-event-catalog.yml` 是命令、事件、Section 类型、字段和约束的唯一配置目录。终端上报的能力与该目录共同决定：

- 可发送的命令；
- 可编辑、渲染的 Section 类型；
- 可作为 Node Workflow 触发器或节点的能力；
- RICH / COMPACT 渲染模式的字段裁剪。

不要在客户端硬编码目录内容；应使用能力、板型和事件目录 API 获取可用项。

## Section UI

Section 是服务端下发 UI 的数据单元。当前 Java 模型包含 14 类：

`hero`、`metric`、`chart`、`timer`、`image`、`action`、`progress`、`text`、`overlay`、`list`、`toggle`、`nav`、`dashboard`、`speak`（wire type 均以 `_section` 结尾）。

`SectionDataCodec` 负责 Java 模型与 wire data 的转换。全量场景通过 `SECTION_SCENE` 发送，局部更新通过 `SECTION_PATCH` 发送。字段、交互事件和约束不在本文重复维护，统一读取 YAML 目录。

## Node Workflow

工作流由定义、部署和运行记录组成。定义中包含槽位、节点、边和 UI 模板；部署将槽位绑定到真实设备；运行时接收事件、计算执行计划、执行能力节点并保存结果与产物。入口为 `/api/v1/sdui/node-workflows`。

详细模型和示例见 [Node Workflow 交互流程](NODE_WORKFLOW_INTERACTION_FLOW.md)。

## 当前实现与重构提案

本文件描述当前代码实现。`lcd085-refactor/` 下的文档是 LCD_085 的后续重构提案，不能据此推断现有协议、API 或终端行为。

## v2 通道（LCD_085 重构，开发中）

`com.zwbd.agentnexus.sdui.v2` 是 LCD_085 重构后的新协议通道，与上述旧实现**并行运行**，旧实现保持不变。

- 端点：`/ws/sdui/v2`，只处理 v2 信封与 v2 二进制帧；旧端点 `/ws/sdui` 不受影响。
- 控制面按消息名分发（`Envelope.Request` / `Result` / `Event`），数据面按 `BinaryDataType` 分发。
- 装配入口：`SduiV2WebSocketHandler` → `SduiV2MessageRouter`；上限与超时集中在 `V2ProtocolProperties`（配置前缀 `sdui.v2`）。
- 测试桩：`src/test/java/.../sdui/v2/sim/SimulatedLcd085Device.java` 提供无设备条件下的端到端回归能力。

设计依据与实施进度见 [LCD_085 重构设计](lcd085-refactor/2026-09-18/README.md)、[平台侧升级方案](lcd085-refactor/2026-09-18/10_PLATFORM_UPGRADE.md) 与 [功能清单](lcd085-refactor/2026-09-18/11_FEATURE_MATRIX.md)。v2 尚未替换旧协议；旧路径的删除属于 P5 阶段。
