# Gap Analysis: localcloud (Cloud Workflows Emulator) vs OpenRun

**Date:** 2026-06-17  
**Scope:** Compare localcloud's workflow orchestration solution with OpenRun's web app platform  
**Conclusion:** These are fundamentally different tools solving different problems. Direct capability overlap is minimal (~15%). The gap analysis focuses on feature parity, architectural differences, and integration opportunities.

---

## 1. Executive Summary

| Dimension | localcloud | OpenRun |
|-----------|-----------|---------|
| **Primary purpose** | GCP Cloud Workflows emulator — local dev/test of orchestration workflows | Web app deployment platform — host internal tools with GitOps |
| **Execution model** | Step-based DAG workflow engine (YAML-defined) | Per-request handler model (Starlark functions) |
| **Language** | Java 21 (Armeria, Netty, HikariCP, Jackson) | Go 1.21+ (Starlark interpreter, HTMX) |
| **Persistence** | PostgreSQL (JSONB) | SQLite (single-node) / PostgreSQL (multi-node) |
| **Deployment** | Single Docker container | Single binary or Kubernetes Helm chart |
| **GCP parity target** | High — API-compatible with Cloud Workflows | N/A — not a GCP emulator |
| **License** | Internal/proprietary | Apache 2.0 |

**Bottom line:** localcloud emulates GCP Cloud Workflows for offline development. OpenRun is a general-purpose internal tool deployment platform. They overlap only in the narrow sense that both execute user-defined logic (YAML workflows vs Starlark handlers). They serve completely different developer personas and use cases.

---

## 2. Architectural Comparison

### 2.1 Execution Engines

| Capability | localcloud | OpenRun |
|-----------|-----------|---------|
| **Execution paradigm** | Workflow steps (sequential, parallel, branching) | HTTP request → handler → response |
| **Step types** | 10 step types: assign, call, switch, for, parallel, try, raise, return, next, steps (inline) | Route types: HTML, API (TEXT/JSON), Action |
| **Parallelism** | `parallel` step with virtual threads, semaphore-controlled concurrency (up to 10), shared variables with per-variable locks | Not a workflow engine — each request is independent |
| **Sub-workflows** | Named subworkflows with parameter passing and scope isolation | Not applicable (but handler functions can call other functions) |
| **Error handling** | try/retry/except with exponential backoff, structured error maps, raise step | Automatic error handling with `error_handler` function, manual `if not ret:` pattern |
| **Retry** | Configurable: max_retries, initial_delay, max_delay, multiplier | Not built into engine; manual retry in Starlark handler |
| **Loops** | `for` step: iterate over list or range, support break/continue via `next` | Not applicable (per-request model) |
| **Conditionals** | `switch` step with multiple branches and default | Starlark `if/else` in handler function |
| **Expression language** | `${...}` template syntax with full expression parser (tokenizer → AST → evaluator) | Starlark (Python subset) — full programming language |
| **Step limits** | 100,000 steps per execution, 128KB source limit, 32KB argument limit | 32MB request body limit (configurable) |
| **Execution tracking** | Step-level history: name, type, duration, state, JSON metadata; step timeline UI | Audit trail: API calls, operations, user actions |
| **Cancellation** | In-flight cancellation, checks at each step boundary and HTTP call | Not applicable (per-request model) |
| **Orphan recovery** | Startup sweep marks QUEUED/ACTIVE executions as FAILED | Not applicable |

**Gap:** localcloud has a full workflow engine (DAG, parallelism, loops, retry), which OpenRun lacks entirely. OpenRun has a full programming language (Starlark), which localcloud's expression language cannot match.

### 2.2 Integration / Connector Model

| Capability | localcloud | OpenRun |
|-----------|-----------|---------|
| **Integration pattern** | Connector registry: maps `googleapis.SERVICE.VERSION.RESOURCE.METHOD` → HTTP calls to local emulators | Plugin system: Go-implemented plugins callable from Starlark |
| **Number of integrations** | ~18 GCP services, ~90 connectors (Storage, BigQuery, Pub/Sub, Secret Manager, Tasks, Firestore, Logging, Monitoring, Compute, Run, GKE, Spanner, Bigtable, Scheduler, Functions, AlloyDB, Dataproc, IAM, KMS, Vertex AI, Cloud SQL) | 5 core plugins: http, exec, store (SQLite), container, proxy, fs |
| **Service orchestration** | Workflows can chain calls across any GCP service in a single execution | Apps are isolated; cross-app communication via HTTP |
| **HTTP client** | `http.get/post/put/patch/delete/request` with headers, body, query, timeout, retry predicates | `http.get/head/options/post/put/delete/patch` with headers, body, form, JSON, auth, timeout |
| **Auth model** | Connector calls are local (no auth); remote fallback mode | Basic auth, signed auth (SLSignature), OAuth via server config |
| **Container execution** | Not supported | Native container lifecycle management (Docker/Podman) |
| **Shell execution** | Not built into stdlib (workaround via connector/HTTP) | `exec.run` plugin — direct shell command execution |
| **Database access** | Via ConnectorRegistry to AlloyDB/CloudSQL emulators | `store.in` plugin: document-store interface over SQLite |
| **File system** | Not exposed | `fs` plugin: file read/write/serve from allowed paths |
| **Extensibility** | Register new connectors by adding to ConnectorRegistry Java code | New plugins registered in Go at startup, Starlark-callable |

**Gap:** localcloud's connector model is tightly coupled to GCP service APIs — this is by design (it's a GCP emulator). OpenRun's plugin model is generic. OpenRun supports shell execution, file system access, and container management that localcloud does not. localcloud has far deeper cloud service integration.

### 2.3 State and Persistence

| Capability | localcloud | OpenRun |
|-----------|-----------|---------|
| **Workflow definitions** | PostgreSQL `workflows` table with versioned revisions in `workflow_revisions` | Git-based (source repos) — declarative config |
| **Execution state** | PostgreSQL `workflow_executions` table with full lifecycle (QUEUED→ACTIVE→SUCCEEDED/FAILED/CANCELLED) | Not applicable |
| **Step history** | PostgreSQL `workflow_step_entries` table (per-execution step timeline) | Audit logs (all operations, API calls) |
| **Hot-reload** | `LOCALCLOUD_WORKFLOWS_DIR` file watcher for YAML hot-reload | Dev mode with live reload for templates, CSS, JS |
| **Versioning** | Revision IDs auto-incremented on update; list revisions API | Git-based — no internal versioning needed |
| **Soft delete** | Yes — 30-day soft delete with purge | Not applicable (remove from config repo) |
| **Seed/Reset** | UPSERT seed support, full reset (all tables) and per-project reset | App creation/destruction via CLI |

### 2.4 API Surface

| Capability | localcloud | OpenRun |
|-----------|-----------|---------|
| **Protocols** | REST (Armeria) + gRPC | HTTP + CLI |
| **Workflow CRUD** | Create, Get, List, Delete, Undelete, Update (with LRO operations) | App create, update, delete, list |
| **Execution API** | Create execution, Get, List, Cancel, Export data | Not applicable |
| **Step history API** | List step entries, Get step entry, Delete execution history | Not applicable |
| **Revision API** | List revisions | Not applicable |
| **Filter/query** | CEL-inspired filter parser (state=VALUE, labels.KEY=VALUE, AND/OR/NOT) | Not applicable |
| **Environment vars** | Per-workflow user env vars, global env vars with presets (local/dev/prod) | App-level config via `--param` and `--conf-str` |
| **Remote import** | Web console import from remote source (connect → list → select → import) | Git clone (native) |
| **IAM policies** | getIamPolicy/setIamPolicy via generic handler | Built-in RBAC, OAuth/OIDC/SAML |

### 2.5 UI / Developer Experience

| Capability | localcloud | OpenRun |
|-----------|-----------|---------|
| **Management console** | Solid.js web app: workflow list, source viewer (YAML syntax highlighting), execution history, step timeline, env vars management | App listing page, form-based actions, API docs |
| **YAML editor** | Syntax-highlighted YAML view with line numbers | Not applicable (no YAML) |
| **Execution monitoring** | Visual step timeline (SVG circles, color-coded by state), auto-refresh for active executions | Not applicable |
| **Action/Form UI** | Not applicable | Auto-generated forms from parameter definitions, suggest handlers, file upload |
| **CLI** | Not present (REST API + web console) | Full CLI: `openrun app create/update/delete/list/approve/sync` |
| **Dev mode** | File watcher hot-reload | Dev mode: CSS/JS generation, live reload, template watching |
| **Templating** | Not applicable (workflows are YAML) | Go HTML templates + HTMX for Hypermedia apps |
| **Styling** | Custom CSS design system | TailwindCSS + DaisyUI |

---

## 3. Gap Analysis Matrix

### 3.1 Capabilities OpenRun Has That localcloud Lacks

| # | Capability | OpenRun Implementation | localcloud Status | Priority for localcloud |
|---|-----------|----------------------|-------------------|------------------------|
| 1 | **Full programming language** | Starlark (Python subset) for handler logic | Expression language only (${...} templates + function calls) | Low — workflows use YAML DSL by design |
| 2 | **Container management** | Docker/Podman lifecycle (build, run, stop, scale to zero) | N/A — not a container platform | Low — different domain |
| 3 | **Shell command execution** | `exec.run` plugin | Not in stdlib; workaround via HTTP to another service | Medium — useful for scripting in workflows |
| 4 | **Document store (DB)** | `store.in` plugin with schema, indexes, CRUD over SQLite | Not in stdlib; workaround via AlloyDB connector | Medium — simple key-value persistence in workflows |
| 5 | **File system access** | `fs` plugin: read/write/serve from allowed paths | Not available | Low — workflows typically use GCS |
| 6 | **GitOps deployment** | Declarative app config in Git, `openrun apply/sync` | Not available (seeding via YAML + web console) | Low — different deployment model |
| 7 | **OAuth/OIDC/SAML auth** | Built-in authentication with RBAC for app access | Not available — local dev tool assumes trusted environment | Low — local emulator |
| 8 | **Kubernetes support** | Helm chart, K8s-native deployment | Not available — single Docker container | Low — different deployment target |
| 9 | **Auto-TLS** | Let's Encrypt via certmagic | Not needed — localhost only | None |
| 10 | **Action UI (auto forms)** | Parameter-driven form generation for backend actions | Not applicable | None — workflows are not user-facing apps |
| 11 | **HTMX/Hypermedia** | HTMX + Go templates for interactive UI | Not applicable | None |
| 12 | **Audit trail** | All API calls and operations logged | Execution history (step-level) only | Low — step history covers workflow observability |
| 13 | **AppSpecs** | Zero-config framework deployment (Flask, Streamlit, etc.) | Not applicable | None |
| 14 | **Multi-domain routing** | Domain-based and path-based routing | Not applicable | None |
| 15 | **Secrets management** | Integration with external secret managers | Not available — secrets via env vars only | Medium |

### 3.2 Capabilities localcloud Has That OpenRun Lacks

| # | Capability | localcloud Implementation | OpenRun Status |
|---|-----------|-------------------------|---------------|
| 1 | **Full workflow engine** | 10 step types, DAG execution, step tracking | No workflow engine — per-request model |
| 2 | **Parallel execution** | Virtual threads + semaphore (up to 10 concurrent), shared variables with per-variable locks | No built-in parallelism for single request |
| 3 | **Retry with backoff** | try/retry/except with exponential backoff | Manual implementation in Starlark |
| 4 | **Step timeline** | Per-execution step history with name, type, duration, state, JSON metadata | Not applicable |
| 5 | **Execution state machine** | QUEUED → ACTIVE → SUCCEEDED/FAILED/CANCELLED with auto-sweep | Not applicable |
| 6 | **Workflow revisions** | Versioned revisions stored in DB, listable via API | Not applicable (Git provides versioning) |
| 7 | **18+ GCP service connectors** | Storage, BigQuery, Pub/Sub, Secret Manager, Tasks, Firestore, Logging, Monitoring, Compute, Run, GKE, Spanner, Bigtable, Scheduler, Functions, AlloyDB, Dataproc, IAM, KMS, Vertex AI, Cloud SQL | N/A — not a GCP emulator |
| 8 | **YAML workflow format** | Full YAML parser for Cloud Workflows syntax | No YAML workflows |
| 9 | **Expression language** | `${...}` template syntax with tokenizer→AST→evaluator | Starlark handles expressions natively |
| 10 | **Standard library** | 18 stdlib modules: http, sys, json, base64, math, text, list, map, types, events, hash, time, uuid, retry, random, error, date | Plugin-based: http, exec, store, fs, container, proxy |
| 11 | **Child workflow execution** | Call subworkflows from within workflows, or execute other workflows | Not applicable |
| 12 | **CEl-inspired filter parser** | Custom filter language: state=VALUE AND labels.KEY=VALUE | Not applicable |
| 13 | **Environment presets** | Multi-preset env vars (local/dev/prod) with switching | App-level params (static) |
| 14 | **Remote workflow import** | Connect → list → select → import with URL rewriting | Native Git clone |
| 15 | **Workflow hot-reload** | File watcher on LOCALCLOUD_WORKFLOWS_DIR | Dev mode live reload |
| 16 | **Operation LRO emulation** | Operations API with create/update/delete metadata | Not applicable |
| 17 | **Execution cancellation** | Mid-flight cancellation with state machine guards | Not applicable |
| 18 | **Orphan recovery** | Startup sweep of abandoned executions | Not applicable |

---

## 4. Where They Could Learn From Each Other

### 4.1 What localcloud Could Adopt from OpenRun

| Feature | Value | Effort | Priority |
|---------|-------|--------|----------|
| **Plugin-style extensibility** | Allow users to register custom step types or stdlib functions without modifying Java code | High | Medium |
| **Shell execution (`exec.run`)** | Many workflow use cases need `exec` — running scripts, CLI tools, etc. | Low (add to stdlib) | **High** |
| **Document store persistence** | Simple key-value/document persistence within a workflow execution | Medium | Medium |
| **File system access** | Reading/writing local files from workflows (with security sandbox) | Medium | Low |
| **Action-like parameter UI** | Auto-generate a web form for workflow input parameters (like OpenRun Actions) | High | Low |
| **CLI tooling** | `localcloud workflow create/execute/list` CLI commands for developer ergonomics | Medium | Medium |
| **Audit logging** | Structured audit trail for all workflow CRUD + execution operations | Low | Medium |

### 4.2 What OpenRun Could Adopt from localcloud

| Feature | Value | Effort |
|---------|-------|--------|
| **Workflow engine** | Add DAG-based workflow execution with steps, parallelism, retry, loops | Very High (different paradigm) |
| **Step timeline** | Per-app-request step tracking with duration | Medium |
| **GCP service connectors** | Built-in connectors to GCS, BigQuery, Pub/Sub, etc. | High (not their domain) |
| **Multi-step transactions** | Coordinated multi-service operations with rollback | Very High |
| **Expression templates in config** | `${...}` template evaluation in Starlark config values | Low (Starlark handles this natively) |

---

## 5. Integration Opportunities

Given that localcloud already emulates 20+ GCP services and has a workflow engine, and OpenRun is a web app deployment platform, there are practical integration scenarios:

### 5.1 Run OpenRun *on* localcloud

- OpenRun could be deployed as a containerized app on localcloud's Cloud Run emulator
- OpenRun's Starlark apps could call localcloud-emulated GCP services via HTTP
- This creates an "internal tools on local GCP" development environment

### 5.2 Workflow-Triggered OpenRun Apps

- localcloud workflows could call OpenRun-deployed apps as HTTP targets
- OpenRun actions could be triggered from workflow steps via `http.post`
- Enables "workflow → internal tool" automation loops

### 5.3 Shared Plugin/Connector Model

- localcloud could expose a subset of its GCP connectors as an OpenRun plugin
- OpenRun Starlark apps could directly call localcloud-emulated BigQuery, GCS, Pub/Sub
- This would make OpenRun a viable frontend for GCP-local development

---

## 6. Summary Assessment

### localcloud Strengths
- **Best-in-class GCP Cloud Workflows emulation** — API-compatible, YAML-compatible
- **Rich workflow execution engine** — parallel, retry, loops, subworkflows, cancellation
- **Deep GCP service integration** — 20+ services, 90+ connector endpoints
- **Production-grade execution tracking** — step timeline, duration, structured errors
- **Operational robustness** — orphan recovery, soft delete, revision history

### localcloud Gaps (vs general workflow platforms)
- No built-in shell/exec capability
- No document store / key-value persistence in workflows
- No CLI tooling (REST + web console only)
- No plugin/extension API for custom steps
- Limited expression language (templates only, not a full language)

### OpenRun Strengths
- **Full-featured web app platform** — HTMX, templates, TailwindCSS, auto-forms
- **Flexible plugin system** — http, exec, store, container, proxy
- **Production deployment** — TLS, OAuth/OIDC/SAML, RBAC, Kubernetes, scale-to-zero
- **GitOps-native** — declarative config, atomic updates, blue-green deployment
- **Developer ergonomics** — CLI, dev mode, live reload, AppSpecs for zero-config deploys

### OpenRun Gaps (vs workflow platforms)
- No workflow execution engine
- No DAG/step-based orchestration
- No built-in retry/backoff for operations
- No execution state machine
- Per-request model unsuitable for long-running multi-step processes

### Verdict
**These tools are not competitors — they are complementary.** localcloud is a GCP development emulator with a workflow engine. OpenRun is an internal tool deployment platform. The overlap is incidental: both execute user-defined logic, but the execution models, target users, and deployment patterns are fundamentally different.

For a team building on GCP, localcloud provides the offline development loop for Cloud Workflows. For a team building internal tools, OpenRun provides the deployment and hosting platform. They could potentially be integrated: OpenRun apps deployed on localcloud's Cloud Run, calling localcloud's GCP emulators, triggered by localcloud workflows.

---

## Appendix A: Code Metrics Comparison

| Metric | localcloud (Workflows subsystem) | OpenRun (Core) |
|--------|----------------------------------|----------------|
| **Source files** | ~60 Java files | ~130 Go files |
| **Test files** | ~10 test classes | ~30+ test files |
| **Lines of code (est.)** | ~8,000 Java (workflows subsystem) | ~25,000 Go (entire project) |
| **Stdlib/plugin modules** | 18 stdlib modules | 5 core plugins + container |
| **Service connectors** | 90+ GCP connectors | N/A |
| **API endpoints** | ~25 REST endpoints | ~20 CLI commands + REST |
| **External dependencies** | Armeria, Jackson, HikariCP, Netty | Starlark-go, HTMX, certmagic, Docker SDK |

## Appendix B: Technology Stack

| Layer | localcloud | OpenRun |
|-------|-----------|---------|
| **Language** | Java 21 | Go 1.21+ |
| **HTTP framework** | Armeria | net/http (stdlib) + custom router |
| **Persistence** | PostgreSQL (HikariCP) | SQLite / PostgreSQL |
| **Serialization** | Jackson (JSON, YAML) | encoding/json |
| **Config language** | YAML (workflow defs) | Starlark (app config) |
| **Templating** | N/A | Go html/template + HTMX |
| **Frontend** | Solid.js (management console) | HTMX + TailwindCSS + DaisyUI |
| **Container runtime** | Docker (whole app) | Docker/Podman (per-app) |
| **Orchestration** | Docker Compose | Kubernetes (optional) |
| **Observability** | SLF4J logging + step timeline | Telemetry (OpenTelemetry) |
