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

**Decision**: H2 (embedded mode, file-backed) as primary persistence engine + ConcurrentHashMap for transient/simple data

**Rationale**: H2 provides full SQL (needed for BigQuery and Spanner), JSON column type (needed for Firestore documents), and VARBINARY support (needed for Bigtable cells and GCS metadata). Using a single database engine minimizes complexity. Cloud Storage blob data is stored on the filesystem with metadata in H2. Pub/Sub messages, Cloud Tasks queues, and Secret Manager entries use in-memory ConcurrentHashMap with optional H2 persistence for durability.

**Alternatives considered**:
- SQLite via JDBC: Single-writer limitation, native wrapper adds platform concerns in Docker.
- RocksDB: Excellent for key-value but no SQL; would require H2 anyway for BigQuery/Spanner.
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

## Decision 6: Web Dashboard Architecture

**Decision**: Java-embedded admin REST API + minimal static SPA (Preact or Alpine.js) bundled in the JAR

**Rationale**: The gateway exposes admin endpoints at `/_localcloud/` (health, service status, request logs, data browsing). A lightweight SPA is served as static files from the JAR. No Python runtime needed in the container. The dashboard is read-only.

**Admin API endpoints**:
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
| 8080 | REST gateway (GCS, BigQuery, Secret Manager, Cloud Tasks, admin dashboard) | HTTP/REST |
| 9010 | Firestore | gRPC |
| 9020 | Pub/Sub | gRPC |
| 9030 | Spanner | gRPC |
| 9040 | Bigtable | gRPC |

**Rationale**: REST services share the gateway port via path-based routing. gRPC services get dedicated ports because Google Cloud client libraries for these services connect to `host:port` directly (the `*_EMULATOR_HOST` env vars expect a `host:port` value, not a URL path).

## Decision 10: State Persistence Strategy

**Decision**: H2 file-backed database + filesystem blobs, stored under `/var/lib/localcloud/` in the container, exposed as a Docker volume mount

**Directory structure**:
```
/var/lib/localcloud/
├── db/                    # H2 database files
│   └── localcloud.mv.db
├── blobs/                 # GCS object data
│   └── {bucket}/{object-path}
├── logs/                  # Cloud Logging entries
└── metrics/               # Cloud Monitoring data
```

**Volume mount**: `docker run -v localcloud-data:/var/lib/localcloud ...`
