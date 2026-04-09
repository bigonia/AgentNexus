# SDUI 终端应用开发指南（当前版本）

> 版本范围：基于当前 Java 工程代码（`com.zwbd.agentnexus.sdui.*`）的真实实现，不再沿用旧版“大 Python 脚本全包”模型。

## 1. 文档目标

本指南用于帮助开发者在**当前架构**下快速开发可运行的 SDUI 应用，明确：

1. 哪些能力由系统固定提供（不需要应用重复实现）。
2. 哪些能力由应用脚本灵活定义（AI 生成或人工编写）。
3. 终端交互链路中的协议、资源、状态与约束。

## 2. 当前架构总览

当前系统是“三层职责”模型：

1. 终端层（ESP 设备）
- 接收 `ui/layout`、`ui/update`、`cmd/control`。
- 上报 `telemetry/heartbeat`、`ui/click`、`ui/change`、`ui/page_changed`、`audio/record`、`motion`、`cmd/control_ack`。

2. 平台层（Java 后端，系统职责）
- WebSocket 接入、路由、会话管理。
- 设备注册/认领/在线状态维护。
- 应用版本管理、发布绑定、运行时调度。
- Python Worker 进程池管理。
- 资产预处理与运行时上下文注入。
- 协议封装与下发、控制指令 ACK 跟踪。

3. 应用层（Python 脚本，业务职责）
- `on_start(ctx)`：返回首屏布局。
- `on_event(ctx, event)`：处理交互并返回 `update/layout/noop`。
- 使用 `cap.store` 做会话状态，使用 `cap.asset_*` 读取资产元数据。

## 3. 职责边界（最重要）

## 3.1 系统已完成职责（固定流程）

以下功能已由平台完成，应用脚本不应重复造轮子：

1. 终端连接与路由
- WebSocket 入口：`/ws/sdui`。
- 统一消息信封解析：`topic + device_id + payload`。
- Topic 到 Handler 的路由分发。

2. 设备生命周期
- 首次心跳自动建档。
- 未认领设备自动下发注册页（包含 claim code）。
- 已认领但未发布应用时自动下发等待页。
- 在线/离线状态刷新与心跳遥测入库。

3. 应用发布与激活
- 应用版本与设备绑定（active binding）。
- 发布时自动调用 `on_start` 并下发布局。
- 写入 `currentAppId/currentPageId`。

4. 运行时调度
- Python Worker 池（多进程）执行脚本。
- 超时、异常捕获、错误回传。
- `store` 回写（Java 保存每个 app+device 的状态）。

5. 协议封装
- `sendLayout(deviceId, payload)` -> `ui/layout`。
- `sendUpdate(deviceId, payload)` -> `ui/update`。
- `sendControl(deviceId, payload)` -> `cmd/control`。

6. 资源处理与注入
- 资产注册时自动预处理（图片缩放、音频转码、透传）。
- 运行时通过 `ctx.assets` 注入资产列表。

7. 控制指令闭环
- 设备控制命令落库（`SENT/ACKED/REJECTED/ERROR/TIMEOUT`）。
- 接收 `cmd/control_ack` 回写状态。

## 3.2 应用脚本职责（灵活定义）

应用需要定义的核心只有两件事：

1. UI 与交互编排
- 根据业务场景产出首屏结构。
- 按事件驱动局部更新（优先 `update`）。

2. 业务状态机
- 使用 `cap.store` 维护每台设备上的应用状态。
- 根据事件和状态输出下一步动作。

## 4. 终端交互链路

## 4.1 启动与首屏

1. 设备连接后发送 `telemetry/heartbeat`。
2. 平台登记会话并判断设备状态。
3. 若设备已发布应用：
- Java 构建 `ctx`。
- 调用脚本 `on_start(ctx)`。
- 将返回的 `layout` 下发到终端。

## 4.2 事件处理

1. 终端上报事件（如 `ui/click`）。
2. Java 根据 active binding 找到应用版本。
3. Java 组装 `ctx + event` 调用 `on_event`。
4. 脚本返回：
- `action=update`：下发 `ui/update`（自动补 revision/page_id）。
- `action=layout`：下发 `ui/layout`（整页重建）。
- `action=noop`：不下发。

## 4.3 支持的上行 Topic（当前代码）

1. `telemetry/heartbeat`
2. `ui/click`
3. `ui/change`
4. `ui/page_changed`
5. `audio/record`
6. `motion`
7. `cmd/control_ack`

## 5. 应用脚本运行时契约

## 5.1 必须实现函数

```python

def on_start(ctx):
    ...
    return {"action": "layout", "page_id": "home", "payload": {...}}


def on_event(ctx, event):
    ...
    return {"action": "update", "page_id": "home", "payload": {...}}
```

## 5.2 `ctx` 字段

`ctx` 至少包含：

1. `device_id`
2. `app_id`
3. `device_state`
4. `assets`
5. `store`
6. `topic`（仅 on_event 注入）

## 5.3 全局能力对象 `cap`

Python Worker 会注入全局对象 `cap`：

1. `cap.store.get(key, default=None)`
2. `cap.store.set(key, value)`
3. `cap.asset_get(asset_id)`
4. `cap.asset_search(tags, limit=5)`
5. `cap.device_get_state()`

## 5.4 脚本返回动作约束

允许动作仅三种：

1. `layout`
2. `update`
3. `noop`

建议：

1. `on_start` 固定返回 `layout`。
2. `on_event` 常规返回 `update/noop`，仅在结构大变更时返回 `layout`。

## 5.5 Python 沙箱约束

当前 Worker 为受限环境：

1. 不要依赖 import 外部库。
2. 不要使用文件/网络/子进程访问。
3. 只使用纯逻辑与字典操作构建返回 JSON。

## 6. UI 协议与组件约束

## 6.1 布局协议

1. 根 `payload` 建议包含 `children`。
2. 组件使用 SDUI 类型：
- `container`
- `label`
- `button`
- `image`
- `bar`
- `slider`
- `scene`
- `overlay`
- `viewport`
- `widget`

3. 字段风格使用协议键：
- 布局：`w/h/flex/justify/align_items/pad/gap`
- 文本：`text/font_size/text_color`
- 行为：`on_click`

## 6.2 Update 协议

1. 推荐直接 patch：
```json
{"id":"title","text":"处理中..."}
```
2. 若使用 `ops[]`：
- 平台会补齐 `revision`（缺失时）。
- 平台会补齐 `transaction=true`（缺失且包含 ops 时）。

## 6.3 兼容性说明

平台对旧式 DSL 有有限“纠偏”能力（如 `column/row/text/props` -> 协议字段映射），但仅用于兼容，不建议依赖。

## 7. 资源（Asset）开发模型

## 7.1 资源生命周期

1. 上传文件（知识文件）。
2. 注册为 SDUI 资产（`assetType/name/tags`）。
3. 平台自动预处理并存 `processedPayload`。
4. 创建资源集合（Asset Set），并把资源加入集合。
5. 应用生成时选择 `assetSetIds`，仅注入所选集合的资源上下文。
6. 发布/运行时通过 `ctx.assets` 注入给脚本（平台会将选中集合资源绑定到应用上下文）。

## 7.2 `ctx.assets` 典型字段

每项资产通常包含：

1. `asset_id`
2. `asset_type`
3. `usage_type`
4. `name`
5. `tags`
6. `file_id`
7. `processed_status`
8. `processed_payload`
9. `original_filename`（若可用）
10. `stored_filename`（若可用）

## 7.3 应用侧建议

1. 脚本只消费 `processed_payload` 元数据，不直接做重处理。
2. 对资源缺失要兜底（无资源时展示 placeholder）。

## 8. 系统接口（管理面）快速索引

REST 基础路径：`/api/v1/sdui`

1. 应用
- `POST /apps/generate`
- `POST /apps/{appId}/revise`
- `GET /apps`
- `GET /apps/{appId}/versions`
- `POST /apps/{appId}/publish`

2. 设备
- `GET /devices`
- `GET /devices/unclaimed`
- `POST /devices/{deviceId}/claim`
- `GET /devices/{deviceId}/telemetry`
- `POST /devices/{deviceId}/control`

3. 资源
- `POST /assets`
- `GET /assets/source-files`
- `GET /assets`
- `POST /apps/{appId}/assets:bind`
- `GET /apps/{appId}/assets`
- `DELETE /apps/{appId}/assets/{bindingId}`

4. 运维
- `GET /runtime/workers`
- `GET /ops/overview`

## 9. AI 生成应用时的建议约束模板

建议你在生成要求里固定写入以下约束：

1. 仅输出 Python 脚本：实现 `on_start/on_event`。
2. `on_start` 返回 `layout`，`on_event` 优先 `update`。
3. 组件仅使用协议支持类型与字段，不使用 Flutter/React DSL。
4. 使用 `cap.store` 维护状态，不使用 import。
5. 至少包含：标题、主操作按钮、状态反馈区域。
6. 所有可交互组件使用稳定 `id`，并绑定 `server://...` 行为。

## 10. 开发者最小实践（5 分钟起步）

1. 调用 `POST /apps/generate` 生成初版。
2. 查看 `validationReport`，不为 `OK` 则 `revise`。
3. 给应用绑定资产（可选）。
4. 调用 `POST /apps/{appId}/publish` 到目标设备。
5. 在终端触发点击/滑动，观察事件是否驱动 `ui/update`。
6. 通过 `/runtime/workers` 与 `/ops/overview` 排查运行状态。

## 11. 常见问题与排查

1. 发布成功但设备不显示业务页面
- 先看设备是否 `CLAIMED` 且存在 active binding。
- 再看 `on_start` 是否返回 `layout + payload`。

2. 点击无反应
- 检查按钮 `on_click` 是否为 `server://...`。
- 检查脚本 `on_event` 是否识别 payload 字段（如 `action`）。

3. 更新乱序或不生效
- 使用 `ui/update` 时带 `page_id/revision`。
- 若脚本未带，平台会尝试补齐，但建议应用显式维护。

4. 资源可见但无法使用
- 检查资产是否已 `bind` 到 app。
- 检查 `processed_status` 是否 `READY`。

## 12. 与旧版本开发方式的差异总结

1. 旧版：单体 Python 同时负责连接、路由、状态、资源、UI。
2. 当前：连接与平台能力已系统化，应用脚本只负责业务状态机与 UI 编排。
3. 旧版文档中“系统层职责”不再由脚本承担。
4. 新版开发的关键是遵守运行时契约，并把脚本做成纯函数式事件处理器。

---

如果你要继续维护旧文档，建议把它们拆成两类：

1. 协议与组件规范（继续复用）。
2. 旧运行模型说明（标注为 Legacy，避免混入当前 AI 生成提示）。

## 13. 配置建议（当前版本可落地）

以下配置是“稳定优先”的推荐值，可作为团队默认基线。

## 13.1 运行时配置（`application.yml`）

1. `sdui.runtime.worker-count`
- 建议：开发环境 `2`，联调/压测环境 `4-8`。
- 原则：优先保证无超时，再追求吞吐。

2. `sdui.runtime.request-timeout-ms`
- 建议：默认 `800`，复杂脚本可提高到 `1200-1500`。
- 原则：不要无限放大超时，应优先优化脚本事件分支和 update 粒度。

3. `sdui.runtime.python-command`
- 建议：固定到明确解释器路径，避免环境漂移。

4. `sdui.command.timeout-scan-ms`
- 建议：`3000` 保持默认即可。

## 13.2 资源处理配置

1. `sdui.asset.image-max-width` / `image-max-height`
- 建议：默认 `200x200`。
- 原则：首屏核心图可适当放宽，但避免频繁大图重下发。

2. `sdui.asset.processed-dir`
- 建议：独立目录并定期清理。
- 原则：开发环境可按天清，线上建议带版本目录。

3. `sdui.asset.ffmpeg-command`
- 建议：固定可执行路径，避免系统 PATH 差异导致失败。

## 13.3 发布与联调配置建议

1. 先用单设备做脚本验证，再批量发布。
2. 新版本先绑定测试设备组，稳定后推广。
3. 发布后优先验证 `on_start -> layout` 与 2-3 个关键交互分支。

## 14. SDUI 开发约束（建议作为团队规范）

本节用于约束 AI 生成与人工编写，目标是提高“首版可用率”。

## 14.1 布局结构约束

1. 首屏必须有稳定根容器：`container(id=root)`。
2. 页面建议采用“三层结构”：
- 背景层（`scene/container`）。
- 内容卡片层（`container`）。
- 反馈层（状态文字/进度/提示）。

3. 每个交互组件必须有稳定 `id`，不得随机命名。
4. 首屏建议组件数 `4-9`，超出时应分页或折叠。

## 14.2 交互与状态约束

1. `on_start` 只做初始化，不做复杂分支。
2. `on_event` 先解析 `event.action`，未知动作必须 `noop`。
3. 所有状态读写走 `cap.store`，不要把状态硬编码在分支文本里。
4. 常规交互优先 `update`，仅结构切换使用 `layout`。

## 14.3 协议约束

1. 仅允许动作：`layout/update/noop`。
2. 仅使用协议组件类型，不使用 `column/row/text/props` DSL。
3. 更新 payload 必须可定位目标组件（含 `id` 或 `ops` 目标）。
4. 使用 `ops` 时应显式带 `page_id`；平台会补 revision，但业务应主动维护页面语义。

## 14.4 资源使用约束

1. 资源读取只通过 `ctx.assets` / `cap.asset_*`。
2. 当资源不存在或未 READY 时，必须展示兜底 UI。
3. 脚本内不得做音视频转码、图片处理等重任务。

## 14.5 可维护性约束

1. 事件分支建议不超过 8 个主分支；超过时拆函数。
2. 文案集中定义，避免魔法字符串散落。
3. `id` 命名建议：`page_block_widget`（如 `home_header_title`）。

## 15. 旧文档与旧脚本复用策略（非迁移，提质复用）

## 15.1 可直接复用

1. 协议与字段语义：`EXAMPLE_LIBRARY.md`、`BACKEND_DEV_GUIDE.md` 中的组件字段、topic 语义。
2. 视觉与结构范式：卡片布局、层次化标题区、按键组、状态反馈区。
3. 交互节奏：`layout` 初始化 + 高频 `update` 的模式。

## 15.2 需选择性复用

1. 旧脚本中的主题系统、布局 token、动画节奏。
2. 旧脚本中的 page/revision 管理经验。
3. 旧脚本中的多场景切换策略。

## 15.3 不应复用（当前架构下）

1. 旧版“脚本自管连接/路由/会话”的逻辑。
2. 旧版“脚本直接做系统级资源处理”的逻辑。
3. 与当前 `ctx/cap` 契约不兼容的能力调用。

## 16. 参考脚本提炼出的高质量范式（来自 `docs/script`）

## 16.1 视觉范式

1. 使用统一 token（颜色、字号、间距、圆角）保证一致性。
2. 场景层与内容层分离，避免信息与特效互相干扰。
3. 主次信息颜色对比明确，避免全局同亮度。

## 16.2 交互范式

1. 首屏只给 1-3 个主操作，其他操作放次级区。
2. 高频刷新字段采用局部 update（如 `value/text/opa/x/y`）。
3. 重建布局前有明确触发条件（翻页、模式切换、场景大改）。

## 16.3 稳定性范式

1. 未初始化状态先输出安全占位布局。
2. 事件 payload 缺字段时走兜底分支，不抛异常。
3. 所有分支都返回合法动作 dict。

## 17. AI 生成约束模板（推荐直接投喂）

可将以下规则作为“系统约束+开发规范”输入生成器：

1. 输出内容
- 只输出 Python 脚本。
- 必须包含 `on_start(ctx)`、`on_event(ctx, event)`。

2. 运行时契约
- 仅使用 `ctx` 与全局 `cap` 能力。
- 不使用 import/open/eval/exec/网络/文件操作。

3. UI 约束
- 组件只用 SDUI 支持类型。
- 首屏采用背景层 + 卡片层 + 反馈层。
- 每个交互组件必须配置 `on_click`。

4. 行为约束
- `on_start` 返回 `layout`。
- `on_event` 默认返回 `update/noop`。
- 未识别动作返回 `noop`。

5. 质量约束
- 结构清晰、ID 稳定、文案简洁。
- 优先局部更新，避免每次事件重建整页。

## 18. 开发与收敛流程（面向“优秀模板”沉淀）

这是一套无需产品化评分系统、但可持续提质的工程流程。

## 18.1 第 0 步：定义场景规格卡

每个应用先写一页规格卡：

1. 场景目标（用户要完成什么）。
2. 首屏必备组件（最多 9 个）。
3. 关键事件列表（最多 8 个）。
4. 状态变量清单（`cap.store` key 列表）。
5. 资源依赖（asset type + usage type）。

## 18.2 第 1 步：生成首版

1. 基于规格卡调用 `generate`。
2. 若 `validationReport != OK`，直接 `revise`，不发布。

## 18.3 第 2 步：三轮人工快测

1. 首屏质量：布局层次、文本可读性、关键操作可见。
2. 交互质量：主按钮、次按钮、异常输入分支。
3. 稳定性：连续点击、切页、资源缺失兜底。

## 18.4 第 3 步：固定为模板

满足以下条件即可沉淀模板：

1. 同一脚本在至少 2 台设备表现稳定。
2. 至少覆盖 5 个关键事件且无明显退化。
3. 资源缺失或异常输入时仍可操作。

## 18.5 第 4 步：模板库维护

模板建议字段：

1. 适用场景标签。
2. UI 结构说明。
3. 事件/状态键清单。
4. 资源依赖说明。
5. 可调参数（颜色、文案、布局密度）。

## 19. 可直接采用的“优秀模板”骨架

```python

def on_start(ctx):
    state = ctx.get("store", {})
    title = state.get("title", "SDUI App")
    status = state.get("status", "Ready")
    return {
        "action": "layout",
        "page_id": "home",
        "payload": {
            "safe_pad": 14,
            "children": [
                {
                    "type": "scene",
                    "id": "home_scene",
                    "bg_color": "#081420"
                },
                {
                    "type": "container",
                    "id": "home_card",
                    "w": "92%",
                    "h": "88%",
                    "align": "center",
                    "pad": 12,
                    "radius": 20,
                    "bg_color": "#13263A",
                    "border_w": 1,
                    "border_color": "#5FB2FF70",
                    "flex": "column",
                    "gap": 8,
                    "children": [
                        {"type": "label", "id": "title", "text": title, "font_size": 22, "text_color": "#E8F3FF"},
                        {"type": "label", "id": "status", "text": status, "font_size": 13, "text_color": "#9AB8D5"},
                        {"type": "button", "id": "btn_primary", "text": "执行", "w": 110, "h": 42, "on_click": "server://ui/click?action=run"}
                    ]
                }
            ]
        }
    }


def on_event(ctx, event):
    action = (event or {}).get("action", "")
    if action == "run":
        cap.store.set("status", "Running")
        return {
            "action": "update",
            "page_id": "home",
            "payload": {"id": "status", "text": "Running", "text_color": "#FFD971"}
        }
    return {"action": "noop", "page_id": "home", "payload": {}}
```

## 20. 文档维护建议

1. 将本文作为“当前版本唯一开发基线”。
2. 将旧版文档标记 `Legacy`，仅保留可复用协议部分。
3. 每次新增能力（新 topic、新组件、新 cap 接口）同步更新本文件。
4. 每沉淀一个优秀模板，在本文件附录补“适用场景 + 限制 + 参数”。

## 21. 配套文档索引（建议一起使用）

1. [APP_TEMPLATE_PACKS.md](d:/src/AgentNexus/docs/sdui/current/APP_TEMPLATE_PACKS.md)
- 三类模板包：展示类、控制类、媒体类。
- 每类含场景、状态键、事件规范、样例骨架、验收要点。

2. [AI_PROMPT_SNIPPETS.md](d:/src/AgentNexus/docs/sdui/current/AI_PROMPT_SNIPPETS.md)
- generate/revise 可直接复制的 Prompt 片段。
- 包含场景规格卡与输出自检清单。

3. [FRONTEND_UPDATE_GUIDE.md](d:/src/AgentNexus/docs/sdui/current/FRONTEND_UPDATE_GUIDE.md)
- 前端改造说明：资产集合管理、生成参数变更、联调流程与兼容策略。

4. [EXAMPLE_LIBRARY.md](d:/src/AgentNexus/docs/sdui/reference/EXAMPLE_LIBRARY.md)
- 协议字段与组件示例库（作为组件语义字典）。

5. [BACKEND_DEV_GUIDE.md](d:/src/AgentNexus/docs/sdui/reference/BACKEND_DEV_GUIDE.md)
- 终端协议细节与更新机制（用于深度联调）。
