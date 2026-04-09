# SDUI 文档总览

## 1. 分类说明

`docs/sdui` 已按用途拆分为三类目录：

1. `current/`
- 当前版本开发基线。
- 面向“现在就要开发和收敛”的规范、模板、Prompt。

2. `reference/`
- 协议与联调参考库。
- 面向字段细节、topic 语义、组件示例、终端接口对照。

3. `legacy/`
- 历史设计文档。
- 面向架构演进与回溯，不作为当前实现的直接开发依据。

## 2. 建议阅读顺序

1. [CURRENT_APP_DEV_GUIDE.md](d:/src/AgentNexus/docs/sdui/current/CURRENT_APP_DEV_GUIDE.md)
2. [APP_TEMPLATE_PACKS.md](d:/src/AgentNexus/docs/sdui/current/APP_TEMPLATE_PACKS.md)
3. [AI_PROMPT_SNIPPETS.md](d:/src/AgentNexus/docs/sdui/current/AI_PROMPT_SNIPPETS.md)
4. [EXAMPLE_LIBRARY.md](d:/src/AgentNexus/docs/sdui/reference/EXAMPLE_LIBRARY.md)
5. [BACKEND_DEV_GUIDE.md](d:/src/AgentNexus/docs/sdui/reference/BACKEND_DEV_GUIDE.md)

## 3. 目录清单

## 3.1 current

1. `CURRENT_APP_DEV_GUIDE.md`
2. `APP_TEMPLATE_PACKS.md`
3. `AI_PROMPT_SNIPPETS.md`
4. `MVP_API_QUICKSTART.md`
5. `MVP_IMPLEMENTATION_PLAN.md`

## 3.2 reference

1. `BACKEND_DEV_GUIDE.md`
2. `BACKEND_INTEGRATION_FLOW.md`
3. `EXAMPLE_LIBRARY.md`
4. `FIELD_REFERENCE.md`
5. `PROTOCOL_CHEATSHEET.md`
6. `TERMINAL_GUIDE.md`
7. `SDUI_MVP.postman_collection.json`

## 3.3 legacy

1. `backend-design.md`

## 4. 维护规则（建议）

1. 新增“当前可执行规范”文档，放 `current/`。
2. 新增“协议细节与示例”文档，放 `reference/`。
3. 被新架构替代的旧说明，移入 `legacy/`。
4. 每次移动文档后，必须同步更新本文件的目录清单。
