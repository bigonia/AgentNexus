# LCD_085 平台接入指南（lcd085.v1）

本指南面向平台服务端、设备接入和联调人员，定义 LCD_085 终端的 WebSocket 接入方式、会话生命周期和首期能力边界。它是接入约定；终端实际可用能力以 `device.schema` 为准。

## 1. 接入范围与安全边界

- 传输：一个设备对应一个长期 WebSocket 会话，生产环境使用 `wss://`，不接受 HTTP/HTTPS 重定向后的 WebSocket 地址。
- 终端地址：配网时配置完整 WebSocket 基址；终端会追加 `deviceId=<MAC>` 查询参数。平台必须保留已有查询参数并以该参数识别设备。
- TLS：生产端必须提供终端可验证的证书链。LCD_085 默认使用 ESP-IDF 证书包校验服务端；自签名证书、证书链不完整或仅 HTTP 重定向都会导致连接失败。
- 反向代理：必须透传 Upgrade，使用 HTTP/1.1，并将 `proxy_read_timeout`、`proxy_send_timeout` 配置为适合长连接的值（建议至少 1 小时）。不可把空闲 WebSocket 当作普通 HTTP 请求在数秒后关闭。
- 容量：单个完整 Text/Binary WebSocket 消息不得超过 9,216 bytes；平台必须在发送前限制大小。

最小 Nginx 反代要点：

```nginx
proxy_http_version 1.1;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
proxy_read_timeout 3600s;
proxy_send_timeout 3600s;
proxy_buffering off;
```

## 2. 会话与可靠性模型

```text
终端连接 ── device.hello ──> 平台
平台缓存命中能力 hash：下发业务配置 / 视图
平台未知能力 hash：device.schema.get ──> 终端
终端 ── device.schema、请求结果 ──> 平台
运行期：平台请求 <──> 终端结果；终端主动事件 / 音频上行
```

终端连接成功后发送：

```json
{"name":"device.hello","body":{"protocol_version":"lcd085.v1","capability_hash":"<sha256>"}}
```

平台以 `(protocol_version, capability_hash)` 缓存 Schema。未命中时发送 `device.schema.get`；终端先发送 `device.schema` 主动事件，随后发送该请求的结果。平台应在收到 `device.hello` 后再开始下发业务状态。

每个终端请求都必须有会话内唯一字符串 `id`：

```json
{"id":"cfg-0001","name":"business.reset"}
{"id":"cfg-0001","ok":true}
```

失败结果为 `{"id":"...","ok":false,"error":"..."}`。终端缓存最近 8 个完成结果；同连接内用相同 `id` 重试不会重复执行。不要将同一 `id` 用于不同业务意图。

终端的所有上行数据由内部单一发送队列串行写出，平台无需为了终端稳定性限制并发请求。但有依赖关系的操作仍应在前一条成功后发送，例如 `display.image.begin` 成功后才能发送 `0x21` 帧，`audio.start` 成功后才能发送 `0x12` 帧。平台应把断线视作一个新会话：等待新的 `device.hello`，并重新下发期望的业务配置和当前视图；不要假设内存态一定保留。

推荐错误处理：`queue_full`、`busy`、`not_ready` 可使用新的 `id` 退避重试；`invalid_request`、`invalid_config`、`unsupported_request` 应修正内容后再提交；`resource_limit` 应降低资源规格。执行中的响应序列遇到错误会停止，已成功的前序响应不回滚。

## 3. 控制面

下列请求均为 Text JSON Frame：

| 类别 | `name` | 用途 |
|---|---|---|
| 能力 | `device.schema.get` | 获取完整能力 Schema |
| 业务 | `business.update` | 整体替换本地事件绑定，最多 16 条 |
| 业务 | `business.reset` | 清除绑定、停止录音、关闭 RGB |
| 业务 | `business.trigger` | 触发 `platform.trigger.<token>` 规则 |
| 展示 | `display.section.set` | 替换主视图 |
| 展示 | `display.image.begin` / `display.image.end` | 一帧 indexed RGB565 图片 |
| 展示 | `display.canvas.open` / `display.canvas.close` | 多帧 Canvas 会话 |
| 音频 | `audio.start` / `audio.stop` | 声明或结束 PCM 上传/下载会话 |
| 系统 | `system.volume.set` / `system.brightness.set` | 0–100 的整数 |
| 系统 | `system.provisioning.start` / `system.reboot` | 进入配网或异步重启 |

### 本地交互规则

`business.update` 的 `bindings` 是完整替换，不是 patch。每条包含唯一 `trigger` 和最多 4 个顺序执行的 `responses`：

```json
{
  "id":"rules-1",
  "name":"business.update",
  "body":{"bindings":[{
    "trigger":"button.plus.short_press",
    "responses":[
      {"name":"feedback.rgb.set","body":{"r":0,"g":96,"b":255,"brightness":35}},
      {"name":"platform.interaction.report","body":{"token":"play-tone"}}
    ]
  }]}
}
```

支持 Trigger：`button.pwr|plus.(down|up|short_press|long_press)` 和 `platform.trigger.*`。支持 Response：`audio.record.start`、`audio.record.stop`、`audio.record.toggle`、`feedback.rgb.set`、`feedback.rgb.off`、`platform.interaction.report`。

终端主动上报格式：

```json
{"name":"platform.interaction","body":{"token":"play-tone"}}
```

平台收到该事件后可按自己的业务逻辑更新界面、请求云端能力，或开始音频下发。不要把物理按键事件误认为平台请求结果；它没有 `id`。

### 主视图

`display.section.set.body.type` 支持：

- `text_section`：`title`（≤64 bytes）、`text`（≤240 bytes）
- `status_section`：`title`、`value`（≤64）、`description`（≤128）
- `metrics_section`：2–4 个 `{label,value,unit?}` 项
- `menu_section`：1–5 个 `{label,token}` 项；PLUS 短按切换，PWR 短按上报所选 token
- `chart_section`：2–32 个 int16 `points`

文本为 UTF-8，但应按字节上限裁剪；不要依赖终端对超长中文自动省略。

## 4. Binary 音频与图像

每个 Binary Frame 的首 byte 是类别，余下是负载：

| 类型 | 首 byte | 前置条件 |
|---|---:|---|
| 终端上行录音 PCM | `0x11` | 本地规则已启动录音 |
| 平台下行播放 PCM | `0x12` | `audio.start(direction=download)` 成功 |
| 图片索引帧 | `0x21` | `display.image.begin` 成功 |
| Canvas 索引帧 | `0x22` | `display.canvas.open` 成功 |

音频仅支持单声道 `pcm_s16le`、`22050 Hz`。下行顺序示例：

```text
audio.start(direction=download, format=pcm_s16le, sample_rate=22050, channels=1)
→ 等待成功结果
→ 多个 Binary [0x12][PCM chunk]
→ audio.stop(direction=download)
```

图片/Canvas 支持 16、32、64、128 的正方形 indexed RGB565 表面，2/4/16 色分别对应 1/2/4 bpp。最大 `128×128×4bpp` 负载为 8,192 bytes；Binary 加类别 byte 后为 8,193 bytes。平台在断线后必须重新 `begin/open`，不能续传旧会话。

## 5. 平台验证模拟器

仓库提供标准库实现的终端模拟器：[lcd085_platform_simulator.py]。它作为 WebSocket 客户端连接平台，模拟 Schema 协商、请求结果、本地触发、主动交互事件和可选 PCM 上行；无需 ESP-IDF、硬件或第三方 Python 包。

```powershell
# 连接生产或测试平台（推荐 wss）
python tools/lcd085_platform_simulator.py wss://platform.example.com/ws --device-id LCD085-SIM-001

# 收到 business.update 后，模拟一次实体按键
python tools/lcd085_platform_simulator.py ws://127.0.0.1:8080/ws --trigger button.plus.short_press

# 测试环境使用自签名证书时才使用
python tools/lcd085_platform_simulator.py wss://localhost/ws --insecure
```

平台建议至少验证：接收 `device.hello`、按 hash 获取 Schema、以不同 `id` 下发 reset/update/view、收到对应结果、下发一次 0x12 PCM、配置 `platform.interaction.report` 后接收模拟按键事件。模拟器日志会打印每个方向的 Text/Binary Frame；它用于平台协议冒烟，不替代 LCD 屏幕、RGB、I2S 和真实按键的硬件验证。

## 6. 本次重构的迁移边界

这不是在旧闭环协议上增加几个命令，而是 LCD_085 专用的端云边界重划。平台原有的业务编排、云端处理、会话管理和设备管理可以继续复用；但**设备适配层、消息模型和 UI 投影层需要按 `lcd085.v1` 重建**。

| 维度 | 旧路径的典型做法 | LCD_085 重构后的契约 | 平台改造要点 |
|---|---|---|---|
| 设备定位 | 平台把一次按键拆成多轮硬件细粒度命令 | 平台预下发有限本地规则，终端立即执行并异步报告业务 token | 把“按键后立即亮灯/录音”等步骤放入 `business.update` 的 response 序列 |
| 业务配置 | 增量修改或多条独立状态命令 | `business.update` 为完整、校验后原子替换 | 平台保存每设备/产品的完整目标规则，不生成局部 patch |
| 业务清理 | 按模块分别关 UI、录音、灯效 | `business.reset` 是集中清理边界 | 场景切换、会话结束或异常恢复先 reset，再配置新状态 |
| 命令确认 | 旧 ACK/状态消息与业务事件可能混用 | 每条请求以 `id` 对应唯一 `{ok,error?}` 结果 | 接入层按 `id` 关联 future/回调，主动事件不可当作 ACK |
| 设备能力 | 平台按设备型号或固件版本硬编码能力 | `protocol_version + capability_hash + device.schema` 协商 | 以 Schema 缓存驱动功能开关，禁止把未知能力直接下发 |
| UI | 通用 UI3/模板/patch/TLV 思路 | LCD_085 单主视图，固定 Section 或 indexed surface | 由平台服务将业务状态投影为一个完整主视图，而非控件 patch |
| 音频 | Base64 或旧音频报文 | `audio.start` + 原始 PCM Binary + `audio.stop` | 音频网关使用二进制数据面，控制面不承载 Base64 |
| 大图/动画 | 通用 UI 协议或自由资源对象 | `begin/open + 0x21/0x22 + end/close`，固定上限 | 媒体服务负责调色板索引化、尺寸裁剪和生命周期控制 |
| 发送并发 | 多业务任务可能各自直接发 WebSocket | 终端统一有界发送队列、单发送者 | 平台可以并发接收/下发；依赖顺序由业务语义而非稳定性要求决定 |

因此，旧 `cmd/control`、旧 ACK、UI3 Section patch、UI3 TLV 和 Base64 音频**不是 `lcd085.v1` 的兼容输入**。若平台需要一段灰度期，应在服务端按设备能力 hash/产品型号选择旧适配器或 LCD_085 适配器；不要把两套报文混发到同一个 LCD_085 会话，也不要尝试在终端做格式转换。

### 6.1 职责重新划分

```text
平台：业务真相、账号/会话、云端推理、持久化、跨设备协作、内容生成
  │
  ├─ 完整本地规则 ───────> 终端：按键/RGB/录音等有限即时响应
  ├─ 一个完整主视图 ─────> 终端：LCD_085 专用渲染
  ├─ PCM / 图片 / Canvas ─> 终端：受限数据面消费
  │
  └<─ interaction / PCM ── 终端：业务 token 与录音数据上行
```

终端不解释 token 的业务含义，不执行开放脚本、条件分支、循环或跨云等待；平台也不应依赖每个按键的低层时序去维持本地即时体验。一个典型闭环是：

```text
平台下发：PLUS short → RGB 蓝色 → 上报 play-tone
用户按 PLUS
终端立即点亮 RGB，并上报 platform.interaction(play-tone)
平台执行云端业务
平台下发：新的 text_section + audio.start + 0x12 PCM + audio.stop
```

## 7. 平台设备会话模型

平台应为每个 `deviceId` 保留下列状态。它们是接入层状态，不要求全部写入业务数据库，但不得混同。

| 状态 | 归属 | 建议持久化 | 说明 |
|---|---|---:|---|
| `deviceId`、产品类型、协议版本 | 设备管理 | 是 | 从 WebSocket query 与 `device.hello` 建立身份与路由 |
| capability hash / Schema | 能力缓存 | 是 | hash 未变化时可复用；Schema 解析失败不得启用能力 |
| 连接实例、最近活动时间 | 接入层 | 否 | 每次重连替换，旧实例不得继续下发 |
| 已发送请求的 `id` 与结果 | 请求关联层 | 短期 | 至少覆盖平台超时重试窗口 |
| 完整业务规则 | 业务状态 | 是 | `business.update` 的来源；重连和重启后可重新投影 |
| 当前主视图描述 | 业务/UI 投影层 | 视业务而定 | 用于重连恢复，不要依赖终端屏幕仍保留旧帧 |
| 活跃下行音频/表面传输 | 流会话层 | 否 | 与具体连接绑定；断线后中止、重建，不续传 |

推荐连接处理顺序：

1. 以 URL query 的 `deviceId` 定位设备，并替换旧连接实例。
2. 收到 `device.hello` 后校验 `protocol_version`，按 hash 读取或获取 Schema。
3. 将连接标记为 ready；恢复当前业务所需的完整规则和主视图。
4. 仅在前置请求成功后启动依赖的音频、图片或 Canvas 流。
5. 断线时取消该连接的流式任务，保留“期望业务状态”；不要向旧 socket 写入。

`device.hello` 是本协议的就绪信号，不是遥测或业务事件。平台在其之前收到的连接不应参与业务派发。

### 7.1 请求、主动事件与 Binary 的判别

| 入站方向 | 形态 | 平台处理方式 |
|---|---|---|
| 平台 → 终端 | Text `{id,name,body?}` | 请求；为每次发送登记 `id` 与超时 |
| 终端 → 平台 | Text `{id,ok,error?}` | 请求结果；完成对应 pending request |
| 终端 → 平台 | Text `{name,body}` | 主动事件；按 `name` 路由，不得匹配 request `id` |
| 双向 | Binary `[type][payload]` | 必须由已成功的控制面会话授权 |

平台可同时维护多个无依赖请求，但应避免对同一个业务对象制造互相覆盖的写入。例如两个 `display.section.set` 都成功时，最终显示哪一页取决于到达顺序；平台若需要确定结果，就应在前一个成功后再下发下一个。这是**业务一致性**要求，而非终端并发稳定性限制。

## 8. UI 迁移与显示所有权

LCD_085 是 128×128、无触摸、PWR/PLUS 两个业务按键的固定产品，不再承载跨设备通用 UI 抽象。平台 UI 层应从“发送控件变化”切换为“生成 LCD_085 的完整投影”。

### 8.1 显示状态机

```text
启动 / 配网 / Wi-Fi 未就绪 ──> 系统 UI
WS 已连接但未有业务主视图 ───> 等待平台 UI
display.section.set / image / canvas ─> 平台业务主视图（平台独占）
business.reset 或确认断联 ────> 安全等待 UI
```

- 业务主视图建立后，网络短暂波动、录音、播放和普通错误不应由终端系统页面抢占；平台应使用新的主视图或 RGB/音频反馈表达业务状态。
- `display.section.set`、图片和 Canvas 互斥；任一新的主视图操作都会终止正在进行的表面会话。
- `menu_section` 会消费 PLUS/PWR 短按，平台不应同时配置同一两个触发器期待业务规则也被执行。
- 复杂图表、富文本、任意布局和动效不属于 Section 能力。平台应简化为现有 Section，或离线渲染为 indexed image/Canvas。

### 8.2 UI 映射建议

| 业务意图 | LCD_085 投影 | 不应使用 |
|---|---|---|
| 单个状态、进度或连接结果 | `status_section` | 旧 progress/hero patch |
| 2–4 个核心指标 | `metrics_section` | 可无限扩展的仪表板 |
| 等待、告警、云端处理说明 | `text_section` | 富文本、弹窗栈、浮层 |
| 少量可选动作 | `menu_section` + token | 触摸、滚动列表、嵌套导航 |
| 单序列趋势 | `chart_section` | 交互图表、多轴图、缩放 |
| 复杂视觉结果 | `display.image` 或 `display.canvas` | 将 RGB565/Base64 直接塞入 JSON |

UI 下发成功仅表示终端已接受并完成渲染请求，不表示用户已经看见或确认内容。用户确认必须由菜单 token、业务交互 token 或后续云端状态表达。

## 9. 交互、音频与上报迁移

### 9.1 交互规则不是事件订阅

旧闭环中，平台可能先等待按键上报、再决定是否录音/亮灯。新模型中 `business.update` 是**命令式本地能力授权**：它把允许的即时动作预装到终端。平台收到 `platform.interaction` 时，应该将其理解为“本地序列已走到指定业务节点”，而不是要求终端重新执行刚完成的动作。

建议为 token 使用稳定、业务语义明确的命名，例如 `voice.capture.started`、`assistant.reply.requested`、`menu.settings.opened`。token 最大 95 bytes；平台负责版本管理和含义解释。

### 9.2 音频状态和上报

- 录音与播放互斥。播放期间请求录音、录音期间请求播放会得到 `audio_busy`，平台必须先下发/等待停止。
- 录音启动后，终端使用 Binary `0x11` 上送 PCM；平台以会话状态识别归属，而不是将 Binary 当作任意文件块。
- 下行播放使用 `0x12`，只接受 `pcm_s16le/22050/mono`。平台应以 20ms 左右的 chunk 发送，不能把整段音频作为超长单帧。
- `business.reset` 会停止录音和播放。断线不会把未完成 Binary 资源迁移到新连接；平台应在新连接按业务需要重新开始流。

首期未定义跨连接 `stream_id` 或可靠媒体确认。平台对录音/播放应按会话超时和业务取消处理，不应将旧连接残留帧归属到重连后的新会话。

### 9.3 可观测性

平台接入日志至少记录：`deviceId`、连接实例 ID、`protocol_version`、capability hash、请求 `id/name`、结果 `ok/error`、主动事件 token、Binary 类型/字节数、关闭原因和重连次数。不要记录 PCM 原文、Wi-Fi 凭据或用户敏感文本。

建议指标：在线设备数、`device.hello` 后 ready 耗时、请求成功率、`queue_full/busy/audio_busy` 分布、主动事件延迟、上/下行音频字节数、图片/Canvas 传输失败率与异常断线率。

## 10. 平台验收清单

### 必须完成

- [ ] 以 `deviceId` 绑定连接，并在 `device.hello` 后才允许业务下发。
- [ ] 实现 Schema hash 缓存与 `device.schema.get` 回退；未知能力不下发。
- [ ] 请求关联以 `id` 完成；主动事件和 Binary 不进入 ACK 逻辑。
- [ ] 平台保存完整规则并用 `business.update` 全量替换，不发送局部 patch。
- [ ] 场景切换采用 `business.reset → 成功 → business.update → 成功 → 主视图`。
- [ ] 实现 `platform.interaction` token 路由，以及菜单 token 的业务处理。
- [ ] 音频、图片、Canvas 在控制请求成功后才发送 Binary，断线立即取消流任务。
- [ ] WSS 反代完整透传 Upgrade 且没有短读写超时。
- [ ] 使用模拟器完成协议冒烟，并用真实 LCD_085 完成按键、屏幕、RGB、录音和扬声器验收。

### 不应出现

- [ ] 未等 `device.hello` 就向连接下发业务命令。
- [ ] 复用同一个 `id` 表示不同请求。
- [ ] 通过旧 UI3/旧 ACK/旧 Base64 音频消息控制 LCD_085。
- [ ] 在 `audio.start`、`image.begin`、`canvas.open` 失败后继续发送 Binary。
- [ ] 将 `platform.interaction` 当作请求结果，或把 Binary 音频当作 JSON/文件上传。
- [ ] 在 `menu_section` 显示期间假定相同按键还会执行业务绑定。
