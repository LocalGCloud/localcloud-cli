# Research: LocalCloud - GCP Local Emulator

**Date**: 2026-03-09
**Feature Branch**: `001-gcp-local-emulator`

## Decision 1: API Gateway Framework

**Decision**: Armeria

**Rationale**: Armeria natively supports both gRPC and REST on the same port with HTTP/JSON transcoding from proto annotations. Since GCP APIs are a mix of gRPC (Pub/Sub, Firestore, Spanner, Bigtable, Secret Manager, Cloud Tasks, Logging, Monitoring) and REST (Cloud Storage, BigQuery), Armeria eliminates the need for separate protocol handling. It supports virtual host and multi-port binding for gRPC services that need dedicated ports.

**Alternatives considered**:
- Spring Cloud Gateway: No native gRPC serving; `JsonToGrpc` filter is limited to proxying, not serving. Overkill dependency footprint.
- Vert.x: Requires manual gRPC integration; no built-in HTTP/JSON transcoding.
- Helidon: Split between SE and MP variants; no transcoding.
- Raw Netty: Maximum flexibility but requires building all protocol handling from scratch.

## Decision 2: Embedded Database

**Decision**: PostgreSQL (via Docker container, managed by supervisord) as primary persistence engine + ConcurrentHashMap for transient/simple data

**Rationale**: PostgreSQL provides full SQL (needed for BigQuery and Spanner emulation), JSONB columns (needed for Firestore documents), and robust concurrent access. Running as a companion service inside the Docker container keeps the deployment self-contained while providing production-grade persistence. Cloud Storage blob data is stored on the filesystem with metadata in PostgreSQL. Pub/Sub messages, Cloud Tasks queues use in-memory ConcurrentHashMap with PostgreSQL persistence for durability.

**Alternatives considered**:
- H2 embedded: Full SQL and zero-config but single-connection limitations under concurrent load; may revisit for lightweight deployment mode in future.
- SQLite via JDBC: Single-writer limitation, native wrapper adds platform concerns in Docker.
- RocksDB: Excellent for key-value but no SQL; would still require a SQL engine for BigQuery/Spanner.
- MapDB: Unmaintained, memory-mapped file issues, no SQL.

## Decision 3: Docker Base Image & JVM Configuration

**Decision**: `eclipse-temurin:21-jre-jammy` with ZGC generational mode

**Rationale**: Eclipse Temurin is the community standard OpenJDK distribution. JRE-only image reduces size to ~210MB. Java 21 LTS provides virtual threads and ZGC generational mode for sub-millisecond GC pauses. JVM configured with `-XX:MaxRAMPercentage=75.0` to respect the <2GB memory constraint.

**Key JVM flags**:
- `-XX:+UseContainerSupport` - respect Docker memory limits
- `-XX:MaxRAMPercentage=75.0` - 75% of container memory for heap
- `-XX:+UseZGC -XX:+ZGenerational` - low-latency GC
- `-Xss256k` - reduced thread stack size
- `-XX:MaxMetaspaceSize=128m` - bounded metaspace
- `-XX:+ExitOnOutOfMemoryError` - fail fast for Docker restart

**Alternatives considered**:
- Amazon Corretto: Excellent but Temurin has broader open-source adoption.
- Alpine-based images: Smaller but library compatibility issues with JNI.
- GraalVM native image: Significantly reduces startup time but limits reflection-heavy frameworks.

## Decision 4: GCP API Definitions Source

**Decision**: Use pre-compiled `proto-google-cloud-*` Maven artifacts for gRPC services; use Google API Discovery Documents for REST services

**Rationale**: The `googleapis/googleapis` GitHub repo contains all proto definitions. Pre-compiled Java artifacts (`proto-google-cloud-pubsub-v1`, `proto-google-cloud-firestore-v1`, etc.) are available on Maven Central. Extending `*Grpc.*ImplBase` stubs guarantees API compatibility. For REST-only services (GCS v1, BigQuery v2), Google API Discovery Documents provide the complete endpoint/parameter/response schema.

## Decision 5: Python's Role

**Decision**: Python lives outside the Docker container. It handles: CLI tool (`localcloud` command via Click), seed file processing (using Google Cloud Python client libraries), and integration tests (pytest).

**Rationale**: This mirrors LocalStack's architecture. The CLI is pip-installable and manages the Docker container via the Docker SDK for Python. Seed processing uses official Google Cloud Python SDKs to validate emulator compatibility as a side effect. No Python runtime inside the container keeps the image lean.

**Alternatives considered**:
- Typer for CLI: Built on Click but adds a dependency layer with no significant advantage for Docker-management CLIs.
- Python inside the container for dashboard: Would bloat the image with a second runtime. Dashboard is better served as a static SPA from the Java gateway.

## Decision 6: Web Console Architecture

**Decision**: Separate web console with Solid.js SPA frontend + Flask backend, launched via `localcloud console` CLI command

**Rationale**: A dedicated console process (port 9090) with Flask proxying admin API calls to the Java gateway provides better developer experience than embedding a static SPA in the JAR. Solid.js offers reactive state management with minimal bundle size (~26KB minified). The console includes data browsing, service management, log viewing, and settings.

**Admin API endpoints** (served by Java gateway, proxied by Flask):
- `GET /_localcloud/health` - service health
- `GET /_localcloud/services` - running services with ports
- `GET /_localcloud/requests` - recent request log (ring buffer)
- `GET /_localcloud/browse/{service}/{resource}` - data browsing

## Decision 7: SDK Endpoint Override Patterns

**Decision**: Support all three Google Cloud SDK override mechanisms

| Pattern | Services | Mechanism |
|---------|----------|-----------|
| Auto-detect env var | Firestore, Bigtable, Spanner | `*_EMULATOR_HOST` env var (automatic) |
| `setHost()` | Cloud Storage, BigQuery | REST endpoint override |
| `setEndpoint()` + plaintext | Secret Manager, Cloud Tasks, Logging, Monitoring, Pub/Sub | gRPC endpoint with `usePlaintext()` |

**Key requirement**: All services must accept plaintext (no TLS) and accept requests without credentials.

## Decision 8: Seed File Format

**Decision**: YAML format organized by service, supporting `source` file references and inline `content`. Processed via Google Cloud Python client libraries for SDK-compatibility validation.

**Key design choices**:
- `source` for file references, `content` for inline data, `dataBase64` for binary
- Secret versions listed newest-first; first ENABLED becomes `latest`
- Firestore subcollections via path notation in `collection` field
- BigQuery supports inline `rows` and external JSONL `source` files
- Processing via Python SDKs doubles as an integration test

## Decision 9: Port Strategy

**Decision**: Hybrid port allocation

| Port | Service | Protocol |
|------|---------|----------|
| 8080 | REST gateway (GCS browse, SecretManager, CloudTasks, Logging, Monitoring, GKE, Compute, Cloud Run, admin dashboard) | HTTP/REST + gRPC transcoding |
| 4443 | Cloud Storage (fake-gcs-server) | HTTP/HTTPS |
| 8085 | Pub/Sub emulator | gRPC |
| 8086 | Firestore emulator | gRPC |
| 8087 | Bigtable emulator | gRPC |
| 9010 | Spanner emulator (gRPC) | gRPC |
| 9020 | Spanner emulator (REST gateway) | HTTP/REST |
| 9050 | BigQuery emulator (REST) | HTTP/REST |
| 9060 | BigQuery emulator (gRPC) | gRPC |
| 6443 | GKE / k3d Kubernetes API | HTTPS |

**Rationale**: External emulators (fake-gcs-server, gcloud emulators, spanner-emulator, bigquery-emulator) use their own default ports since Google Cloud client libraries connect to `host:port` directly via `*_EMULATOR_HOST` env vars. Facade services (Secret Manager, Cloud Tasks, Logging, Monitoring, GKE, Compute, Cloud Run) share the Armeria gateway on port 8080 via path-based and gRPC service routing. The Spanner emulator exposes both a gRPC port (9010) and a REST gateway (9020). BigQuery similarly exposes separate REST (9050) and gRPC (9060) ports.

## Decision 10: State Persistence Strategy

**Decision**: PostgreSQL database (managed by supervisord) + filesystem blobs, stored under `/var/lib/localcloud/` in the container, exposed as a Docker volume mount

**Directory structure**:
```
/var/lib/localcloud/
├── postgresql/            # PostgreSQL data directory
├── blobs/                 # GCS object data (managed by external GCS emulator)
│   └── {bucket}/{object-path}
├── logs/                  # Cloud Logging entries
└── metrics/               # Cloud Monitoring data
```

**Volume mount**: `docker run -v localcloud-data:/var/lib/localcloud ...`
