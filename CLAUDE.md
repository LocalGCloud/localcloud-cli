# localcloud Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-05-26

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
docker build -t localcloud/localcloud:latest .

# Start/stop container
./start.sh
./stop.sh
```

## Code Style

Java 21 (LTS, primary): Follow standard conventions

## Architecture

- **Gateway (port 8080)**: Armeria server hosting admin API + in-process gRPC facades (Secret Manager, Cloud Tasks, Logging, Monitoring, GKE, Compute, Cloud Run, Cloud Workflows, Cloud Scheduler, Cloud Functions, AlloyDB, Dataproc, Cloud IAM, Vertex AI, Cloud KMS, Cloud SQL)
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
- Bigtable emulator: `github.com/jhsenjaliya/little_bigtable@v0.0.1` — pulled and built from published Go module during `docker build`
- PostgreSQL 17 (matching Debian Trixie's glibc requirements)
- `/services` returns array format with id, name, status, port, protocol, endpoint, env_var, env_value, request_count
- `/reset` reads `restore_seed` from JSON body (not query params)
- Seed YAML supports both flat format (`gcs: ...`) and nested format (`services: { gcs: ... }`)
- BigQuery emulator v2 is Python-based (DuckDB+SQLGlot), native on both arm64 and amd64
- Container needs `-m 4g` memory limit for stable operation
- Terraform integration via `GOOGLE_*_CUSTOM_ENDPOINT` env vars — `GET /env?format=terraform` outputs all overrides
- Phase 1 Terraform resources verified: google_storage_bucket, google_pubsub_topic/subscription, google_bigquery_dataset/table, google_spanner_instance/database

## Recent Changes
- 2026-05-11 licensing-security: Container preflight gate (license-gate.sh), session-based key management (SessionAuthDecorator), RS256 JWT signing (JwtSigner/KeyPairManager), BUILD_MODE production/dev switch, trial expiry enforcement (LicenseValidator), license-tier service gating (AdminApiService + LicenseTierProvider). See `docs/licensing-security.md`.

- 002-memorystore-emulator: Added Java 21 LTS (primary) + Netty codec-redis (RESP2 parser), Armeria (lifecycle), HikariCP (PostgreSQL pool), Jackson (JSONB)

- 001-gcp-local-emulator: Java 21 + Armeria (API gateway, gRPC+REST, console static files), PostgreSQL (persistence), proto-google-cloud-* (gRPC stubs), HikariCP, Solid.js (web console). 23 GCP services emulated (GCS, Pub/Sub, Firestore, BigQuery, Secret Manager, Cloud Tasks, Spanner, Bigtable, Logging, Monitoring, GKE, Compute Engine, Cloud Run, Memorystore, Workflows, Cloud Scheduler, Cloud Functions, AlloyDB, Dataproc, Cloud IAM, Vertex AI, Cloud KMS, Cloud SQL).

## Test Counts

- Java server (`localcloud-server`): 930 unit tests (JUnit 5 + Mockito)
- License server (`localcloud-license-server`): 47 unit tests (JUnit 5)
- Console: esbuild (no test suite)

### Licensing Security Test Coverage (added 2026-05-11)

| Test Class | Module | Tests | What it covers |
|---|---|---|---|
| `LicenseGateMainTest` | server | 2 | Container preflight gate: dev bypass exit-0, bad key format exit-1 |
| `ProductionModeTest` | server | 8 | BUILD_MODE file: missing→dev, "production"→enforced, bypass blocked in prod |
| `OnlineKeyValidatorTest` | server | 12 | JWT accept/reject, tampered/expired/wrong-key, tier parsing, public-key cache |
| `AdminServiceTierGatingTest` | server | 8 | Community blocked from pro services, PRO/Enterprise allowed, disable always allowed |
| `LicenseTierTest` | server | 10 | `includes()` ordinal comparisons across all tier pairs |
| `SessionRepositoryTest` | license-server | 6 | Create/validate/expire session tokens, null/blank/invalid token rejection |
| `SessionAuthDecoratorTest` | license-server | 4 | Missing header→401, invalid token→401, valid token sets context, DB error→500 |
| `JwtSignerTest` | license-server | 6 | RS256 sign/verify roundtrip, wrong key rejected, null fields, expired, issuer claim |
| `KeyPairManagerTest` | license-server | 5 | Ephemeral generation, base64 output, malformed env throws, X.509 format |
| `LicenseValidatorExpiryTest` | license-server | 6 | Trial expiry, active trial, no trial record, subscription expiry, perpetual key |

<!-- MANUAL ADDITIONS START -->

## Communication Style

- When asked to simplify, do it immediately. Provide the minimal working solution first, then offer to elaborate. Don't generate elaborate guides when a simple Dockerfile or config is requested.
- Prefer concise, actionable output over comprehensive documentation unless explicitly asked.

## Docker

- Always check ARG placement (must be after FROM for multi-stage builds).
- Verify volume mount paths and avoid relying on Docker cache during debugging iterations.
- Prefer incremental rebuilds over full rebuilds when debugging build issues.
- Always build the shadow JAR before `docker build` — the Dockerfile copies the pre-built JAR.

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

This project has a graphify knowledge graph at graphify-out/.

Rules:
- Before answering architecture or codebase questions, read graphify-out/GRAPH_REPORT.md for god nodes and community structure
- If graphify-out/wiki/index.md exists, navigate it instead of reading raw files
- For cross-module "how does X relate to Y" questions, prefer `graphify query "<question>"`, `graphify path "<A>" "<B>"`, or `graphify explain "<concept>"` over grep — these traverse the graph's EXTRACTED + INFERRED edges instead of scanning files
- After modifying code files in this session, run `graphify update .` to keep the graph current (AST-only, no API cost)
