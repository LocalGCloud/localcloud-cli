# LocalCloud Service Status Matrix

> **Last updated:** 2026-05-26
> **Source of truth:** `services.yaml`

Canonical reference for every emulated GCP service — type, port, tier, protocol, API coverage, and limitation summary.

---

## Legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Supported — full CRUD and primary workflow |
| 🟡 | Partial — core operations work, advanced features missing |
| 🔴 | Not supported |
| — | Not applicable |

---

## Service Matrix

### Data & Storage

| Service | Type | Port(s) | Tier | Protocol | Coverage | Key Limitations |
|---------|------|---------|------|----------|----------|-----------------|
| **Cloud Storage** | external | 4443 | Community | HTTP | ✅ | Versioning, lifecycle execution, CMEK |
| **BigQuery** | external | 9050 (REST), 9060 (gRPC) | Community | REST + gRPC | 🟡 ~96% SQL | Scripting, BQML, GEOGRAPHY, partitioning execution |
| **Spanner** | external | 9010 (gRPC), 9020 (REST) | Pro | gRPC + REST | 🟡 | Partitioned DML, change streams; LevelDB race condition on restart |
| **Bigtable** | external | 8087 | Pro | gRPC | ✅ SDK data plane | Console/sync split from SDK state; instance/cluster admin not emulated |
| **Firestore** | external | 8086 | Community | gRPC | 🟡 | Composite indexes, aggregation queries, seed/browser parity gaps |
| **Memorystore (Redis/Valkey)** | external | 6379 | Community | RESP2 | 🟡 | Pub/Sub, Lua, streams, MULTI/EXEC not supported |
| **AlloyDB** | facade | 8080 | Community | gRPC | 🟡 Metadata + PostgreSQL wire | Backup/restore, PSC, cross-region replication |
| **Cloud SQL** | facade | 8080 | Pro | REST | 🟡 PostgreSQL data plane only | MySQL data plane, read replicas, backup/restore |

### Messaging & Events

| Service | Type | Port(s) | Tier | Protocol | Coverage | Key Limitations |
|---------|------|---------|------|----------|----------|-----------------|
| **Pub/Sub** | external | 8085 | Community | gRPC | 🟡 SDK endpoint verified; gateway partial | BQ/GCS subscriptions, exactly-once delivery, and gateway advanced routes |
| **Cloud Tasks** | facade | 8080 | Community | gRPC | ✅ | App Engine tasks, OAuth token generation |
| **Cloud Scheduler** | facade | 8080 | Community | gRPC | ✅ | Timezone rules beyond cron-utils support |

### Serverless & Compute

| Service | Type | Port(s) | Tier | Protocol | Coverage | Key Limitations |
|---------|------|---------|------|----------|----------|-----------------|
| **Cloud Workflows** | facade | 8080 | Community | gRPC | ✅ | No execution checkpointing (lost on restart); no KMS or IAM enforcement |
| **Cloud Functions (2nd gen)** | facade | 8080 | Community | gRPC | 🟡 Metadata CRUD only | Build/container execution not emulated (use Functions Framework) |
| **Cloud Run** | facade | 8080 | Pro | gRPC | 🟡 Service CRUD, revisions | Traffic splitting, custom domains, Jobs |
| **Compute Engine** | facade | 8080 | Pro | REST | 🟡 Instance CRUD via Docker | Disks, snapshots, templates, networking |
| **GKE** | facade | 8080 | Pro | gRPC | 🟡 Cluster CRUD via k3d | Node pools, auto-scaling, upgrades |
| **Dataproc** | facade | 8080 | Community | gRPC | 🟡 Metadata + local spark-submit | Autoscaling, YARN/K8s cluster mode; requires Spark on host |

### Security & Identity

| Service | Type | Port(s) | Tier | Protocol | Coverage | Key Limitations |
|---------|------|---------|------|----------|----------|-----------------|
| **Secret Manager** | facade | 8080 | Community | gRPC | ✅ | Rotation, CMEK, per-secret IAM |
| **Cloud IAM** | facade | 8080 | Community | gRPC | 🟡 Permissive (allow all) | Role validation, conditions, deny policies |
| **Cloud KMS** | facade | 8080 | Pro | REST | 🟡 Key CRUD + local encrypt/decrypt | HSM, EKM, import jobs |

### Observability

| Service | Type | Port(s) | Tier | Protocol | Coverage | Key Limitations |
|---------|------|---------|------|----------|----------|-----------------|
| **Cloud Logging** | facade | 8080 | Community | gRPC | 🟡 Write/List/Delete | Metrics, sinks, exclusions, audit logs |
| **Cloud Monitoring** | facade | 8080 | Community | gRPC | 🟡 Time series, descriptors | Alerting, uptime checks, dashboards |

### AI/ML

| Service | Type | Port(s) | Tier | Protocol | Coverage | Key Limitations |
|---------|------|---------|------|----------|----------|-----------------|
| **Vertex AI** | facade | 8080 | Pro | REST | 🔴 Metadata stubs | Model serving, prediction, training |

---

## Quick Reference by Count

| Dimension | Count |
|-----------|-------|
| Total services in registry | 23 |
| Enabled by default | 17 |
| Disabled by default | 6 (GKE, Compute, Cloud Run, Vertex AI, KMS, Cloud SQL) |
| Community tier | 15 |
| Pro tier | 8 |
| Facade (in-process on gateway) | 17 |
| External (separate process) | 6 (GCS, Pub/Sub, Firestore, Bigtable, Spanner, BigQuery) + Memorystore (Valkey) |
| gRPC protocol | 18 |
| REST protocol | 4 (GCS, BigQuery, Compute, Vertex AI, KMS, Cloud SQL) |
| RESP2 protocol | 1 (Memorystore) |

---

## Tier Mapping

### Community Tier (Free — 15 services)

GCS, Pub/Sub, Firestore, BigQuery, Secret Manager, Cloud Tasks, Cloud Scheduler, Cloud Functions, AlloyDB, Dataproc, Cloud IAM, Logging, Monitoring, Memorystore, Cloud Workflows

### Pro Tier (8 services)

Spanner, Bigtable, GKE, Compute Engine, Cloud Run, Vertex AI, Cloud KMS, Cloud SQL

> **Note:** Spanner and Bigtable are Pro-tier but enabled by default. They can be disabled via `LOCALCLOUD_ENABLE_SPANNER=false` and `LOCALCLOUD_ENABLE_BIGTABLE=false`.

---

## Service Architecture Types

| Type | Description | Examples |
|------|-------------|----------|
| **external** | Separate process managed by supervisord. Communicates via its own port. | GCS (fake-gcs-server), Pub/Sub (in-process facade, port 8085), Firestore (JAR), Spanner (Go binary), BigQuery (Python+DuckDB), Bigtable (Go binary), Memorystore (Valkey) |
| **facade** | In-process Java implementation on Armeria gateway. Shares port 8080. PostgreSQL-backed. | All 17 remaining services |

---

## See Also

- [services.yaml](../services.yaml) — Machine-readable service registry (single source of truth)
- [DEVELOPER_GUIDE.md](../DEVELOPER_GUIDE.md) — Detailed usage and connection docs
- [terraform/COMPATIBILITY.md](../terraform/COMPATIBILITY.md) — Terraform resource support matrix
- Individual service coverage docs: [bigquery-coverage-gaps.md](bigquery-coverage-gaps.md), [pubsub-comparison.md](pubsub-comparison.md), [bigtable-feature-coverage.md](bigtable-feature-coverage.md), [spanner-emulator-feature-gaps.md](spanner-emulator-feature-gaps.md)

<!-- compatibility:generated:start -->
> Generated from `localcloud-server/src/main/resources/compatibility/services/*.yaml`.

| Service | Coverage | Terraform Resources | Key Limitations |
|---|---|---|---|
| `alloydb` | partial | `google_alloydb_cluster`, `google_alloydb_instance` | [prod_only] PSC (Private Service Connect) and cross-region replication.<br>Backup/restore is not complete. |
| `bigquery` | partial | `google_bigquery_dataset`, `google_bigquery_table` | DuckDB-backed via SQLGlot transpiler (~96% coverage).<br>⚠ Type coercion differences: DuckDB is permissive, BigQuery strict.<br>GROUP BY ROLLUP/CUBE, SEMI/ANTI JOIN, BQML, AEAD, KLL not supported.<br>GEOGRAPHY uses haversine approximation. Full scripting, materialized views, external tables (Parquet/CSV/JSON), Storage Read/Write gRPC API supported. |
| `bigtable` | supported | `google_bigtable_instance`, `google_bigtable_table` | [prod_only] Clusters, multi-region, replication require Google infrastructure.<br>Snapshots and backups (10 RPCs), IAM, UndeleteTable return Unimplemented.<br>GoogleSQL queries, change streams, app profiles, logical views not implemented.<br>Materialized views fully supported with write-time sync. SQLite persistence. |
| `cloudbilling` | partial | - | Real billing, budget enforcement, and cost export are not implemented. |
| `cloudfunctions` | partial | `google_cloudfunctions2_function` | Build and container execution are metadata-only; use Functions Framework locally. |
| `cloudiam` | partial | - | Role validation, conditions, and deny policies are not complete. |
| `cloudresourcemanager` | supported | `google_project` | [prod_only] Organization/folder hierarchy not modeled in LocalCloud. |
| `cloudrun` | partial | `google_cloud_run_v2_service` | Container execution and routing require host runtime architecture.<br>[prod_only] Custom domains and production routing (Google Front End load balancers, managed TLS). |
| `cloudscheduler` | partial | `google_cloud_scheduler_job` | Timezone rules beyond cron-utils support are not fully verified. |
| `cloudsql` | partial | `google_sql_database_instance`, `google_sql_database`, `google_sql_user` | [prod_only] Read replicas (cross-region replication) and PSC (Private Service Connect).<br>MySQL data plane and backup/restore are not complete. |
| `cloudtasks` | partial | `google_cloud_tasks_queue` | App Engine tasks and OAuth token generation are not complete. |
| `compute` | partial | `google_compute_instance` | [prod_only] Persistent disks and live migration (hypervisor-level).<br>Snapshots, instance templates, and VPC networking are not yet emulated. |
| `dataproc` | partial | `google_dataproc_cluster` | Autoscaling and YARN/Kubernetes cluster mode are not complete. |
| `firestore` | partial | - | Seed and browser parity is not fully hardened.<br>Index/query behavior is unverified. |
| `gcs` | supported | `google_storage_bucket`, `google_storage_bucket_object` | [prod_only] IAM, lifecycle policies, and notifications not emulated in LocalCloud. |
| `gke` | partial | `google_container_cluster` | Kubernetes runtime parity depends on host runtime/k3d integration.<br>[prod_only] Node pools, autoscaling, and upgrades (GCP-managed cluster autoscaler, regional instance groups). |
| `kms` | partial | `google_kms_key_ring`, `google_kms_crypto_key` | [prod_only] HSM (physical FIPS 140-2 hardware) and EKM (external key manager providers).<br>Import jobs and Cloud HSM level enforcement are not implemented. |
| `logging` | partial | `google_logging_project_sink` | Metrics, exclusions, audit logs, and production sink behavior are limited. |
| `memorystore` | partial | `google_redis_instance` | Pub/Sub, Lua, streams, and MULTI/EXEC are not supported. |
| `monitoring` | partial | `google_monitoring_alert_policy` | Alerting, uptime checks, and dashboards are partial. |
| `pubsub` | partial | `google_pubsub_topic`, `google_pubsub_subscription` | External emulator supports schemas, snapshots, seek, and dead-letter policy; gateway/Terraform REST facade exposes core topic/subscription routes only.<br>gcloud and console paths remain partial/unverified for advanced Pub/Sub workflows. |
| `secretmanager` | partial | `google_secret_manager_secret`, `google_secret_manager_secret_version` | [prod_only] Rotation and CMEK (customer-managed encryption keys).<br>Per-secret IAM is not complete. |
| `serviceusage` | partial | - | Quotas and service entitlement behavior are stubs. |
| `spanner` | partial | `google_spanner_instance`, `google_spanner_database` | Google's official emulator (C++, ZetaSQL). Full DDL, full SQL/DML, Partitioned DML, transactions, secondary indexes, foreign keys, generated columns, JSON, NUMERIC all supported.<br>Change streams not supported. Fork adds LevelDB persistence. |
| `vertexai` | partial | `google_vertex_ai_*` | [prod_only] Model training and tuning (requires TPU/GPU clusters).<br>Prediction endpoints and model management are out of current scope. |
| `workflows` | partial | `google_workflows_workflow` | In-flight execution checkpointing is not durable across restart. |

<!-- compatibility:generated:end -->
