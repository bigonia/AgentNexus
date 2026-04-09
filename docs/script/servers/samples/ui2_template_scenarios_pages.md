# UI2 Template Scenarios Pages

文件: `scripts/servers/samples/ui2_template_scenarios_server.py`

## 页面顺序（`PAGE_IDS`）
1. `page_font_sizes`
2. `page_font_xl`
3. `page_interactive`
4. `page_terminal_actions`
5. `page_info`
6. `page_viz`
7. `page_cards`
8. `page_data`
9. `page_alerts`
10. `page_tasks`
11. `page_form`
12. `page_theme`
13. `page_control`
14. `page_runtime`

前 3 页是新增的“负一屏扩展页”，用于字体与交互验证。

## 每个页面说明

### 1) `page_font_sizes`
- 目标: 默认字号分级验证（小/中/大）。
- 内容: `14 / 20 / 26` 三档文本样例。
- 用途: 快速确认不同字号在圆屏上的可读性和层级关系。

### 2) `page_font_xl`
- 目标: 超大字号显示能力验证。
- 内容: `34` 和 `30` 的超大文本，附带中文说明。
- 用途: 检查极端字号场景下的布局和裁切风险。

### 3) `page_interactive`
- 目标: 交互能力样例（动态元素 + 弹窗卡片）。
- 内容:
  - `Add Button`：逐步显示动态按钮（最多 3 个）。
  - `Show Popup`：显示弹窗式卡片。
  - `Close`：关闭弹窗。
  - 状态行：展示 `buttons` 数量和 `popup` 开关状态。
- 事件主题:
  - `ui/demo_add_button`
  - `ui/demo_open_modal`
  - `ui/demo_close_modal`

### 4) `page_terminal_actions`
- 目标: 终端控制动作交互样例。
- 内容: `Flip / Lift / Shake` 三个动作按钮 + 实时状态面板（last/count/time）。
- 事件主题:
  - `ui/term_flip`
  - `ui/term_lift`
  - `ui/term_shake`

### 5) `page_info`
- 目标: 基础信息页模板。
- 内容: 标题、副标题、状态色文本、主按钮、状态行。
- 事件主题: `ui/info_action`

### 6) `page_viz`
- 目标: 同心圆可视化模板。
- 内容: 3 层 `dial`（CPU/MEM/NET）+ 中心指标文字。
- 动态: 周期更新 `value` 与状态文字。

### 7) `page_cards`
- 目标: 卡片式布局模板。
- 内容:
  - 顶部 2 张纯色不透明卡片。
  - 中部 2 张半透明彩色卡片（CPU/MEM）。
  - 底部 1 张横向状态卡（Cluster Health）。
- 动态: CPU/MEM/健康值实时刷新。

### 8) `page_data`
- 目标: 数据表格/列表展示。
- 内容: 节点行（CPU/MEM/NET/状态）+ 告警汇总 + 刷新按钮。
- 事件主题: `ui/data_refresh`

### 9) `page_alerts`
- 目标: 告警流展示与操作。
- 内容: 4 行告警样例 + `Ack` / `Mute` 按钮。
- 事件主题:
  - `ui/alerts_ack`
  - `ui/alerts_mute`

### 10) `page_tasks`
- 目标: 任务进度可视化。
- 内容: 3 组任务名 + 进度条 + 汇总状态。
- 动态: 进度值随周期模拟变化。

### 11) `page_form`
- 目标: 表单交互模板。
- 内容: 环境参数、自动开关、提交按钮、提交结果。
- 事件主题:
  - `ui/form_env`
  - `ui/form_auto`
  - `ui/form_submit`

### 12) `page_theme`
- 目标: 主题/场景状态展示。
- 内容: 当前 `theme` 和 `scene` 文本。
- 说明: 实际切换在控制页触发，此页用于结果回显。

### 13) `page_control`
- 目标: 运行控制与调试入口。
- 内容: 主题切换、场景切换、速度快慢、运行状态行。
- 事件主题:
  - `ui/switch_theme`
  - `ui/switch_scene`
  - `ui/pace_faster`
  - `ui/pace_slower`

### 14) `page_runtime`
- 目标: 运行时内部状态观测。
- 内容: `hydrated/deferred/rev/last/mute` 等状态字段。
- 用途: 便于联调下发策略和刷新状态。

## 全局动态行为（跨页面）
- 周期泵 `pump` 持续模拟节点数据并触发局部更新。
- 采用“当前页面实时更新 + 非当前页面 deferred 缓存 + 切页补发”的策略。
- 全局主题/场景变化通过 `apply_theme` 同步到相关控件。
