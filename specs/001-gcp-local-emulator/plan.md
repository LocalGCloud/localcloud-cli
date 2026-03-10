# Implementation Plan: LocalCloud - GCP Local Emulator

**Branch**: `001-gcp-local-emulator` | **Date**: 2026-03-09 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/001-gcp-local-emulator/spec.md`

## Summary

LocalCloud is a local emulation platform for Google Cloud Platform services, packaged as a single Docker container. It provides a unified API gateway (Armeria-based) that routes both REST and gRPC requests to modular service emulators. Developers interact with the platform using official Google Cloud client libraries via standard environment variable overrides, with zero application code changes between local and production. The platform is implemented primarily in Java with a Python CLI tool for container management, and uses H2 as the embedded persistence engine.

## Technical Context

**Language/Version**: Java 21 (LTS, primary), Python 3.11+ (CLI/tooling)
**Primary Dependencies**: Armeria (API gateway, gRPC+REST), proto-google-cloud-* (gRPC stubs), H2 Database (persistence), HikariCP (connection pooling), Click (Python CLI), Docker SDK for Python
**Storage**: H2 embedded (file-backed) for structured data, local filesystem for GCS blobs, in-memory ConcurrentHashMap for transient data (Pub/Sub messages, task queues)
**Testing**: JUnit 5 + Testcontainers (Java), pytest + Google Cloud Python client libraries (integration tests)
**Target Platform**: Docker container (linux/amd64, linux/arm64), CLI on macOS/Linux/Windows
**Project Type**: Developer tool / infrastructure emulator
**Performance Goals**: <60s startup, <500ms p95 response time, 100 concurrent requests
**Constraints**: <2GB memory for 10 services, single Docker container, no Go, state persistence via Docker volumes
**Scale/Scope**: 10 GCP services emulated, targeting 80% API coverage for common operations

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution is in template state (no active gates configured). No violations to check. Proceeding.

## Project Structure

### Documentation (this feature)

```text
specs/001-gcp-local-emulator/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0: technology decisions
├── data-model.md        # Phase 1: entity model
├── quickstart.md        # Phase 1: developer quickstart guide
├── contracts/
│   ├── admin-api.md     # Phase 1: admin/dashboard REST API
│   ├── cli-commands.md  # Phase 1: CLI command reference
│   └── emulated-services.md  # Phase 1: GCP API surface coverage
└── tasks.md             # Phase 2 output (via /speckit.tasks)
```

### Source Code (repository root)

```text
# Java service emulator (core platform)
localcloud-server/
├── build.gradle
├── src/
│   ├── main/java/com/localcloud/
│   │   ├── LocalCloudApplication.java      # Entry point, Armeria server bootstrap
│   │   ├── gateway/
│   │   │   ├── ApiGateway.java             # Request routing, service dispatch
│   │   │   ├── RequestLogger.java          # Request/response logging (ring buffer)
│   │   │   └── HealthCheckService.java     # /_localcloud/health endpoint
│   │   ├── admin/
│   │   │   ├── AdminApiService.java        # /_localcloud/* REST endpoints
│   │   │   ├── BrowseService.java          # Data browsing endpoints
│   │   │   └── SeedService.java            # Seed loading endpoint
│   │   ├── persistence/
│   │   │   ├── H2DataSource.java           # H2 connection pool setup
│   │   │   ├── SchemaManager.java          # DDL initialization
│   │   │   └── BlobStore.java              # Filesystem blob storage for GCS
│   │   ├── emulators/
│   │   │   ├── EmulatorBase.java           # Shared emulator interface/lifecycle
│   │   │   ├── gcs/
│   │   │   │   └── StorageEmulator.java    # Cloud Storage REST handlers
│   │   │   ├── pubsub/
│   │   │   │   └── PubSubEmulator.java     # Pub/Sub gRPC service impl
│   │   │   ├── firestore/
│   │   │   │   └── FirestoreEmulator.java  # Firestore gRPC service impl
│   │   │   ├── bigquery/
│   │   │   │   └── BigQueryEmulator.java   # BigQuery REST handlers + SQL engine
│   │   │   ├── secretmanager/
│   │   │   │   └── SecretManagerEmulator.java
│   │   │   ├── cloudtasks/
│   │   │   │   └── CloudTasksEmulator.java
│   │   │   ├── spanner/
│   │   │   │   └── SpannerEmulator.java
│   │   │   ├── bigtable/
│   │   │   │   └── BigtableEmulator.java
│   │   │   ├── logging/
│   │   │   │   └── LoggingEmulator.java    # Sink: accept and store log entries
│   │   │   └── monitoring/
│   │   │       └── MonitoringEmulator.java # Sink: accept and store metrics
│   │   ├── events/
│   │   │   └── EventBus.java              # Cross-service event wiring
│   │   └── config/
│   │       └── LocalCloudConfig.java      # Configuration loading
│   └── main/resources/
│       ├── application.yaml               # Default configuration
│       └── dashboard/                     # Static SPA files for web dashboard
│           ├── index.html
│           ├── app.js
│           └── style.css
└── src/test/java/com/localcloud/
    ├── emulators/
    │   ├── StorageEmulatorTest.java
    │   ├── PubSubEmulatorTest.java
    │   └── ...
    └── integration/
        └── CrossServiceTest.java

# Python CLI tool
localcloud-cli/
├── pyproject.toml
├── src/localcloud/
│   ├── __init__.py
│   ├── cli.py                # Click CLI entry point
│   ├── commands/
│   │   ├── start.py          # Docker container management
│   │   ├── stop.py
│   │   ├── status.py
│   │   ├── env.py            # Environment variable generation
│   │   ├── seed.py           # Seed file processing
│   │   └── reset.py
│   ├── docker_manager.py     # Docker SDK wrapper
│   └── seed_processor.py     # YAML parsing, SDK-based seed loading
└── tests/
    ├── test_cli.py
    ├── test_seed_processor.py
    └── integration/
        ├── test_gcs_integration.py
        ├── test_pubsub_integration.py
        └── ...

# Docker packaging
Dockerfile
docker-compose.yml            # Reference compose file
docker-compose.override.yml   # Example override for applications
seed.yaml                     # Example seed file
```

**Structure Decision**: Two-project layout. `localcloud-server` is a Gradle-based Java project containing the Docker container contents (API gateway + all emulators). `localcloud-cli` is a Python package (pip-installable) that manages the Docker container from the host. They communicate via the admin REST API over HTTP.

## Complexity Tracking

No constitution violations to justify.
