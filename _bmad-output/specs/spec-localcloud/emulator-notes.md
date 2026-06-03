# Emulator-Specific Implementation Notes

> **Companion to SPEC.md.** Detailed implementation constraints and known behaviors for each emulated service.

## GCS (Cloud Storage)
- **Emulator:** fake-gcs-server (Go)
- **Protocol:** HTTP-only (`-scheme http`) on port 4443
- **Project isolation:** Handled via `gcs_bucket_projects` PostgreSQL table, filtered in `BrowseService`
- **Persistence:** Filesystem (blobs stored as files). NOT `gs://` URIs — DuckDB or other tools expecting real GCS will not work
- **Note:** Does NOT enforce project-level bucket isolation natively

## Pub/Sub
- **Emulator:** In-process Java facade
- **Protocol:** gRPC on port 8085
- **Persistence:** PostgreSQL
- **Features:** Topics, subscriptions, pull/push delivery
- **Scheduler integration:** Pub/Sub targets dispatch to local Pub/Sub emulator via gRPC

## Firestore
- **Emulator:** cloud-firestore-emulator (Google official, Java JAR)
- **Persistence:** In-memory (no persistence across restarts)

## Bigtable
- **Emulator:** little_bigtable (Go, custom)
- **Source:** `github.com/jhsenjaliya/little_bigtable@v0.0.1` — pulled and built during `docker build`
- **Persistence:** SQLite
- **Features:** Change streams, materialized views

## Spanner
- **Emulator:** spanner-emulator-wrapper (Go gateway) + emulator_main (C++)
- **Image:** `jaysen2apache/spanner-emulator-extended:latest`
- **Ports:** 9010 (gRPC), 9020 (REST via grpc-gateway)
- **Persistence:** LevelDB (forked from upstream in-memory emulator)
- **Supported types:** `INT64`, `STRING(MAX)`, `BOOL`, `FLOAT64`, `NUMERIC`, `JSON`, `TIMESTAMP`, `DATE`, `BYTES`, `ARRAY<T>` (all verified 2026-06-01)
- **Mutations:** REST mutation API supports all column types including `ARRAY<T>` (fixed in latest image). Use `columnTypes` for proper FLOAT64 handling.
- **Known issue:** LevelDB race condition on persistence — verify data survives restarts under concurrent writes

## BigQuery
- **Emulator:** bigquery-emulator v2 (Python, custom)
- **Engine:** DuckDB + SQLGlot for SQL translation
- **Ports:** 9050 (gRPC), 9060 (HTTP)
- **Coverage:** ~96% SQL coverage, 818 functional tests, 200+ mapped functions
- **Platform:** Native on both arm64 and amd64
- **Note:** Schema/browse endpoints only work for PostgreSQL-backed services via admin API; the BQ emulator itself uses DuckDB

## Memorystore
- **Emulator:** valkey-server (C, Redis fork)
- **Protocol:** Redis RESP2, port 6379
- **Persistence:** RDB snapshots
- **Gateway:** Netty codec-redis for RESP2 parsing in Java gateway
- **Storage:** Single `redis_data` table in PostgreSQL with JSONB values for 5 data types

## Cloud Scheduler
- **Implementation:** In-process Java facade
- **Cron parsing:** `cron-utils` library
- **Dispatch:** `ScheduledExecutorService` for job scheduling
- **Targets:** HTTP, Pub/Sub, App Engine
- **Persistence:** Jobs survive restarts — re-scheduled from DB on startup
- **Env var:** `CLOUD_SCHEDULER_EMULATOR_HOST`

## Cloud Functions (2nd Gen)
- **Implementation:** Metadata-only facade
- **CRUD:** Function configs stored in PostgreSQL
- **Execution:** Developers run functions locally using Functions Framework (not executed by emulator)
- **Trigger routing:** Pub/Sub topics auto-create subscriptions that forward to function's local URL
- **Build config:** Stored but not executed
- **Env var:** `CLOUD_FUNCTIONS_EMULATOR_HOST`

## AlloyDB
- **Implementation:** PostgreSQL-compatible at wire level
- **Cluster mapping:** Each cluster → dedicated PostgreSQL database (`alloydb_<cluster_id>`)
- **Connection info:** `GetConnectionInfo` returns `localhost:5432`
- **Extensions:** pgvector installed automatically
- **Env var:** `ALLOYDB_EMULATOR_HOST`

## Dataproc
- **Implementation:** Spark 3.5.x via `spark-submit` in local mode (`--master local[*]`)
- **CRUD:** Cluster and job metadata only (stored in PostgreSQL)
- **Job execution:** Forks `spark-submit` processes tracked via Java `Process`
- **Prerequisite:** Spark must be installed on host at `SPARK_HOME`
- **Env var:** `DATAPROC_EMULATOR_HOST`

## Cloud IAM
- **Implementation:** Permissive policy stub
- **Behavior:** `testIamPermissions` returns ALL requested permissions as allowed
- **Policies:** JSONB per resource in PostgreSQL (`iam_policies` table)
- **No validation:** No role validation, no condition support
- **Env var:** `IAM_EMULATOR_HOST`

## Cloud KMS
- **Implementation:** REST facade
- **Storage:** `kms_key_rings` and `kms_crypto_keys` tables

## Cloud SQL
- **Implementation:** REST facade
- **Storage:** `cloud_sql_instances` table (metadata only)

## Usage Metrics
- **Collection:** In-memory counters in Java gateway
- **Flush:** Every 30 seconds to PostgreSQL `usage_metrics` table
- **Semantics:** UPSERT by project+service
- **Note:** Secret Manager seeding uses direct PostgreSQL inserts (not gRPC), so `incrementRequestCount()` is NOT called from seed/browse paths. Admin API operations tracked directly via `UsageMetricsRepository`.

## Seed Data Notes
- YAML format with `services:` wrapper
- Supports both flat (`gcs: ...`) and nested (`services: { gcs: ... }`) formats
- Secret Manager seeding uses direct PostgreSQL inserts (not gRPC transcoding)
- `/reset` reads `restore_seed` from JSON body (not query params)
