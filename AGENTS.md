# localcloud Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-04-07

## Active Technologies
- Java 21 LTS (primary) + Netty codec-redis (RESP2 parser), Armeria (lifecycle), HikariCP (PostgreSQL pool), Jackson (JSONB) (002-memorystore-emulator)
- PostgreSQL — single `redis_data` table with JSONB values for all 5 data types (002-memorystore-emulator)

- Java 21 (LTS, primary) + Armeria (API gateway, gRPC+REST, console static files), proto-google-cloud-* (gRPC stubs), PostgreSQL (persistence), HikariCP (connection pooling), Solid.js (console frontend) (001-gcp-local-emulator)

## Project Structure

```text
localcloud-server/    # Java API gateway + facade emulators (Armeria + PostgreSQL)
localcloud-console/   # Web console (Solid.js, served by Armeria gateway)
specs/                # Speckit specifications
services.yaml         # Service registry (ports, env vars, protocols) — single source of truth
seed.yaml             # Example seed data file
```

## Build Commands

```bash
# Java server (build + test)
cd localcloud-server && ./gradlew build

# Java shadow JAR (for Docker image)
cd localcloud-server && ./gradlew shadowJar

# Console frontend
cd localcloud-console && npm install && npm run build

# Docker image (requires shadow JAR built first)
docker compose build
docker compose up -d
```

## Code Style

Java 21 (LTS, primary): Follow standard conventions

## Architecture

- **Gateway (port 8080)**: Armeria server hosting admin API + in-process gRPC facades (Secret Manager, Cloud Tasks, Logging, Monitoring, GKE, Compute, Cloud Run)
- **External emulators**: Managed by supervisord inside Docker container (GCS on 4443, Pub/Sub on 8085, Firestore on 8086, Bigtable on 8087, Spanner on 9010, BigQuery on 9050)
- **Persistence**: PostgreSQL (inside container) for facade service data; filesystem for GCS blobs
- **Console**: Solid.js app served by Armeria gateway at `/` (port 8080), opened via `localcloud console`
- **Seed**: YAML files with `services:` wrapper; loaded via `POST /seed`

## Key Implementation Notes

- GCS emulator uses HTTP-only (`-scheme http`) on port 4443
- Secret Manager seeding uses direct PostgreSQL inserts (not REST transcoding)
- JVM tuned to `-Xmx512m -Xms128m` to coexist with emulators in container
- Docker image uses debian:trixie-slim base with custom Java 25 JRE (jlink, ~72 MB) — no gcloud SDK at runtime
- Emulators (Firestore, Pub/Sub, Bigtable) run as direct JAR/binary execution, not via gcloud CLI
- PostgreSQL 17 (matching Debian Trixie's glibc requirements)
- `/services` returns array format with id, name, status, port, protocol, endpoint, env_var, env_value, request_count
- `/reset` reads `restore_seed` from JSON body (not query params)
- Seed YAML supports both flat format (`gcs: ...`) and nested format (`services: { gcs: ... }`)
- BigQuery emulator v2 is Python-based (DuckDB+SQLGlot), native on both arm64 and amd64
- Container needs `-m 4g` memory limit for stable operation

## Recent Changes
- 003-scheduler-functions-alloydb-dataproc-iam: Added 5 new gRPC facade emulators (Cloud Scheduler, Cloud Functions 2nd gen, AlloyDB, Dataproc, Cloud IAM) with PostgreSQL-backed state, browse API support, and Solid.js console views.

- 002-memorystore-emulator: Added Java 21 LTS (primary) + Netty codec-redis (RESP2 parser), Armeria (lifecycle), HikariCP (PostgreSQL pool), Jackson (JSONB)

- 001-gcp-local-emulator: Java 21 + Armeria (API gateway, gRPC+REST, console static files), PostgreSQL (persistence), proto-google-cloud-* (gRPC stubs), HikariCP, Solid.js (web console). 14 GCP services emulated (GCS, Pub/Sub, Firestore, BigQuery, Secret Manager, Cloud Tasks, Spanner, Bigtable, Logging, Monitoring, GKE, Compute Engine, Cloud Run, Memorystore).

## Test Counts

- Java server: 250+ unit tests (JUnit 5 + Mockito)
- Console: esbuild (no test suite)

<!-- MANUAL ADDITIONS START -->

## Communication Style

- When asked to simplify, do it immediately. Provide the minimal working solution first, then offer to elaborate. Don't generate elaborate guides when a simple Dockerfile or config is requested.
- Prefer concise, actionable output over comprehensive documentation unless explicitly asked.

## Docker

- Always check ARG placement (must be after FROM for multi-stage builds).
- Verify volume mount paths and avoid relying on Docker cache during debugging iterations.
- Prefer incremental rebuilds over full rebuilds when debugging build issues.
- Always build the shadow JAR before `docker compose build` — the Dockerfile copies the pre-built JAR.

## Frontend / UI (Solid.js)

- Signals must be properly tracked in JSX — call `signal()` inside JSX, not outside.
- State updates must use setter functions (`setSignal(value)`), never mutate directly.
- Test reactivity behavior after each change rather than batching multiple reactive changes.
- Console uses esbuild (not Vite/Webpack) — run `npm run build` from `localcloud-console/`.

## Emulator-Specific Notes

- GCS emulator (fake-gcs-server) does NOT enforce project-level bucket isolation natively. Project isolation is handled by the `gcs_bucket_projects` table in PostgreSQL, with filtering in `BrowseService`.
- GCS files are local filesystem — don't use `gs://` URIs with DuckDB or other tools expecting real GCS.
- BigQuery schema/browse endpoints only work for PostgreSQL-backed services via admin API; the BQ emulator itself uses DuckDB.
- Spanner emulator has a known LevelDB race condition on persistence — verify data survives restarts.
- Secret Manager seeding uses direct PostgreSQL inserts, not gRPC — so emulator `incrementRequestCount()` is NOT called from seed/browse paths. Usage metrics for admin API operations are tracked directly via `UsageMetricsRepository`.
- Usage metrics are persisted to PostgreSQL (`usage_metrics` table) with UPSERT semantics — one row per project+service, flushed every 30 seconds from in-memory counters.
- **Cloud Scheduler**: Uses `cron-utils` library for cron parsing + `ScheduledExecutorService` for job dispatch. Supports HTTP, Pub/Sub, and App Engine targets. Pub/Sub target publishes to local Pub/Sub emulator via gRPC. Jobs survive restarts (re-scheduled from DB on startup). Env var: `CLOUD_SCHEDULER_EMULATOR_HOST`.
- **Cloud Functions (2nd gen)**: Metadata-only facade — CRUD for function configs. Developers run functions locally using Functions Framework. Trigger routing: Pub/Sub topics auto-create subscriptions that forward to the function's local URL. Build config stored but not executed. Env var: `CLOUD_FUNCTIONS_EMULATOR_HOST`.
- **AlloyDB**: PostgreSQL-compatible at wire level. Each cluster maps to a dedicated PostgreSQL database (`alloydb_<cluster_id>`). `GetConnectionInfo` returns localhost:5432. pgvector extension installed automatically. Env var: `ALLOYDB_EMULATOR_HOST`.
- **Dataproc**: Spark 3.5.x runs via `spark-submit` in local mode (`--master local[*]`). Cluster CRUD is metadata-only. Jobs are submitted by forking `spark-submit` processes tracked via `Process`. Requires Spark installed on host at `SPARK_HOME`. Env var: `DATAPROC_EMULATOR_HOST`.
- **Cloud IAM**: Permissive policy stub. `testIamPermissions` returns ALL requested permissions as allowed. `getIamPolicy`/`setIamPolicy` manage JSONB policies per resource in PostgreSQL. No role validation or condition support. Env var: `IAM_EMULATOR_HOST`.

## Project Management / Specs

- Always use local OpenSpec stories and specs from the project directory — never reference Jira, Atlassian, or external project management tools unless explicitly asked.
- Story files live in `openspec/` directory. Specs live in `specs/`.

<!-- MANUAL ADDITIONS END -->

<!-- gortex:communities:start -->
<!-- gortex:skills:start -->
## Community Skills

| Area | Description | Skill |
|------|-------------|-------|
| Get | 257 symbols | `/gortex-get` |
| Get | 199 symbols | `/gortex-get` |
| Seed | 196 symbols | `/gortex-seed` |
| Get | 182 symbols | `/gortex-get` |
| List | 141 symbols | `/gortex-list` |
| Stdlib | 80 symbols | `/gortex-stdlib` |
| Licensing | 75 symbols | `/gortex-licensing` |
| Bigtablesql | 74 symbols | `/gortex-bigtablesql` |
| Expression | 64 symbols | `/gortex-expression` |
| Generate | 60 symbols | `/gortex-generate` |
| Adapters | 49 symbols | `/gortex-adapters` |
| Build | 46 symbols | `/gortex-build` |
| Bigtablesql | 45 symbols | `/gortex-bigtablesql` |
| Pages | 45 symbols | `/gortex-pages` |
| Services | 45 symbols | `/gortex-services` |
| Gateway | 44 symbols | `/gortex-gateway` |
| Engine | 43 symbols | `/gortex-engine` |
| Localcloud | 32 symbols | `/gortex-localcloud` |
| Expression | 32 symbols | `/gortex-expression` |
| Get | 32 symbols | `/gortex-get` |
<!-- gortex:skills:end -->

<!-- gortex:communities:end -->

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, invoke the `skill` tool with `skill: "graphify"` before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
