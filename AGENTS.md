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
- **Seed**: YAML files with `services:` wrapper; loaded via `POST /_localcloud/seed`

## Key Implementation Notes

- GCS emulator uses HTTP-only (`-scheme http`) on port 4443
- Secret Manager seeding uses direct PostgreSQL inserts (not REST transcoding)
- JVM tuned to `-Xmx512m -Xms128m` to coexist with emulators in container
- Docker image uses debian:trixie-slim base with custom Java 25 JRE (jlink, ~72 MB) — no gcloud SDK at runtime
- Emulators (Firestore, Pub/Sub, Bigtable) run as direct JAR/binary execution, not via gcloud CLI
- PostgreSQL 17 (matching Debian Trixie's glibc requirements)
- `/_localcloud/services` returns array format with id, name, status, port, protocol, endpoint, env_var, env_value, request_count
- `/_localcloud/reset` reads `restore_seed` from JSON body (not query params)
- Seed YAML supports both flat format (`gcs: ...`) and nested format (`services: { gcs: ... }`)
- BigQuery emulator v2 is Python-based (DuckDB+SQLGlot), native on both arm64 and amd64
- Container needs `-m 4g` memory limit for stable operation

## Recent Changes
- 002-memorystore-emulator: Added Java 21 LTS (primary) + Netty codec-redis (RESP2 parser), Armeria (lifecycle), HikariCP (PostgreSQL pool), Jackson (JSONB)

- 001-gcp-local-emulator: Java 21 + Armeria (API gateway, gRPC+REST, console static files), PostgreSQL (persistence), proto-google-cloud-* (gRPC stubs), HikariCP, Solid.js (web console). 14 GCP services emulated (GCS, Pub/Sub, Firestore, BigQuery, Secret Manager, Cloud Tasks, Spanner, Bigtable, Logging, Monitoring, GKE, Compute Engine, Cloud Run, Memorystore).

## Test Counts

- Java server: 187 unit tests (JUnit 5 + Mockito)
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

## Project Management / Specs

- Always use local OpenSpec stories and specs from the project directory — never reference Jira, Atlassian, or external project management tools unless explicitly asked.
- Story files live in `openspec/` directory. Specs live in `specs/`.

<!-- MANUAL ADDITIONS END -->

<!-- gortex:communities:start -->
<!-- gortex:skills:start -->
## Community Skills

| Area | Description | Skill |
|------|-------------|-------|
| Get | 182 symbols | `/gortex-get` |
| Handle | 177 symbols | `/gortex-handle` |
| Get | 144 symbols | `/gortex-get` |
| Admin | 135 symbols | `/gortex-admin` |
| Stdlib | 74 symbols | `/gortex-stdlib` |
| Expression | 62 symbols | `/gortex-expression` |
| Workflows | 61 symbols | `/gortex-workflows` |
| Engine | 50 symbols | `/gortex-engine` |
| Get | 36 symbols | `/gortex-get` |
| Gateway | 30 symbols | `/gortex-gateway` |
| Admin | 30 symbols | `/gortex-admin` |
| Expression | 29 symbols | `/gortex-expression` |
| Engine | 28 symbols | `/gortex-engine` |
| Localcloud | 27 symbols | `/gortex-localcloud` |
| Engine | 26 symbols | `/gortex-engine` |
| Pages | 26 symbols | `/gortex-pages` |
| Memorystore | 25 symbols | `/gortex-memorystore` |
| Sync | 25 symbols | `/gortex-sync` |
| Gateway | 25 symbols | `/gortex-gateway` |
| Adapters | 24 symbols | `/gortex-adapters` |
<!-- gortex:skills:end -->

<!-- gortex:communities:end -->
