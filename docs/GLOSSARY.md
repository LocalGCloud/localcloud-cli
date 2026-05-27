# LocalCloud Glossary

> **Last updated:** 2026-05-26
> **Document type:** Reference — terminology dictionary
> **Audience:** All users and contributors

Standardized terminology used across LocalCloud documentation, code, and communication.

---

## Core Concepts

| Term | Definition |
|------|------------|
| **LocalCloud** | The product: a single Docker container emulating 23 GCP services for local development, testing, and CI/CD. |
| **Emulator** | A program that implements real GCP wire protocols (gRPC/REST) locally, behaving like the real service. LocalCloud uses both official Google emulators and custom implementations. |
| **Facade** | A lightweight in-process Java implementation that provides the API surface for a GCP service. Runs inside the Armeria gateway on port 8080. Backed by PostgreSQL. |
| **Gateway** | The Armeria-based Java server (port 8080) that routes traffic, serves the console, hosts facade services, and exposes the admin API. The central nervous system of LocalCloud. |
| **Seed data** | YAML-based configuration (`seed.yaml`) that pre-populates services with test data on startup or via `POST /seed`. |
| **Console** | The Solid.js web UI served by the gateway at `http://localhost:8080`. Provides dashboard, data browser, SQL editor, log viewer, and settings. |
| **Supervisord** | The process manager inside the Docker container that starts and monitors PostgreSQL, the gateway, and all external emulators. |

---

## Service Architecture

| Term | Definition |
|------|------------|
| **External emulator** | A service running as a separate process (not inside the gateway JVM) on its own port. Managed by supervisord. Examples: GCS (port 4443), Firestore (port 8086), BigQuery (port 9050). |
| **Facade service** | A service implemented in-process inside the Armeria gateway JVM. Shares port 8080. Uses PostgreSQL for persistence. Examples: Secret Manager, Cloud Tasks, Cloud Workflows. |
| **Service registry** | The `services.yaml` file — the single source of truth for all service definitions (ports, protocols, env vars, tier, type). |
| **Tier (Community/Pro)** | License-based access level. Community tier includes 15 services; Pro tier unlocks all 23. Controlled via licensing. |

---

## Protocols & Ports

| Term | Definition |
|------|------------|
| **gRPC** | Google's high-performance RPC framework using Protocol Buffers. Used by most GCP APIs. |
| **REST** | Traditional HTTP/JSON API. Used by GCS, BigQuery, Compute, and some admin endpoints. |
| **RESP2** | Redis Serialization Protocol v2. Used by Memorystore (Valkey) on port 6379. |
| **Armeria** | The Java HTTP/gRPC server framework that powers the gateway. Handles both REST and gRPC on a single port. |
| **jlink** | Java tool that builds a minimal custom JRE. LocalCloud uses it to create a 72 MB JRE instead of shipping the full 200+ MB JDK. |

---

## Development & Testing

| Term | Definition |
|------|------------|
| **Inner loop** | A developer's local write-build-test cycle. LocalCloud reduces inner loop latency from 2-5s (cloud) to <1ms (local). |
| **Shadow JAR** | A fat JAR containing the Java server and all its dependencies. Built by the Gradle Shadow plugin. |
| **esbuild** | The JavaScript bundler used to build the Solid.js console app. Much faster than Webpack/Vite for this use case. |
| **Multi-project** | LocalCloud's support for multiple isolated GCP projects within one container. Managed via `/projects` API. |
| **Hybrid routing** | Per-service configuration to route traffic to either the local emulator or real GCP. Toggled in console Settings. |

---

## Persistence & State

| Term | Definition |
|------|------------|
| **PostgreSQL** | The primary persistence database. Stores all facade service data, usage metrics, projects, and admin state. Version 17. |
| **DuckDB** | The embedded analytical database backing the BigQuery emulator. In-process, columnar, SQL-compatible. |
| **LevelDB** | The embedded key-value store used by the Spanner emulator for persistence. |
| **Docker volume** | Persistent storage for `/var/lib/localcloud` — survives container restarts. Contains PostgreSQL data, GCS blobs, and Spanner/BigQuery files. |
| **UPSERT** | Insert-or-update database operation. Used for usage metrics (one row per project+service, counter updated in-place). |

---

## Admin API

| Term | Definition |
|------|------------|
| **`/health`** | Health check endpoint. Returns JSON with service status, uptime, and version. |
| **`/services`** | Lists all 23 services with status, port, protocol, request count. |
| **`/env`** | Returns environment variables for SDK configuration. Supports `shell`, `json`, `docker-compose`, and `terraform` formats. |
| **`/browse/{service}`** | Read-only data browser endpoint. Returns service data from PostgreSQL (may not match SDK-visible state for external emulators). |
| **`/mutate/{service}`** | Data mutation endpoint for the console. Writes to PostgreSQL (may not affect SDK-visible state for external emulators). |
| **`/seed`** | Loads seed data from a YAML body. Writes to each service's native API or PostgreSQL. |
| **`/reset`** | Resets all data. Supports `{"restore_seed": true}` to re-load seed after reset. |

---

## Source-of-Truth Split

| Term | Definition |
|------|------------|
| **Source-of-truth split** | A known architectural gap where SDK-created data lives in the external emulator's memory/files but the console reads from PostgreSQL. This affects Bigtable, Firestore, and (historically) Pub/Sub. Tracked in TECH_DEBT.md. |

---

## Licensing

| Term | Definition |
|------|------------|
| **`ENFORCE_LICENSE`** | Docker build argument controlling whether license validation runs at startup. Default `false` (no license required). |
| **`LOCALCLOUD_API_KEY`** | Runtime environment variable for license activation. Required when `ENFORCE_LICENSE=true`. |
| **License server** | Optional standalone service (`localcloud-license-server`) for centralized license management in team/enterprise deployments. |
| **JWT (RS256)** | JSON Web Token with RSA-SHA256 signing. Used for session-based license validation between gateway and license server. |

---

## See Also

- [ARCHITECTURE.md](ARCHITECTURE.md) — System design
- [SERVICE_STATUS.md](SERVICE_STATUS.md) — Service coverage matrix
- [DEVELOPER_GUIDE.md](../DEVELOPER_GUIDE.md) — Usage documentation
