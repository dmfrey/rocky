# Rocky

Multi-module application targeting Tanzu Platform deployment:

- **`backend/`** — Spring Boot 4.1.1 application (this is the original single-module project; all Gradle/Java content lives here now)
- **`frontend/`** — React + Vite + TypeScript application
- **`ingestion-core/`** — plain Java library (no Spring Boot app of its own) holding the hexagonal document-ingestion slice (domain model, ports, JDBC persistence, Tika/chunk/pgvector indexing) shared between `backend` (web upload) and `sharepoint-batch` (SharePoint polling) — see Shared Ingestion Library below
- **`sharepoint-batch/`** — standalone Spring Boot + Spring Batch service that polls a Microsoft SharePoint document library via Microsoft Graph and (re-)ingests changed files through `ingestion-core`, on its own deployment/schedule separate from `backend` — see SharePoint Batch Service below

## Tech Stack

### Backend (`backend/`)

- **Java 25** (toolchain)
- **Spring Boot 4.1.1** — deployed to Cloud Foundry as a plain executable jar via `java_buildpack` (see Build and Deployment below). It was a GraalVM native-image container until `java-cfenv` was adopted for service bindings — see the GraalVM section below for why that doesn't work as a native image.
- **Spring Data JDBC** + **Liquibase** (PostgreSQL)
- **Spring MVC** (webmvc)
- **Spring AI** — OpenAI-compatible client (`spring-ai-starter-model-openai`, not Ollama's native client) against whichever OpenAI-compatible endpoint an environment has: Ollama's own `/v1` routes locally and in tests (Ollama exposes these alongside its native API — see Running the Backend and Testcontainers below), Tanzu Platform's `genai-service` marketplace proxy or another OpenAI-compatible endpoint in production (see Deployment below); **pgvector** vector store; JDBC-backed chat memory
- **Observability**: Micrometer tracing (Brave bridge), Prometheus, datasource-micrometer
- **Testcontainers**: PostgreSQL, Ollama

### Frontend (`frontend/`)

- **React** + **TypeScript**, scaffolded with **Vite** (`react-ts` template)
- **npm** as package manager
- **React Router** for navigation, **TanStack Query** for server state, **Tailwind CSS v4** for styling — no separate component library
- **Vitest** + **React Testing Library** for tests (`npm test` in `frontend/`)

Dev server proxies `/api` to the backend (`vite.config.ts`) rather than the frontend hardcoding a backend origin — frontend code always calls relative paths (`/api/...`) unless `VITE_API_BASE_URL` is set at build time (see `src/vite-env.d.ts`, `src/api/client.ts`), in which case every request is prefixed with it. Cloud Foundry (see Deployment below) is the deployment topology that needs this: frontend and backend are separate CF routes/origins there, not one shared origin, so the frontend's build has to know the backend's absolute URL ahead of time.

## Local Development

### Backend Prerequisites

This project uses **Podman** (not Docker). Setup is platform-specific:

**macOS** (Homebrew):

```bash
brew install podman podman-compose
podman machine init
podman machine start

# Route the standard docker.sock path to the Podman machine socket (once) -
# lets Testcontainers/Docker-Java find it with no DOCKER_HOST needed
podman-mac-helper install
```

**Linux**:

```bash
# Enable Podman socket (once)
systemctl --user enable --now podman.socket

# Set DOCKER_HOST (add to ~/.bashrc)
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock

# Install podman-compose (once)
sudo apt install podman-compose
```

If `docker.sock` isn't routed to Podman (no `podman-mac-helper`, or on Linux without the socket enabled), Testcontainers needs `DOCKER_HOST` pointed at the Podman socket explicitly. On macOS, `podman machine inspect` reports the live path under `$TMPDIR`; a stable path that survives socket-path churn across machine restarts is the symlink Podman maintains at `~/.local/share/containers/podman/machine/podman.sock`.

### Running Backend Tests

```bash
./gradlew :backend:test
```

Rootless Podman can't run Ryuk (Testcontainers' privileged cleanup-reaper container), so it's disabled for every developer via `environment 'TESTCONTAINERS_RYUK_DISABLED', 'true'` on the `test` task in `backend/build.gradle`. Testcontainers 2.x reads this only from the environment — the older `ryuk.disabled` properties-file key (`testcontainers.properties`) has no effect and was removed from this project.

`TestRestTemplate` moved out of `spring-boot-test` as part of Boot 4.1's HTTP-client module split — it now lives in `org.springframework.boot.resttestclient.TestRestTemplate` (artifact `spring-boot-resttestclient`, added as a `testImplementation` in `backend/build.gradle`), and needs `@AutoConfigureTestRestTemplate` explicitly on the test class (it's no longer auto-wired just from `@SpringBootTest(webEnvironment = RANDOM_PORT)`). See `users/adapter/in/endpoint/AuthControllerIT` for the pattern.

`TestcontainersConfiguration`'s `OllamaContainer` eagerly starts and pulls `nomic-embed-text` (see the bean method) so ingestion tests can call a real embedding model rather than mocking the vector store boundary. If that pull fails with `x509: certificate signed by unknown authority`, it's a TLS-inspecting proxy on the network (this project has hit that with the Symantec WSS Agent) re-signing HTTPS to `registry.ollama.ai` with a CA the container doesn't trust — disable whatever's doing the inspection and retry; it's not a code problem. `execInContainer`'s result is checked for a non-zero exit code deliberately — it does not throw on pull failure, so a silent ignore there masked this exact failure the first time.

`@SpringBootTest` disables Micrometer metrics export by default (a `DisableMetricsExportContextCustomizer` sets `management.defaults.metrics.export.enabled=false`), which makes `PrometheusMetricsExportAutoConfiguration`'s `@ConditionalOnEnabledMetricsExport` never match — so `/actuator/prometheus` 401s in a test even though `management.endpoints.web.exposure.include` genuinely includes it and production works fine. Add `@AutoConfigureMetrics` (`org.springframework.boot.micrometer.metrics.test.autoconfigure`) to any test that needs to exercise the real Prometheus endpoint; see `configuration/ActuatorSecurityIT`.

### Test Coverage

`./gradlew :backend:test` also generates a JaCoCo report (`jacocoTestReport` runs as a `finalizedBy` of `test`) — HTML at `backend/build/reports/jacoco/test/html/index.html`, XML at `backend/build/reports/jacoco/test/jacocoTestReport.xml`. No coverage threshold is enforced (`jacocoTestCoverageVerification` isn't wired up) — this is reporting only, not a build gate. Coverage runs high (class/line coverage both in the 90s%) mostly as a side effect of this project's testing style: every feature has a real integration test exercising its full stack against Testcontainers Postgres/Ollama rather than unit tests with mocked ports, so a passing test suite already walks through nearly every adapter and domain service.

### Running the Backend

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :backend:bootRun
```

The `local` profile (`application-local.yaml`) enables Spring Boot Docker Compose (`spring.docker.compose.enabled: true`, pointed at `backend/src/compose.yaml` via `spring.docker.compose.file` — bootRun's working directory is `backend/`, not `backend/src/`, which isn't one of Boot's default compose-file discovery locations) with `podman-compose`. Without the profile, Docker Compose stays disabled — this default matters: merely having `spring-boot-docker-compose` on the classpath (a `developmentOnly` dependency) makes Spring Boot try to start it unconditionally at every startup, and with no compose file at the working directory it's a **hard startup failure** (`IllegalStateException: No Docker Compose file found`), not a silent no-op. `application.yaml` explicitly sets `spring.docker.compose.enabled: false` as the base default for exactly this reason (required for CI/AOT builds, and for running against a manually-provided datasource).

For a one-off manual run against an already-running Postgres and a real (non-Testcontainers) Ollama instance — e.g. the GPU-backed one, see memory — skip the `local` profile and override directly. The app talks to Ollama via its OpenAI-compatible `/v1` routes, not Ollama's native API (see Tech Stack above), so the URL needs that suffix, and `SPRING_AI_OPENAI_API_KEY` needs an explicit empty value (a deliberate no-auth signal, not the same as leaving it unset — see `OpenAiAutoConfigurationUtil` in `spring-ai-openai`):

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/client_rag_demo \
SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATASOURCE_PASSWORD=postgres \
SPRING_AI_OPENAI_BASE_URL=http://<ollama-host>:11434/v1 \
SPRING_AI_OPENAI_API_KEY= \
./gradlew :backend:bootRun
```

### Running the Frontend

```bash
cd frontend
npm install   # first time only
npm run dev
```

### Running Frontend Tests

```bash
cd frontend
npm test          # single run
npm run test:watch
```

`vite.config.ts` sets `mockReset: true` deliberately — without it, a `vi.fn()`'s call history from one test leaks into the next within the same file, which silently breaks any assertion of the form "not called yet" (found the hard way writing `DocumentsPage.test.tsx`'s confirm-before-upload tests). Also worth knowing: `userEvent.upload()` on a file input respects that input's `accept` attribute and silently drops non-matching files rather than delivering them to the change handler — if you need to test rejection of an unsupported file type, drive it through a real `drop` event instead (see `DocumentsPage.test.tsx`), since drag-and-drop has no such restriction and is the actual bug surface.

## Backend Architecture

The backend (`backend/`) follows **Hexagonal Architecture** (Ports and Adapters). Features are the primary unit of organisation — each feature is a self-contained module under the base package `com.example.clientragdemo`.

### Package Structure

```
com.example.clientragdemo
├── configuration/                        ← cross-cutting Spring configuration
├── shared/                               ← small types multiple features are allowed to depend on
│   └── exception/                        ← NotFoundException, ConflictException — see Error Handling below
└── <feature>/                            ← e.g. notes
    ├── adapter/
    │   ├── in/
    │   │   └── endpoint/                 ← REST controllers (other types: messaging, graphql, …)
    │   └── out/
    │       └── persistence/              ← DB adapters (other types: messaging, external APIs, …)
    ├── application/
    │   ├── domain/
    │   │   ├── model/                    ← domain model (Java Records; may evolve)
    │   │   └── service/                  ← one service class per use case
    │   └── port/
    │       ├── in/                       ← input port interfaces (UseCase + inner Command record)
    │       └── out/                      ← output port interfaces
    └── configuration/                    ← feature-scoped Spring configuration
```

`shared/` is a deliberate, narrow exception to "features are self-contained" — it exists only for types that would otherwise force cross-feature imports (see Error Handling). Don't grow it into a dumping ground; a feature-specific type belongs in that feature, not here.

### Conventions

**Input Ports** (`application/port/in/`):
- One interface per use case, named `<Verb><Feature>UseCase` (e.g., `CreateNoteUseCase`)
- Single method: `execute(Command command)`
- `Command` is an inner record on the interface itself

```java
public interface CreateNoteUseCase {
    Note execute(CreateNoteCommand command);

    record CreateNoteCommand(String title, String content) {}
}
```

The verb-prefixed `Command` name (e.g., `CreateNoteCommand`) keeps commands identifiable when they cross boundaries — important if the system evolves toward event-driven messaging.

**Output Ports** (`application/port/out/`):
- One interface per operation, verb-first, ending with `Port` (e.g., `LoadNotePort`, `SaveNotePort`)

**Domain Services** (`application/domain/service/`):
- One service class per use case, implementing the corresponding interface (e.g., `CreateNoteService implements CreateNoteUseCase`)

**Domain Model** (`application/domain/model/`):
- Java Records to start; may gain behaviour as requirements evolve
- Named after the real-world concept, singular, no suffix (e.g., `Note` not `NoteModel`)

**Input Adapters** (`adapter/in/`):
- Thin — delegate all work to input port interfaces; contain no business logic
- Types so far: `endpoint` (REST via Spring MVC), `security` (Spring Security SPI implementations, e.g. a `UserDetailsService` that's "driven" by the framework rather than by HTTP — see `users/adapter/in/security/`)

**Output Adapters** (`adapter/out/`):
- Implement output port interfaces; encapsulate the output technology
- Types so far: `persistence` (Spring Data JDBC), `vectorstore` (Spring AI's `VectorStore`/pgvector — chunking, embedding, filtered delete; see `documents/adapter/out/vectorstore/`), `async` (dispatches to a background executor rather than blocking the caller — the in-process stand-in for a message queue; see `documents/adapter/out/async/`), `ai` (Spring AI's `ChatClient` — streaming, RAG grounding via advisors, non-streaming completions; see `chat/adapter/out/ai/`), `chatmemory` (reads Spring AI's `ChatMemory` bean; see `chat/adapter/out/chatmemory/`)

**Feature Configuration** (`<feature>/configuration/`):
- Feature-scoped `@Configuration` only
- `@ComponentScan` scoped to the feature's root package — picks up `@Service`, `@Repository`, etc. within this feature only
- `@EnableJdbcRepositories` scoped to the feature's persistence package — limits Spring Data JDBC repository scanning to this feature
- For anything with more than one field, or that wants a typed default/validation, prefer a `@ConfigurationProperties` record over `@Value` (`@Value` is still fine for a single simple property like `SecurityConfig`'s CORS origins list). See `chat/configuration/ChatMemoryProperties` — bind it with `@EnableConfigurationProperties` on the feature's `@Configuration` class, not `@ConfigurationPropertiesScan`, to keep the feature's Spring wiring explicit and visible in one place rather than discovered by scanning.

**Root Configuration** (`com.example.clientragdemo.configuration/`):
- Cross-cutting concerns only (security, observability config, etc.)

**Error Handling**:
- One global `@RestControllerAdvice` (`configuration/GlobalExceptionHandler`) maps exceptions to HTTP status for every controller — no per-controller `@ExceptionHandler` methods.
- A feature's "not found" exception extends `shared.exception.NotFoundException` (→ 404); a "conflict" exception (e.g. a uniqueness violation) extends `shared.exception.ConflictException` (→ 409). This is *why* `shared/` is allowed to exist: the global handler only needs to know about these two base types, never about a specific feature's exception class, so a new feature never requires touching it.
- Plain `IllegalArgumentException` → 400 (input validation in a domain service) and Spring Security's `AuthenticationException` → 401 are handled generically there too.

### Adding a New Feature

1. Create the package tree under `com.example.clientragdemo.<feature>`
2. Define domain model records in `application/domain/model/`
3. Define input port interfaces (with inner `Command` records) in `application/port/in/`
4. Define output port interfaces in `application/port/out/`
5. Implement one service per use case in `application/domain/service/`
6. Implement input adapter(s) in `adapter/in/endpoint/` (thin — call the port)
7. Implement output adapter(s) in `adapter/out/persistence/` (implement the port)
8. Wire everything in `<feature>/configuration/`
9. Add Liquibase changeset(s) in `backend/src/main/resources/db/changelog/`

## Shared Ingestion Library (`ingestion-core/`)

Holds everything both `backend` and `sharepoint-batch` need to turn bytes into an indexed,
status-tracked document: `Document`/`ContentType`/`DocumentStatus` domain model, the
`Save`/`LoadById`/`LoadByFilename`/`LoadBySourceAndExternalId`/`LoadAll`/`Delete`
`DocumentPort`s, `IngestDocumentUseCase`/`DeleteDocumentUseCase`, `DocumentPersistenceAdapter`
(Spring Data JDBC), and `DocumentIndexingAdapter` (Tika parse → `TokenTextSplitter` chunk →
`VectorStore.add`). Same hexagonal conventions as every `backend` feature — one difference:
`IngestionCoreConfiguration` (`@ComponentScan` + `@EnableJdbcRepositories` +
`@EnableConfigurationProperties`) has to be explicitly `@Import`ed by each consuming app's own
config, since this module has no `@SpringBootApplication` of its own to auto-scan it.

- **Plain Java library, not a Spring Boot app** — no `org.springframework.boot` plugin, no Boot
  starters as main dependencies (`ingestion-core/build.gradle` uses `java-library` +
  `io.spring.dependency-management` importing the same `spring-boot-dependencies`/`spring-ai-bom`
  versions `backend` uses, so both stay in lockstep). Only the non-starter `spring-ai-model`/
  `spring-ai-vector-store` API jars are `api` dependencies — each consuming app supplies the real
  `VectorStore`/`EmbeddingModel` beans from its own starter dependencies, so this module never
  drags Boot autoconfiguration or a pgvector driver into whatever depends on it.
- **`Document.source`/`externalId`/`sourceVersion`** (added by `documents-003`, alongside the
  original `filename`-only tracking from the web-upload-only era) distinguish `UPLOAD` rows
  (identified by filename, `externalId`/`sourceVersion` null) from `SHAREPOINT` rows (identified
  by the Graph driveItem id via `LoadDocumentBySourceAndExternalIdPort` — filename can collide
  across SharePoint folders, a driveItem id can't; `sourceVersion` holds the driveItem's eTag for
  change detection). The unique constraint moved from `filename` alone to `(source, filename)`.
- **Schema ownership**: `backend` remains the *sole* Liquibase runner against `documents`/
  `vector_store` (`ingestion-core`'s changelog is packaged into its jar and referenced by
  `backend`'s own `db.changelog-master.yaml` via classpath, same cross-jar `sqlFile`/`include`
  trick already used for `spring-ai-model-chat-memory-repository-jdbc`'s vendor schema).
  `sharepoint-batch` never runs these changesets itself — only its own (see below) — to avoid two
  independent Liquibase instances racing over one changelog-lock/history table. Operational
  consequence: **`sharepoint-batch` must not be pointed at a database `backend` hasn't migrated
  at least once.**
- **Testing**: no HTTP layer to test through (that's `backend`'s `DocumentController`), so
  `ingestion-core`'s own IT (`IngestDocumentServiceIT`) exercises `IngestDocumentUseCase`/
  `DeleteDocumentUseCase` directly against real Testcontainers Postgres+pgvector+Ollama, via a
  test-only synthetic `@SpringBootApplication` (`IngestionCoreTestApplication`) that `@Import`s
  `IngestionCoreConfiguration` — the standard shape for testing a library module that deliberately
  isn't a Boot app itself. A test-scope-only gotcha found here: Ollama's OpenAI-compatible
  endpoint 404s ("model not found, try pulling it first") unless
  `spring.ai.openai.embedding.model` explicitly matches whatever
  `TestcontainersConfiguration` actually pulled (`nomic-embed-text`) — leaving it unset doesn't
  fall back to that model, it falls back to Spring AI's own OpenAI default
  (`text-embedding-ada-002`), which was never pulled. Set in both `ingestion-core`'s and
  `sharepoint-batch`'s test `application.yaml`.
- **`IngestionCoreConfiguration` supplies its own `JdbcAggregateOperations`
  (`IngestionJdbcConfiguration extends AbstractJdbcConfiguration`), rather than relying on the
  consuming app's own `JdbcRepositoriesAutoConfiguration`** — found necessary after the exact same
  failure (`Cannot resolve reference to bean 'JdbcAggregateOperations'` on
  `DocumentJdbcRepository`) recurred through three *different* mechanisms across three different
  consuming-app contexts: CF's `java_buildpack` injecting `java-cfenv` (see SharePoint Batch
  Service below), `sharepoint-batch`'s own now-removed `@EnableBatchProcessing` (which bypasses
  Boot's `BatchAutoConfiguration` entirely), and `spring-cloud-task-batch`'s own autoconfiguration
  racing `@EnableJdbcRepositories`'s eager repository-FactoryBean creation. Boot's
  `JdbcRepositoriesAutoConfiguration` is deferred until *after* regular `@Configuration` classes
  are processed, but `@EnableJdbcRepositories` (used by `IngestionCoreConfiguration` directly, not
  via autoconfiguration) creates its repository FactoryBean eagerly in that earlier pass — a
  chicken-and-egg ordering race that happened to resolve correctly often enough to look solid,
  until some new autoconfiguration class in a *particular* consuming app shifted bean-creation
  order enough to expose it. A library depended on by multiple apps with different
  autoconfiguration profiles shouldn't rely on getting this right by accident in every one of
  them. `IngestionJdbcConfiguration` overrides just `jdbcDialect()` with
  `@ConditionalOnMissingBean(JdbcDialect.class)` so it still defers to `backend`'s own explicit
  `JdbcDialectConfig` (added for a *different*, AOT-specific reason — see GraalVM native image
  below) rather than double-defining that one bean, which otherwise fails outright with
  `BeanDefinitionOverrideException` under AOT's stricter bean-registration pass (caught by
  `:backend:processTestAot`, not assumed).
- **Supported types: PDF, DOC, DOCX, TXT; PNG/JPG/GIF only with the optional vision model** (`ContentType` is only an allow-list - Tika auto-detects the format; legacy `.doc` goes through POI via Tika's Microsoft module). There is **no OCR** (Tika needs a Tesseract binary), so `DocumentIndexingAdapter` throws when nothing extractable comes out - a scanned/image-only file ends up `FAILED` with a clear message instead of `READY` with zero chunks. **Optional vision model** (`app.genai.vision-service`, i.e. `APP_GENAI_VISION_SERVICE`; unset = off): when a third, vision-capable `ai-models` service is bound and named, image files (`ContentType` `PNG`/`JPEG`/`GIF`) are transcribed by it, and a PDF that yields no Tika text is rendered page by page (`PdfPageRenderer`, PDFBox, 150 dpi, at most `app.documents.ingestion.vision-max-pages`=100) and each page transcribed (`VisionTranscriptionAdapter` behind `TranscribeImagePort`, wired by `GenaiVisionConfiguration` in `ingestion-core` so both apps get it). The vision model is deliberately *not* a `ChatModel` bean (backend's single chat model must stay unambiguous). Without it, images and scanned PDFs fail with a message saying so. A vision service whose plan grants no `VISION` model does *not* fail startup (vision is optional, and `cf marketplace` doesn't show capabilities, so a site may only learn this by trying): it logs a warning and documents fail with "grants no VISION model"; a service granting several still needs `app.genai.vision-model`. Only a PDF with *no* text goes through vision; a mixed text/scanned PDF is indexed from its text pages only. Verified on this foundation that `tanzu-gemma-4-31B-it-qat` advertises `CHAT, TOOLS, VISION` and reads an image correctly (call the binding's `openai_api_base`, not `api_base`). On CF it is enabled with cf commands, not the manifest (a manifest can't bind conditionally): `offline/enable-vision.sh` (run by `deploy.sh` when `vision-service` is set in `vars.yml`) binds the service and sets the env var; `cf push` keeps both.
- **`documents-004` makes `uploaded_by` nullable** — it was `NOT NULL` from the original
  web-upload-only design (`documents-001`), before `SOURCE_SHAREPOINT`/other automated-ingestion
  rows existed; those legitimately have no human uploader. Caught by `IngestDocumentServiceIT`
  failing with a real `DataIntegrityViolationException` against a real Postgres, not assumed.

## SharePoint Batch Service (`sharepoint-batch/`)

Polls a SharePoint document library on a schedule and (re-)ingests changed files through
`ingestion-core`. One correction worth keeping in mind: there's no GraphQL API for SharePoint —
Microsoft's modern surface is **Microsoft Graph**, a REST+OData API
(`com.microsoft.graph:microsoft-graph`, the official Java SDK); "the SharePoint GraphQL client"
in casual conversation means this.

- **Spring Batch job shape**: `sharePointSyncJob` = `resolveDeltaLinkStep` (tasklet: loads the
  delta link persisted from the previous poll, or builds the base delta URL on a first-ever run)
  → `syncStep` (chunk-oriented: `ChangedItemReader` pages Graph's delta query via
  `ListChangedItemsPort`, `ChangedItemProcessor` finds-or-creates the `Document` row and downloads
  content via `DownloadItemContentPort`, `ChangedItemWriter` calls `IngestDocumentUseCase`/
  `DeleteDocumentUseCase`) → `persistDeltaLinkStep` (tasklet: writes the new delta link, only
  reached after `syncStep` fully succeeds). The delta link travels step-to-step via the **job's**
  `ExecutionContext` (a step's own `ExecutionContext` isn't visible to other steps) —
  `ChangedItemReader` reads its start link via `@StepScope`'s `#{jobExecutionContext[...]}` SpEL
  binding and writes the final one back via `ItemStream#update`, promoted from step to job level
  by an `ExecutionContextPromotionListener` registered on `syncStep`.
- **Deployment model: a Spring Cloud Task, not a long-running service** — `SharepointBatchApplication`
  is `@EnableTask` + `spring-cloud-starter-task` (pulling in `spring-cloud-task-batch`), not
  `@EnableScheduling`. `TaskJobLauncherApplicationRunner` (from `spring-cloud-task-batch`) launches
  `sharePointSyncJob` once at startup and the JVM exits when it's done — there's deliberately no
  web starter on the classpath (Boot infers `WebApplicationType.NONE`), since a persistent process
  with nothing to keep it alive would just be dead weight. `sharePointSyncJob`'s
  `.incrementer(new RunIdIncrementer())` gives each fresh JVM invocation a unique `run.id`
  automatically, since there's no `@Scheduled` loop supplying one — every invocation is a brand
  new process with no CLI-supplied `JobParameters` of its own. On Cloud Foundry this deploys as a
  **CF Task** (`cf run-task rocky-sharepoint-batch`, see `manifest.yml` — pushed with
  `instances: 0`, never started as a running app) triggered by an *external* scheduler (Scheduler
  for VMware Tanzu, or plain cron calling `cf run-task`); this app no longer schedules itself
  internally. Needs its own schema on top of Spring Batch's — `TASK_EXECUTION`/
  `TASK_EXECUTION_PARAMS`/`TASK_TASK_BATCH`/etc. (`db.changelog-sharepoint-002.yaml`, same
  vendor-`sqlFile` convention as Spring Batch's own schema, `spring.cloud.task.initialize-enabled: false`
  so Liquibase stays the sole owner) — `TASK_TASK_BATCH` is what correlates a `TASK_EXECUTION` row
  with the job's own `BATCH_JOB_EXECUTION` row. Tests override `spring.batch.job.enabled: false`
  (the opposite of production) so the job doesn't auto-fire at context startup before each test's
  own `@BeforeEach`/`JobLauncherTestUtils.launchJob(...)` gets to run.
- **Graph/SharePoint specifics stay entirely inside this app** (`sharepoint/adapter/out/graph/`,
  `sharepoint/configuration/`) — never in `ingestion-core`, which stays source-agnostic. Auth is
  app-only client-credentials (`com.azure:azure-identity`'s `ClientSecretCredential`, scope
  `https://graph.microsoft.com/.default`) against an Azure AD app registration with application
  permission `Sites.Selected` scoped to one site.
- **A second, filesystem-backed ingestion source exists alongside Graph** — `app.ingestion.source`
  (`graph`, default, or `filesystem`) picks between `GraphChangedItemsAdapter`/
  `GraphItemDownloadAdapter` and `FilesystemChangedItemsAdapter`/`FilesystemItemDownloadAdapter`
  (`sharepoint/adapter/out/filesystem/`), each pair `@ConditionalOnProperty`-gated so only one is
  ever wired in — including the `GraphServiceClient` bean itself, so a filesystem-only deployment
  never needs real Graph credentials. This is a genuine alternative production input (the client
  themselves floated an NFS-style mount as a possible SharePoint substitute), not just test
  scaffolding, tested end-to-end against a real Cloud Foundry `block-storage` volume service and a
  real document corpus. A plain filesystem has no delta-link/eTag concept, so
  `FilesystemChangedItemsAdapter` always does one full recursive scan per invocation (size+mtime
  stands in for `eTag`) and **does not detect deletions** — a real gap before this becomes more
  than a one-off test. The container mount path a bound volume service gets is only knowable
  *after* binding (CF assigns a GUID-based path per binding, and the block-storage broker has no
  bind parameters to request one), so the app reads it from its own `VCAP_SERVICES`:
  `sharepoint/configuration/FilesystemVolumeEnvironmentPostProcessor` (registered in
  `META-INF/spring.factories`) sets `app.filesystem-source.path` from
  `<volume service>.volume_mounts[].container_dir` via `java-cfenv`'s `CfEnv` — the binding named by
  `app.filesystem-source.volume-service` (default `rag-docs-volume`). It adds that as the
  *lowest-precedence* property source and does nothing if the path is already set, so an explicit
  `APP_FILESYSTEM_SOURCE_PATH` always wins; that is also why `application.yaml` deliberately has no
  `path` default (it would count as explicit), and `FilesystemSourceProperties` falls back to
  `/mnt/rag-docs` itself. This replaced a hand-copied `APP_FILESYSTEM_SOURCE_PATH` that had to be
  re-set (`cf set-env` + restage) after every fresh bind. `FilesystemChangedItemsAdapter` logs the
  path it will scan at startup.
- **Deployment: plain executable jar via `java_buildpack_offline`, bindings via `java-cfenv`.** It has
  been through three shapes: a JVM jar (original), a GraalVM native-image container (to dodge
  `java_buildpack` staging OOMs, Exit status 137 on three cells), and now a JVM jar again — because
  adopting `java-cfenv` (below) is not native-image friendly (see the GraalVM section), and the
  staging OOMs have not recurred for `backend` on the same foundation. The first batch deploy is
  the real test of that for this app (Tika/POI/PDFBox/Graph on a JVM; it gets 1500M, like
  `backend`). Moving off native also means `app.ingestion.source` (`graph`/`filesystem`) is chosen
  at runtime from the environment — the native image baked it in at build time, which is why CI
  used to set `APP_INGESTION_SOURCE=filesystem` while building it.
- **`java_buildpack` breaks `@EnableJdbcRepositories` outright unless explicitly told not to
  "help"** — unlike a Docker/native-image push, `java_buildpack` unconditionally adds
  `java-cfenv-boot` (on-disk component name `java_cf_env`, confirmed via `cf run-task` shelling
  out to `find` — `cf ssh` was blocked in the environment this was debugged from, `cf run-task`
  wasn't, and turned out to be the more reliable diagnostic tool anyway since it doesn't need an
  interactively-reachable running instance) to the classpath whenever it detects bound services.
  Its `CloudProfileApplicationListener` activates a `cloud` Spring profile and rebinds the
  datasource from `VCAP_SERVICES`, which broke `ingestion-core`'s `@EnableJdbcRepositories` wiring
  outright on the very first real deploy (`JdbcAggregateOperations` bean not found — see
  `IngestionJdbcConfiguration` above, which is the durable fix; disabling this buildpack component
  is still worth doing regardless, since the datasource rebind itself is redundant here). The fix
  for *this* symptom is the buildpack's own documented per-component opt-out,
  `JBP_CONFIG_JAVA_CF_ENV: '{enabled: false}'` — note this is *not* the same as the older,
  differently-named `JBP_CONFIG_SPRING_AUTO_RECONFIGURATION` (a legacy, unrelated framework
  component with zero effect on this buildpack version despite the superficially similar purpose —
  tried first, confirmed to do nothing here). This app already configures its datasource
  explicitly via `SPRING_DATASOURCE_*` env vars, so the buildpack's own auto-reconfiguration was
  always redundant.
- **`java-cfenv` is now used on purpose here too, with the buildpack's copy still disabled.** The
  jar bundles `java-cfenv-boot` (an `api` dependency of `ingestion-core`, so both apps get it):
  its `CfDataSourceEnvironmentPostProcessor` sets `spring.datasource.*` from the Postgres binding
  — the same mechanism that broke `@EnableJdbcRepositories` when the *buildpack* injected it, now
  harmless because `IngestionJdbcConfiguration` supplies `JdbcAggregateOperations` itself.
  `JBP_CONFIG_JAVA_CF_ENV: '{enabled: false}'` stays in `manifest.yml` so there is exactly one copy.
  The embedding model comes from the `rocky-embed` binding via `ingestion-core`'s
  `GenaiBindingsConfiguration` (`app.genai.embedding-service`, set in `application-cloud.yaml`,
  which `SPRING_PROFILES_ACTIVE: cloud` activates); this app is bound to that one service only —
  it never chats. `EmbeddingDimensionCheck` runs `@Order(HIGHEST_PRECEDENCE)` so it stops the
  task *before* `TaskJobLauncherApplicationRunner` starts ingesting if the model's dimension
  doesn't match `vector_store.embedding`. The `SPRING_DATASOURCE_*`/`SPRING_AI_OPENAI_*` env vars
  and `vars.yml` that this app used are gone.
- **`spring-batch-core` 6.0.5 (pulled in transitively by `spring-boot-starter-batch` under Boot
  4.1.1) reorganized nearly every core class's package** from the Spring Batch 5.x layout most
  documentation/examples still show — confirmed by inspecting the actual jars, not assumed:
  `Job`/`Step` moved to `org.springframework.batch.core.job`/`.step`; `JobParameters(Builder)` to
  `org.springframework.batch.core.job.parameters`; `JobExplorer` to
  `org.springframework.batch.core.repository.explore`; `ItemReader`/`Writer`/`Processor`/`Stream`/
  `ExecutionContext`/`Chunk` all moved out of `org.springframework.batch.item` entirely into
  `org.springframework.batch.infrastructure.item` (a separate `spring-batch-infrastructure` jar);
  `RepeatStatus` similarly into `org.springframework.batch.infrastructure.repeat`. Also new in
  6.0: a unified `JobOperator` (extends `JobLauncher`) is the modern way to launch/inspect jobs,
  replacing separate `JobLauncher`+`JobExplorer` calls — though at this exact 6.0.5 release
  `JobLauncher`, `JobOperator`, and `JobOperator.getRunningExecutions` are *themselves* already
  flagged `@Deprecated(forRemoval=true)`, with no non-deprecated alternative shipped yet (confirmed
  via `javap`, not a false positive) — this is 6.0's launch API still mid-churn, not something
  this codebase got wrong; revisit on the next Spring Batch upgrade. `spring-batch-test`'s own
  package (`org.springframework.batch.test`) is unchanged.
- **Testing** (no way to test against a real SharePoint tenant — everything below leans on fakes):
  unit tests for `ChangedItemProcessor` against Mockito-mocked ports
  (`ChangedItemProcessorTest`); a full-job IT (`SharePointSyncJobIT`, `@SpringBatchTest` +
  `JobLauncherTestUtils`, real Testcontainers Postgres+pgvector+Ollama for the shared-schema
  writes) with the Graph side replaced by `FakeListChangedItemsPort`/`FakeDownloadItemContentPort`
  (`@Primary` test beans — the real Graph adapters are still component-scanned into the test
  context alongside them, so `@Primary` is what wins the ambiguity, not an exclusion filter),
  covering a first-ever full-enumeration run and a single-item-skip run; `SyncStateJdbcAdapterIT`
  for the delta-link persistence round trip. **Not covered**: a restart-after-failure scenario and
  a mixed incremental add+modify+delete run — real gaps to close before this handles production
  traffic, not implemented here due to time. One gotcha worth knowing before adding more
  `@SpringBatchTest` test methods: its `StepScopeTestExecutionListener` reflectively scans the
  test class's own declared methods for *any* method whose return type is `StepExecution` —
  matched by return type alone, not by name — and invokes whichever one it finds with zero
  arguments to set up step-scope test context. A local helper method that happens to return
  `StepExecution` (regardless of what it's called or how many parameters it takes) gets swept up
  and breaks every test in the class with `Could not create step execution from method: <name>`;
  confirmed by decompiling the listener, not guessed. `StepExecutionLookup` is a separate
  (non-nested) class for exactly this reason — renaming the method doesn't help, since the match
  isn't name-based.
- **Unverified against a real tenant**: `GraphChangedItemsAdapter`/`GraphItemDownloadAdapter`
  compile cleanly against the real `microsoft-graph` SDK jar (confirming the method chains/type
  names are real), but there's no way to confirm the actual request/response shape is correct
  without a real SharePoint site. The first thing to do once the client supplies tenant/site/drive
  credentials is a real end-to-end poll against a small test document library, before trusting
  this against production data.

## Database Migrations

Liquibase changelogs live in `backend/src/main/resources/db/changelog/`. The master changelog is `db.changelog-master.yaml`. Add new changesets as separate files and include them from the master.

Several dependencies own tables that need a schema but (correctly) don't manage it themselves against a real Postgres — check before hand-writing DDL:
- If the owning module ships real per-vendor `.sql` files (e.g. `spring-ai-model-chat-memory-repository-jdbc`'s `schema-postgresql.sql`), reference the file directly from the changelog with a `sqlFile` change pointing at its classpath resource path inside the dependency jar (`db/changelog/chat/db.changelog-chat-001.yaml` is the example) — don't copy its contents in.
- If it doesn't (e.g. `spring-ai-pgvector-store`'s `PgVectorStore` builds its DDL as Java string templates, not a shipped file), there's nothing to reference; the DDL has to be reconstructed by hand and verified against the library's actual runtime behavior (`db/changelog/documents/db.changelog-documents-002.yaml` is the example — verified by decompiling `PgVectorStore.class`, then confirming a real insert/query/delete against it works end-to-end in `DocumentControllerIT`, not just by starting successfully).
- Either way, set that dependency's own `initialize-schema` property to disabled (`false` / `never`, whichever it uses) so it doesn't also try to create the table itself.

## Build

### Executable jar (CI)

`.github/workflows/backend.yaml` runs the backend and ingestion-core tests, then (on push to `main`, or manual dispatch) publishes the backend's `bootJar` to this repo's GitHub Packages Maven registry (`com.example:rocky-backend`, see CI/CD below), authenticating with the workflow's own `GITHUB_TOKEN`. `deploy-cf.yaml` builds the same `./gradlew :backend:bootJar` itself before `cf push`. Locally, `./gradlew :backend:bootJar` writes `backend/build/libs/backend-<version>.jar`.

(`sharepoint-batch` is built the same way: `sharepoint-batch.yaml` publishes its `bootJar`; `./gradlew :sharepoint-batch:bootJar` builds it locally.)

### GraalVM native image

**Neither `backend` nor `sharepoint-batch` ships as a native image anymore.** The notes below are kept as history because they are hard-won and apply if anyone returns to native; the `*RuntimeHints` classes in `backend/configuration/` and `ingestion-core` are now inert but harmless, and removing them is a possible cleanup (`sharepoint-batch`'s own hint classes were deleted along with its native build).

Why both apps left native: adopting `java-cfenv` (Postgres + GenAI bindings straight from `VCAP_SERVICES` instead of `vars.yml`, see Deployment) broke the native image. `java-cfenv` parses `VCAP_SERVICES` with `json-io` (`com.cedarsoftware`), which is fundamentally reflective: its static initializer reads its own `config/*.txt` files from the classpath and then loads/instantiates every type they name, including generated array types. In a spike, hints for the config files and for primitive arrays got the native executable further, then it failed on `java.util.AbstractMap$SimpleEntry[][]` — Spring's `TypeReference` also mangles array type names (it emitted `java.lang.boolean[][][]`), so those have to be hand-written in a `reachability-metadata.json`. Each fix cost a ~3 minute rebuild and exposed the next gap; it was abandoned. Two further native-only traps found on the way: Spring AOT evaluates `@ConditionalOnProperty`/profile properties at build time, when there is no `VCAP_SERVICES`, so the GenAI beans are silently excluded unless `processAot` is given `--spring.profiles.active=cloud --genai.locator.config-url=<placeholder>`; and commit `63481c7` (in `main`'s history; the spike branch itself is deleted) has the partial native work if anyone wants to resume it (alternative: a small `EnvironmentPostProcessor` parsing `VCAP_SERVICES` with Jackson instead of using `java-cfenv-boot` — untested).

Working (back when both apps were native images), as of the fixes below - all discovered by actually running `processAot`, `nativeCompile`, and a real native executable against Postgres/Ollama, not anticipated in advance:

- **`processAot` needs a live, reachable database purely because of Spring Data JDBC's dialect auto-detection** (`DataJdbcRepositoriesAutoConfiguration$SpringBootJdbcConfiguration#jdbcDialect`, `@ConditionalOnMissingBean`) - AOT's eager bean-factory introspection instantiates `NamedParameterJdbcOperations` → `dataSource` to query the dialect from a real connection, and does so *before* `@ConfigurationProperties` binding has run for that early pass, so `spring.datasource.*` (env var or `-D`, doesn't matter) is seen as unset and dataSource creation fails with "Failed to determine a suitable driver class" even with a real reachable database and correct properties. Fixed by declaring an explicit `JdbcDialect` bean (`configuration/JdbcDialectConfig`, `JdbcPostgresDialect.INSTANCE` - this app is Postgres-only) so Boot's auto-detecting bean never gets created at all; also removes a real, if small, per-startup DB round trip in production.
- **Liquibase's changelog parser reflectively invokes each Change/config class's setters (to populate it) and getters (to recompute checksums, on *every* startup, not just the first)** - GraalVM strips unregistered members, so an uncovered type throws `MissingReflectionRegistrationError` at whatever point that type's parsing/checksum path is first hit, which can be startup N, not necessarily startup 1. `configuration/LiquibaseRuntimeHints` registers exactly the change types this project's changelogs use (`createTable`, `createIndex`, `sql`, `sqlFile`) plus their shared superclasses. Add a type there if a new changelog change type is introduced and this error resurfaces.
- **`org.eclipse.angus:angus-activation`** (pulled in transitively via `jaxb-core`, used by Tika/POI for XML/OOXML parsing, nothing to do with email) **registers a native-image `Feature` that unconditionally references `jakarta.mail.Part`** - without that class on the classpath, native-image generation itself fails with `NoClassDefFoundError` before the app even starts. Fixed with a `runtimeOnly` dependency on just `jakarta.mail:jakarta.mail-api` (API only, no implementation, no functionality used) to satisfy the class lookup.
- **PDFBox 3.x's `PDDocument` static initializer touches `java.awt.image.ColorModel`/`Raster`** (AWT/Java2D, apparently regardless of whether the PDF or the extraction path involves images at all) **- on macOS specifically, this crashed a directly-invoked (`nativeCompile`) native executable at runtime with `NoSuchFieldError: ColorModel.nBits`**, a JNI field-registration gap in GraalVM's macOS AWT native-image support. Did not reproduce building the same app the way it's actually shipped - via Cloud Native Buildpacks (Linux, see above) - so treated as a macOS-native-image-only gap, not something this project's production path hits.
- **PDFBox ships none of its own native-image metadata at all** (no `META-INF/native-image` directory in its jar, confirmed by inspecting it directly) **- every classpath resource it reads at runtime is silently missing from the native image unless registered**: the first real PDF uploaded to the actual deployed backend failed ingestion with `IOException: resource '/org/apache/pdfbox/resources/afm/ZapfDingbats.afm' not found` - a font-metrics file for one of the 14 standard PDF fonts, needed depending on what a given PDF actually uses (fonts/CMaps/ICC profiles/glyphs vary per document, so this is exactly the kind of gap no fixed set of local test PDFs would reliably catch either). `configuration/PdfBoxRuntimeHints` registers all of `org/apache/pdfbox/resources/**` rather than one file at a time as different PDFs exercise different resources.
- **`openai-java-core`'s own bundled `META-INF/native-image/reflect-config.json` is itself incomplete** - about a third of its ~12,600 entries (confirmed by inspecting the shipped JSON) grant only reflection *query* access, not *invoke* access, to `com.openai.models.*` request/response classes Jackson deserializes reflectively. Invisible to any local/CI testing, which only ever exercises Ollama's `/v1` endpoint: the first real embeddings call against Tanzu Platform's genai proxy in production crashed with `MissingReflectionRegistrationError` invoking `CreateEmbeddingResponse$Usage.putAdditionalProperty` - a Jackson "any-setter" fallback for JSON fields the generated model doesn't declare, which that proxy's responses have and Ollama's don't. `configuration/OpenAiRuntimeHints` scans `com.openai.models` via the classpath and grants full invoke/field access to everything found, rather than fixing one class at a time as each new response shape gets exercised in production.
- Micrometer/Prometheus needed no extra hints - `micrometer-registry-prometheus` is `runtimeOnly` and worked without further changes.

### CI/CD

**Executable jars (GitHub Packages)**: each of `backend.yaml` and `sharepoint-batch.yaml` has a `publish-jar` job (depends on `test`) that publishes that app's `bootJar` to this repo's GitHub Packages Maven registry (`com.example:rocky-backend` / `rocky-sharepoint-batch`, versioned off the existing `0.0.1-SNAPSHOT`), using the workflow's own `GITHUB_TOKEN` (no new secret). For `backend` this jar is *the* deployable artifact (an offline client downloads it — see `offline/manifest.yml`); `sharepoint-batch` is the same (its native-image container job is gone). SNAPSHOT versions are stored with a timestamp (`rocky-backend-0.0.1-20260825.162146-1.jar`), so downloaded file names change on every publish. Both `build.gradle` files' `publishing {}` blocks need `./gradlew :<module>:publish --no-configuration-cache` specifically — `maven-publish`'s `GenerateMavenPom` task can't serialize into this project's configuration cache (`Configuration`/`Project`/`DependencyHandler` aren't cache-safe types, confirmed locally: the task itself succeeds and produces a correct POM either way, only the cache *write* fails and turns that into a false `BUILD FAILED`).

**Automated CF deploy (`deploy-cf.yaml`)**: `cf push`es all three `manifest.yml` apps (backend, frontend, sharepoint-batch) on every push to `main` (also `workflow_dispatch` for an ad-hoc redeploy, e.g. after a config-only `manifest.yml` change that doesn't touch any build workflow's path filters). The actual safety gate isn't the trigger — it's a **required-reviewer protection rule on a `production` GitHub Environment** (created via `gh api --method PUT repos/<owner>/<repo>/environments/production` with a `required_reviewers` rule, since this isn't expressible in the workflow YAML itself), which pauses the job immediately after it's created until a human clicks Approve in the Actions UI. By the time you approve, you've had a chance to confirm the build workflows finished green (and, for the frontend specifically, that `frontend-package.yaml` — see below — has had time to publish its current build; the job downloads that release rather than running its own `npm ci`/`npm run build`, so it ships the exact bundle an offline client would get, with the same `sed` swap of `__RUNTIME_API_BASE_URL__` for this foundation's real backend route).

The deploy job builds both jars itself (`./gradlew :backend:bootJar :sharepoint-batch:bootJar`) before `cf push`.

CF login uses a service-account username/password (`CF_USERNAME`/`CF_PASSWORD` repo secrets) — UAA `client_credentials` wasn't available for this org/space. **The job passes no credentials to the apps**: both read Postgres and their model endpoint from their service bindings (`java-cfenv`, see Deployment). It used to fetch the database and AI credentials live from `cf service-key` output, mask them with `::add-mask::` and write a runtime-only `vars.yml` for `sharepoint-batch`; that whole step, `vars.yml.example` and the `--vars-file` flag are gone. (`cf env` shows only a CredHub pointer for `ai-models` bindings, which is what first made reading bindings look unviable — but inside a running container the platform has already replaced it with the real `credentials.endpoint`.)

Two gotchas hit getting the first real run to succeed, neither a workflow bug:
- **A workflow run queued before a secret exists never sees that secret, even after later waiting through the approval gate and executing steps well after the secret was created.** The first real deploy attempt showed `CF_USERNAME`/`CF_PASSWORD` as blank in the log despite `gh secret list`/the GitHub API confirming both existed at the repo level — root-caused by timing: the run had been queued (and started waiting on approval) *before* the secrets were added, several minutes earlier. A fresh run created after the secrets existed picked them up correctly. If a deploy run mysteriously can't see a secret you just added, re-trigger rather than re-approving the stale one.
- **A real Cloud Controller-side 500** (`Puma caught this error: undefined method 'escape' for true (NoMethodError)`, deep in `cloud_controller_ng`'s `security_context_configurer.rb#user_from_token`) hit the very next attempt, after login/credential-fetch both succeeded — the same category of transient CF platform flakiness already documented elsewhere in this file (staging OOM, Log Cache 404s), resolved by simply retrying rather than treating it as a real failure.

**Offline frontend bundle (`frontend-package.yaml`)**: publishes a pre-built `frontend/dist` as a GitHub Release asset (tag `frontend-latest`, asset re-uploaded with `--clobber` on every run rather than creating a new release each time) for a client deploying into an environment with no npm registry access at deploy time. Since `VITE_API_BASE_URL` is normally baked in at build time (see Deployment below), a single pre-built bundle can't hardcode a real backend route without only working for whichever route it happened to be built against - this builds with a fixed placeholder string (`__RUNTIME_API_BASE_URL__`) instead of a real URL. `src/api/client.ts`'s `API_BASE_URL` is just a plain string prefix with no build-time validation (confirmed by reading it - see `vite-env.d.ts`), so the placeholder survives into the built JS bundle as a literal string; the deploying party swaps it for their real backend route with one `sed` command (documented in the release's own notes) before `cf push` - no Node/npm/rebuild needed on their end, and it works for whatever backend route *they* have, not just this foundation's.

## Deployment

`manifest.yml` (repo root) deploys the apps to Cloud Foundry with one `cf push`. See the comment block at the top of the file for the exact order of operations, since the frontend has to be built with the backend's route baked in *before* `cf push` runs (it's a static SPA — see Tech Stack above). Route domains and model names are filled in directly in the manifest (`apps.tas-ndc.kuhn-labs.com`, confirmed via `cf domains`). Neither Java app takes secrets from the manifest or a `vars.yml` anymore — both read Postgres and their model endpoint from their service bindings (see below); there is no `vars.yml` (the old gitignored file, if you still have one locally, is dead). Never put real credentials directly in `manifest.yml`, it's committed. `offline/` is the same deployment from downloaded jars and a pre-built frontend bundle, for a foundation with no GHCR/npm access — a self-contained folder: `manifest.yml` (backend + frontend) and `manifest-batch.yml` (the **optional** batch job — a CF manifest can't conditionally include an app, so it is a separate file; `deploy.sh` pushes it only when `deploy-batch: true` in `vars.yml` or `--with-batch`, and only *after* the core push succeeded so the backend has migrated the database first; `--without-batch` overrides, `--dry-run` prints the pushes; `preflight.sh` checks batch-only things — jar, volume service, variables — only then; CF accepts a vars file containing variables a manifest doesn't use), each with every site-specific value a `((variable))`, `vars.yml.example`, `preflight.sh` (read-only checks of vars, files, buildpacks, services and route domains against the targeted foundation), `prepare-frontend.sh` (swaps the bundle's `__RUNTIME_API_BASE_URL__` placeholder for `https://<backend-route>`), `deploy.sh` (both + `cf push`) and its own `README.md`. Values that must agree are driven by one variable each (`frontend-route` → the CORS origin; `chat-service`/`embed-service`/`volume-service` → both the `services:` binding and the app's `APP_GENAI_*_SERVICE`/`APP_FILESYSTEM_SOURCE_VOLUME_SERVICE` lookup), and `chunk-size` is shared by backend and batch. Manifest env values that must be strings are written `"((var))"` so an unquoted number in `vars.yml` still works. `offline/vars.yml` is gitignored (site-specific; holds no credentials unless the optional Graph block is enabled).

The backing services in this foundation (`dev` space) are `rocky-db` (Postgres) and **two single-model `ai-models` services**, `rocky-chat` (plan `tanzu-gpt-oss-120b`) and `rocky-embed` (plan `tanzu-nomic-embed-text-v2`) — it used to be one `client-rag-demo-ai` on the `tanzu-all-models` plan, replaced so which model is used is explicit (which service is bound) instead of a model name in a manifest. **`backend` and `sharepoint-batch` read their bindings from `VCAP_SERVICES` with `java-cfenv`** (`backend` binds db + chat + embed; `sharepoint-batch` db + embed + the volume) (`io.pivotal.cfenv:java-cfenv-boot` and `java-cfenv-boot-tanzu-genai`, 4.0.2 — built against Boot 4.1.1/Spring AI 2.0.1): `java-cfenv-boot` sets `spring.datasource.*` from the Postgres binding; for the models, each service is looked up **by name** (`app.genai.chat-service` / `app.genai.embedding-service`, defaults in each app's `application-cloud.yaml`, override with `APP_GENAI_CHAT_SERVICE`/`APP_GENAI_EMBEDDING_SERVICE`), a `DefaultGenaiLocator` is built from that binding's `credentials.endpoint` (`config_url`, `api_key`, `api_base`), and `GenaiModelSelector` takes the one CHAT / EMBEDDING model the service grants. The embedding side — `GenaiBindingsConfiguration` (which also supplies the `GenaiLocatorFactory`), `GenaiModelSelector`, `EmbeddingDimensionCheck` — lives in **`ingestion-core`** so both apps embed with exactly the same model selected the same way; `backend`'s `configuration/GenaiModelConfiguration` adds only the chat bean — a service granting several is rejected at startup as ambiguous unless `app.genai.chat-model`/`embedding-model` names one ("first available" is deliberately never used: it depends on catalog order, and an embedding model with the wrong dimension would corrupt retrieval). The library's own auto-configured locator is excluded (`spring.autoconfigure.exclude` in `application-cloud.yaml`): it is a single bean for one `genai.locator.*` property set, so two bindings would overwrite each other. `application-cloud.yaml` also sets `spring.ai.model.{chat,embedding,image,moderation,audio.*}: none` so Spring AI's OpenAI autoconfiguration doesn't also run (its unused audio/image beans otherwise fail startup demanding an API key). The whole thing is `@ConditionalOnProperty` on the two service names, which only the `cloud` profile sets, so local runs and tests keep using plain `spring.ai.openai.*` against Ollama. **`EmbeddingDimensionCheck`** (an `ApplicationRunner`, `@Order(HIGHEST_PRECEDENCE)` so it precedes the batch job launcher) reads the `vector_store.embedding` column's dimension back from Postgres (`pg_attribute.atttypmod` — the Liquibase `vector(768)` is the only place that number is written down) and fails startup, naming both numbers, if the embedding model returns a different dimension; it runs in every environment, so the integration tests exercise it against real Ollama. Changing embedding models still means a migration plus a full reindex. In the running container the platform replaces the `credhub-ref` that `cf env` shows with the real `credentials.endpoint` (verified with a throwaway diagnostic app printing only key names), which is what makes reading bindings viable. `JBP_CONFIG_JAVA_CF_ENV: '{enabled: false}'` stops `java_buildpack` injecting a second copy of `java-cfenv-boot` next to the one bundled in the jar. Each GenAI service has its own endpoint path, which is why each app must read its own service's binding rather than share one URL.

The backend app deploys its `bootJar` via `java_buildpack` (Java 25, `JBP_CONFIG_OPEN_JDK_JRE`); the frontend app deploys its `dist/` build via the `staticfile_buildpack`, with `frontend/public/Staticfile` (copied into `dist/` by Vite) enabling pushstate so React Router's client-side routes survive a direct load/refresh. `sharepoint-batch` deploys its `bootJar` the same way (`instances: 0`, `no-route`, run via `cf run-task`); both Java apps get `JBP_CONFIG_JAVA_CF_ENV: '{enabled: false}'`.

`application-cloud.yaml` (activated via `SPRING_PROFILES_ACTIVE: cloud` in the manifest) sets the session cookie's `SameSite=None; Secure` and `server.forward-headers-strategy: framework` — both required only because the frontend and backend are separate CF routes rather than one shared origin (cross-site cookies need `SameSite=None`, which browsers reject without `Secure`; CF's Gorouter terminates TLS in front of the app, so the app needs forwarded-header handling to know the original request was HTTPS).

