# Repository Guidelines

## Project Structure & Module Organization

AgentNexus is a Java 17 Spring Boot backend for server-driven UI devices. Production code lives under `src/main/java/com/zwbd/agentnexus/`. Its main domains are `sdui/`, `ai/`, `security/`, `common/`, and `drawthings/`. Configuration, YAML catalogs, static debug pages, and the Python worker are in `src/main/resources/`. Tests mirror production packages under `src/test/java/`. Keep documentation in `docs/` and operational tooling in `ops/` or `scripts/`. Treat `target/`, `logs/`, and `upload-dir/` as generated output.

## Build, Test, and Development Commands

Use the checked-in Maven wrapper so builds use a consistent Maven version:

- `./mvnw test` (Windows: `.\mvnw.cmd test`) runs the full test suite.
- `./mvnw test -Dtest=CommandServiceTest` runs one test class; append `#methodName` for one method.
- `./mvnw clean package` compiles, tests, and creates the executable JAR in `target/`.
- `./mvnw spring-boot:run` starts the backend on port 8080. On macOS, add `-Dspring-boot.run.profiles=macos` for compatible MCP commands.

Integration work requires PostgreSQL 15+ with pgvector. Swagger UI is at `http://localhost:8080/swagger-ui.html`.

## Coding Style & Naming Conventions

Use four-space indentation, one public type per file, `PascalCase` types, `camelCase` members, and lowercase packages. Apply role suffixes such as `Controller`, `Service`, `Repository`, `Entity`, `Request`, and `Response`. Prefer constructor injection; Lombok annotations such as `@RequiredArgsConstructor`, `@Data`, and `@Slf4j` are established patterns. Keep controllers thin and domain behavior in services. No formatter or lint plugin is enforced, so match neighboring code and organize imports.

## Testing Guidelines

Tests use JUnit 5 and Mockito through `spring-boot-starter-test`. Name test classes `<Subject>Test` and test methods after observable behavior. Mirror the source package and cover validation, protocol codecs, workflow transitions, and failure paths. `@SpringBootTest` loads the real application context; ensure required database/configuration dependencies are available before running integration-style tests. No numeric coverage threshold is configured, but every behavior change should include focused regression tests.

## Commit & Pull Request Guidelines

Recent commits use concise, imperative Chinese summaries. Keep each commit focused and describe the user-visible or architectural outcome. Pull requests should explain the change, affected modules, configuration or schema impact, and verification commands. Link relevant issues and include screenshots or request/response samples for static UI or API changes.

## Security & Configuration

Do not commit real API keys, JWT secrets, database passwords, uploaded files, or generated audio. Override local values with environment-specific configuration, and redact credentials from logs, examples, and bug reports.
