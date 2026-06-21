# SDUI Section 前端接入说明

本文档只面向前端接入 `debug` 模块中的 section 调试能力。

目标是让前端按“后端托管 debug workspace”的方式接入，而不是在前端长期维护整页 section JSON。

本文档不覆盖：

- 工作流页面编排：参阅 [`STATE_MACHINE_FRONTEND_INTEGRATION.md`](STATE_MACHINE_FRONTEND_INTEGRATION.md)
- 设备查看：参阅 [`DEVICE_VIEW_API.md`](DEVICE_VIEW_API.md)
- 命令调试：参阅 [`DEBUG_API.md`](DEBUG_API.md)

---

## 1. 核心模型

当前 debug section 采用“每设备一个 debug workspace”的模型：

- 每个 `deviceId` 只有一个 debug workspace
- workspace 状态由后端维护
- page 和 section 的创建标识默认由后端分配
- 前端只负责：
  - 读取编辑辅助信息
  - 读取当前 workspace 状态
  - 提交 page / patch 操作
  - 在操作后重新拉取状态

前端不要做的事：

- 不要把自己本地的 section JSON 当成权威状态
- 不要假设后端会自动容错无效 section
- 不要在 `update` 时尝试修改已有 section 的 `sectionType`
- 不要在创建时自己设计 `pageId` / `sectionId` 生成规则，除非确实需要显式指定

---

## 2. 推荐接入顺序

建议页面初始化顺序：

1. `GET /api/v1/sdui/debug/{deviceId}/section-editor`
2. `GET /api/v1/sdui/debug/{deviceId}/section/state`

建议用户操作顺序：

1. 首次创建或整体替换页面时，调用 `POST /section`
2. 增删改单个 section 时，调用 `POST /section/patch`
3. 每次成功后，重新调用 `GET /section/state`
4. 清空调试空间时，调用 `DELETE /section/state`

前端当前不需要本地做复杂同步算法，采用“操作成功后重新拉取 state”即可。

---

## 3. 接口清单

### 3.1 获取编辑辅助信息

- 方法：`GET`
- 路径：`/api/v1/sdui/debug/{deviceId}/section-editor`

用途：

- 获取当前设备支持的 `layouts`
- 获取当前设备支持的 `sectionTypes`
- 获取每种 section 可展示的字段结构 `displayFields`
- 获取 section 支持的 `events`、`patchOps`

成功响应示例：

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "online": true,
    "renderMode": "rich",
    "sizeClass": "large",
    "layouts": [
      "vertical_scroll",
      "fixed_single"
    ],
    "sectionTypes": [
      {
        "type": "hero_section",
        "displayFields": [
          { "name": "value", "type": "string", "children": [] },
          { "name": "label", "type": "string", "children": [] },
          { "name": "progress", "type": "int", "children": [] }
        ],
        "events": [],
        "patchOps": ["add", "update", "remove"]
      }
    ]
  }
}
```

前端使用建议：

- `sectionTypes[].displayFields` 用于动态表单渲染
- `layouts` 直接作为布局枚举列表使用
- `patchOps` 可用于决定某种 section 是否允许增量编辑

### 3.2 获取当前 debug workspace 状态

- 方法：`GET`
- 路径：`/api/v1/sdui/debug/{deviceId}/section/state`

用途：

- 作为前端唯一权威状态源
- 页面初始化时回显当前调试内容
- 每次操作成功后重新拉取

成功响应示例：

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "online": true,
    "activePageId": "debug_page_ab12cd34",
    "pages": [
      {
        "pageId": "debug_page_ab12cd34",
        "layout": "vertical_scroll",
        "autoScroll": false,
        "autoScrollMs": 0,
        "sections": [
          {
            "sectionId": "section_ef56gh78",
            "sectionType": "hero_section",
            "fields": {
              "value": "92%",
              "label": "CPU Usage",
              "subtitle": "Running Normal",
              "tone": "primary",
              "iconSrc": "",
              "iconSymbol": null,
              "progress": 92
            },
            "updatedAt": 1774412030123
          }
        ],
        "updatedAt": 1774412030123
      }
    ]
  }
}
```

未初始化设备时示例：

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "online": true,
    "activePageId": null,
    "pages": []
  }
}
```

前端使用建议：

- 直接以 `pages[0]` 或 `activePageId` 对应 page 渲染编辑区
- 不要根据旧请求体推导当前状态，应以返回值为准

### 3.3 整体创建或替换页面

- 方法：`POST`
- 路径：`/api/v1/sdui/debug/{deviceId}/section`

用途：

- 创建一个新的 debug page
- 用新的完整 page 替换同名 page
- 将该 page 设为 `activePageId`
- 若未提供 `pageId`，由后端自动生成
- 若某个 section 未提供 `sectionId`，由后端自动生成

请求示例：

```json
{
  "layout": "vertical_scroll",
  "autoScroll": false,
  "autoScrollMs": 0,
  "sections": [
    {
      "sectionType": "hero_section",
      "fields": {
        "value": "85%",
        "label": "CPU Usage",
        "subtitle": "Running Normal",
        "tone": "primary",
        "progress": 85
      }
    }
  ]
}
```

成功响应示例：

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "sent": true,
    "pageId": "debug_page_ab12cd34",
    "layout": "vertical_scroll",
    "sectionsBuilt": 1,
    "sectionsRequested": 1,
    "sectionIds": [
      "section_ef56gh78"
    ]
  }
}
```

规则：

- `sections[]` 不能为空
- 每个 section 必须提供：
  - `sectionType`
  - `fields`
- `pageId` 可选
- `sectionId` 可选
- 如果显式传入 `sectionId`，同一 page 内不能重复
- `sectionType` 必须是设备支持的类型
- `fields` 必须能构建出合法的 section 数据

前端建议：

- 如果是“整体保存页面”，直接调用此接口
- 除非有明确业务需求，创建时不要主动传 `pageId` 和 `sectionId`
- 调用成功后立即刷新 `GET /section/state`

### 3.4 增量修改页面

- 方法：`POST`
- 路径：`/api/v1/sdui/debug/{deviceId}/section/patch`

用途：

- 对当前 debug workspace 中某个 page 做 section 级增量修改
- 若不传 `pageId`，默认作用于当前 `activePage`

请求示例：

```json
{
  "patches": [
    {
      "sectionId": "section_ef56gh78",
      "op": "update",
      "fields": {
        "value": "92%",
        "progress": 92
      }
    },
    {
      "op": "add",
      "sectionType": "text_section",
      "fields": {
        "title": "提示",
        "body": "新的动态内容"
      }
    }
  ]
}
```

成功响应示例：

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "sent": true,
    "pageId": "debug_page_ab12cd34",
    "patchesBuilt": 2,
    "patchesRequested": 2,
    "sectionIds": [
      "section_ef56gh78",
      "section_xy90zt12"
    ]
  }
}
```

patch 语义：

- `add`
  - 必须提供 `sectionType`
  - 必须提供 `fields`
  - `sectionId` 可省略，由后端生成
  - 新 section 默认追加到 page 末尾
- `update`
  - 必须提供 `sectionId`
  - 必须提供 `fields`
  - 后端会对原有字段做 merge
  - 不能修改已有 section 的 `sectionType`
- `remove`
  - 仅需 `sectionId`

严格约束：

- `pageId` 若显式提供，必须存在于当前 debug workspace
- 若未提供 `pageId`，当前必须已有 `activePage`
- `update/remove` 的目标 section 必须已经存在
- `add` 若显式传入 `sectionId`，不能与现有 section 冲突
- 非法 `op` 会直接报错
- 任意一个 patch 非法，整个请求返回错误

前端建议：

- 单个用户交互尽量发单条 patch，便于定位错误
- `add` 成功后以响应中的 `sectionIds` 或重新拉取的 state 获取新 ID
- 本地编辑完成后，以重新拉取的 state 刷新 UI

### 3.5 清空 debug workspace

- 方法：`DELETE`
- 路径：`/api/v1/sdui/debug/{deviceId}/section/state`

用途：

- 清空当前设备的 debug workspace
- 重置 `activePageId`
- 清空所有 pages

成功响应示例：

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "esp32-A1B2C3",
    "online": true,
    "cleared": true,
    "activePageId": null,
    "pages": []
  }
}
```

前端建议：

- 用于“退出调试”“重置页面”“清空当前实验内容”
- 调用成功后可直接把本地编辑区重置为空态

---

## 4. 错误处理建议

### 4.1 常见错误响应

设备离线：

```json
{
  "code": 40000,
  "message": "device is offline",
  "data": null
}
```

不支持的 sectionType：

```json
{
  "code": 40000,
  "message": "unsupported sectionType: unknown_section",
  "data": null
}
```

page 不存在：

```json
{
  "code": 40000,
  "message": "page not found in debug workspace: debug_page_ab12cd34",
  "data": null
}
```

section 不存在：

```json
{
  "code": 40000,
  "message": "section not found: section_ef56gh78",
  "data": null
}
```

前端建议：

- 直接展示 `message`
- 不要在前端自行吞掉后端报错
- patch 失败后立即重新拉取 `GET /section/state`，确保页面状态和后端一致

---

## 5. 推荐前端状态管理方式

推荐保留两份状态：

- `editorDraft`
  - 当前表单输入、拖拽中的临时态
  - 仅存在前端
- `workspaceState`
  - 来自 `GET /section/state`
  - 作为当前设备 debug 页面真实状态

推荐流程：

1. 打开页面时拉取 `editor  + state`
2. 用户修改表单时只改 `editorDraft`
3. 点击保存/新增/删除时调用后端接口
4. 成功后重新拉取 `workspaceState`
5. 用 `workspaceState` 覆盖展示区

不推荐流程：

- 前端本地直接长期维护最终 page JSON
- 操作成功后不重新拉 state，只靠本地猜测结果

---

## 6. 与状态机的关系

当前 debug section 与 state machine authoritative state 是两套独立状态：

- `debug/{deviceId}/section/state`
  - 返回 debug workspace
- `state-machines/deployments/{deploymentId}`
  - 返回状态机部署运行态页面状态

二者都可能向设备发送页面。

当前策略是：

- 不做 ownership 仲裁
- 谁最后发送，设备就显示谁
- 但两边后端状态各自独立保存，不互相覆盖存储

前端接入建议：

- debug 页面不要把状态机 `authoritativeState` 当成自己的编辑数据源
- 状态机页面也不要读取 debug workspace 作为正式运行态

---

## 7. 最小接入示例

### 初始化

```ts
await Promise.all([
  api.get(`/api/v1/sdui/debug/${deviceId}/section-editor`),
  api.get(`/api/v1/sdui/debug/${deviceId}/section/state`)
]);
```

### 整页保存

```ts
await api.post(`/api/v1/sdui/debug/${deviceId}/section`, {
  layout: "vertical_scroll",
  sections: [
    {
      sectionType: "hero_section",
      fields: {
        value: "85%",
        label: "CPU Usage",
        subtitle: "Running Normal",
        tone: "primary",
        progress: 85
      }
    }
  ]
});

const state = await api.get(`/api/v1/sdui/debug/${deviceId}/section/state`);
const pageId = state.data.activePageId;
const sectionId = state.data.pages[0].sections[0].sectionId;
```

### 单个 section 更新

```ts
await api.post(`/api/v1/sdui/debug/${deviceId}/section/patch`, {
  patches: [
    {
      sectionId,
      op: "update",
      fields: {
        value: "92%",
        progress: 92
      }
    }
  ]
});

const state = await api.get(`/api/v1/sdui/debug/${deviceId}/section/state`);
```

### 清空调试空间

```ts
await api.delete(`/api/v1/sdui/debug/${deviceId}/section/state`);
```
