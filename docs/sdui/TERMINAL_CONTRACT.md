# LCD_085 端云契约基线与开发前确认

> 目的：先确定哪些信息已经有效、哪些材料存在冲突、还缺哪些输入。只有“必须裁决”项会阻塞正式开发。  
> 基线日期：2026-09-20。

## 1. 信息来源与采用规则

| 优先级 | 来源 | 用途 |
| ---: | --- | --- |
| 1 | `platform/lcd085_platform_simulator.py` | 可执行的线协议样例；明确报文名称、字段和 Binary 形态 |
| 2 | 终端提供的《平台接入指南》归档稿 | 完整接入语义、能力边界和容量限制 |
| 3 | 交互、系统边界、UI、协议设计文档 | 解释设计意图和职责边界 |
| 4 | 当前 Java 平台实现 | 只说明平台现状，不反向定义终端协议 |

采用原则：脚本已经明确且其他材料没有冲突的内容，直接作为开发契约，不再询问终端。材料互相冲突时只确认“最终以哪一种为准”。脚本没有模拟硬件效果不代表协议未定义，应列入真机验收而不是协议确认。

## 2. 已有有效信息

### 2.1 连接与控制消息

- 终端连接平台配置的完整 WebSocket URL，并追加 query 参数 `deviceId=<MAC>`。
- 生产环境使用 WSS；反向代理必须透传 Upgrade，不做 HTTP 重定向，长连接超时建议至少一小时。
- 连接后终端主动发送无 `id` 的 `device.hello` Event；平台在 hello 和 Schema 协商完成前不得下发业务状态。
- 平台请求：`{ "id": "...", "name": "...", "body": {} }`。
- 终端结果：`{ "id": "...", "ok": true, "error": "..." }`。
- 终端事件：`{ "name": "...", "body": {} }`。
- 同连接的请求 `id` 唯一；主动事件不能当作请求结果。
- 重连是新会话。平台替换旧连接，重建流生命周期，并重新投影期望业务状态和主视图。

WebSocket 的具体 URL path 由平台部署决定，不属于终端 wire 协议；AgentNexus 计划使用 `/ws/sdui/v2`。

### 2.2 Binary 数据面

Binary 形态由样例脚本明确为：

```text
[1 byte 类型][payload]
```

没有额外 magic、version、length、CRC 或 sequence 通用帧头；完整消息长度由 WebSocket 提供。

| 首 byte | 方向 | payload | 前置条件 |
| ---: | --- | --- | --- |
| `0x11` | 终端 → 平台 | 录音裸 PCM | 本地规则已启动录音 |
| `0x12` | 平台 → 终端 | 播放裸 PCM | `audio.start(direction=download)` 成功 |
| `0x21` | 平台 → 终端 | 图片索引矩阵 | `display.image.begin` 成功 |
| `0x22` | 平台 → 终端 | Canvas 索引矩阵 | `display.canvas.open` 成功 |
| `0x31` | 终端 → 平台 | Schema 分片 | `device.schema.begin` 已发送 |

Schema 分片格式由脚本明确为：

```text
[0x31][kind: 0=command, 1=ui][sequence big-endian uint16][JSON chunk]
```

单个完整 Text/Binary WebSocket 消息不超过 9,216 bytes。Indexed surface 支持边长 16/32/64/128，2/4/16 色对应 1/2/4 bpp，索引 payload 最大 8,192 bytes。

因此，平台当前的 8 字节 `0x414E + version + dataType + length` 帧头不是待确认方案，而是与终端样例不兼容的待改造实现。

### 2.3 已确定的消息和能力

| 领域 | 已确定内容 |
| --- | --- |
| Schema | `device.hello`、`device.schema.get`、`device.schema.begin/end`、`device.schemas.ready`、`device.ready` |
| 交互 | `platform.interaction.body.token`；token 对终端不透明 |
| Trigger | PWR/PLUS 的 down、up、short_press、long_press；`platform.trigger.*` |
| Response | `audio.record.start/stop/toggle`、`feedback.rgb.set/off`、`platform.interaction.report` |
| 显示 | `display.section.set`、`display.image.begin/end`、`display.canvas.open/close`，三种主视图互斥 |
| Section | `text/status/metrics/menu/chart_section`，字段和数量边界已在接入指南给出 |
| 音频 | `pcm_s16le`、22050 Hz、单声道；控制成功后才允许发送 Binary |
| 系统 | `system.volume.set`、`system.brightness.set`、`system.provisioning.start`、`system.reboot` |
| 清理 | `business.reset` 清绑定、停止录音和 RGB；不清连接身份和能力缓存 |

系统音量和亮度为 0–100 整数。菜单会消费 PLUS/PWR 短按，平台不能同时期待相同按键再触发普通业务绑定。

### 2.4 hash 算法

样例脚本的算法已经明确：

```text
UTF-8(JSON，separators=(",", ":")，保留对象插入顺序)
→ SHA-256
→ 小写 hex
```

平台必须按终端实际发送的 Schema 原始规范化规则复现；不得继续使用平台自定义的字段排序或另一套 `CapabilitySchemaV2` 规范化算法。

## 3. 开发前必须裁决的冲突

这里只保留会改变平台核心数据模型或 wire 协议的事项。

| ID | 冲突 | 方案 A | 方案 B | 建议 | 状态 |
| --- | --- | --- | --- | --- | --- |
| C1 | 协议版本 | 接入指南：`lcd085.v1`、单 `capability_hash` | 最新脚本：`lcd085.v2`、command/ui 双 hash | 以最新脚本 `lcd085.v2` 为准，并同步接入指南 | 待裁决 |
| C2 | Schema 模型 | 接入指南：单个 `device.schema` Event | 最新脚本：command/ui 两份 Schema，经 begin + `0x31` chunks + end | 以脚本的双 Schema 分片流程为准 | 待裁决 |
| C3 | 业务配置 | 设计/接入指南：`business.update {bindings}` | 最新脚本：`business.scenes.replace {scenes[]}`，显示用 `scene_id` 激活 | 以 scenes 模型为准；它能表达预装多场景和本地切换 | 待裁决 |
| C4 | 音频异常结束 | 设计稿包含 `audio.abort(reason)` / `buffer_full` | 脚本能力列表和处理逻辑只有 `audio.start/stop` | 明确固件是否实现 abort；若未实现，平台以断流超时收敛 | 待裁决 |

裁决的最小回复可以只有四行：

```text
C1: v1 / v2
C2: single-event / dual-schema-chunks
C3: business.update / business.scenes.replace
C4: audio.abort supported / unsupported
```

若明确“以当前模拟脚本为唯一基线”，则 C1、C2、C3 自动选择方案 B，只剩 C4 需要回答。

## 4. 现有材料确实缺失的信息

| ID | 缺失信息 | 为什么需要 | 是否阻塞 |
| --- | --- | --- | --- |
| M1 | `display.image.begin` / `display.canvas.open` 的完整 body 示例：palette 字段名、RGB565 字节序、索引行序和位打包顺序 | 平台必须生成终端可正确显示的像素数据 | 阻塞图片/Canvas；不阻塞连接、Section、交互和音频 |
| M2 | `business.scenes.replace` 的完整 JSON Schema及上限：scene、binding、response 的字段和数量限制 | 当前只能从 Python 解析逻辑反推，缺少正式示例和错误边界 | 若 C3 选 scenes，则阻塞业务配置开发 |
| M3 | 生产设备认证方式：仅 `deviceId`、设备密钥、签名 token 或网关认证 | query 中的 MAC 不能独立证明设备身份 | 不阻塞本地联调，阻塞生产接入定版 |

建议终端侧直接补充两个可执行 JSON/Binary 样例解决 M1/M2，不需要再写长篇设计说明。

## 5. 不再列为协议待确认的事项

- Binary 类型和值：脚本已明确。
- 握手承载：query `deviceId` + `device.hello` 已明确。
- 请求、结果、事件判别：脚本和接入指南一致。
- hash 基本算法：脚本已明确；只随 C1/C2 确定最终 Schema 输入。
- Trigger、Response、Section 和系统命令名称：Schema 常量已明确。
- 音频基础格式和方向：接入指南与脚本已明确。
- 真机屏幕颜色、按键、RGB、I2S、扬声器效果：属于验收，不是协议问题。
- 动态工作流节点是否生成临时绑定：属于平台产品和编排设计，不要求终端替平台决策。

## 6. 正式开发启动条件

1. C1–C4 有结论；如果声明脚本为唯一基线，只需补 C4。
2. C3 若选择 scenes，提供 M2；若选择 bindings，M2 自动关闭。
3. 图片/Canvas 可后置时，M1 不阻塞第一阶段；生产部署前补 M3。
4. 将最终结论固化到模拟脚本并作为平台契约测试输入。

达到以上条件后，不再增加泛化的“待终端确认”项。后续差异按契约缺陷或真机验收问题处理。
