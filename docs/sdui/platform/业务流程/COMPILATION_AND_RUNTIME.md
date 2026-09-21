# 编译与运行期（提案）

> **状态**：提案，待平台侧确认。
> **关系**：模型见[业务流模型](./BUSINESS_MODEL.md)，契约见[能力与交互契约](./CONTRACTS.md)，
> 终端协议见[协议总览](../protocol/PROTOCOL.md)。

## 1. 编译总览

```text
业务模型
  │  ① 校验 + 引用解析
  ▼
中间表示（IR：页面 + 事件 + 自动化计划）
  │  ② 生成终端消息
  ▼
business.scenes.replace   （安装规则域，一次）
display.*                 （主视图，每次状态变化）
audio.start / 0x12 / stop （播放）
business.trigger          （平台→终端触发本地规则）
```

编译产物分两部分：**终端消息**（下发）与**运行期计划**（平台侧执行）。

## 2. 页面行为编译（功能语义）

终端不会自己跳页，也不会执行平台动作。因此按钮行为按归属分两类：

| 按钮行为 | 终端侧 | 平台侧 |
|---|---|---|
| `native` | 直接执行响应，不必上报 | 无 |
| `executor` | 只需把按钮上报给平台 | 收到后调用能力 |
| `jump` | 只需把按钮上报给平台 | 收到后切换页面 |

- 每个**有按钮行为的页面**生成一个 scene：`native` 响应直接进入 scene；
  平台行为则生成一条"按钮上报"响应；
- 每个上报对应一个**事件名**（编译生成，作者无需手写）；
- `jump` 与 `transitions` 是**同一套跳转机制**：`jump` 是按钮场景的简写，
  编译后统一进入跳转表。

**终端硬限制（编译期必须校验）**：

| 限制 | 值 |
|---|---|
| scene 数 | ≤ 8 |
| 每 scene binding 数 | ≤ 16 |
| 每 binding responses | 1~4 |
| trigger 命名 | `button.pwr\|plus.{down\|up\|short_press\|long_press}`、`platform.trigger.<token>` |
| response 命名 | `audio.record.start\|stop\|toggle`、`feedback.rgb.set\|off`、`platform.interaction.report` |

**菜单冲突**：`menu_section` 原生占用 `button.plus.short_press` 与
`button.pwr.short_press`。菜单页的 scene **不得**绑定这两个 trigger，否则编译失败
（`ui_rule_conflict`）。

### 2.1 事件与行为的唯一性

- **事件来源**：终端按钮上报 / 外部触发器 / 系统定时器；
- **同一个事件只能对应一种平台行为**（跳转或自动化）；两者同时配置属于冲突，
  编译期报错；
- 未命中任何行为的事件：记录并丢弃。

## 3. 主视图编译

| 页面 mode | 终端消息 |
|---|---|
| `template` | `display.section.set`（`type` + 字段 + `scene_id`） |
| `image` | `display.image.begin` → Binary `0x21` → `display.image.end` |
| `canvas` | `display.canvas.open` → Binary `0x22`… → `display.canvas.close` |

所有模板字段上限沿用 UI Schema（见
[平台接入指南](../platform-integration/lcd085/README.md#6-ui-主视图)）。

## 4. 槽位绑定与转换器插入

1. 槽位值是**字面量**或 **`@引用`**；
2. `@引用` 从 invocation 作用域解析；
3. 若值的契约 ≠ 槽位需要的契约：
   - 找到唯一匹配的 `transform` 能力 → **插入一个转换步骤**（可见、可替换）；
   - 无匹配 → 编译失败（`slot_contract_mismatch`）。

## 5. 事件分发

终端事件只有一种入口：`platform.interaction`（携带 token）。

```text
device event(token)
  ├─ 命中 transition.on  → 显示目标页面
  ├─ 命中 automation.on  → 执行自动化
  └─ 都未命中            → 记录并丢弃
```

外部触发器与系统定时器走同一条分发逻辑（它们也是"源产生事件"）。

## 6. 自动化执行

```text
1. 显示 pending 页面（若配置）
2. 按序执行 steps：
     解析 in 绑定 → 调用能力 → 结果写入 invocation 作用域
     超时 / 返回错误 → 立即中止，进入 on_error
3. 执行 present（按顺序）
4. 结束，释放 invocation 作用域
```

- **线性、无分支、无循环**；
- 任一步失败 → 截止后续步骤；
- 已完成步骤**不回滚**（本地副作用如 RGB 无法回滚）；
- `invocation_id` 用于幂等与审计。

## 7. 并发与重入

**v1 规则：同一设备同一时刻只处理一个流程。**

| 情况 | 行为 |
|---|---|
| 流程进行中收到新事件 | 丢弃并记录（返回 `busy`，若来源可回执） |
| 重复触发器（同幂等键） | 丢弃（`duplicate`） |
| 同一设备快速连按 | 由终端本地规则消化，平台只处理已上报事件 |

## 8. 断线与重连

**v1 简化**：

- 设备断线 → **中止**当前流程，清理 invocation 作用域与未完成资源；
- 重连 → 重新走 `device.hello` → Schema → `ready`；
- ready 后平台**重新投影当前路由页面**（路由状态在平台，未丢失）；
- 不续传音频/图片/画布。

## 9. 音频路径

| 方向 | 流程 |
|---|---|
| 上行录音 | 终端本地规则触发 → Binary `0x11` → 平台按设备组装 |
| 下行播放 | 平台 `audio.start` → Binary `0x12` → `audio.stop` |

- 组装边界：`capture.started` / `capture.done` 事件之间；
- **单设备单会话**，用 `deviceId` 作 key，无需 session_id；
- 录音与播放互斥，冲突返回 `busy` / `audio_busy`。

## 10. 审计

至少记录：

- 模型版本与部署记录（device/business/version）；
- 每次触发：`invocation_id`、事件、来源、结果；
- 每次 provider 调用：能力、耗时、成功/失败、错误码；
- 页面切换历史。

## 11. 边界

- **不修改终端协议**；编译产物全部落在既有 `lcd085.v2` 消息内；
- `business.reset` 在代码中已实现但**未声明在 Command Schema**，平台在 Schema
  补齐前不应依赖，或由平台在迁移时补声明；
- 图标、字体等资源不在 v1 范围。

## 12. 校验清单（编译前）

| 检查 | 失败错误 |
|---|---|
| entry 页面存在 | `invalid_model` |
| scene ≤ 8、binding ≤ 16、responses 1~4 | `resource_limit` |
| 菜单页未绑定 plus/pwr 短按 | `ui_rule_conflict` |
| 槽位契约可满足（能插入转换器） | `slot_contract_mismatch` |
| 引用可解析 | `unresolved_reference` |
| 同一事件只有一个平台行为 | `duplicate_event` |
| 能力存在于目录 | `unknown_capability` |

## 13. 默认超时与错误

- 能力未声明 `timeout_ms` 时，采用平台按 `role` 设定的默认值；
- 步骤失败默认进入 `on_error`；未配置 `on_error` 时显示通用错误页；
- **不自动重试**，除非能力声明 `idempotent=true`。
