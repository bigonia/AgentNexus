# SDUI 终端协议

## 连接

设备通过 `ws://{host}:8080/ws/sdui` 建立单条 WebSocket 连接。TEXT 帧承载 JSON Topic 消息，BINARY 帧承载 UI、音频和输入数据。服务端也兼容 `/` 路径，新增设备应使用 `/ws/sdui`。

## JSON Topic

当前命令控制 Topic 为：

| Topic | 方向 | 用途 |
| --- | --- | --- |
| `cmd/control` | 服务端 → 终端 | 下发设备控制命令。 |
| `cmd/control_ack` | 终端 → 服务端 | 命令回执。 |

能力、心跳、动作与页面事件由各 Topic handler 处理。具体字段必须以 handler、能力上报模型及运行时目录为准，不以本文的概述替代实现契约。

## 二进制帧

`BinaryProtocolCodec` 当前使用小端序的 16 字节头：

| 字段 | 字节数 |
| --- | ---: |
| magic `0x5344` | 2 |
| version（当前为 `1`） | 1 |
| message type | 1 |
| sequence | 4 |
| payload length | 4 |
| CRC32 | 4 |

CRC32 覆盖头部（CRC 字段按零处理）和 payload。Section scene/patch 的 outbound payload 是 UTF-8 JSON；入站输入事件按 TLV 解析。无法识别或校验失败的帧必须拒绝处理。

| Message type | 方向 | 用途 |
| ---: | --- | --- |
| 15 | 服务端 → 终端 | Section scene。 |
| 16 | 服务端 → 终端 | Section patch。 |
| 17 | 服务端 → 终端 | PCM 音频。 |
| 18 | 终端 → 服务端 | 录音 PCM 分片。 |
| 9 | 终端 → 服务端 | 输入事件（由输入 handler 解析）。 |

输入事件常用 TLV：120 event kind、121 node ID、122 event name、123 page ID、124 section ID、125 时间戳、126 event value。

## 演进规则

- 先扩展能力目录，再增加服务端和终端实现。
- 破坏性协议变更必须增加版本或显式能力协商，不能复用既有 message type 改写语义。
- 同时更新 codec 测试、终端实现和本文件；设计提案不修改当前协议文档。
