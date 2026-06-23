# SDUI 能力节点工作流设计

## 1. 目标

本文定义一套面向 SDUI 终端的能力节点编排模型。

系统的目标不是让用户直接编排底层协议、命令、section patch 或音频分片，而是让用户围绕“终端角色”和“能力节点”组织流程：

```text
选择板卡类型 -> 创建虚拟终端角色 -> 从角色能力中选择节点 -> 连接节点 -> 部署到真实终端
```

最终用户关心的是：

```text
哪个终端角色的哪个能力，连接到另一个角色或平台的哪个能力。
```

底层复杂度由节点封装和工作流运行时负责，包括命令分发、会话状态、上下文产物、音频采集、STT/TTS、UI 更新、section diff 和终端推送。

## 2. 设计原则

### 2.1 板卡能力是统一类型来源

每种板卡类型统一声明输入、输出和 UI 能力。流程编辑器、校验器、部署器和运行时都从同一份能力定义中获取信息。

能力定义需要覆盖：

- 输入能力：按钮、传感器、麦克风事件、section 交互事件。
- 输出能力：RGB、音频播放、亮度、设备控制。
- UI 能力：屏幕、section 类型、显示约束、渲染能力。
- 平台能力：STT、TTS、HTTP、数据转换、任务监听等。

### 2.2 流程引用虚拟终端角色，不引用真实设备

流程定义中不直接使用真实 `deviceId`，而是先添加虚拟终端角色。

例如：

```text
type A / name1
type A / name2
type B / name3
```

这些角色代表流程中的设备槽位。部署时再将角色映射到真实终端。

这样同一个流程可以部署到不同设备组合上，只要部署时能力校验通过即可。

### 2.3 节点是能力封装，不是普通函数

节点不仅包含输入输出，还需要声明自己的运行模式、参数、产物和状态管理方式。

例如：

- 按钮节点只能作为触发节点。
- RGB 节点需要用户配置灯光参数。
- 音频采集节点需要内部管理开始、停止、上传、完成等状态。
- 音频播放节点需要接收音频文件或文本，文本可通过平台 TTS 转成音频。
- UI 节点修改某个终端角色的 UI 上下文，而不是直接暴露底层 patch API。

### 2.4 上下文承载节点产物

节点执行后的输出产物写入工作流上下文。后续节点可以引用这些产物。

例如音频采集节点最终输出：

- 音频文件。
- 音频时长。
- 音频元信息。
- 可选 STT 文本。

音频播放节点、UI 更新节点、数据存储节点都可以引用这些输出。

### 2.5 UI 是每个终端角色的独立上下文

每个引入流程的终端角色维护自己的 UI 上下文。

初始 UI 由用户定义，运行过程中 UI 节点修改 UI 上下文。系统自动对 UI 上下文进行 diff，并转换为 section patch 或 scene 推送到真实终端。

用户不需要直接处理 `section.add`、`section.update`、`section.remove`、`scene`、`patch` 等底层概念。

## 3. 核心对象

### 3.1 板卡类型

板卡类型描述一类终端支持哪些能力。

板卡能力定义至少包含：

- 输入能力清单。
- 输出能力清单。
- UI 能力清单。
- 能力参数 schema。
- 能力事件 schema。
- 能力产物 schema。
- 显示约束和资源约束。

编辑流程时，用户先选择板卡类型。系统根据板卡类型自动展开该角色可用的能力节点。

### 3.2 虚拟终端角色

虚拟终端角色是流程中的设备占位。

例如：

```text
name1: type A
name2: type A
screen1: type B
```

每个角色拥有自己的：

- 输入能力节点。
- 输出能力节点。
- UI Surface。
- UI 上下文。
- 运行时状态。

部署时，角色映射到真实终端：

```text
name1 -> device-001
name2 -> device-002
screen1 -> device-101
```

### 3.3 能力节点

能力节点是流程编排的最小可视化单元。

每个节点需要声明：

- 节点类型。
- 所属角色或平台。
- 输入端口。
- 输出端口。
- 参数配置。
- 运行模式。
- 输出产物。
- 是否需要会话状态。
- 错误和完成事件。

节点按行为分为：

| 节点类别 | 说明 | 示例 |
| --- | --- | --- |
| Trigger Node | 只产生触发事件 | 按钮、传感器、section 点击 |
| Action Node | 执行一次动作 | RGB 设置、音频播放、亮度设置 |
| Transform Node | 输入转换为输出 | STT、TTS、HTTP、JSON 映射 |
| Session Node | 有生命周期和内部状态 | 音频采集、网络对讲、任务监听 |
| UI Node | 修改 UI 上下文 | 更新文本、进度、图表、弹窗 |
| Control Node | 控制流程 | 条件、延迟、分支、循环 |

### 3.4 工作流上下文

工作流运行时维护结构化上下文。

建议分为：

| 命名空间 | 说明 |
| --- | --- |
| `$event` | 当前触发事件 |
| `$nodes` | 每个节点最近一次输出产物 |
| `$sessions` | 会话节点运行状态 |
| `$ui` | 每个终端角色的 UI 状态 |
| `$workflow` | 流程级变量 |

示例：

```json
{
  "$nodes": {
    "record_1": {
      "audio_file": "storage://audio/abc.wav",
      "duration_ms": 4200,
      "sample_rate": 16000
    },
    "stt_1": {
      "text": "打开会议模式"
    }
  }
}
```

后续节点可以引用：

```text
$nodes.record_1.audio_file
$nodes.stt_1.text
```

### 3.5 UI 上下文

每个终端角色维护自己的 UI 上下文。

示例：

```json
{
  "$ui": {
    "name1": {
      "activeView": "main",
      "sections": {}
    },
    "name2": {
      "activeView": "main",
      "sections": {}
    }
  }
}
```

UI 节点修改的是 UI 上下文，例如：

```text
name2.main.statusText.value = $nodes.stt_1.text
```

系统负责将 UI 上下文变化转换为 section diff，并下发给终端。

## 4. 节点运行模式

节点需要声明运行模式。运行模式决定运行时如何调度节点、是否等待结果、是否持久化状态、是否允许并发、是否支持取消和超时。

| 运行模式 | 说明 | 示例 |
| --- | --- | --- |
| `event` | 只产生事件，不主动执行 | 按钮、传感器 |
| `instant` | 立即执行一次 | RGB 设置、亮度设置 |
| `transform` | 输入转换为输出 | STT、TTS、HTTP、数据映射 |
| `session` | 维护生命周期 | 音频采集、对讲、任务监听 |
| `binding` | 持续绑定数据 | 定时数据源到图表 |
| `ui_patch` | 修改 UI 上下文 | 更新文本、图表、进度 |

## 5. 会话节点

会话节点用于封装具有生命周期的复杂能力。

典型场景包括：

- 音频采集。
- 网络对讲。
- 任务进度监听。
- 长时间数据订阅。

以音频采集为例，节点内部维护状态：

```text
idle -> recording -> processing -> completed / failed
```

用户不需要手动判断当前是否正在录音。节点提供高级控制端口：

```text
start
stop
toggle
```

因此按钮控制录音可以表达为：

```text
name1.buttonA.press -> name1.audio.record.toggle
```

音频采集节点内部负责：

- 判断当前状态。
- 下发开始录音命令。
- 下发停止录音命令。
- 等待终端上传音频。
- 组装音频文件。
- 生成音频产物。
- 写入 `$nodes` 和 `$sessions`。
- 发出完成或失败事件。

## 6. UI 节点

UI 节点不直接暴露底层 section API，而是提供更高层的 UI 操作。

推荐内置 UI 节点：

- `ShowView`
- `UpdateText`
- `SetProgress`
- `UpdateChart`
- `AppendLog`
- `ShowOverlay`
- `BindDataToSection`

这些节点修改 `$ui` 上下文。底层复用现有 section scene/patch 机制完成真实终端渲染。

例如任务进度展示：

```text
task.progress.percent -> name1.ui.SetProgress
task.progress.message -> name1.ui.UpdateText
task.log.line -> name1.ui.AppendLog
```

## 7. 流程运行模型

工作流本质是：

```text
事件流 + 数据上下文 + 会话状态 + UI 状态更新
```

运行过程：

1. 终端输入事件触发 Trigger Node。
2. 运行时查找与该输出端口相连的后续节点。
3. 节点执行并产生输出产物。
4. 输出产物写入 `$nodes`。
5. 如节点为会话节点，则更新 `$sessions`。
6. 如节点为 UI 节点，则更新 `$ui`。
7. UI 上下文变化由系统自动 diff 成 section patch 或 scene。
8. 后续节点可继续引用上下文执行。

## 8. 编排示例

### 8.1 网络对讲

基础流程：

```text
name1.buttonA.press -> name1.audio.record.toggle
name1.audio.record.audio_file -> name2.audio.play.audio_file
```

加入 STT：

```text
name1.audio.record.audio_file -> platform.stt
platform.stt.text -> name2.ui.UpdateText
platform.stt.text -> name2.audio.play.text
```

加入 UI 状态：

```text
name1.audio.record.recording_started -> name1.ui.UpdateText("录音中")
name1.audio.record.completed -> name1.ui.UpdateText("已发送")
name2.audio.play.play_started -> name2.ui.UpdateText("正在播放")
```

### 8.2 定时数据图表

```text
system.timer.every_10s -> platform.http.fetch
platform.http.fetch.result -> platform.data.map
platform.data.map.points -> name1.ui.UpdateChart
platform.data.map.summary -> name2.ui.UpdateText
```

### 8.3 任务进度展示

```text
platform.task.subscribe -> task.progress
task.progress.percent -> screen1.ui.SetProgress
task.progress.message -> screen1.ui.UpdateText
task.completed -> screen1.ui.ShowOverlay("完成")
task.failed -> screen1.ui.ShowOverlay("失败")
```

## 9. 编辑器交互

推荐流程编辑器分为四步：

### 9.1 选择参与角色

用户添加虚拟终端角色：

```text
type A / name1
type A / name2
type B / screen1
```

### 9.2 展开角色能力

系统按板卡类型展示每个角色的能力：

```text
name1
  inputs
  outputs
  ui

name2
  inputs
  outputs
  ui
```

### 9.3 编排节点连接

用户连接节点端口。

简单场景直接连线：

```text
name1.buttonA.press -> name2.rgb.set
```

复杂场景使用会话节点或平台节点封装：

```text
name1.buttonA.press -> name1.audio.record.toggle
name1.audio.record.audio_file -> name2.audio.play.audio_file
```

### 9.4 部署绑定

用户将虚拟角色绑定到真实终端：

```text
name1 -> real device A001
name2 -> real device A002
screen1 -> real device B001
```

部署前系统执行能力校验。

## 10. 校验模型

系统需要在多个阶段校验流程。

### 10.1 流程定义校验

不依赖真实设备，校验：

- 节点类型是否存在。
- 端口连接是否兼容。
- 参数是否满足 schema。
- 上下文引用是否可解析。
- UI 引用的 view、section、field 是否存在。

### 10.2 部署校验

绑定真实终端后校验：

- 真实设备是否支持角色要求的板卡类型。
- 真实设备是否支持流程中使用的输入能力。
- 真实设备是否支持流程中使用的输出能力。
- 真实设备是否支持流程中使用的 UI 能力和 section 类型。

### 10.3 运行时校验

运行中校验：

- 节点输入是否存在。
- 会话状态是否合法。
- 上下文产物是否过期。
- 目标终端是否在线。
- UI patch 是否可投影。

## 11. 与当前状态机和 Section 系统的关系

当前状态机和 section 投影能力可以作为底层能力继续复用。

新的能力节点工作流位于更高层：

```text
能力节点工作流
  -> UI 上下文更新
    -> section diff
      -> scene / patch
        -> 终端渲染
```

状态机中已有的 section 权威状态、patch/scene 推送、命令分发、事件接收等能力，可以沉淀为节点运行时和 UI 投影层的基础设施。

用户不直接面对状态机细节，而是通过能力节点和 UI 节点完成编排。

## 12. 落地顺序

建议按以下顺序落地：

1. 建立板卡能力定义与节点类型定义的映射。
2. 支持流程内添加虚拟终端角色。
3. 根据角色板卡类型展开可用能力节点。
4. 定义节点端口、参数、运行模式和产物 schema。
5. 实现结构化工作流上下文。
6. 实现会话节点模型，优先支持音频采集。
7. 为每个终端角色建立 UI 上下文。
8. 实现 UI 节点到 section patch/scene 的投影。
9. 支持部署时角色到真实终端的映射和能力校验。
10. 用网络对讲、数据图表、任务进度三个场景验证抽象。

## 13. 总结

这套设计以板卡能力为基础，以虚拟终端角色为编排对象，以能力节点为封装单元。

工作流运行时负责管理：

- 事件流。
- 节点产物。
- 会话状态。
- UI 上下文。
- 终端投影。

用户编排的是清晰的能力连接关系；系统内部负责把这些连接转化为命令、会话、上下文、UI 更新和终端渲染。

