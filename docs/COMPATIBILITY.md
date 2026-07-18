# LocalCloud Compatibility

This document contains generated compatibility summaries. Detailed runtime metadata is available from the LocalCloud gateway at `/compatibility`, `/coverage`, and `/compatibility/evidence`.

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
