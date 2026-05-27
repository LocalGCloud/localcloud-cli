# LocalCloud Architecture

> **Last updated:** 2026-05-26
> **Document type:** Reference — technical description of system machinery
> **Audience:** Contributors, platform engineers, technical evaluators

Overview of LocalCloud's runtime architecture, service topology, data flow, and key design decisions.

---

## Container Layout

```
Docker Container (debian:trixie-slim)
├── supervisord (process manager)
│   ├── Armeria Gateway (Java 25, port 8080)
│   │   ├── Admin REST API (/health, /services, /env, /browse, /seed, /reset)
│   │   ├── Web Console (Solid.js SPA, served at /)
│   │   ├── gRPC Facade Services (in-process)
│   │   │   ├── Secret Manager, Cloud Tasks, Cloud Logging, Cloud Monitoring
│   │   │   ├── GKE, Compute Engine, Cloud Run
│   │   │   ├── Cloud Workflows, Cloud Scheduler, Cloud Functions
│   │   │   ├── AlloyDB, Dataproc, Cloud IAM
│   │   │   └── Vertex AI, Cloud KMS, Cloud SQL
│   │   └── Memorystore RESP2 server (port 6379)
│   ├── Pub/Sub Facade (port 8085, in-process Java)
│   ├── fake-gcs-server (port 4443, Go)
│   ├── cloud-firestore-emulator (port 8086, Java JAR)
│   ├── Spanner emulator (port 9010 gRPC, 9020 REST, Go)
│   ├── BigQuery emulator (port 9050 REST, 9060 gRPC, Python + DuckDB + SQLGlot)
│   ├── Bigtable emulator (port 8087, Go — little_bigtable)
│   ├── Memorystore/Valkey (port 6379, Valkey 8.x)
│   └── PostgreSQL 17 (port 5432, internal only)
└── Persistent Volume (/var/lib/localcloud)
    ├── pgdata/              # PostgreSQL data directory
    ├── gcs-data/            # GCS blob filesystem storage
    ├── spanner-data/        # Spanner LevelDB data files
    ├── spanner-data-backup/ # Spanner periodic snapshot backup
    └── bigquery-data/       # BigQuery DuckDB database files
```

---

## Service Categories

### External Emulators (separate processes, own ports)

| Service | Binary | Language | Port | Persistence |
|---------|--------|----------|------|-------------|
| Cloud Storage | fake-gcs-server | Go | 4443 | Filesystem |
| Pub/Sub | In-process Java facade | Java | 8085 | PostgreSQL |
| Firestore | cloud-firestore-emulator | Java (JAR) | 8086 | In-memory |
| Bigtable | little_bigtable | Go | 8087 | SQLite |
| Spanner | spanner-emulator-wrapper | Go | 9010/9020 | LevelDB |
| BigQuery | bigquery-emulator v2 | Python | 9050/9060 | DuckDB |
| Memorystore | valkey-server | C | 6379 | RDB snapshots |

### Facade Services (in-process on gateway port 8080)

All facade services share the Armeria gateway JVM and are backed by PostgreSQL. They expose gRPC or REST endpoints via Armeria's unified port model.

| Service | Protocol | Persistence Tables |
|---------|----------|-------------------|
| Secret Manager | gRPC | `secrets`, `secret_versions` |
| Cloud Tasks | gRPC | `task_queues`, `tasks` |
| Cloud Logging | gRPC | `log_entries` |
| Cloud Monitoring | gRPC | `time_series` |
| GKE | gRPC | `gke_clusters` |
| Compute Engine | REST | `compute_instances` |
| Cloud Run | gRPC | `cloud_run_services` |
| Cloud Workflows | gRPC | `workflows`, `workflow_executions` |
| Cloud Scheduler | gRPC | `scheduler_jobs` |
| Cloud Functions | gRPC | `cloud_functions` |
| AlloyDB | gRPC | `alloydb_clusters`, `alloydb_instances` |
| Dataproc | gRPC | `dataproc_clusters`, `dataproc_jobs` |
| Cloud IAM | gRPC | `iam_policies` |
| Vertex AI | REST | `vertex_ai_*` (stubs) |
| Cloud KMS | REST | `kms_key_rings`, `kms_crypto_keys` |
| Cloud SQL | REST | `cloud_sql_instances` |

---

## Data Flow

### SDK Request Path (application code)

```text
Application (Python/Java/Go/Node.js)
    │  GCP client library
    │  EMULATOR_HOST env var points to localhost
    ▼
Service Endpoint (gRPC or REST)
    │  e.g., localhost:4443 (GCS), localhost:8085 (Pub/Sub)
    ▼
Emulator Process (external or facade)
    │
    ▼
Storage Backend (PostgreSQL, DuckDB, filesystem, LevelDB)
```

### Admin API / Console Path

```text
Browser (http://localhost:8080)
    │  Solid.js SPA
    ▼
Armeria Gateway (port 8080)
    ├─ / → Static files (console SPA)
    ├─ /health, /services, /env, /browse, /seed, /reset → Admin REST handlers
    └─ /browse/{service} → Read PostgreSQL directly (BrowseService)
        /mutate/{service} → Write PostgreSQL (MutateService)
```

### Seed Data Flow

```text
seed.yaml
    │  POST /seed (or auto-load on startup)
    ▼
SeedService.java
    ├─ GCS → fake-gcs-server REST API
    ├─ Pub/Sub → Pub/Sub gRPC client
    ├─ BigQuery → BigQuery REST/gRPC client
    ├─ Firestore → Firestore gRPC client
    ├─ Bigtable → Bigtable gRPC client
    ├─ Spanner → Spanner gRPC client
    ├─ Secret Manager → Direct PostgreSQL INSERT (not gRPC)
    └─ Facades → Direct PostgreSQL INSERT
```

---

## PostgreSQL Schema (key tables)

```
localcloud (database)
├── gcs_bucket_projects    # Project-to-bucket ownership mapping
├── pubsub_topics           # Topic definitions
├── pubsub_subscriptions    # Subscription definitions
├── pubsub_messages         # Published messages (persistent)
├── firestore_documents     # Firestore document storage
├── bigtable_data           # Bigtable row mirror (for console browse)
├── secrets                 # Secret Manager secrets
├── secret_versions         # Secret versions with data
├── task_queues             # Cloud Tasks queue definitions
├── tasks                   # Task instances
├── log_entries             # Cloud Logging entries
├── time_series             # Cloud Monitoring time series
├── workflows               # Workflow definitions
├── workflow_executions     # Workflow execution state
├── scheduler_jobs          # Cloud Scheduler job definitions
├── cloud_functions         # Cloud Functions configs
├── alloydb_clusters        # AlloyDB cluster metadata
├── alloydb_instances       # AlloyDB instance metadata
├── dataproc_clusters       # Dataproc cluster metadata
├── dataproc_jobs           # Dataproc job metadata
├── iam_policies            # IAM policy storage
├── kms_key_rings           # KMS key ring metadata
├── kms_crypto_keys         # KMS crypto key metadata
├── cloud_sql_instances     # Cloud SQL instance metadata
├── compute_instances       # Compute Engine instance metadata
├── cloud_run_services      # Cloud Run service metadata
├── gke_clusters            # GKE cluster metadata
├── redis_data              # Memorystore key-value store (JSONB)
├── service_routing         # Per-service routing config (local/remote)
├── usage_metrics           # Per-project service usage counters
├── projects                # Multi-project management
└── request_log             # Request log entries
```

---

## Key Design Decisions

### 1. Single PostgreSQL as primary persistence

All facade services store state in PostgreSQL. External emulators use their own storage (filesystem for GCS, DuckDB for BigQuery, LevelDB for Spanner, SQLite for Bigtable). The long-term direction is PostgreSQL for everything (see TECH_DEBT.md items 2.10, 2.11).

### 2. Armeria unified gateway

Armeria handles both gRPC and REST on a single port (8080). This eliminates port proliferation and simplifies TLS, logging, and middleware. Facade services register gRPC service implementations directly with the Armeria server builder.

### 3. Solid.js SPA served from gateway

The console is a client-side Solid.js app. Armeria serves the built static files at `/` and the admin API at `/health`, `/services`, etc. No separate web server process needed.

### 4. Seed data via YAML

Seed data is defined in `seed.yaml` with a `services:` wrapper. The Java `SeedService` parses YAML and dispatches to each service's native API (gRPC, REST, or direct PostgreSQL). Seeded data is not guaranteed visible through all paths for all services (see Bigtable, Firestore persistence gaps).

### 5. Usage metrics via in-memory counters + periodic flush

The `UsageMetricsRepository` maintains in-memory counters per service per project. Every 30 seconds, counters are UPSERT-ed into the `usage_metrics` PostgreSQL table. This avoids per-request database writes.

### 6. Hybrid routing (local vs remote)

Per-service routing can be toggled between local emulator and real GCP. The routing decision is stored in PostgreSQL (`service_routing` table) and managed via the console Settings page. Custom CA certificates are auto-detected for corporate proxy environments.

### 7. Multi-project support

Projects are isolated in PostgreSQL via `project_id` columns on all data tables. The `/projects` admin API manages project lifecycle. The console includes a project switcher for scoped browsing.

---

## Build & Deploy

| Stage | Tool | Output |
|-------|------|--------|
| Java server | Gradle (`./gradlew shadowJar`) | Fat JAR with all dependencies |
| Console | esbuild (`npm run build`) | Minified JS + CSS in `dist/` |
| Docker image | `docker build` | `localcloud/localcloud:latest` |
| Container orchestration | supervisord | Manages 7+ internal processes |

The Docker image uses a multi-stage build:
1. `eclipse-temurin:25-jdk` → jlink → custom 72 MB JRE
2. `debian:trixie-slim` base with PostgreSQL 17, Valkey 8, and all emulator binaries
3. Pre-built server JAR and console dist copied into image

---

## See Also

- [SERVICE_STATUS.md](SERVICE_STATUS.md) — Per-service coverage and tier mapping
- [DEVELOPER_GUIDE.md](../DEVELOPER_GUIDE.md) — User-facing setup and usage
- [services.yaml](../services.yaml) — Machine-readable service registry
- [TECH_DEBT.md](TECH_DEBT.md) — Known technical debt and architectural concerns
- [native-mode-plan.md](native-mode-plan.md) — Non-Docker deployment mode (planned)
