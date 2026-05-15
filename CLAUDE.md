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

# Run the app
mvnw spring-boot:run
# Or after packaging:
java -jar target/AgentNexus-0.0.1-SNAPSHOT.jar
```

The app starts on port **8080**. Swagger UI at `http://localhost:8080/swagger-ui.html`. API base path is `/api/v1/` (not all controllers follow this).

## Architecture Overview

**Stack:** Java 17, Spring Boot 3.5.5, Spring AI 1.0.3, Maven, PostgreSQL 15+ with pgvector.

### Package Map

| Package | Responsibility |
|---------|---------------|
| `ai` | Agent runtime, RAG chat, model registry, tool management, MCP connections |
| `common` | Shared web config (CORS, GlobalContext ThreadLocal), ApiResponse wrapper, exception handling |
| `datasource` | Multi-database metadata collection via Dialect plugin pattern (MySQL/PG/SQLServer) |
| `document` | DomainDocument lifecycle, ETL pipeline (load → split → vectorize), Python cleaning via GraalVM |
| `file` | File upload/download/storage |
| `sdui` | Server-Driven UI for IoT devices: WebSocket, binary protocol, section orchestration, workflows |
| `security` | JWT auth, RBAC (admin/editor/guest), stateless sessions |
| `space` | Multi-tenant business space CRUD |
| `utils` | Map flattening, StringListConverter (JPA), TemplateRenderService |

### Key Architectural Patterns

**GlobalContext ThreadLocal** (`common/web/GlobalContext.java`): Request-scoped context holding `space_id` and `user_id`. Populated by `GlobalContextInterceptor` from the `X-Space-Id` header. Multi-tenancy uses Hibernate's `@TenantId` + `SpaceIdResolver` to inject the space ID into JPA queries.

**Dialect Plugin Pattern** (`datasource/dialect/`): Abstract `DatabaseDialect` defines the interface; `MySQLDialect`, `PostgreSQLDialect`, `SQLServerDialect` are auto-discovered Spring `@Component` implementations. `DialectFactory` maintains a `Map<DataBaseType, DatabaseDialect>` registry and a `Map<Long, DataSource>` connection pool cache.

**ETL Pipeline** (`document/etl/`): `DocumentLoader` (6 impls: text, Tika, markdown, PPTX, DB stream, DB metadata) → `TokenTextSplitter` → `VectorStore`. `PythonScriptProcessor` runs user Python scripts via GraalVM Polyglot in a sandboxed context for data cleaning.

**Tool Provider Namespacing** (`ai/tools/GlobalToolManager.java`): Tools registered as `providerId:toolName` (e.g., `local:getCurrentDateTime`, `mcp-playwright:navigate`). Supports local `@Tool`-annotated methods and MCP tools.

**SDUI Binary Protocol** (`sdui/protocol/`): Custom binary frame format for IoT devices — header (magic `0x5344`, version, msgType, seq, payloadLen, CRC32) + TLV payload, little-endian byte order.

**Section Type System** (`sdui/section/`): Sealed class hierarchy — `SectionData` subclasses (HeroData, MetricData, ChartData, etc.) map to `SectionType` enum values (HERO, METRIC, CHART, TIMER, IMAGE, ACTION, PROGRESS, TEXT, OVERLAY, LIST, TOGGLE, NAV). Server pushes `SectionScene`/`SectionPatch` to devices via WebSocket, adapted to device capabilities.

### API Response Convention

All controllers return `ApiResponse<T>` (code `20000` = success). The `GlobalResponseAdvice` (`@RestControllerAdvice`) handles `MethodArgumentNotValidException` and `CommonException` globally. Validation uses `jakarta.validation` annotations on request DTOs.

### JWT Auth Flow

1. `POST /api/auth/login` returns a JWT token
2. `JwtRequestFilter` extracts Bearer token from `Authorization` header, validates, sets `SecurityContext`
3. In-memory users: admin (`ROLE_admin`), editor (`ROLE_editor`), guest (`ROLE_sdui_only`)
4. JWT secret and expiration configured via `jwt.secret` / `jwt.expiration` in application.yml
5. All `/api/**`, `/ws/**`, `/` paths are permitted (auth checks are role-based within endpoints)

### Database

PostgreSQL with `pgvector` extension for vector similarity search. Hibernate `ddl-auto: update` manages schema. The `document_chunks` table uses HNSW index with COSINE_DISTANCE and 1024 dimensions. The initial DDL script is at `docs/sql/`.

### WebSocket (SDUI)

Endpoint: `ws://host:8080/ws/sdui`. `SduiWebSocketHandler` routes text messages by topic string and binary messages by msgType int. Device sessions are tracked in `DeviceSessionManager` (`ConcurrentHashMap<deviceId, WebSocketSession>`). Binary frame handlers implement `BinaryFrameHandler` interface; `ErrorBinaryHandler` handles error frames and logs with code/detail.

### Multi-threading

`@EnableAsync` + `CommonConfig.executorService()` (fixed thread pool of 10). Metadata collection uses `CompletableFuture` with configurable timeouts.

## Frontend Project

The management console is a **separate project** (Vue3 + vue-flow) not in this repository. During development it typically runs on **port 8001** and proxies API requests to this backend on port 8080.

Key frontend documentation:
- `docs/sdui/SDUI_FRONTEND_GUIDE.md` — complete frontend integration guide (REST API reference, WebSocket protocol, Section UI, workflows, error handling)
- `docs/sdui/SDUI_BACKEND.md` — backend integration doc with API endpoint reference and workflow editor integration notes

When debugging frontend-reported issues:
- The frontend sends requests with `Origin: http://localhost:8001` and `X-Space-Id` header for multi-tenant isolation
- CORS is configured in `common/web/WebConfig.java` allowing all origins for `/api/**`
- The `GlobalContextInterceptor` extracts `X-Space-Id` into the ThreadLocal context; missing header defaults to `"default"`
- All SDUI REST endpoints are under `/api/v1/sdui/` (see `SduiManagementController.java`)
- Use the curl commands from the frontend's actual requests (Origin + X-Space-Id + Authorization headers) to reproduce issues on the backend side
