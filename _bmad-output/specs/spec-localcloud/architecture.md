# Architecture

> **Companion to SPEC.md.** Technical architecture, design decisions, data flow, and build/deploy pipeline.

## Container Layout

```
┌─────────────────────────────────────────────────────┐
│  Docker Container (debian:trixie-slim, 4GB mem)     │
│                                                      │
│  ┌──────────────────┐  ┌──────────────────────────┐ │
│  │  supervisord      │  │  Armeria Gateway :8080    │ │
│  │                   │  │                           │ │
│  │  GCS :4443       │  │  Admin API (REST)         │ │
│  │  Pub/Sub :8085   │  │  gRPC Facades (in-proc)  │ │
│  │  Firestore :8086 │  │  Console Static Files     │ │
│  │  Bigtable :8087  │  │  Seed Data Loading        │ │
│  │  Spanner :9010   │  │  Health Checks            │ │
│  │  BigQuery :9050  │  │  Usage Metrics            │ │
│  │  Memorystore :6379│  │  Hybrid Routing           │ │
│  └──────────────────┘  └──────────────────────────┘ │
│                                                      │
│  ┌──────────────────┐  ┌──────────────────────────┐ │
│  │  PostgreSQL 17    │  │  License Server :9090    │ │
│  │  - redis_data     │  │  (Java, own PG schema)   │ │
│  │  - usage_metrics  │  │                           │ │
│  │  - gcs_bucket_*   │  └──────────────────────────┘ │
│  │  - facade tables  │                               │
│  └──────────────────┘                               │
└─────────────────────────────────────────────────────┘
```

## Service Categories

### External Emulators

Separate processes with own ports, managed by supervisord. Each has independent persistence (filesystem, SQLite, LevelDB, DuckDB, RDB).

### Facade Services

In-process on gateway port 8080. Store metadata in PostgreSQL. Return plausible responses but do not execute real cloud operations. Seed data flows through these facades.

## Data Flow

### SDK Request Path (application code)
```
App Code → GCP SDK → gRPC/REST to emulator port → Emulator/Facade → Response
```

### Admin API / Console Path
```
Browser → :8080 → Armeria Gateway → PostgreSQL query → JSON response
```

### Seed Data Flow
```
seed.yaml → POST /seed → Gateway parses → Per-service inserts → PostgreSQL
```

## PostgreSQL Schema (key tables)

| Table | Purpose |
|-------|---------|
| `redis_data` | Memorystore key-value store (JSONB values) |
| `usage_metrics` | Per-project+service request counts (UPSERT, 30s flush) |
| `gcs_bucket_projects` | GCS bucket → project mapping |
| `secret_versions` | Secret Manager secrets |
| `task_queues` | Cloud Tasks queues |
| `log_entries` | Cloud Logging entries |
| `time_series` | Cloud Monitoring metrics |
| `gke_clusters` | GKE cluster metadata |
| `compute_instances` | Compute Engine instances |
| `cloud_run_services` | Cloud Run services |
| `workflow_executions` | Cloud Workflows executions |
| `scheduler_jobs` | Cloud Scheduler jobs |
| `cloud_functions` | Cloud Functions configs |
| `alloydb_clusters` | AlloyDB clusters |
| `alloydb_instances` | AlloyDB instances |
| `dataproc_clusters` | Dataproc clusters |
| `dataproc_jobs` | Dataproc jobs |
| `iam_policies` | IAM policies (JSONB) |
| `kms_key_rings` | KMS key rings |
| `kms_crypto_keys` | KMS crypto keys |
| `cloud_sql_instances` | Cloud SQL instances |

## Key Design Decisions

1. **Single PostgreSQL as primary persistence** — Facade services share one PG instance. Separate databases per AlloyDB cluster. Simplifies backup, seed, and reset.

2. **Armeria unified gateway** — Single entry point (port 8080) for admin API, gRPC transcoding, and console static files. Simplifies routing, health checks, and CORS.

3. **Solid.js SPA served from gateway** — Browser-based console served as static files from Armeria at `/`. No separate web server needed.

4. **Seed data via YAML** — Deterministic, version-controllable, human-readable. Loaded on startup or via `POST /seed`. Supports both flat and nested formats.

5. **Usage metrics via in-memory counters + periodic flush** — Counters in Java memory, flushed to PostgreSQL every 30 seconds. UPSERT by project+service. Avoids per-request DB writes.

6. **Hybrid routing (local vs remote)** — Per-service toggle between local emulator and real GCP. Example: BigQuery to cloud, everything else local.

7. **Multi-project support** — Projects isolated in PostgreSQL via `project_id` columns. `/projects` admin API manages lifecycle. Console includes project switcher.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Gateway server | Java 21 LTS + Armeria |
| Database | PostgreSQL 17 |
| Connection pool | HikariCP |
| Redis protocol | Netty codec-redis (RESP2 parser) |
| JSON processing | Jackson (JSONB) |
| Console frontend | Solid.js |
| Console build | esbuild |
| Container base | debian:trixie-slim |
| JRE | jlink custom (Java 25, ~72 MB) |
| Process manager | supervisord |
| Build tool | Gradle (shadow JAR) |
| Cors | Armeria built-in |
| gRPC stubs | proto-google-cloud-* |
| Cron parser | cron-utils |
| Job dispatch | ScheduledExecutorService |
| Test framework | JUnit 5 + Mockito |

## Build & Deploy

```bash
# Build server
cd localcloud-server && ./gradlew build

# Build shadow JAR (for Docker)
cd localcloud-server && ./gradlew shadowJar

# Build console
cd localcloud-console && npm install && npm run build

# Build and run Docker image
docker compose build
docker compose up -d
```

JVM tuning: `-Xmx512m -Xms128m`
Container memory: 4GB minimum recommended.
