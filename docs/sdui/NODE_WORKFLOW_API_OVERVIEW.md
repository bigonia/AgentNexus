# SDUI Node Workflow API Overview

本文档简要说明 SDUI 工作流升级后新增的主要模块、端点和职责。

不展开具体请求体和字段细节，重点描述前端、联调和后续开发需要理解的系统边界。

## 1. 能力节点模块

能力节点模块负责把真实终端能力投影成可编排节点。

它不直接执行工作流，只提供“某个设备/板卡类型支持哪些节点”的统一能力视图。

主要能力：

- 查询真实设备可用节点。
- 查询板卡类型可用节点。
- 将按钮、RGB、音频录制、音频播放、UI 更新等终端能力统一封装为节点。

主要端点：

```text
GET /api/v1/sdui/capability-nodes/{deviceId}
GET /api/v1/sdui/board-types/{typeKey}/capability-nodes
```

典型节点类型：

```text
button.trigger
rgb.effect
audio.record
audio.play
ui.update
display.section
```

## 2. Node Workflow 模块

Node Workflow 是正式工作流主模块。

它负责工作流定义、槽位映射、DAG 执行、真实事件触发、运行记录和上下文管理。

主要能力：

- 创建、更新、删除工作流定义。
- 校验节点、槽位和 DAG 连线。
- 部署时将 slot 绑定到真实设备。
- 监听真实终端输入事件并触发工作流。
- 支持多层 DAG 串行执行。
- 支持节点之间通过上下文传递数据。
- 保存 run、step、resolved params、result、error 和 context。

工作流定义端点：

```text
POST   /api/v1/sdui/node-workflows
GET    /api/v1/sdui/node-workflows
GET    /api/v1/sdui/node-workflows/{workflowId}
PUT    /api/v1/sdui/node-workflows/{workflowId}
DELETE /api/v1/sdui/node-workflows/{workflowId}

POST   /api/v1/sdui/node-workflows/validate
```

部署端点：

```text
POST   /api/v1/sdui/node-workflows/{workflowId}/deployments
GET    /api/v1/sdui/node-workflows/{workflowId}/deployments
GET    /api/v1/sdui/node-workflows/{workflowId}/deployments/{deploymentId}
DELETE /api/v1/sdui/node-workflows/{workflowId}/deployments/{deploymentId}

POST   /api/v1/sdui/node-workflows/{workflowId}/deployments/validate
POST   /api/v1/sdui/node-workflows/{workflowId}/deployments/inspect
POST   /api/v1/sdui/node-workflows/{workflowId}/deployments/{deploymentId}/test-trigger
```

运行记录端点：

```text
GET /api/v1/sdui/node-workflows/{workflowId}/deployments/{deploymentId}/runs
GET /api/v1/sdui/node-workflows/{workflowId}/runs/{runId}
GET /api/v1/sdui/node-workflows/{workflowId}/runs/{runId}/artifacts
```

## 3. 工作流上下文与产物引用

每次 workflow run 都会创建独立上下文。

上下文用于节点之间传递事件、槽位、节点结果和产物。

主要命名空间：

```text
$event      当前触发事件
$slots      槽位与真实设备绑定
$nodes      节点执行结果
$artifacts  节点产物快捷引用
$run        当前运行信息
```

引用示例：

```text
$event.nodeId
$slots.target.deviceId
$nodes.record_stop.artifact.artifactRef
$nodes.record_stop.artifact.text
$artifacts.record_stop.text
```

当前支持：

- 字符串完整引用。
- 字符串模板引用。
- Map/List 参数递归解析。
- 缺失引用时当前 step failed，run failed。

## 4. 正式产物模块

产物模块用于保存音频录制等 workflow 运行产物。

它替代 debug artifact 成为正式 workflow 主路径。

主要能力：

- 保存音频录制 artifact。
- 查询 artifact 元数据。
- 下载 artifact blob。
- 查询设备最新录音。
- 查询某次 workflow run 的产物列表。

主要端点：

```text
GET /api/v1/sdui/artifacts/{artifactId}
GET /api/v1/sdui/artifacts/{artifactId}/blob
GET /api/v1/sdui/devices/{deviceId}/artifacts/latest
GET /api/v1/sdui/node-workflows/{workflowId}/runs/{runId}/artifacts
```

主要引用格式：

```text
artifact:{artifactId}
audio-record-latest
```

其中 `audio-record-latest` 主要用于手工测试和快速验证，会解析为当前设备最新录音。

## 5. UI Template 模块

UI Template 模块负责将 section 组合封装成可复用 UI 模板。

工作流不直接编排复杂 section patch，而是引用模板并更新模板变量。

主要能力：

- 按业务名称管理 UI 模板。
- 模板包含页面、section、默认字段、变量声明和 mock 数据。
- 支持模板预览。
- workflow deployment 可将模板绑定到 slot。
- 部署时模板实例化为该 deployment 的 UI context。
- `ui.update` 节点可更新模板变量。
- 平台自动将变量变化转换为 section patch 并下发终端。

主要端点：

```text
POST   /api/v1/sdui/ui-templates
GET    /api/v1/sdui/ui-templates
GET    /api/v1/sdui/ui-templates/{templateId}
PUT    /api/v1/sdui/ui-templates/{templateId}
DELETE /api/v1/sdui/ui-templates/{templateId}

POST   /api/v1/sdui/ui-templates/{templateId}/preview
```

工作流集成关系：

```text
workflow definition 引用 uiTemplates
deployment 实例化 UI context
ui.update 更新 variableKey
平台生成 section patch
终端刷新显示
```

首版边界：

- 支持模板初始化。
- 支持变量更新。
- 暂不开放 workflow 动态增删 section。
- 低层 scene/patch 能力保留为高级兜底。

## 6. 工作流管理与轻量监控

管理模块用于查看当前工作流定义、部署、设备绑定、触发冲突和最近运行状态。

主要能力：

- 查看 workflow/deployment 总览。
- 查看 active/stopped/replaced deployments。
- 查看某个设备参与了哪些 workflow deployment。
- 检查真实按钮事件触发冲突。
- 查看最近失败 runs。
- 部署前 inspect。

主要端点：

```text
GET /api/v1/sdui/node-workflows/management/overview
GET /api/v1/sdui/node-workflows/management/deployments
GET /api/v1/sdui/node-workflows/management/devices/{deviceId}/deployments
GET /api/v1/sdui/node-workflows/management/conflicts
GET /api/v1/sdui/node-workflows/management/runs/recent
```

## 7. Debug 与验证模块

Debug 模块仍保留，用于开发验证。

正式业务主路径不依赖 debug workflow 或 debug artifact。

主要能力：

- 验证真实输入事件是否能激活节点。
- 验证输出节点是否能真实下发到终端。
- 手动触发 workflow。
- 提供静态测试页面用于开发阶段快速验证。

典型端点前缀：

```text
/api/v1/sdui/debug/{deviceId}/node-tests/...
/api/v1/sdui/debug/node-workflows/...
```

正式主路径应优先使用：

```text
/api/v1/sdui/capability-nodes
/api/v1/sdui/node-workflows
/api/v1/sdui/ui-templates
/api/v1/sdui/artifacts
/api/v1/sdui/node-workflows/management
```

## 8. 整体关系

升级后的工作流系统可以理解为：

```text
设备能力
  -> capability nodes
    -> workflow definition
      -> deployment slot binding
        -> runtime DAG execution
          -> context / artifacts / UI context
            -> device output / section update
```

当前核心闭环已经覆盖：

- 终端能力抽象。
- 槽位到真实设备映射。
- DAG 编排与真实事件触发。
- 节点上下文与产物传递。
- 正式 artifact 管理。
- UI 模板初始化。
- UI 变量更新。
- 部署管理与轻量监控。
