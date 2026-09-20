# LCD_085 平台接入指南（lcd085.v2）

本文是 LCD_085 平台接入的唯一交付文档，覆盖连接初始化、Schema 协商、请求响应、规则组、UI、音频和二进制数据面。平台无需获取终端项目源码；文中的 Python 代码可直接作为实现参考，再按平台技术栈改写。

终端实际能力始终以本次连接取得并校验通过的 Command Schema 和 UI Schema 为准。本文解释协议语义和平台约束，不能代替运行时 Schema 做能力判断。

## 1. 接入边界

LCD_085 通过 WebSocket 与平台建立长连接。终端负责有限的本地显示、按键、RGB、录音和播放能力；平台负责设备身份、业务状态、完整规则配置、UI 内容生成、媒体转换、连接恢复和持久化。

- 协议版本：`lcd085.v2`
- 文本帧：UTF-8 JSON
- 二进制帧：首字节为数据类型，其余字节为负载
- 设备身份：WebSocket URL query 中的 `deviceId`
- 业务可用信号：终端主动发送 `device.ready`
- 单设备同一时刻只保留一个有效连接；新连接应替换旧连接

推荐生产地址使用 `wss://`。反向代理必须正确透传 WebSocket Upgrade、长连接和二进制帧，不应设置过短的读写超时。

## 2. 消息模型

平台请求、成功结果和失败结果分别为：

```json
{"id":"req-1001","name":"system.volume.set","body":{"value":60}}
```

```json
{"id":"req-1001","ok":true}
```

```json
{"id":"req-1001","ok":false,"error":"invalid_request"}
```

平台必须为每条请求生成 1～63 bytes、且在重试窗口内唯一的字符串 `id`，并通过 `id` 关联结果。当前终端会缓存最近 8 个请求结果；同一连接内使用相同 `id` 重发时，终端返回缓存结果而不会再次执行，因此不得用同一个 `id` 表示不同操作。

终端主动事件没有 `id`，平台按 `name` 路由，不能把它当作请求结果：

```json
{"name":"platform.interaction","body":{"token":"dashboard-refresh"}}
```

存在依赖的操作必须等待前置请求成功，例如 Image、Canvas、音频的 begin/start 成功后才能发送二进制帧。对同一 UI 或业务对象的写入也应串行，避免“最后到达者生效”造成不确定结果。

最小请求关联参考实现：

```python
import asyncio
import json
import uuid

pending = {}

async def request(ws, name, body=None, timeout=5):
    request_id = uuid.uuid4().hex
    future = asyncio.get_running_loop().create_future()
    pending[request_id] = future
    message = {"id": request_id, "name": name}
    if body is not None:
        message["body"] = body
    await ws.send(json.dumps(message, separators=(",", ":")))
    try:
        result = await asyncio.wait_for(future, timeout)
    finally:
        pending.pop(request_id, None)
    if result.get("ok") is not True:
        raise RuntimeError(result.get("error", "unknown_error"))
    return result

def on_text_message(message):
    value = json.loads(message)
    if "id" in value and "ok" in value:
        future = pending.get(value["id"])
        if future and not future.done():
            future.set_result(value)
        return
    route_event(value)  # hello、ready、interaction 等主动事件
```

## 3. 初始化与 Schema 协商

每次 WebSocket 新建或重连都必须重新完成以下流程：

```text
终端连接
  → device.hello
  → 平台读取 Schema 缓存；未命中时分别请求 Command/UI Schema
  → 平台校验分片、长度、SHA-256 和 JSON
  → device.schemas.ready
  → 终端校验两个 hash
  → device.ready
  → 平台恢复完整规则列表和当前业务主视图
```

### 3.1 Hello 与缓存

```json
{
  "name":"device.hello",
  "body":{
    "protocol_version":"lcd085.v2",
    "command_schema_hash":"<64位小写SHA-256>",
    "ui_schema_hash":"<64位小写SHA-256>"
  }
}
```

平台应以 `kind + hash` 为键缓存原始 Schema 字节及解析结果。只有缓存字节重新计算的 SHA-256 与 hello 中的 hash 一致时才能复用。两份 Schema 独立缓存，任意一份未命中就只请求该份：

```json
{"id":"schema-command-1","name":"device.schema.get","body":{"kind":"command"}}
```

```json
{"id":"schema-ui-1","name":"device.schema.get","body":{"kind":"ui"}}
```

### 3.2 Schema 分片

终端先发送开始事件：

```json
{"name":"device.schema.begin","body":{"kind":"command","hash":"<sha256>","length":2048,"chunks":3}}
```

随后发送 `chunks` 个 WebSocket Binary Frame：

```text
byte 0      0x31（Schema 分片）
byte 1      Schema 类型：0=command，1=ui
byte 2..3   分片序号，大端 uint16，从 0 连续递增
byte 4..N   原始 Schema UTF-8 JSON 字节，单片最多 768 bytes
```

最后发送结束事件：

```json
{"name":"device.schema.end","body":{"kind":"command","hash":"<sha256>"}}
```

`device.schema.get` 自身的 `{id,ok}` 结果也会返回，但它不承载 Schema 内容。平台应同时等待请求成功和对应 Schema 流校验完成。

平台必须校验：`kind` 和 hash 一致；分片序号连续；实收分片数与字节数符合声明；原始字节 SHA-256 正确；内容能解析为 JSON object。任何一项失败都不得确认 ready，也不得缓存失败内容。Schema 分片不是通用文件传输能力。

Schema 接收参考实现：

```python
import hashlib
import json

schema_streams = {}

def on_schema_begin(body):
    kind = body["kind"]
    assert kind in ("command", "ui")
    assert isinstance(body["length"], int) and body["length"] >= 0
    assert isinstance(body["chunks"], int) and body["chunks"] > 0
    schema_streams[kind] = {
        "hash": body["hash"], "length": body["length"],
        "chunks": body["chunks"], "next": 0, "data": bytearray(),
    }

def on_schema_binary(frame):
    if len(frame) < 5 or frame[0] != 0x31:
        return False
    kind = "ui" if frame[1] == 1 else "command" if frame[1] == 0 else None
    if kind is None or kind not in schema_streams:
        raise ValueError("unexpected schema chunk")
    stream = schema_streams[kind]
    index = (frame[2] << 8) | frame[3]
    if index != stream["next"]:
        raise ValueError("non-contiguous schema chunk")
    stream["data"].extend(frame[4:])
    stream["next"] += 1
    return True

def on_schema_end(body, expected_hash):
    kind = body["kind"]
    stream = schema_streams.pop(kind)
    raw = bytes(stream["data"])
    if body["hash"] != stream["hash"] or stream["hash"] != expected_hash:
        raise ValueError("schema hash declaration mismatch")
    if stream["next"] != stream["chunks"] or len(raw) != stream["length"]:
        raise ValueError("schema length/chunk count mismatch")
    if hashlib.sha256(raw).hexdigest() != expected_hash:
        raise ValueError("schema digest mismatch")
    value = json.loads(raw.decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("schema must be an object")
    return raw, value
```

两份 Schema 均已取得或从可信缓存命中后，平台发送：

```json
{"id":"schemas-ready-1","name":"device.schemas.ready","body":{"command_hash":"<command hash>","ui_hash":"<ui hash>"}}
```

终端校验成功后主动发送：

```json
{"name":"device.ready","body":{"protocol_version":"lcd085.v2"}}
```

平台必须以 `device.ready` 作为业务可用边界。在此之前发送普通业务请求会得到 `not_ready`。不要仅凭 `device.schemas.ready` 的成功结果提前开放业务下发。

## 4. 会话与重连恢复

平台至少维护：设备身份、连接实例 ID、协议版本、两个 Schema hash、ready 状态、pending 请求、期望的完整规则列表、当前主视图描述，以及当前音频/Image/Canvas 流。

断线时：

- 立即取消该连接上的 pending 请求与流式发送，禁止向旧 socket 继续写入。
- Image、Canvas、音频传输不能跨连接续传。
- 终端会保留已经写入的规则组，但平台不能以此作为状态真相；新连接 ready 后仍应按平台保存的完整配置恢复。
- 动态业务 UI 不保证保留，平台应根据当前业务状态重新投影主视图。
- 新连接必须重新执行完整 Schema/ready 流程，即使 hash 未变化。

推荐恢复顺序：`business.scenes.replace` 成功后，再发送携带 `scene_id` 的主视图请求。

## 5. 规则组与本地交互

### 5.1 全量替换

规则只能通过 `business.scenes.replace` 全量替换。终端不提供读取、patch、单组删除或独立激活命令。平台必须保存完整目标列表：删除某组时在下一次全量列表中省略它，清空全部规则时发送 `{"scenes":[]}`。每次全量替换都会取消当前激活组，因此规则写入成功后，平台应通过下一次主视图下发重新选择 `scene_id`。

当前限制：最多 8 个 scene；每个 scene 最多 16 个 binding；每个 binding 包含 1～4 个按顺序执行的 response；`scene.id` 为 1～31 bytes 且不能重复；同一 scene 内 trigger 不能重复。

```json
{
  "id":"rules-1",
  "name":"business.scenes.replace",
  "body":{"scenes":[
    {"id":"dashboard","bindings":[
      {"trigger":"button.pwr.short_press","responses":[
        {"name":"platform.interaction.report","body":{"token":"dashboard-refresh"}}
      ]},
      {"trigger":"button.plus.short_press","responses":[
        {"name":"feedback.rgb.set","body":{"r":0,"g":96,"b":255,"brightness":35}},
        {"name":"platform.interaction.report","body":{"token":"dashboard-reset"}}
      ]}
    ]},
    {"id":"assistant","bindings":[
      {"trigger":"button.pwr.short_press","responses":[
        {"name":"audio.record.toggle"},
        {"name":"platform.interaction.report","body":{"token":"assistant-record"}}
      ]}
    ]}
  ]}
}
```

支持的 trigger：

- `button.pwr.down|up|short_press|long_press`
- `button.plus.down|up|short_press|long_press`
- `platform.trigger.<token>`

支持的 response：

- `audio.record.start`、`audio.record.stop`、`audio.record.toggle`
- `feedback.rgb.set`：`r/g/b` 为 0～255，`brightness` 为 0～100
- `feedback.rgb.off`
- `platform.interaction.report`：`token` 为 1～95 bytes

平台也可主动触发当前激活规则组中的 `platform.trigger.<token>`：

```json
{"id":"trigger-1","name":"business.trigger","body":{"token":"refresh"}}
```

### 5.2 与 UI 原子切换

规则组不单独激活，而是随新的业务主视图一起切换。`display.section.set`、`display.image.begin`、`display.canvas.open` 均可携带可选 `scene_id`。

携带有效 `scene_id` 时，视图建立成功后该规则组同时生效；不携带时，新视图生效且不激活任何规则组。`scene_id` 不存在时返回 `scene_not_found`，原视图和原激活规则组保持不变。

输入优先级为：系统保留操作 > UI 原生交互 > scene 规则。`menu_section` 原生占用 `button.plus.short_press` 切换选项、`button.pwr.short_press` 确认选择；若其 scene 也绑定任一相同 trigger，请求返回 `ui_rule_conflict`，原状态不变。平台应在下发前完成同样的冲突检查。

## 6. UI 主视图

LCD_085 只有一个 128×128 业务主视图，支持 Template、Image、Canvas 三种模式。新主视图替换旧主视图，不维护页面栈、叠层或业务状态。启动、配网、断网重试、系统错误和 Ready 等本地页面不属于 UI Schema，也不由平台控制。

### 6.1 Template

所有模板使用 `display.section.set`。字符串上限按 UTF-8 字节计算，平台应在下发前校验，不应依赖终端截断。

```json
{
  "id":"view-1","name":"display.section.set",
  "body":{"type":"metrics_section","scene_id":"dashboard","title":"设备状态","items":[
    {"label":"温度","value":"24","unit":"°C"},
    {"label":"湿度","value":"58","unit":"%"},
    {"label":"网络","value":"OK"}
  ]}
}
```

| type | body 字段与限制 |
|---|---|
| `text_section` | `title?` ≤64 bytes；`text` 1～240 bytes |
| `status_section` | `title?` ≤64；`value` 1～64；`description?` ≤128 bytes |
| `metrics_section` | `title?`；`items` 2～4；每项 `label` 1～32、`value` 1～32、`unit?` ≤16 bytes |
| `chart_section` | `title?`；`points` 为 2～32 个 int16 |
| `menu_section` | `title?`；`items` 1～5；每项 `label` 1～48、`token` 1～95 bytes |

菜单确认后终端上报对应 token：

```json
{"name":"platform.interaction","body":{"token":"menu-settings"}}
```

### 6.2 Image 与 Canvas

两者都是调色板索引的完整像素帧，不是 RGB565 原始帧、矢量图元、增量 patch 或 Base64 JSON。

- `width`/`height`：必须相同，取 16、32、64、128；终端等比放大到 128×128。
- `bits_per_index`：1、2、4。
- `palette`：RGB565 整数数组，元素数必须分别为 2、4、16。
- 像素顺序：从左到右、从上到下；索引每字节高位优先。
- 帧负载长度：`width * height * bits_per_index / 8`，最大 8192 bytes。

Image 只接受一帧：先发送 `display.image.begin`，等待成功后发送 Binary `[0x21][packed indexes]`，最后发送 `display.image.end`。

Canvas 可接受多个完整帧：先发送 `display.canvas.open`，等待成功后发送多个 Binary `[0x22][packed indexes]`，最后发送 `display.canvas.close`。

begin/open 的 body 示例：

```json
{"scene_id":"surface","width":32,"height":32,"bits_per_index":4,"palette":[0,65535,63488,2016,31,65504,63519,2047,33808,16904,64800,45029,30735,1007,31727,50712]}
```

4bpp 打包和发送参考：

```python
def pack_4bpp(indexes):
    if len(indexes) % 2:
        raise ValueError("pixel count must be even")
    if any(index < 0 or index > 15 for index in indexes):
        raise ValueError("palette index outside 0..15")
    return bytes((indexes[i] << 4) | indexes[i + 1]
                 for i in range(0, len(indexes), 2))

await ws.send(bytes([0x21]) + pack_4bpp(indexes))  # Image
# Canvas 使用 0x22
```

begin/open 失败、连接断开或会话已 close/end 后，禁止继续发送旧帧。当前数据面没有逐帧 ACK、重传、帧序号和跨连接续传，平台应控制发送节奏与队列积压。

## 7. 音频

当前下行只支持单声道 `pcm_s16le`、22050 Hz：

```json
{"id":"audio-1","name":"audio.start","body":{"direction":"download","format":"pcm_s16le","sample_rate":22050,"channels":1}}
```

等待成功后，以约 20 ms 一帧发送 Binary `[0x12][PCM]`。441 samples、882 bytes 的参考生成方式：

```python
import math
import struct

samples = [int(5500 * math.sin(2 * math.pi * 440 * n / 22050))
           for n in range(441)]
pcm_20ms = struct.pack("<" + "h" * len(samples), *samples)
await ws.send(bytes([0x12]) + pcm_20ms)
```

全部 PCM 发送完成后：

```json
{"id":"audio-2","name":"audio.stop","body":{"direction":"download"}}
```

`audio.stop` 是优雅结束标记，已进入播放队列的数据仍会播放完成。不要使用 Base64 JSON，也不要把整段音频塞进超长单帧。

录音由激活规则中的 `audio.record.start/stop/toggle` 控制。录音期间终端以 Binary `[0x11][PCM]` 上行，格式同为 `pcm_s16le/22050/mono`。平台必须将数据归属到当前连接的录音会话，断线后丢弃残留数据。录音与播放互斥；出现 `audio_busy` 时应先结束当前音频行为再重试。

## 8. 系统能力

平台应先检查 Command Schema 是否声明相应命令：

```jsonl
{"id":"volume-1","name":"system.volume.set","body":{"value":60}}
{"id":"brightness-1","name":"system.brightness.set","body":{"value":80}}
{"id":"provision-1","name":"system.provisioning.start"}
{"id":"reboot-1","name":"system.reboot"}
```

音量和亮度为 0～100 的整数。配网和重启会改变连接状态，平台应将其作为显式用户操作并准备处理断线，而不是自动无限重试。

当前固件还处理 `business.reset`，其行为是清空规则、停止音频/RGB/表面会话并恢复等待页面；但当前 Command Schema 未声明该命令。正式平台必须遵循 Schema 能力发现，在 Schema 补齐前不要依赖此命令。

## 9. Binary 类型表

| 方向 | 首字节 | 负载 | 前置条件 |
|---|---:|---|---|
| 终端 → 平台 | `0x11` | 录音 PCM | 激活规则已启动录音 |
| 平台 → 终端 | `0x12` | 播放 PCM | `audio.start(download)` 成功 |
| 平台 → 终端 | `0x21` | 一帧 Image 索引像素 | `display.image.begin` 成功 |
| 平台 → 终端 | `0x22` | 一帧 Canvas 索引像素 | `display.canvas.open` 成功 |
| 终端 → 平台 | `0x31` | Schema 类型、序号和 JSON 分片 | 已请求对应 Schema |

平台必须按首字节和当前会话状态共同解释 Binary，未知类型应记录并丢弃，不能当作通用文件处理。日志记录类型和字节数即可，不应记录 PCM 原文或敏感负载。

## 10. 错误处理

| error | 含义 | 平台处理 |
|---|---|---|
| `not_ready` | 初始化未完成或对应流未打开 | 等待 `device.ready`，或重建前置会话 |
| `invalid_request` | 参数、字段或顺序不合法 | 不原样重试；修正请求 |
| `invalid_config` | 完整规则列表校验失败 | 修正整份列表后重发 |
| `scene_not_found` | UI 引用了未配置规则组 | 先全量下发规则，或移除 `scene_id` |
| `ui_rule_conflict` | 菜单原生按键与规则冲突 | 调整规则；终端保留原状态 |
| `schema_mismatch` | ready hash 不一致 | 清除缓存并重新获取 Schema |
| `busy` / `audio_busy` | 存在互斥操作 | 等待或结束冲突操作后有限重试 |
| `resource_limit` | 内存或资源不足 | 降低负载并告警，禁止无限重试 |
| `queue_full` | 终端发送队列拥塞 | 退避重试并检查消费速度 |
| `unsupported_request` | 固件不支持命令 | 禁用能力并核对 Schema/版本 |

平台只应对临时错误做有限次数、带退避的重试；参数和能力错误不能自动原样重试。请求超时后若在同一连接重发同一逻辑操作，可沿用原 `id` 获取最近缓存结果；若已重连，则创建新 `id` 并按新会话恢复流程执行。

## 11. 平台实现要求

- 以 `deviceId` 绑定设备并执行单连接替换，防止旧连接继续下发。
- 完整实现双 Schema 缓存、分片组装和校验；功能开关由 Schema 驱动。
- 只有收到 `device.ready` 后才恢复和下发业务。
- 保存每台设备的完整规则目标配置，始终全量替换，不生成规则 patch。
- 在平台侧预检 scene、菜单按键冲突及所有字段上限。
- 将 UI 生成结果限制在 UI Schema 内；复杂视觉内容先转换成 indexed surface。
- 对请求 `id`、超时、有限重试和主动事件进行统一路由。
- 对音频和表面流实行连接级取消；控制请求未成功时不发送 Binary。
- 不把终端当作业务状态数据库；重连后由平台重新投影期望状态。
- 不混发 lcd085.v1 的 `business.update`、单一 `capability_hash`、旧 ACK、UI3 patch/TLV 或 Base64 音频。

建议记录：`deviceId`、连接实例、协议版本、两个 Schema hash、hello→ready 耗时、请求 `id/name/ok/error`、interaction token、Binary 类型/字节数、断线原因及重连次数。不要记录 Wi-Fi 凭据、PCM 原文和敏感业务文本。

## 12. 联调与验收清单

- [ ] 新连接能收到 `device.hello`，缓存未命中时能完整取得并校验两份 Schema。
- [ ] 错误 hash 无法通过 `device.schemas.ready`，正确 hash 后能收到 `device.ready`。
- [ ] ready 前业务请求被拒绝，ready 后正常执行。
- [ ] 规则组可全量写入和空列表清空；不存在 scene 与菜单冲突均被识别。
- [ ] 携带 `scene_id` 的 UI 与规则同步生效，不携带时不激活规则组。
- [ ] 五类 Template 能按 UI Schema 上限显示，菜单能上报选中 token。
- [ ] Image 单帧、Canvas 多帧的索引和 RGB565 色板显示正确。
- [ ] PCM 下行播放、规则触发录音和 `0x11` 上行均正常。
- [ ] 断线能取消旧请求与流；重连重新握手并恢复规则和主视图。
- [ ] 各类错误不会触发无限重试。
- [ ] WSS、代理超时、Binary 透传和同设备连接替换符合生产要求。
