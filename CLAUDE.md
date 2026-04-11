# localcloud Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-04-07

## Active Technologies
- Java 21 LTS (primary), Python 3.11+ (CLI/console updates) + Netty codec-redis (RESP2 parser), Armeria (lifecycle), HikariCP (PostgreSQL pool), Jackson (JSONB) (002-memorystore-emulator)
- PostgreSQL — single `redis_data` table with JSONB values for all 5 data types (002-memorystore-emulator)

- Java 21 (LTS, primary), Python 3.11+ (CLI/tooling) + Armeria (API gateway, gRPC+REST), proto-google-cloud-* (gRPC stubs), PostgreSQL (persistence), HikariCP (connection pooling), Click (Python CLI), Docker SDK for Python, Solid.js (console frontend), Flask (console backend) (001-gcp-local-emulator)

## Project Structure

```text
localcloud-server/    # Java API gateway + facade emulators (Armeria + PostgreSQL)
localcloud-cli/       # Python CLI tool (Click)
localcloud-console/   # Web console (Solid.js + Flask)
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

# Python CLI (install + test)
cd localcloud-cli && pip install -e ".[test]" && pytest

# Python CLI linting
cd localcloud-cli && ruff check .

# Console frontend
cd localcloud-console && npm install && npm run build

# Docker image (requires shadow JAR built first)
docker compose build
docker compose up -d
```

## Code Style

Java 21 (LTS, primary), Python 3.11+ (CLI/tooling): Follow standard conventions

## Architecture

- **Gateway (port 8080)**: Armeria server hosting admin API + in-process gRPC facades (Secret Manager, Cloud Tasks, Logging, Monitoring, GKE, Compute, Cloud Run)
- **External emulators**: Managed by supervisord inside Docker container (GCS on 4443, Pub/Sub on 8085, Firestore on 8086, Bigtable on 8087, Spanner on 9010, BigQuery on 9050)
- **Persistence**: PostgreSQL (inside container) for facade service data; filesystem for GCS blobs
- **Console**: Host-side Flask + Solid.js app (not inside container), started via `localcloud console`
- **Seed**: YAML files with `services:` wrapper; loaded via `POST /_localcloud/seed`

## Key Implementation Notes

- GCS emulator uses HTTP-only (`-scheme http`) on port 4443
- Secret Manager seeding uses direct PostgreSQL inserts (not REST transcoding)
- JVM tuned to `-Xmx512m -Xms128m` to coexist with emulators in container
- `/_localcloud/services` returns array format with id, name, status, port, protocol, endpoint, env_var, env_value, request_count
- `/_localcloud/reset` reads `restore_seed` from JSON body (not query params)
- Seed YAML supports both flat format (`gcs: ...`) and nested format (`services: { gcs: ... }`)
- BigQuery emulator binary is amd64-only; runs via QEMU on arm64
- Container needs `-m 4g` memory limit for stable operation

## Recent Changes
- 002-memorystore-emulator: Added Java 21 LTS (primary), Python 3.11+ (CLI/console updates) + Netty codec-redis (RESP2 parser), Armeria (lifecycle), HikariCP (PostgreSQL pool), Jackson (JSONB)

- 001-gcp-local-emulator: Java 21 + Armeria (API gateway, gRPC+REST), PostgreSQL (persistence), proto-google-cloud-* (gRPC stubs), HikariCP, Click (Python CLI), Docker SDK, Solid.js + Flask (web console). 14 GCP services emulated (GCS, Pub/Sub, Firestore, BigQuery, Secret Manager, Cloud Tasks, Spanner, Bigtable, Logging, Monitoring, GKE, Compute Engine, Cloud Run, Memorystore).

## Test Counts

- Java server: 187 unit tests (JUnit 5 + Mockito)
- Python CLI: 66 unit tests (pytest)
- Console: esbuild (no test suite)

<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->
