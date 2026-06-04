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

**Tests** use JUnit 5 (Jupiter) + Mockito (via `spring-boot-starter-test`). No test database or H2 — `@SpringBootTest` loads the real application context. Tests are under `src/test/java/com/zwbd/agentnexus/`.

## Architecture Overview

**Stack:** Java 17, Spring Boot 3.5.5, Spring AI 1.0.3, Maven, PostgreSQL 15+ with pgvector. Heavy use of Lombok (`@Data`, `@Slf4j`, `@RequiredArgsConstructor`).

### Package Map

| Package | Responsibility |
|---------|---------------|
| `ai` | Agent runtime, RAG chat, model registry, tool management, MCP connections, dynamic LLM provider registration |
| `common` | Shared web config (CORS, GlobalContext ThreadLocal), ApiResponse wrapper, exception handling |
| `datasource` | Multi-database metadata collection via Dialect plugin pattern (MySQL/PG/SQLServer) |
| `document` | DomainDocument lifecycle, ETL pipeline (load → split → vectorize), Python cleaning via GraalVM |
| `file` | File upload/download/storage |
| `sdui` | Server-Driven UI for IoT devices: WebSocket, binary protocol, section orchestration, workflows, capability nodes |
| `security` | JWT auth, RBAC (admin/editor/guest), stateless sessions |
| `space` | Multi-tenant business space CRUD |
| `utils` | Map flattening, StringListConverter (JPA), TemplateRenderService |

### Key Architectural Patterns

**Sealed Class Hierarchies** — Used extensively for type-safe discriminated unions:
- `SectionData` (sealed interface) permits 12 record subclasses (HeroData, MetricData, ChartData, TimerData, ImageData, ActionData, ProgressData, TextData, OverlayData, ListData, ToggleData, NavData), each mapped to a `SectionType` enum value.
- `TriggerDef` (sealed interface) permits 6 trigger types: ManualTrigger, CronTrigger, WebhookTrigger, DeviceEventTrigger, DeviceMessageTrigger, DeviceCommandTrigger.
- `ActionDef` (sealed interface) permits 12 action types including NodeActionDef, FetchAction, PatchSectionAction, ConditionAction, SequenceAction, SetVariableAction, etc.
- All use Jackson `@JsonTypeInfo`/`@JsonSubTypes` for polymorphic JSON serialization.

**GlobalContext ThreadLocal** (`common/web/GlobalContext.java`): Request-scoped context holding `space_id` and `user_id`. Populated by `GlobalContextInterceptor` from the `X-Space-Id` header. Multi-tenancy uses Hibernate's `@TenantId` + `SpaceIdResolver` to inject the space ID into JPA queries.

**Dialect Plugin Pattern** (`datasource/dialect/`): Abstract `DatabaseDialect` defines the interface; `MySQLDialect`, `PostgreSQLDialect`, `SQLServerDialect` are auto-discovered Spring `@Component` implementations. `DialectFactory` maintains a `Map<DataBaseType, DatabaseDialect>` registry and a `Map<Long, DataSource>` connection pool cache.

**ETL Pipeline** (`document/etl/`): `DocumentLoader` (6 impls: text, Tika, markdown, PPTX, DB stream, DB metadata) → `TokenTextSplitter` → `VectorStore`. `PythonScriptProcessor` runs user Python scripts via GraalVM Polyglot in a sandboxed context for data cleaning.

**MessageRouter Plugin Pattern** (`sdui/MessageRouter.java`): Incoming WebSocket messages are routed to handler implementations discovered via Spring constructor injection of `List<TopicHandler>` and `List<BinaryFrameHandler>`. Each handler declares its supported topic string or binary msgType int; the router builds a dispatch map at `@PostConstruct` time. Text messages carry a JSON `SduiMessage` envelope (topic + deviceId + payload); binary messages use the custom binary frame protocol.

**CapabilityNode Plugin Model** (`sdui/workflow/node/`): Extensible workflow action nodes implementing the `CapabilityNode` interface. Each node declares its `type()` string, `NodeSchema` (inputs, outputs, category, icon), and `execute(NodeContext)` → `NodeResult`. `CapabilityNodeRegistry` auto-discovers all `@Component`-annotated nodes via Spring constructor injection. Nodes are organized by category:
- `node/flow/` — ConditionNode, FetchNode, LoopNode, ParallelNode, SequenceNode, SetVariableNode
- `node/device/` — ControlNode, InputReadNode, LightNode, MessageSendNode, PatchSectionNode, PlayAudioNode, SwitchPageNode, UpdatePageNode
- `node/platform/` — LlmChatNode, RagQueryNode, SttNode, TtsNode

**Tool Provider Namespacing** (`ai/tools/GlobalToolManager.java`): Tools registered as `providerId:toolName` (e.g., `local:getCurrentDateTime`, `mcp-playwright:navigate`). Supports local `@Tool`-annotated methods and MCP tools.

**Dynamic AI Provider Registration** (`ai/config/DynamicAiProviderRegistrar.java`): Registers multiple LLM providers (Qwen, DeepSeek, OpenAI-compatible) at runtime from `DynamicAiProvidersProperties`. Enables switching models without restart.

**SDUI Binary Protocol** (`sdui/protocol/`): Custom binary frame format for IoT devices — header (magic `0x5344`, version, msgType, seq, payloadLen, CRC32) + TLV payload, little-endian byte order. Device capability exchange now uses the JSON `device/capabilities` topic rather than a binary TERMINAL_HELLO frame.

**Section Type System** (`sdui/section/`): `SectionData` sealed hierarchy (12 types) with corresponding `SectionType` enum values. `SectionScene` represents a full page push; `SectionPatch` represents incremental updates (add/update/remove). `SectionTypeCatalog` provides a static registry of all 12 section types with field definitions, interaction events, constraints, and compact-mode field filtering. `SectionCapabilityAdapter` adapts sections to device capabilities (RICH vs COMPACT render modes). `SectionSceneBuilder` handles JSON serialization with camelCase-to-snake_case conversion.

**SDUI Workflow Engine** (`sdui/workflow/`): Declarative, device-bound workflow runtime. A `WorkflowDefinition` (persisted as `WorkflowDefinitionEntity`) declares pages, triggers (timer/cron/webhook/event), and actions. `WorkflowService` instantiates a `WorkflowInstance` per device, `TriggerScheduler` fires triggers on cron/timer schedules, and `ActionExecutor` dispatches actions — resolving `${...}` expressions via `VariableResolver` against instance variables, trigger payload, and env vars. Webhook triggers arrive via `WorkflowWebhookController`. `VariableWatcher` auto-rebinds section data when watched variables change. `DagExecutor` supports DAG-based (not just linear) action execution.

**Prompt & Template Rendering**: StringTemplate (`.st`) files in `resources/prompts/` define the system prompt and multi-query expansion prompt for RAG. FreeMarker (`.ftl`) templates in `resources/templates/` render database metadata summaries (table detail, database overview) into structured text fed to the vector store.

### API Response Convention

All controllers return `ApiResponse<T>` (code `20000` = success). The `GlobalResponseAdvice` (`@RestControllerAdvice`) handles `MethodArgumentNotValidException` and `CommonException` globally. Validation uses `jakarta.validation` annotations on request DTOs.

### JWT Auth Flow

1. `POST /api/auth/login` returns a JWT token
2. `JwtRequestFilter` extracts Bearer token from `Authorization` header, validates, sets `SecurityContext`
3. In-memory users: admin (`ROLE_admin`), editor (`ROLE_editor`), guest (`ROLE_sdui_only`)
4. JWT secret and expiration configured via `jwt.secret` / `jwt.expiration` in application.yml
5. All `/api/**`, `/ws/**`, `/` paths are permitted (auth checks are role-based within endpoints)

### Database

PostgreSQL with `pgvector` extension for vector similarity search. Hibernate `ddl-auto: update` manages schema. The `document_chunks` table uses HNSW index with COSINE_DISTANCE and 1024 dimensions. Chat memory uses JDBC-backed persistence with `initialize-schema: always`. Initial DDL and migrations are at `docs/sql/`.

### WebSocket (SDUI)

Endpoint: `ws://host:8080/ws/sdui`. `SduiWebSocketHandler` routes text messages by topic string and binary messages by msgType int via `MessageRouter`. Device sessions are tracked in `DeviceSessionManager` (`ConcurrentHashMap<deviceId, WebSocketSession>`).

Key services in the SDUI layer:
- `SduiProtocolService` — encodes/decodes binary frames, dispatches to device sessions
- `CommandDispatcher` / `CommandService` — sends `SduiDeviceCommand` to connected devices, tracks delivery with ACK
- `DeviceLifecycleService` — handles device hello/connect, claims device to space, capability snapshot parsing
- `EventStreamService` — SSE streams device events to the frontend management console
- `ConsoleLayoutService` — manages per-device console layouts (TTS, RGB, audio presets) via `DeviceConsoleController`
- `ClaimService` — device claiming flow: a device connects unclaimed, an admin assigns it to a space

Binary frame handlers implement `BinaryFrameHandler` interface (`AckBinaryHandler`, `ErrorBinaryHandler`, `EventInputHandler`). Device capabilities are now exchanged via the JSON `device/capabilities` topic rather than a binary hello frame.

### Multi-threading

`@EnableAsync` + `CommonConfig.executorService()` (fixed thread pool of 10). Metadata collection uses `CompletableFuture` with configurable timeouts.

### DrawThings gRPC Integration

DrawThings image generation is integrated via gRPC (see `docs/sdui/DRAWTHINGS_GRPC_INTEGRATION.md`). Server runs on `http://127.0.0.1:7860` by default. The `Txt2ImgNode` capability node exposes it as a workflow action.

## Documentation

Reference docs are in `docs/`:
- `docs/api/` — Per-module API reference: AI/RAG, datasource, document, file, security/space
- `docs/sdui/PROTOCOL_AND_COMMANDS.md` — SDUI protocol, binary frame format, command API, input events, error codes
- `docs/sdui/SECTION_SCHEMA.md` — 12 section type schemas, layout modes, patch mechanism, per-board rendering
- `docs/sdui/CAPABILITY_REPORTING.md` — Terminal capability reporting protocol (v2 format)
- `docs/sdui/DRAWTHINGS_GRPC_INTEGRATION.md` — DrawThings gRPC integration implementation guide
- `docs/RAG_Engine.md`, `docs/DataSource_Access.md`, `docs/DomainDocument_Cleaning.md` — Module-level design docs

## Frontend Project

The management console is a **separate project** (Vue3 + vue-flow) not in this repository. During development it typically runs on **port 8001** and proxies API requests to this backend on port 8080.

When debugging frontend-reported issues:
- The frontend sends requests with `Origin: http://localhost:8001` and `X-Space-Id` header for multi-tenant isolation
- CORS is configured in `common/web/WebConfig.java` allowing all origins for `/api/**`
- The `GlobalContextInterceptor` extracts `X-Space-Id` into the ThreadLocal context; missing header defaults to `"default"`
- All SDUI REST endpoints are under `/api/v1/sdui/` (see `SduiManagementController.java`)
- Use the curl commands from the frontend's actual requests (Origin + X-Space-Id + Authorization headers) to reproduce issues on the backend side
