# AgentNexus - SDUI 终端管理平台

> **定位**: Server-Driven UI (SDUI) 管理平台，为 ESP32 类 IoT 终端设备提供动态 UI 下发、状态机编排、设备管理与能力适配。

## 1. 项目简介

AgentNexus 是一个 **SDUI 终端管理平台**，核心理念是"服务端定义 UI，终端只负责渲染"。平台通过 WebSocket 长连接与 IoT 设备通信，根据设备上报的能力（显示屏尺寸、输入方式、音频等）动态下发 Section UI（12 种组件类型），并通过状态机引擎编排多页面的交互流程。

设备端无需固件升级即可获得全新的 UI 和交互逻辑 —— 所有界面由服务端状态机驱动，通过 Scene（全量）或 Patch（增量）方式推送到设备。

### 核心能力

*   **SDUI 终端管理**: 设备接入、生命周期管理、心跳检测、能力上报与适配。
*   **Section UI 体系**: 12 种 Section 类型（Hero, Metric, Chart, Timer, Image, Action, Progress, Text, Overlay, List, Toggle, Nav），支持 RICH/COMPACT 两种渲染模式。
*   **状态机编排引擎**: 四层架构（类型注册 → 流程定义 → 部署实例 → 设备投影），支持设备事件、命令回执、Cron 三种触发方式，Slot 抽象实现一次定义多处部署。
*   **双通道协议**: JSON Topic 通道（命令/心跳/生命周期）+ UI3 Binary 通道（Section 渲染/音频 PCM/输入事件），CRC32 校验。
*   **音频能力**: 设备录音 → STT（Whisper）→ TTS（ffmpeg/macOS）→ PCM 播放回设备。
*   **AI 辅助**: Agent 管理、RAG 引擎、MCP 协议集成、DrawThings 图像生成。

## 2. 系统架构

```
┌─────────────────────────────────────────────────────┐
│                 Frontend (Vue3 + vue-flow)           │
│               状态机编辑器 / 设备管理 / 调试面板         │
└──────────────────────┬──────────────────────────────┘
                       │ REST API (/api/v1/sdui/)
┌──────────────────────┴──────────────────────────────┐
│              SDUI Management Platform                │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────┐  │
│  │ StateMachine │  │   Section    │  │ Capability  │  │
│  │   Engine     │  │  Type System │  │   System    │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬─────┘  │
│         │                 │                  │        │
│  ┌──────┴─────────────────┴──────────────────┴─────┐  │
│  │              Dual-Channel Protocol              │  │
│  │     JSON Topic (commands/heartbeat/lifecycle)    │  │
│  │     UI3 Binary (sections/audio/input events)     │  │
│  └────────────────────────┬────────────────────────┘  │
│                           │ WebSocket (:8080/ws/sdui) │
├───────────────────────────┼──────────────────────────┤
│  Auxiliary: AI Agent │ RAG │ MCP │ DrawThings │ Auth │
└───────────────────────────┼──────────────────────────┘
                            │
                    ┌───────┴───────┐
                    │  ESP32 设备    │
                    │ (瘦客户端渲染)  │
                    └───────────────┘
```

## 3. 模块详解

### 3.1 SDUI 核心（项目主线）

| 子模块 | 职责 |
|--------|------|
| `sdui/statemachine/` | **状态机引擎** — 四层架构：类型注册(L1) → 流程定义(L2) → 部署实例(L3) → 设备投影(L4)，支持设备事件/命令回执/Cron 触发 |
| `sdui/section/` | **Section UI 体系** — 12 种 Section 类型的 sealed 继承体系，Scene 全量推送 / Patch 增量更新，按设备能力适配 RICH/COMPACT 模式 |
| `sdui/capability/` | **能力系统** — 设备能力上报、预设 Catalog（YAML）、平台能力注册、契约解析与校验 |
| `sdui/event/` | **事件系统** — 配置驱动的事件类型注册（YAML），Section 交互事件动态声明，命令事件 / Section 事件双域分离 |
| `sdui/protocol/` | **双通道协议** — JSON Topic 文本帧 + UI3 Binary 二进制帧（magic 0x5344, TLV, CRC32），协议编解码与设备协议目录 |
| `sdui/service/` | **设备服务** — 生命周期管理、命令下发/ACK 追踪、事件 SSE 推送、音频处理（STT/TTS） |

详见: `docs/sdui/STATE_MACHINE_ARCHITECTURE.md`, `docs/sdui/terminal/PROTOCOL_AND_COMMANDS.md`

### 3.2 状态机编辑器前端

独立的 Vue3 + vue-flow 项目（不在此仓库），通过 REST API 与后端交互。提供可视化的状态图编辑、设备管理、调试面板。详见 `docs/sdui/front/`。

### 3.3 AI 辅助能力

| 子模块 | 职责 |
|--------|------|
| `ai/` | Agent 管理、SSE 流式对话、工具调用、MCP 协议（STDIO/SSE）、动态模型注册 |
| `drawthings/` | 本地 DrawThings (Stable Diffusion) 图像生成，通过 `local:generateImage` Tool 暴露给 Agent |

### 3.4 基础设施

| 子模块 | 职责 |
|--------|------|
| `security/` | Spring Security + JWT 鉴权，RBAC (admin/editor/guest) |
| `space/` | 多租户空间隔离 |
| `common/` | CORS、GlobalContext ThreadLocal、ApiResponse、异常处理 |

## 4. 快速开始

### 环境依赖
*   JDK 17+
*   Maven 3.8+
*   PostgreSQL 15+ (需开启 `pgvector` 插件)

### 启动步骤
1.  **配置数据库**: 修改 `application.yml` 中的 JDBC 连接串。
2.  **编译运行**:
    ```bash
    # macOS 需激活 macos profile 以使用兼容的 MCP 命令
    ./mvnw spring-boot:run -Dspring-boot.run.profiles=macos

    # 打包运行
    ./mvnw clean package -DskipTests
    java -jar target/AgentNexus-0.0.1-SNAPSHOT.jar --spring.profiles.active=macos
    ```
3.  服务启动后访问:
    - Swagger UI: `http://localhost:8080/swagger-ui.html`
    - WebSocket 端点: `ws://localhost:8080/ws/sdui`

## 5. 文档索引

| 文档 | 内容 |
|------|------|
| `docs/PROJECT_OVERVIEW.md` | 项目定位总览 |
| `docs/sdui/STATE_MACHINE_ARCHITECTURE.md` | 状态机四层架构设计 |
| `docs/sdui/STATE_MACHINE_E2E_FLOW.md` | 状态机端到端流程 |
| `docs/sdui/terminal/PROTOCOL_AND_COMMANDS.md` | 二进制协议与命令 API |
| `docs/sdui/terminal/SECTION_SCHEMA.md` | 12 种 Section 类型 Schema |
| `docs/sdui/terminal/CAPABILITY_REPORTING.md` | 终端能力上报协议 |
| `docs/sdui/front/` | 前端集成文档 |
| `docs/api/` | API 参考 |

---
*Copyright © 2025 ZWBD Team.*
