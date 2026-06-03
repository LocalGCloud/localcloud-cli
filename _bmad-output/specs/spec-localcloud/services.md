# LocalCloud Services

> **Companion to SPEC.md.** Complete catalog of all emulated GCP services with their implementation type, persistence, and key details.

## Service Categories

### External Emulators (separate processes, own ports)

These run as independent processes managed by supervisord inside the Docker container. Each has its own port, binary, and persistence mechanism.

| Service | Binary | Language | Port | Persistence | Notes |
|---------|--------|----------|------|-------------|-------|
| Cloud Storage (GCS) | fake-gcs-server | Go | 4443 | Filesystem | HTTP-only (`-scheme http`). Project isolation via `gcs_bucket_projects` PostgreSQL table. |
| Pub/Sub | In-process Java facade | Java | 8085 | PostgreSQL | gRPC API. Supports topics, subscriptions, pull/push delivery. |
| Firestore | cloud-firestore-emulator | Java (JAR) | 8086 | In-memory | Google's official emulator. |
| Bigtable | little_bigtable | Go | 8087 | SQLite | Custom emulator. Change streams, materialized views, persistence. Pulled from `github.com/jhsenjaliya/little_bigtable@v0.0.1` |
| Spanner | spanner-emulator-wrapper | Go | 9010/9020 | LevelDB | Known LevelDB race condition on persistence. |
| BigQuery | bigquery-emulator v2 | Python | 9050/9060 | DuckDB | Custom DuckDB engine. ~96% SQL coverage, 818 tests, 200+ mapped functions. Native on arm64 and amd64. |
| Memorystore | valkey-server | C | 6379 | RDB snapshots | valkey-server (Redis fork). |

### Facade Services (in-process on gateway port 8080)

These are implemented as in-process gRPC/REST facades inside the Armeria gateway. They store metadata in PostgreSQL and return plausible but simplified responses.

| Service | API Type | PostgreSQL Table(s) | Notes |
|---------|----------|---------------------|-------|
| Secret Manager | gRPC | `secret_versions` | Seed uses direct PostgreSQL inserts. |
| Cloud Tasks | gRPC | `task_queues` | Queue CRUD + task enqueue. |
| Cloud Logging | gRPC | `log_entries` | Log storage + retrieval. |
| Cloud Monitoring | gRPC | `time_series` | Metric storage. |
| GKE | REST | `gke_clusters` | Cluster metadata only. |
| Compute Engine | REST | `compute_instances` | Instance metadata only. |
| Cloud Run | REST | `cloud_run_services` | Service metadata only. |
| Cloud Workflows | REST | `workflow_executions` | Workflow execution engine. Synthesized steps. No visual designer. |
| Cloud Scheduler | REST | `scheduler_jobs` | Cron-based job dispatch via `cron-utils` + `ScheduledExecutorService`. Supports HTTP, Pub/Sub, App Engine targets. |
| Cloud Functions (2nd gen) | REST | `cloud_functions` | Metadata-only CRUD. Trigger routing: Pub/Sub topics auto-create subscriptions. |
| AlloyDB | gRPC | `alloydb_clusters`, `alloydb_instances` | PostgreSQL-compatible. Each cluster → dedicated DB (`alloydb_<cluster_id>`). pgvector installed. |
| Dataproc | REST | `dataproc_clusters`, `dataproc_jobs` | Metadata CRUD. Jobs submit via `spark-submit` (local mode). Requires Spark on host. |
| Cloud IAM | gRPC | `iam_policies` | Permissive stub. JSONB policies per resource. No role validation. |
| Cloud KMS | REST | `kms_key_rings`, `kms_crypto_keys` | Key ring and crypto key metadata. |
| Cloud SQL | REST | `cloud_sql_instances` | Instance metadata only. |

### License Server

| Service | Port | Persistence | Notes |
|---------|------|-------------|-------|
| License Server | 9090 | PostgreSQL (own schema) | Standalone auth/authz microservice. User accounts, API keys (online + offline), trial management, device tracking. Returns RS256 JWT tokens. |

## Service Discovery

`services.yaml` is the single source of truth for all service definitions. Fields:

| Field | Description |
|-------|-------------|
| `port` | Integer or `"gateway"` (resolved to gateway.port) |
| `protocol` | `"rest"` or `"grpc"` |
| `type` | `"external"` (supervisord) or `"facade"` (in-process) |
| `envValuePrefix` | `""` for grpc, `"http://"` for rest |
| `additionalPorts` | Optional map of extra ports |
| `healthCheck` | How supervisord verifies the external service |
| `terraformEnvVar` | `GOOGLE_*_CUSTOM_ENDPOINT` name for Terraform |

Read by: Java (Jackson YAML) and Python (PyYAML).

## Admin API

All services expose browse endpoints via the admin API at:

- `GET /browse/{service}` — paginated listing
- `GET /browse/{service}/{id}` — single resource detail

The console at `http://localhost:8080` provides a browser-based interface.

## Service Status

`GET /services` returns array format: `[{id, name, status, port, protocol, endpoint, env_var, env_value, request_count}]`
