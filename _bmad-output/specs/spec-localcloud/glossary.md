# Glossary

> **Companion to SPEC.md.** Key terminology used across the LocalCloud project.

## Core Concepts

| Term | Definition |
|------|-----------|
| **LocalCloud** | A single Docker container that emulates 23+ GCP services locally — same SDKs, same APIs, zero code changes. |
| **Emulator** | Software that mimics a real GCP service's API surface for development and testing. |
| **Facade** | In-process service implementation inside the Armeria gateway that stores metadata in PostgreSQL and returns plausible but simplified responses. Contrast with external emulators that run as separate processes. |
| **Gateway** | The Armeria server on port 8080 — single entry point for admin API, gRPC transcoding, console static files, and seed data loading. |
| **Seed** | YAML file with pre-defined data loaded at startup or via `POST /seed`. Provides deterministic state across environments. |

## Service Architecture

| Term | Definition |
|------|-----------|
| **External Emulator** | Standalone process managed by supervisord with its own port, binary, and persistence. Examples: fake-gcs-server, little_bigtable, bigquery-emulator v2. |
| **Facade Service** | In-process implementation on the gateway (port 8080). Stores metadata in PostgreSQL. Examples: Secret Manager, Cloud Tasks, GKE, IAM. |
| **Admin API** | REST endpoints on `:8080` for browsing, seeding, resetting, and managing the emulator environment. |
| **Console** | Solid.js single-page application served at `/` by the Armeria gateway. Provides SQL editor, data explorer, log viewer, and project switcher. |
| **Hybrid Routing** | Per-service toggle between local emulator and real GCP. Enables mixed local/cloud development. |

## Protocols & Ports

| Term | Definition |
|------|-----------|
| **gRPC** | Google's high-performance RPC framework used by most GCP service SDKs. |
| **REST** | HTTP-based API used by some GCP services and the admin API. |
| **gRPC Transcoding** | Armeria feature that maps REST requests to gRPC service methods, enabling both protocols on one endpoint. |
| **RESP2** | Redis Serialization Protocol v2 — the wire protocol used by Memorystore (valkey-server). |

## Development & Testing

| Term | Definition |
|------|-----------|
| **Inner Development Loop** | The cycle of edit → build → test → debug that developers repeat. LocalCloud makes this instant by running services locally. |
| **CI/CD Sidecar** | Running LocalCloud as a companion container in CI/CD pipelines (e.g., GitHub Actions service container). |
| **Custom Endpoint** | Environment variable (`GOOGLE_*_CUSTOM_ENDPOINT`) that redirects GCP SDKs to a local emulator instead of real GCP. |
| **JRE (jlink)** | Custom Java runtime (~72 MB) bundled in the Docker image, built with `jlink` to minimize size. |

## Persistence & State

| Term | Definition |
|------|-----------|
| **PostgreSQL 17** | Primary database for facade service data, admin state, usage metrics, and GCS project mappings. |
| **JSONB** | PostgreSQL binary JSON type used for Memorystore values, IAM policies, and flexible schemas. |
| **UPSERT** | INSERT ... ON CONFLICT UPDATE pattern used for usage metrics (one row per project+service). |
| **LevelDB** | Embedded key-value store used by the Spanner emulator for persistence. |
| **DuckDB** | Embedded analytical SQL engine used by the BigQuery emulator. |
| **RDB** | Redis Database file format used by valkey-server for Memorystore persistence. |

## Admin API

| Endpoint | Purpose |
|----------|---------|
| `GET /services` | List all services with status, port, protocol, endpoint, env vars |
| `GET /browse/{service}` | Paginated listing of resources for a service |
| `GET /browse/{service}/{id}` | Single resource detail |
| `POST /seed` | Load seed data from YAML |
| `POST /reset` | Reset all services (optionally restore seed) |
| `GET /health` | Health check endpoint |
| `GET /` | Web console (Solid.js SPA) |

## Licensing

| Term | Definition |
|------|-----------|
| **License Server** | Standalone Java microservice (port 9090) that manages user accounts, API keys, trials, and JWT issuance. |
| **JWT (RS256)** | JSON Web Token signed with RSA-SHA256. Contains user, device, tier, and expiry. Issued by license server, validated by gateway. |
| **Tier** | Access level: Community (free), Pro (individual), Team (multi-seat), Enterprise (air-gapped). |
| **Offline Key** | Enterprise license key that includes embedded signing public key for local JWT validation. |
| **Device Fingerprint** | Machine identifier used to bind trial and license keys to specific hardware. |

## Source-of-Truth Files

| File | Purpose |
|------|---------|
| `services.yaml` | Single source of truth for all service definitions (ports, protocols, env vars, health checks). Read by Java and Python. |
| `seed.yaml` | Example seed data file. Schema based on schema.org/Person. |
| `AGENTS.md` | AI agent guidelines — tech stack, build commands, architecture decisions, emulator notes. Primary context file for AI-assisted development. |
