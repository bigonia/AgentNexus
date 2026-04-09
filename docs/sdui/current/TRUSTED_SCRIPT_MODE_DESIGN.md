# SDUI 受信任脚本模式系统设计（Trusted Script Mode）

> 目标：在保留复杂业务脚本能力（资源处理、TTS/STT、外部服务调用）的前提下，实现平台级应用管理、发布下发、回滚、审计与运维可控。

## 1. 设计目标与非目标

### 1.1 设计目标

1. 设备连接与协议下发统一由平台管理，不再由脚本管理 WebSocket。
2. 脚本以受信任进程方式运行，允许处理复杂资源逻辑（如 ffmpeg、TTS/STT、本地缓存）。
3. 平台提供应用化能力：上传、版本、发布、灰度、回滚、审计、熔断。
4. 平台和脚本形成稳定契约，支持脚本 SDK 标准化接入。

### 1.2 非目标

1. 本方案不覆盖不受信任脚本的沙箱设计细节。
2. 本方案不强制脚本完全去资源化（资源处理可继续在脚本内实现）。
3. 本方案不要求一次性迁移所有历史 server 脚本。

## 2. 关键原则

1. 连接收口：终端 WebSocket 只进平台网关。
2. 逻辑下放：复杂业务流程（音乐、TTS/STT）由受信任脚本实现。
3. 治理上收：生命周期、限流、审计、告警、熔断由平台控制。
4. 双轨并行：保留现有沙箱脚本模式，新增受信任脚本模式。
5. 本方案不采用 Legacy 透传过渡，不保留“脚本自持设备 socket”路径。

## 2.1 固化决策（实施约束）

1. 受信任脚本数据面协议固定为 `JSON-RPC over stdio`（NDJSON 单行 JSON）。
2. 不采用“平台将设备 socket 透传给脚本”的兼容方案。
3. 所有新接入受信任脚本必须按统一入口契约改造，不做后端自动改写脚本代码。

## 3. 总体架构

### 3.1 组件

1. `SDUI Gateway`
- 维护设备 WebSocket 会话。
- 统一处理上行 topic（heartbeat/ui/click/audio/..）。
- 负责向设备下发 `ui/layout`、`ui/update`、`audio/play`、`cmd/control`。

2. `Trusted Script Manager`
- 脚本包上传与校验。
- 版本管理与实例生命周期管理（启动/停止/重启/探活）。
- 异常重启与熔断。

3. `Script Runtime Bridge`
- 平台与脚本进程的数据面通信层。
- 固定协议：JSON-RPC（stdio，行分隔 JSON / NDJSON）。

4. `App Registry`
- 应用、版本、发布绑定、实例状态、部署策略。

5. `Observability & Audit`
- 日志、链路追踪、指标、审计记录。

### 3.2 边界定义

1. 平台负责：连接、路由、会话、设备在线状态、协议封包、发布回滚、治理。
2. 脚本负责：业务状态机、资源处理、第三方服务调用、复杂媒体逻辑。
3. 禁止：脚本直接管理设备侧 WebSocket 连接。

## 4. 运行模式

### 4.1 模式分类

1. `SANDBOX_SCRIPT`（现有）
- 受限运行，适用于简单 UI/状态逻辑。

2. `TRUSTED_SCRIPT`（新增）
- 进程级运行，开放资源能力，适用于复杂业务链路。

### 4.2 应用入口模式

在 `sdui_app.entryMode` 扩展支持：

1. `PYTHON_SCRIPT`（现有）
2. `TRUSTED_SCRIPT`（新增）

## 5. 脚本包规范

### 5.1 包结构（zip）

1. `manifest.yaml`（必需）
2. `main.py`（必需）
3. `requirements.txt`（可选）
4. `resources/`（可选）

### 5.2 manifest 字段建议

1. `app_id`
2. `version`
3. `entry_mode: trusted_script`
4. `entrypoint: main.py`
5. `runtime: python3.11`
6. `health_check`（如 `rpc_ping` 或本地 http path）
7. `topics_in`（脚本期望接收）
8. `topics_out`（脚本可能输出）
9. `resource_mounts`（声明可访问目录）
10. `network_policy`（外部访问白名单）
11. `env_schema`（必需环境变量定义）

### 5.3 脚本入口契约

1. `on_start(ctx)`
2. `on_event(ctx, event)`
3. `on_stop(ctx)`（可选）
4. `health()`（可选）

## 6. 平台与脚本交互协议

## 6.1 通信方式

1. 控制面：平台进程管理（start/stop/restart/health）。
2. 数据面：JSON-RPC（固定为 `stdio`）。
3. 本设计不引入本地 HTTP/gRPC 作为主数据面协议。

## 6.2 请求格式（示例）

```json
{
  "request_id": "req-uuid",
  "app_instance_id": "inst-uuid",
  "device_id": "dev-001",
  "method": "on_event",
  "ctx": {
    "app_id": "app-123",
    "device_state": {"status": "ONLINE"},
    "assets": [],
    "store": {}
  },
  "event": {
    "topic": "ui/click",
    "payload": {"action": "play"}
  },
  "timeout_ms": 1200
}
```

## 6.3 响应格式（示例）

```json
{
  "request_id": "req-uuid",
  "ok": true,
  "actions": [
    {
      "type": "ui_layout",
      "payload": {"children": []}
    },
    {
      "type": "audio_play",
      "payload": {"encoding": "base64_pcm_s16le_16000_mono", "data": "..."}
    }
  ],
  "store": {"is_playing": true},
  "error": null
}
```

## 6.4 动作类型建议

1. `ui_layout` -> 平台映射到 `ui/layout`
2. `ui_update` -> 平台映射到 `ui/update`
3. `audio_play` -> 平台映射到 `audio/play`
4. `send_topic` -> 平台按白名单转发自定义 topic
5. `noop`

## 6.5 协议与性能约束

1. 请求与响应均为单行 JSON，禁止多行 payload。
2. 高频数据（如音频）必须分片（建议 4KB-8KB）并支持中断 token。
3. 同一次响应可返回 `actions[]` 批量动作，减少往返开销。
4. 平台对单次请求设置超时与最大报文大小，超限直接失败并审计。

## 7. 生命周期与进程治理

### 7.1 状态机

1. `CREATED`
2. `STARTING`
3. `RUNNING`
4. `DEGRADED`
5. `RESTARTING`
6. `STOPPED`
7. `FAILED`

### 7.2 启停策略

1. 发布时按设备绑定创建或复用实例。
2. 实例异常退出按退避策略重启（如 1s/3s/10s）。
3. 达到阈值进入熔断，停止自动重启并告警。

### 7.3 健康检查

1. 进程存活检查。
2. 协议健康检查（`rpc_ping`）。
3. 业务健康指标（超时率/错误率阈值）。

## 8. 发布、灰度与回滚流程

### 8.1 发布

1. 上传脚本包并创建版本。
2. 静态校验通过后进入可发布状态。
3. 选择目标设备发布并绑定版本。
4. 实例启动成功后切流。

### 8.2 灰度

1. 支持按设备集合分批发布（5%/20%/100%）。
2. 每批观察错误率与延迟指标。
3. 不达标自动停止后续批次。

### 8.3 回滚

1. 一键切换到上一个稳定版本。
2. 停止故障版本实例。
3. 保留回滚审计记录与故障快照。

## 9. 安全与合规

1. 仅允许受信任签名包运行。
2. 每实例独立运行身份与工作目录。
3. 资源目录最小权限挂载（只读优先）。
4. 外部网络白名单（TTS/STT/LLM 域名）。
5. 记录审计：谁在何时发布了哪个版本到哪些设备。

## 10. 可观测性设计

### 10.1 指标

1. `script_request_qps`
2. `script_request_p95_ms`
3. `script_timeout_rate`
4. `script_error_rate`
5. `instance_restart_count`
6. `audio_out_bytes_per_sec`

### 10.2 日志

1. 应用日志：脚本 stdout/stderr 按 `app_id/version/device_id` 归档。
2. 平台日志：路由、下发、失败原因、重试轨迹。
3. 审计日志：上传、发布、回滚、熔断操作。

### 10.3 追踪

1. 全链路 `trace_id/request_id` 贯通：设备消息 -> 平台转发 -> 脚本处理 -> 下发。

## 11. 数据模型（新增建议）

1. `trusted_script_package`
- `id, app_id, version_no, package_uri, manifest_json, signature, status, created_at`

2. `trusted_script_deploy`
- `id, app_id, version_no, device_id, status, rollout_batch, created_at`

3. `trusted_script_instance`
- `id, app_id, version_no, host, pid, state, health, restart_count, last_seen_at`

4. `trusted_script_event_log`
- `id, request_id, app_instance_id, device_id, topic, latency_ms, result, error, created_at`

## 12. 管理 API 设计（建议）

1. `POST /api/v1/sdui/apps/trusted/upload`
- 上传脚本包并创建版本草稿。

2. `POST /api/v1/sdui/apps/{appId}/trusted/validate`
- 触发静态校验与依赖检查。

3. `POST /api/v1/sdui/apps/{appId}/trusted/publish`
- 发布受信任脚本版本到目标设备。

4. `POST /api/v1/sdui/apps/{appId}/trusted/rollback`
- 回滚到指定版本。

5. `GET /api/v1/sdui/apps/{appId}/trusted/instances`
- 查询实例状态。

6. `POST /api/v1/sdui/apps/{appId}/trusted/circuit-break`
- 熔断停止该应用版本实例。

## 13. 与现有系统集成点

1. 复用现有设备网关与 topic 路由（`/ws/sdui`）。
2. 复用现有应用与发布模型（`sdui_app/sdui_app_version`），新增 `entryMode=TRUSTED_SCRIPT`。
3. 复用现有协议下发服务（`sendLayout/sendUpdate/sendTopic`）。
4. 与现有资产体系对接，`ctx.assets` 继续注入。

## 14. 迁移策略（从历史 server 到受信任脚本）

### 14.1 迁移优先级

1. 第一批：`music_player_server.py`（高价值、链路清晰）。
2. 第二批：`archive/server.py`（TTS/STT，依赖外部服务）。

### 14.2 迁移步骤

1. 将脚本改为平台入口契约（去掉设备 WebSocket 管理）。
2. 保留业务资源逻辑（ffmpeg/TTS/STT）在脚本内。
3. 通过 SDK/Bridge 输出标准动作。
4. 完成灰度发布和回滚预案后切全量。

### 14.3 基于当前 `docs/script/servers` 的改造基线

1. `apps/host_info_swipe_server.py`、`apps/host_info_swipe_template_server.py`、`samples/*`：
- 删除 `websockets.serve`、`sdui_handler`、`send_topic` 连接层代码。
- 保留布局构建与业务状态逻辑，入口改为 `on_start/on_event`。

2. `apps/music_player_server.py`：
- 删除设备连接管理与 websocket 收发循环。
- 保留资源扫描、ffmpeg 转码、缓存、播放状态机逻辑。
- 将音频输出改为返回 `audio_play` 动作分片，不直接发 topic。

3. `archive/server.py`（语音链路）：
- 删除设备 websocket 入口与路由。
- 保留 STT/LLM/TTS 业务管线与容错逻辑。
- 输入改为平台转发的 `audio/record` 事件，输出改为 `ui_update/audio_play` 动作。

4. 所有脚本统一禁止事项：
- 禁止监听设备 WebSocket 端口。
- 禁止自行维护设备会话表（`device_id -> ws`）。
- 禁止直接向终端发送协议包，必须通过动作返回给平台下发。

## 15. 失败处理与降级策略

1. 脚本超时：本次请求失败并上报，不阻塞网关。
2. 脚本不可用：切换为“等待页/降级页”。
3. 外部 TTS/STT 故障：脚本返回文本模式（仅 UI update）。
4. 音频发送过载：限速与分片降采样。

## 16. 分阶段实施计划

### Phase 1（MVP，1-2 周）

1. 上传/版本/发布主链路。
2. 进程托管 + stdio JSON-RPC 转发。
3. 基础健康检查与日志采集。

### Phase 2（稳定化，1-2 周）

1. 自动重启与熔断。
2. 关键指标监控与告警。
3. 灰度发布能力。

### Phase 3（治理增强，2 周）

1. 脚本包签名校验。
2. 网络白名单与资源挂载策略。
3. SDK 标准化与模板化脚手架。

## 17. 统一脚本 SDK（推荐）

SDK 作用：定义脚本和平台的统一契约，降低脚本接入成本与维护风险。

建议能力：

1. 入口装饰器：注册 `on_start/on_event/on_stop`。
2. 输出工具：`emit_layout/emit_update/emit_audio/emit_noop`。
3. 上下文工具：`state.get/set`、`assets.find`、`logger`。
4. 错误分类：`RetryableError/FatalError`。
5. 协议适配：自动封装 `request_id/device_id`。

## 18. 验收标准

1. 设备 WebSocket 连接只由平台维护。
2. 受信任脚本可在不改资源逻辑前提下运行。
3. 发布、灰度、回滚可用且有审计记录。
4. 异常场景（超时、崩溃、外部依赖失败）可观测、可降级、可恢复。
5. 至少两个历史脚本迁移成功并稳定运行（建议音乐、语音各一个）。

---

## 附录 A：manifest.yaml 示例

```yaml
app_id: music-player
version: 1.0.0
entry_mode: trusted_script
entrypoint: main.py
runtime: python3.11
health_check:
  type: rpc_ping
topics_in:
  - telemetry/heartbeat
  - ui/click
  - ui/change
  - audio/record
topics_out:
  - ui/layout
  - ui/update
  - audio/play
resource_mounts:
  - path: ./upload-dir/sdui-assets
    mode: read_only
network_policy:
  allowed_hosts:
    - api.deepseek.com
    - tts.example.internal
env_schema:
  - name: DEEPSEEK_API_KEY
    required: true
  - name: SDUI_AUDIO_CACHE_DIR
    required: false
```

## 附录 B：脚本输出动作示例

```json
{
  "request_id": "req-123",
  "ok": true,
  "actions": [
    {
      "type": "ui_update",
      "payload": {"id": "status", "text": "playing"}
    }
  ],
  "store": {"is_playing": true}
}
```
