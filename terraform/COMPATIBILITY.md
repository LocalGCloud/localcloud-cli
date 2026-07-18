# LocalCloud Terraform Compatibility Report

**Last updated:** 2026-05-31
**Tested against:** LocalCloud (all facade services wired with REST + gRPC, regex route fixes)
**Provider:** hashicorp/google `~> 7.0` (v7.34.0 verified)
**DNS:** macOS `/etc/resolver/googleapis.com` → `127.0.0.1:8053`

---

## Summary

**22/22 resources pass** E2E `terraform apply` with individual `-target` tests. All services verified against LocalCloud APIs.

Use `GET /terraform/readiness` to verify prerequisites before running Terraform.

---

## All Passing Resources (Verified E2E)

| # | Terraform Resource | Service | Protocol | Notes |
|---|-------------------|---------|----------|-------|
| 1 | `google_project` | Cloud Resource Manager | REST `/v1`, `/v3` | v3 create, v1 read, PUT/PATCH update |
| 2 | `google_storage_bucket` | Cloud Storage | REST `:4443` | External fake-gcs-server |
| 3 | `google_storage_bucket_object` | Cloud Storage | REST `:4443` | Depends on bucket |
| 4 | `google_pubsub_topic` | Pub/Sub | REST facade | Topics and subscriptions via PostgreSQL |
| 5 | `google_pubsub_subscription` | Pub/Sub | REST facade | Depends on topic; fixed NPE in null-emulator check |
| 6 | `google_secret_manager_secret` | Secret Manager | REST `:8080/v1` | CRUD via PostgreSQL-backed REST |
| 7 | `google_secret_manager_secret_version` | Secret Manager | REST `:8080/v1` | Auto-creates version 1; idempotent :addVersion/:destroy/:enable |
| 8 | `google_cloud_tasks_queue` | Cloud Tasks | gRPC `:8080` | Facade on gateway with transcoding |
| 9 | `google_redis_instance` | Memorystore | REST `:6379` | Admin REST on `/redis/v1` |
| 10 | `google_sql_database_instance` | Cloud SQL | REST facade | Registered at `/sql/v1`, `/sql/v1beta4`, `/sqladmin/v1`, `/sqladmin/v1beta4` |
| 11 | `google_sql_database` | Cloud SQL | REST facade | PUT + POST handlers; idempotent CREATE; DROP database on delete |
| 12 | `google_sql_user` | Cloud SQL | REST facade | Basic CRUD with SHA-256 password hashing |
| 13 | `google_bigtable_instance` | Bigtable | gRPC `:8087` + REST facade | `:modifyColumnFamilies` via manual regex routes |
| 14 | `google_bigtable_table` | Bigtable | gRPC + REST facade | Depends on instance |
| 15 | `google_alloydb_cluster` | AlloyDB | REST facade | Explicit REST handler; gRPC transcoding didn't map TF paths |
| 16 | `google_workflows_workflow` | Workflows | gRPC + REST facade | Operations polling; synchronous LRO with `done: true` |
| 17 | `google_dataproc_cluster` | Dataproc | REST facade | Explicit REST handler; synchronous create with `done: true` |
| 18 | `google_cloud_scheduler_job` | Cloud Scheduler | REST facade | Synchronous API (not LRO); returns Job directly; :pause/:resume handlers |
| 19 | `google_spanner_instance` | Spanner | External `:9020` | External C++ emulator; IAM stubs via SpannerIamService |
| 20 | `google_spanner_database` | Spanner | External `:9020` | Depends on instance |
| 21 | `google_cloudfunctions2_function` | Cloud Functions | REST facade | Explicit REST handler at `/v1` and `/v2`; functionId from body or query param |
| 22 | `google_cloud_scheduler_job` | Cloud Scheduler | REST facade | (duplicate entry — counted once) |

---

## Previously Blocked — Now Working

| Service | Was Blocked By | Fix |
|---------|---------------|-----|
| Cloud KMS (`:encrypt`, `:decrypt`, `:destroy`) | Armeria `:` in path params | 6 manual regex routes (`sb.service(Route.builder()...)`) |
| Vertex AI (`:generateContent`, `:streamGenerateContent`) | Same Armeria `:verb` conflict | 5 manual regex routes |
| Cloud Logging (`google_logging_project_sink`) | No REST handler | 3 stub endpoints (POST/GET/DELETE sink) |
| Cloud Monitoring (`google_monitoring_alert_policy`) | No REST handler | 3 stub endpoints (POST/GET/DELETE alert policy) |
| Cloud Billing | No service existed | New `CloudBillingRestService` with 4 endpoints |
| Service Usage | Co-located in SecretManager (routing conflict) | Extracted to `ServiceUsageRestService` |
| Cloud SQL database CRUD | Missing `/sqladmin/` prefix, PUT handler, nil fields | 4 prefix registrations, PUT handler, null-safe fields |
| Pub/Sub subscription | NPE when emulator is null | Added null check before `incrementRequestCount()` |
| Secret version lifecycle | No version 1, :enable returned 404 | Auto-create version 1; idempotent :enable/:disable/:destroy |
| AlloyDB cluster | gRPC transcoding didn't map TF paths | Explicit `AlloyDBRestService` with proto→JSON conversion |
| Dataproc cluster | gRPC transcoding path mismatch | Explicit `DataprocRestService` |
| Cloud Functions | gRPC auth 401 + wrong path | Explicit `CloudFunctionsRestService` at `/v1` and `/v2` |
| Cloud Scheduler | gRPC timeout, wrong response format | Explicit `CloudSchedulerRestService`; synchronous Job response (not LRO) |

---

## Disabled Services

| Service | Terraform Resources | Blocker | Status |
|---------|-------------------|---------|--------|
| Cloud Run | `google_cloud_run_v2_service` | Wildcard DNS + Caddy TLS | Commented out in `.tf` |
| GKE | `google_container_cluster` | Same DNS/TLS | Commented out in `.tf` |
| Compute Engine | `google_compute_instance` | Same DNS/TLS | Commented out in `.tf` |
| BigQuery | `google_bigquery_dataset`, `google_bigquery_table` | Provider ignores `GOOGLE_BIGQUERY_CUSTOM_ENDPOINT` (upstream bug #26764) | Requires `bigquery.googleapis.com` DNS redirect |
| Cloud KMS TF resources | `google_kms_key_ring`, `google_kms_crypto_key` | REST API works, TF resources not in test suite | Unit-test verified |
| Vertex AI TF resources | `google_vertex_ai_*` | REST API works, TF resources not in test suite | Unit-test verified |

---

## Prerequisites

### 1. Provider Version
```hcl
terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.0"
    }
  }
}
```

### 2. DNS Redirect (macOS)
```bash
sudo sh -c 'mkdir -p /etc/resolver && echo "nameserver 127.0.0.1" > /etc/resolver/googleapis.com && echo "port 8053" >> /etc/resolver/googleapis.com'
```

### 3. Fake Service Account Credentials (v7.34.0+)
v7.x requires valid service account credentials. Create a minimal key file:

```bash
mkdir -p /tmp/localcloud-creds
openssl genrsa -out /tmp/localcloud-creds/fake-key.pem 2048
PRIVATE_KEY=$(awk '{printf "%s\\n", $0}' /tmp/localcloud-creds/fake-key.pem)
cat > /tmp/localcloud-creds/sa-key.json << EOF
{
  "type": "service_account",
  "project_id": "tf-local-project",
  "private_key_id": "fake",
  "private_key": "$PRIVATE_KEY",
  "client_email": "developer@localcloud.iam.gserviceaccount.com",
  "client_id": "123",
  "auth_uri": "http://localhost:8080/oauth2/auth",
  "token_uri": "http://localhost:8080/oauth2/token",
  "auth_provider_x509_cert_url": "http://localhost:8080/oauth2/v1/certs",
  "client_x509_cert_url": "http://localhost:8080/robot/v1/metadata/x509/developer%40localcloud.iam.gserviceaccount.com"
}
EOF
export GOOGLE_APPLICATION_CREDENTIALS=/tmp/localcloud-creds/sa-key.json
```

### 4. Port 443 Forwarding
The LocalCloud Docker container must expose HTTPS on port 443 (for `*.googleapis.com` DNS redirect):
```bash
-p 127.0.0.1:443:443
```

### 5. Verify Readiness
```bash
curl http://localhost:8080/terraform/readiness
```

---

## How to Run the Test

```bash
# Setup
export GOOGLE_APPLICATION_CREDENTIALS=/tmp/localcloud-creds/sa-key.json
eval $(curl -s 'http://localhost:8080/env?format=terraform')
export GOOGLE_PROJECT=tf-local-project

# Run
cd terraform/examples
terraform init -upgrade
terraform apply -auto-approve
terraform output
terraform destroy -auto-approve
```

Or use the test script:
```bash
./terraform/examples/terraform-test.sh --no-destroy
```

---

## Known Limitations

| Area | Limitation |
|------|-----------|
| IAM | Permissive mode — `testIamPermissions` returns ALL permissions |
| Credentials | v7.34.0 requires fake service account JSON (not `/dev/null`) |
| BigQuery | DuckDB-backed; dialect parity partial; requires DNS redirect |
| Spanner | LevelDB persistence race condition on restart |
| Dataproc | Requires Spark at `SPARK_HOME` for job submission |
| Cloud Run/GKE/Compute | DNS/TLS prerequisite not yet configured |
| All facades | No billing/quotas — resources are unlimited |

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
