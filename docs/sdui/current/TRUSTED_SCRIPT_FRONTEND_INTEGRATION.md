# SDUI Trusted Script Mode 前端对接说明

> 适用范围：`/api/v1/sdui` 在 Trusted Script 重构后的管理面与运行协议。  
> 目标：帮助前端从“AI 生成 + 资源管理”模式迁移到“应用管理 + 包上传 + 发布/回滚 + 运行态观测”。

## 1. 总体变化

本次重构后，SDUI 前端定位从“生成器控制台”切换为“应用运维控制台”：

1. 不再提供 AI 生成/修订应用能力。
2. 不再提供资产/资源集管理能力。
3. 新增 Trusted Script 包上传、发布、回滚、实例状态查看。
4. 终端连接与消息下发仍由平台网关处理，前端只通过 REST 管理。

## 2. API 变更对照

## 2.1 删除的旧接口（前端必须下线）

1. `POST /api/v1/sdui/apps/generate`
2. `POST /api/v1/sdui/apps/{appId}/revise`
3. `POST /api/v1/sdui/assets`
4. `GET /api/v1/sdui/assets`
5. `GET /api/v1/sdui/assets/source-files`
6. `POST /api/v1/sdui/asset-sets`
7. `GET /api/v1/sdui/asset-sets`
8. `POST /api/v1/sdui/asset-sets/{assetSetId}/items`
9. `GET /api/v1/sdui/asset-sets/{assetSetId}/items`
10. `DELETE /api/v1/sdui/asset-sets/{assetSetId}/items/{itemId}`
11. `POST /api/v1/sdui/apps/{appId}/assets:bind`
12. `GET /api/v1/sdui/apps/{appId}/assets`
13. `DELETE /api/v1/sdui/apps/{appId}/assets/{bindingId}`

## 2.2 新增/保留接口（前端主流程）

1. `POST /api/v1/sdui/apps`
- 用途：创建 Trusted Script 应用。
- 请求：
```json
{
  "name": "music-player",
  "description": "音乐播放终端应用"
}
```

2. `POST /api/v1/sdui/apps/{appId}/versions:upload`
- 用途：上传 zip 脚本包，创建新版本。
- 请求类型：`multipart/form-data`
- 字段：
  - `file`：zip 包（必填）
  - `signature`：签名字符串（可选）
- 响应 `data` 关键字段：
  - `versionId`
  - `versionNo`
  - `validationReport`
  - `packageUri`

3. `GET /api/v1/sdui/apps`
- 用途：应用列表。

4. `GET /api/v1/sdui/apps/{appId}/versions`
- 用途：应用版本列表。

5. `POST /api/v1/sdui/apps/{appId}/publish`
- 用途：发布指定版本到设备集合。
- 请求：
```json
{
  "versionId": "xxx",
  "rolloutBatch": "MANUAL",
  "deviceIds": ["dev-001", "dev-002"]
}
```

6. `POST /api/v1/sdui/apps/{appId}/rollback`
- 用途：回滚到上一版本并发布到指定设备集合。
- 请求：
```json
{
  "deviceIds": ["dev-001", "dev-002"]
}
```

7. `GET /api/v1/sdui/apps/{appId}/deployments`
- 用途：查看最近部署记录。

8. `GET /api/v1/sdui/runtime/instances`
- 用途：查看实例运行态（state/health/restart_count 等）。

9. 设备/运维接口（保留）
- `GET /api/v1/sdui/devices`
- `GET /api/v1/sdui/devices/unclaimed`
- `POST /api/v1/sdui/devices/{deviceId}/claim`
- `GET /api/v1/sdui/devices/{deviceId}/telemetry`
- `POST /api/v1/sdui/devices/{deviceId}/control`
- `GET /api/v1/sdui/runtime/workers`
- `GET /api/v1/sdui/ops/overview`

## 3. 新协议说明（前端关心部分）

## 3.1 脚本包协议（上传包）

zip 包至少包含：

1. `manifest.yaml`
2. `main.py`（或 `manifest.entrypoint` 指定的脚本）

`manifest.yaml` 必填关键字段：

1. `app_id`
2. `version`
3. `entry_mode: trusted_script`
4. `entrypoint`
5. `runtime`（建议 `python3.11`）

前端需要在上传前做基础校验（扩展名、manifest 存在、entry_mode 值），并展示后端 `validationReport`。

## 3.2 运行动作协议（用于理解调试与监控）

后端运行时已支持两种脚本返回：

1. 旧式：
```json
{"action":"layout|update|noop","page_id":"home","payload":{}}
```

2. 新式（推荐）：
```json
{
  "actions": [
    {"type":"ui_layout","page_id":"home","payload":{}},
    {"type":"ui_update","page_id":"home","payload":{}},
    {"type":"audio_play","payload":{}},
    {"type":"send_topic","payload":{"topic":"custom/topic","payload":{}}}
  ]
}
```

前端管理台通常不直接处理该协议，但在“版本详情/运行日志”页面建议展示该能力说明，便于排障。

## 4. 前端页面与交互调整

## 4.1 应用中心页面改造

旧模块下线：

1. 自然语言生成应用
2. 应用修订
3. 资源绑定

新模块上线：

1. 创建应用
2. 上传版本包
3. 版本列表
4. 发布
5. 回滚
6. 部署记录

## 4.2 版本上传流程（推荐）

1. 用户选择应用。
2. 选择 zip 包并输入可选签名。
3. 调用 `versions:upload`。
4. 展示 `validationReport`。
5. 校验通过后允许进入发布。

## 4.3 发布/回滚流程（推荐）

1. 勾选设备（来自 `/devices`，仅 `CLAIMED` 且在线优先）。
2. 发布时提交 `versionId + deviceIds`。
3. 回滚时提交 `deviceIds`。
4. 发布后跳转部署记录和实例页面查看结果。

## 4.4 运行态可观测页面

建议新增两个页面：

1. 实例页（`/runtime/instances`）
- 关键列：`appId`, `deviceId`, `versionNo`, `state`, `health`, `restartCount`, `updatedAt`

2. 部署记录页（`/apps/{appId}/deployments`）
- 关键列：`deviceId`, `versionNo`, `status`, `rolloutBatch`, `createdAt`

## 5. 错误处理与前端提示

## 5.1 典型错误

1. `app not found`
2. `manifest.yaml is required`
3. `manifest.entry_mode must be trusted_script`
4. `entrypoint file not found in package`
5. `device does not belong to current space`
6. `no previous version for rollback`

## 5.2 建议提示策略

1. 上传失败：保留文件选择，提示具体校验错误。
2. 发布失败：提示失败设备并保持勾选状态。
3. 回滚失败：提示“当前无可回滚历史版本”。

## 6. 联调最小闭环

1. `POST /apps` 创建应用。
2. `POST /apps/{appId}/versions:upload` 上传 zip。
3. `GET /devices` 选择目标设备。
4. `POST /apps/{appId}/publish` 发布。
5. `GET /apps/{appId}/deployments` 看部署结果。
6. `GET /runtime/instances` 看实例健康状态。

---

如需和前端团队同步，可直接以本文作为“重构后的联调基线”。
