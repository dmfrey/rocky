# Rocky

A retrieval-augmented generation (RAG) chat application. Authenticated users build a shared
knowledge base of documents, then chat with an LLM whose answers are grounded in that content and
cite the source documents. Documents arrive by manual upload in the web UI, or automatically from
a SharePoint document library (or a mounted filesystem) via a scheduled batch job.

It is built for deployment on **Tanzu Platform / Cloud Foundry**, against an OpenAI-compatible
model endpoint (Tanzu's `genai-service` proxy in production, Ollama locally) and PostgreSQL with
pgvector.

## Components

| Module | What it is |
|---|---|
| `backend/` | Spring Boot 4 REST API (Java 25). Deployed as a plain executable jar; reads its Postgres and model-proxy credentials from the bound Cloud Foundry services (`java-cfenv`). |
| `frontend/` | React + Vite + TypeScript single-page app, served as static files. |
| `ingestion-core/` | Plain Java library: the document-ingestion slice shared by `backend` and `sharepoint-batch`. |
| `sharepoint-batch/` | Spring Batch / Spring Cloud Task job that ingests from SharePoint or a filesystem. Runs once and exits. |

### Backend

- **Accounts**: local username/password registration and login (session cookie, no external IdP,
  no roles). Profile update and password change.
- **Consent banner**: a configurable banner each session must acknowledge before any other API
  call succeeds (enforced server-side, HTTP 428 otherwise).
- **Documents**: upload PDF, DOC/DOCX and TXT (up to 50 MB), plus images (PNG/JPG/GIF) and scanned PDFs when the optional vision model is bound (see offline/README.md). Re-uploading a filename replaces the
  existing document. Ingestion is asynchronous: parse (Apache Tika) → chunk → embed → store in
  pgvector, with status `PROCESSING` / `READY` / `FAILED`. Delete removes the chunks too. One
  shared knowledge base for all users.
- **Chat**: per-user named sessions with auto-generated titles, rename, archive/unarchive and
  delete. Answers **stream** token-by-token (SSE), are grounded on retrieved document chunks, and
  include **citations** that are persisted with the message. Conversation memory is stored in
  Postgres (window size `app.chat.memory.max-messages`, default 20).
- **Operations**: Actuator health and Prometheus metrics; Micrometer tracing.

### Frontend

The Clarity visual refresh is described in [the implementation plan](CLARITY-IMPLEMENTATION-PLAN.md),
with completed work and validation tracked in [the work log](docs/clarity-work-log.md).
See [preview deployment](docs/clarity-deployment.md) for a separate CF preview and offline packaging.

Login/registration, consent-banner gate, a documents page (drag-and-drop multi-upload, status
badges, replace-confirmation, delete), a chat page with streamed answers and source citations, a
sidebar of sessions (including archived), and a settings page. All API calls go to relative
`/api/...` paths unless `VITE_API_BASE_URL` is set at build time (see deployment).

### ingestion-core

Hexagonal domain/ports/adapters for turning bytes into an indexed, status-tracked document, so web
upload and batch ingestion behave identically. Documents carry a `source` (`UPLOAD` / `SHAREPOINT`),
an `externalId` and a `sourceVersion` (eTag) for change detection. `backend` is the sole owner of
the database schema (Liquibase).

### sharepoint-batch

A Cloud Foundry **task**, not a long-running app. Each run `resolveDeltaLink → sync → persistDeltaLink`:
only changed or deleted items are (re-)ingested.

- **`graph` source** (default): Microsoft Graph delta queries against one SharePoint document
  library, app-only auth (Azure AD app with `Sites.Selected`). *Not yet verified against a real
  tenant.*
- **`filesystem` source**: full recursive scan of a mounted directory (e.g. CF `block-storage`
  volume). Does not detect deletions.
- Triggered externally (`cf run-task`, Scheduler for VMware Tanzu, or cron).

## Architecture

```
 Browser ──► frontend (static) ──► backend ──► PostgreSQL + pgvector
                                      │            ▲
                                      ▼            │
                         OpenAI-compatible LLM     │
                         (chat + embeddings)       │
                                      ▲            │
 SharePoint / filesystem ──► sharepoint-batch ─────┘
```

Backend and batch share the same Postgres and embedding model, so their chunk size settings must
match.

## Local development

See `CLAUDE.md` for full detail. In short (Podman required for Testcontainers/Compose):

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :backend:bootRun   # starts Postgres/Ollama via compose
cd frontend && npm install && npm run dev                  # proxies /api to the backend
./gradlew test                                              # all JVM modules
cd frontend && npm test
```

## Standard deployment (online)

`manifest.yml` deploys all three apps to Cloud Foundry: the backend and the batch service as
executable jars (`java_buildpack_offline`; `./gradlew :backend:bootJar :sharepoint-batch:bootJar`)
and the frontend via `staticfile_buildpack`. Neither Java app takes credentials from the manifest:
both read Postgres and their model endpoint from their service bindings (`java-cfenv`), so there
is no `vars.yml` and no service key to copy. `deploy-cf.yaml` builds the jars and pushes behind a
required-reviewer approval.

## Deploying in an offline environment

For a foundation/network with no access to GHCR, npm or Maven Central. The **`offline/`** folder is
self-contained: copy it onsite, add the two jars (GitHub Packages) and the frontend bundle (GitHub
Release `frontend-latest`), then:

```bash
cd offline
cp vars.yml.example vars.yml && $EDITOR vars.yml   # routes, jar names, buildpacks, service names...
./deploy.sh frontend-dist.tar.gz                   # prepares the frontend, runs checks, cf push
```

The SharePoint/filesystem batch job is optional and off by default (`deploy-batch` in `vars.yml`, or
`./deploy.sh --with-batch`); core-only sites need none of its jar, variables or volume service.
Everything that differs between sites is a `((variable))` in `offline/manifest.yml` (and
`manifest-batch.yml`), set in `vars.yml`; values that must agree (the frontend route and the backend's CORS origin; a service's
name and the app's lookup of it) are driven by one variable each. `offline/preflight.sh` checks the
variables, files, buildpacks, services and route domains before anything is pushed. There are no
credentials to configure - the apps read Postgres and their model endpoints from their service
bindings. See **[`offline/README.md`](offline/README.md)** for the prerequisites (including the two
single-model GenAI services), the full variable reference and troubleshooting.

## CI/CD

| Workflow | Purpose |
|---|---|
| `backend.yaml` | Test backend; publish its executable jar (GitHub Packages). |
| `sharepoint-batch.yaml` | Test the batch service; publish its executable jar (GitHub Packages). |
| `frontend-package.yaml` | Publish the offline frontend bundle as the `frontend-latest` release asset. |
| `deploy-cf.yaml` | Build the backend and batch jars, then `cf push` all apps, gated by a `production` environment approval. |

## Further reading

- `CLAUDE.md` — architecture, conventions, GraalVM and Cloud Foundry gotchas.
- `docs/requirements.md` — the v1 requirements and data model.
