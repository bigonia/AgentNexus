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
