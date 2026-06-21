# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build / Run / Test

```bash
# Build (skip tests)
mvnw clean package -DskipTests

# Run tests
mvnw test

# Run a single test class
mvnw test -Dtest=ClassName

# Run a single test method
mvnw test -Dtest=ClassName#methodName

# Run the app (macOS: use the macos profile for correct MCP stdio commands)
mvnw spring-boot:run -Dspring-boot.run.profiles=macos
# Or after packaging:
java -jar target/AgentNexus-0.0.1-SNAPSHOT.jar --spring.profiles.active=macos
```

The app starts on port **8080**. Swagger UI at `http://localhost:8080/swagger-ui.html`. API base path is `/api/v1/` (not all controllers follow this).

**macOS profile** (`application-macos.yml`): Overrides the MCP Playwright stdio command from Windows `cmd /c npx` to `zsh -c npx`. Always use `-Dspring-boot.run.profiles=macos` on macOS.

**Tests** use JUnit 5 (Jupiter) + Mockito (via `spring-boot-starter-test`). No test database or H2 — `@SpringBootTest` loads the real application context. All 18 test classes are under `src/test/java/com/zwbd/agentnexus/sdui/` (SDUI domain only).

## Architecture Overview

**This is an SDUI (Server-Driven UI) management platform** — the backend serves a Vue3 management console (state machine editor, device manager, debug panel) and communicates with ESP32-class IoT devices over WebSocket. AI/Agent capabilities are auxiliary, supporting the SDUI platform (e.g., TTS/STT for audio, DrawThings for image generation, RAG for knowledge retrieval).

**Stack:** Java 17, Spring Boot 3.5.5, Spring AI 1.0.3, Maven, PostgreSQL 15+ with pgvector. Heavy use of Lombok (`@Data`, `@Slf4j`, `@RequiredArgsConstructor`).

Key dependencies: `spring-boot-starter-websocket`, `spring-boot-starter-security` + jjwt 0.11.5 (JWT auth), `spring-boot-starter-freemarker`, `jackson-dataformat-yaml` (YAML config parsing), `springdoc-openapi` 2.6.0 (Swagger), `spring-boot-starter-actuator` + Micrometer tracing + Zipkin Brave (observability), `spring-boot-devtools`.

Main class: `DbCrawlerV4Application.java` (legacy name from the project's origins as a database crawler). `@EnableAsync` + `CommonConfig.executorService()` provides a fixed thread pool of 10.

### Package Map

| Package | Responsibility |
|---------|---------------|
| `ai` | (Auxiliary) Agent runtime, chat with SSE streaming, model registry, tool management, MCP connections, dynamic LLM provider registration |
| `common` | Shared web config (CORS, GlobalContext ThreadLocal), ApiResponse wrapper, exception handling, JPA converters, FreeMarker config |
| `drawthings` | AI image generation via local DrawThings (Stable Diffusion) server. Conditionally enabled (`drawthings.enabled=true`). Exposes `local:generateImage` as a Spring AI `@Tool`. |
| `sdui` | **Server-Driven UI for IoT devices** — the project's main focus. WebSocket, binary protocol, section orchestration, device management, capability system, event system, state machine engine. |
| `security` | JWT auth, RBAC (admin/editor/guest), stateless sessions |
| `space` | Multi-tenant business space CRUD (`BusinessSpace` entity, `SpaceRepository`, `SpaceService`, `SpaceController`) |
| `utils` | `MapUtil` (map flattening), `StringListConverter` (JPA `AttributeConverter`), `TemplateRenderService` (FreeMarker-based string template rendering) |

### Key Architectural Patterns

**Sealed Class Hierarchies** — Used for type-safe discriminated unions:
- `SectionData` (sealed interface) permits 12 record subclasses (HeroData, MetricData, ChartData, TimerData, ImageData, ActionData, ProgressData, TextData, OverlayData, ListData, ToggleData, NavData), each mapped to a `SectionType` enum value.
- All use Jackson `@JsonTypeInfo`/`@JsonSubTypes` for polymorphic JSON serialization.

**GlobalContext ThreadLocal** (`common/web/GlobalContext.java`): Request-scoped context holding `space_id`, `user_id`, and `trace_id`. Populated by `GlobalContextInterceptor` from the `X-Space-Id` header. Multi-tenancy uses Hibernate's `@TenantId` + `SpaceIdResolver` to inject the space ID into JPA queries.

**MessageRouter Plugin Pattern** (`sdui/MessageRouter.java`): Incoming WebSocket messages are routed to handler implementations discovered via Spring constructor injection of `List<TopicHandler>` and `List<BinaryFrameHandler>`. Each handler declares its supported topic string or binary msgType int; the router builds a dispatch map at `@PostConstruct` time. Text messages carry a JSON `SduiMessage` envelope (topic + deviceId + payload); binary messages use the custom binary frame protocol.

**Tool Provider Namespacing** (`ai/tools/GlobalToolManager.java`): Tools registered as `providerId:toolName` (e.g., `local:getCurrentDateTime`, `mcp-playwright:navigate`, `local:generateImage`). Supports local `@Tool`-annotated methods and MCP tools.

**Dynamic AI Provider Registration** (`ai/config/DynamicAiProviderRegistrar.java`): Registers multiple LLM providers (OpenAI-compatible) at runtime from `DynamicAiProvidersProperties`. Enables switching models without restart.

### API Response Convention

All controllers return `ApiResponse<T>` (code `20000` = success). The `GlobalResponseAdvice` (`@RestControllerAdvice`) handles `MethodArgumentNotValidException` and `CommonException` globally. Validation uses `jakarta.validation` annotations on request DTOs.

### JWT Auth Flow

1. `POST /api/auth/login` returns a JWT token
2. `JwtRequestFilter` extracts Bearer token from `Authorization` header, validates, sets `SecurityContext`
3. In-memory users: admin (`ROLE_admin`), editor (`ROLE_editor`), guest (`ROLE_sdui_only`)
4. JWT secret and expiration configured via `jwt.secret` / `jwt.expiration` in application.yml
5. All `/api/**`, `/ws/**`, `/` paths are permitted (auth checks are role-based within endpoints)

### Database

PostgreSQL with `pgvector` extension for vector similarity search. Hibernate `ddl-auto: update` manages schema. The `document_chunks` table uses HNSW index with COSINE_DISTANCE and 1024 dimensions. Chat memory uses JDBC-backed persistence with `initialize-schema: always`. Note: `docs/sql/` is empty and `resources/schema.sql.bak` is a legacy backup (not loaded by Hibernate).

## SDUI Core Architecture

The SDUI (Server-Driven UI) system is the project's primary focus. It enables ESP32-class IoT devices to connect via WebSocket, report capabilities, receive dynamic UI (Section-based screens), and send interaction events back. See `docs/PROJECT_OVERVIEW.md` for the full vision.

### Dual-Channel Protocol (`sdui/protocol/`)

- **JSON Topic channel**: Text WebSocket frames with a `SduiMessage` envelope (topic + deviceId + payload). Used for commands, heartbeat, capability exchange, and device lifecycle.
- **UI3 Binary channel**: Custom binary frame format — header (magic `0x5344`, version, msgType, seq, payloadLen, CRC32) + TLV payload, little-endian byte order. Used for section rendering, audio PCM, and input events.
- `SduiProtocolService` encodes/decodes binary frames; `BinaryProtocolCodec` handles the low-level format.
- `DeviceProtocolCatalog` records protocol decisions per device (capability snapshot, negotiated protocol version).

### Section Type System (`sdui/section/`)

12 section types form the UI vocabulary delivered to devices: Hero, Metric, Chart, Timer, Image, Action, Progress, Text, Overlay, List, Toggle, Nav. Each is a record subclass of the sealed `SectionData` interface.

- `SectionScene` — a full page push (all sections for a page).
- `SectionPatch` — incremental updates (add/update/remove individual sections).
- `SectionTypeCatalog` — static registry of all 12 types with field definitions, interaction events, constraints, and compact-mode field filtering.
- `SectionCapabilityAdapter` — adapts sections to device capabilities (RICH vs COMPACT render modes).

### UI State Machine (`sdui/statemachine/`) — **Core Engine**

Replaces the old DAG-based workflow engine (fully removed — the `workflow/` package is empty; `StateMachineLegacyWorkflowCleanup` drops legacy tables at startup). Treats each "state" as a complete page of Section UI; transitions are triggered by device events, command lifecycle events, cron, or manual invocation.

**Four-layer architecture (see `docs/sdui/STATE_MACHINE_ARCHITECTURE.md`):**

| Layer | What | Key Entity |
|-------|------|------------|
| L1: Platform Type Registry | All section types, command schemas, event types pre-registered centrally | `SectionTypeCatalog`, `EventRegistry`, `CommandSchemaRegistry`, `CapabilityCatalog` |
| L2: Flow Definition | Static blueprint: states + transitions + actions, using **slots** instead of device IDs | `StateMachine` (JPA entity, JSON columns for states/transitions) |
| L3: Deployment Instance | Binds a definition to real device IDs, carries runtime state (`currentStateId`, `contextData`) | `StateMachineDeployment` (JPA entity) |
| L4: Device Projection + Protocol Adapter | Auto-diffs section state, pushes scenes/patches to devices, dispatches commands | `StateMachineProjectionService`, `CommandDispatcher` |

**Key design principles:**
- **Section = state carrier**: Everything manifests as section data changes; commands are side effects.
- **Slot abstraction**: Design with slots (e.g., `{{main_device}}`), deploy with concrete device ID mappings. One definition → many deployments.
- **Authoritative state + auto diff**: Platform always holds complete section state; `StateMachineProjectionService` calculates scene vs. patch on each change.
- **Terminal is stateless**: On reconnect, full state is restored from the platform.

**Event bridges feeding the state machine:**
- `StateMachineEventBridge` — listens to device input events (via `EventInputHandler.PayloadEventListener`) and routes them as state transitions.
- `StateMachineCommandEventBridge` — listens to command lifecycle events (via `CommandResultStreamService.CommandResultListener`) and routes ACK/timeout/failure as transitions.
- `CronTriggerScheduler` — `@Scheduled` (every 60s) scans deployed machines for `system:cron`-triggered transitions.

**Controllers and services:**
- `StateMachineController` — REST CRUD at `/api/v1/sdui/state-machines`, plus deploy/undeploy/manual trigger operations.
- `StateMachineService` — core business logic: CRUD for definitions, deploy/undeploy, event handling, state machine execution coordination.
- `StateMachineValidationService` — validates JSON definitions against `EventRegistry` and section type schemas.

### Capability System (`sdui/capability/`)

Defines what IoT devices can do — inputs (buttons, audio, motion), outputs (display, brightness, RGB, audio), commands (with parameter schemas), and display constraints per `size_class`.

- `CapabilityCatalog` — loads preset device capabilities from `capability-catalog.yml` (v2.0 format) at startup.
- `PlatformCapabilityRegistry` — registers server-side capabilities (e.g., `platform.audio.prompt.play` for TTS) with runtime handlers and debug routes.
- `CapabilityContractService` — resolves a device's full capability contract by merging reported capabilities against the catalog.
- `CapabilityInvocationValidator` / `CapabilityValidator` — validates capability invocations (debug and workflow) and state machine deployment definitions.

### Event System (`sdui/event/`)

Configuration-driven event type system loaded from `sdui-event-catalog.yml`.

- `EventDefinition` — canonical event record: `eventId`, `EventKind` (COMMAND/SECTION), `Direction` (INBOUND/OUTBOUND), `EventCategory`, payload schema, constraints.
- `EventRegistry` — in-memory registry with lookup by ID, alias resolution, and validation. Separates command vs. section event domains.
- Section interaction events are dynamically declared per section type (e.g., `action_section` → `action.click`, `toggle_section` → `toggle.change`).

### WebSocket & Device Management

Endpoint: `ws://host:8080/ws/sdui`. `SduiWebSocketHandler` routes text messages by topic and binary messages by msgType via `MessageRouter`. Device sessions tracked in `DeviceSessionManager` (`ConcurrentHashMap<deviceId, WebSocketSession>`).

Key services:
- `DeviceLifecycleService` — device hello/connect, disconnect, heartbeat, timeout detection.
- `ClaimService` — device claiming flow: a device connects unclaimed, an admin assigns it to a space.
- `CommandDispatcher` / `CommandService` — sends `SduiDeviceCommand` to connected devices, tracks delivery with ACK.
- `CommandResultStreamService` — streams command lifecycle results (ACK, timeout, rejection) to listeners (used by the state machine command event bridge).
- `EventStreamService` — SSE streams device events to the frontend management console.

Binary frame handlers implement `BinaryFrameHandler` interface (`AckBinaryHandler`, `ErrorBinaryHandler`, `EventInputHandler`).

### Audio Subsystem (`sdui/service/audio/`)

Provider-based audio pipeline:
- `SttProvider` interface with `FfmpegSttProvider` (whisper) implementation.
- `TtsProvider` interface with `FfmpegTtsProvider` and `MacOsTtsEngine` (macOS `say` command) implementations.
- `AudioRecordHandler` / `AudioRecordChunkHandler` — handle incoming audio recordings and streaming chunks from devices.
- `AudioConversionService` — format conversion (sample rate, codec).

### SDUI Controllers (`sdui/controller/`)

REST APIs under `/api/v1/sdui/` serving the frontend management console:
- `BoardTypeController` — board/device type definitions for the state machine editor.
- `CapabilityController` — device capability information.
- `DebugController` — debug endpoints for testing device commands and section rendering.
- `DeviceController` — device listing, details, status, connection history.
- `EventCatalogController` — event catalog for building state machine transitions.

### DrawThings Image Generation

Calls a local DrawThings (Stable Diffusion) server at `http://127.0.0.1:7860` by default via the Automatic1111-compatible `/sdapi/v1/txt2img` endpoint. See `docs/sdui/DRAWTHINGS_GRPC_INTEGRATION.md`.

## AI Agent Platform (`ai/`) — Auxiliary

AI capabilities are **auxiliary** to the SDUI platform. Used primarily for TTS/STT in audio pipelines, image generation for device display, and optional RAG-powered chat.

| Sub-package | Purpose |
|-------------|---------|
| `ai/config/` | `DynamicAiProviderRegistrar` for runtime LLM registration, `ChatClientConfig` |
| `ai/entity/` | `AgentEntity`, `Conversation`, `McpConnectionEntity` (JPA entities, `@TenantId` on all) |
| `ai/service/` | `AgentChatService` (SSE streaming), `AgentFactory`, `ChatClientService`, `ConversationService`, `McpConnectionService`, `ModelRegistry` |
| `ai/tools/` | `GlobalToolManager` (namespaced tool registry), `CommonTools` (datetime, calculator) |
| `ai/web/controller/` | REST controllers for Agent CRUD, chat (SSE), conversation, MCP connection management |

MCP supports both STDIO and SSE transport types via `spring-ai-starter-mcp-client-webflux`.

## Frontend Project

The management console is a **separate project** (Vue3 + vue-flow) not in this repository. During development it typically runs on **port 8001** and proxies API requests to this backend on port 8080.

Key frontend docs (for state machine editor UI and debug APIs):
- `docs/sdui/front/STATE_MACHINE_UX_DESIGN.md` — node-edge state machine editor UI design
- `docs/sdui/front/STATE_MACHINE_FRONTEND_INTEGRATION.md` — frontend-backend integration for state machines
- `docs/sdui/front/DEBUG_API.md` — debug API reference
- `docs/sdui/front/DEVICE_VIEW_API.md` — device view API
- `docs/sdui/front/SECTION_FRONTEND_INTEGRATION.md` / `SECTION_RENDERING_GUIDE.md` — section rendering

When debugging frontend-reported issues:
- The frontend sends requests with `Origin: http://localhost:8001` and `X-Space-Id` header for multi-tenant isolation
- CORS is configured in `common/web/WebConfig.java` allowing all origins for `/api/**`
- The `GlobalContextInterceptor` extracts `X-Space-Id` into the ThreadLocal context; missing header defaults to `"default"`
- All SDUI REST endpoints are under `/api/v1/sdui/` (spread across `sdui/controller/` and `sdui/statemachine/StateMachineController`)
- Use the curl commands from the frontend's actual requests (Origin + X-Space-Id + Authorization headers) to reproduce issues on the backend side

## Documentation

- `docs/PROJECT_OVERVIEW.md` — current project overview (SDUI Terminal Platform focus)
- `docs/sdui/DRAWTHINGS_GRPC_INTEGRATION.md` — DrawThings integration guide
- `docs/sdui/STATE_MACHINE_ARCHITECTURE.md` — state machine four-layer architecture design
- `docs/sdui/STATE_MACHINE_E2E_FLOW.md` — end-to-end state machine flows
- `docs/sdui/EVENT_API_ANALYSIS.md` — event API design analysis
- `docs/sdui/terminal/PROTOCOL_AND_COMMANDS.md` — SDUI binary protocol, command API, input events, error codes
- `docs/sdui/terminal/SECTION_SCHEMA.md` — 12 section type schemas, layout modes, patch mechanism, per-board rendering
- `docs/sdui/terminal/CAPABILITY_REPORTING.md` — terminal capability reporting protocol (v2 format)
- `docs/sdui/terminal/DEVICE_PROTOCOL_CATALOG_DESIGN.md` — device protocol catalog design
- `docs/sdui/terminal/AUDIO_RECORD_PLATFORM_INTEGRATION.md` — audio record platform integration
- `docs/sdui/front/` — frontend-focused docs (debug APIs, device view, section rendering, state machine UX)
- `docs/api/` — API reference docs for ai, datasource, document, file, security/space modules
