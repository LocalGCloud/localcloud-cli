# LocalCloud Terraform Compatibility Report

**Generated:** 2026-05-27  
**Tested against:** LocalCloud with all facade services wired (gRPC + REST dual registration)

---

## Fully Supported (Terraform CRUD works)

These services pass full `terraform apply` → `terraform destroy` lifecycle with the Google provider.

| Service | Terraform Resources | Protocol | Notes |
|---------|-------------------|----------|-------|
| **Cloud Storage (GCS)** | `google_storage_bucket`, `google_storage_bucket_object` | REST (fake-gcs-server) | External emulator on port 4443 |
| **Pub/Sub** | `google_pubsub_topic`, `google_pubsub_subscription` | gRPC (external) | External emulator on port 8085 |
| **BigQuery** | `google_bigquery_dataset`, `google_bigquery_table` | REST (external) | DuckDB-backed; SQL dialect partial parity |
| **Spanner** | `google_spanner_instance`, `google_spanner_database` | gRPC (external) | External emulator on port 9010 |
| **Secret Manager** | `google_secret_manager_secret`, `google_secret_manager_secret_version` | gRPC + explicit REST | Facade on gateway; dual registration |
| **Cloud Tasks** | `google_cloud_tasks_queue` | gRPC + explicit REST | Facade on gateway; dual registration |
| **Cloud SQL** | `google_sql_database_instance`, `google_sql_database`, `google_sql_user` | REST (facade) | `/sql/v1` and `/sql/v1beta4` |
| **Bigtable** | `google_bigtable_instance`, `google_bigtable_table` | gRPC (external) | External emulator on port 8087 |
| **Cloud Run** | `google_cloud_run_v2_service` | gRPC (facade) | gRPC transcoding; metadata-only |
| **GKE** | `google_container_cluster` | gRPC (facade) | gRPC transcoding; k3d optional |
| **Compute Engine** | `google_compute_instance` | REST (facade) | `/compute/v1`; container-backed or simulated |
| **Memorystore (Redis)** | `google_redis_instance` | Redis protocol + REST admin | External on port 6379; admin REST on `/redis/v1` |

---

## Newly Wired (this PR)

These facade services are now wired into the gateway with gRPC + HTTP/JSON transcoding.

| Service | Terraform Resources | Protocol | Status |
|---------|-------------------|----------|--------|
| **Cloud Scheduler** | `google_cloud_scheduler_job` | gRPC (facade) | ✅ Wired with transcoding |
| **Cloud Functions 2nd gen** | `google_cloudfunctions2_function` | gRPC (facade) | ✅ Wired with transcoding |
| **AlloyDB** | `google_alloydb_cluster`, `google_alloydb_instance` | gRPC (facade) | ✅ Wired with transcoding |
| **Dataproc** | `google_dataproc_cluster` | gRPC (facade) | ✅ Wired with transcoding |
| **Cloud Workflows** | `google_workflows_workflow` | gRPC + explicit REST (facade) | ✅ Wired with dual registration |
| **Cloud Logging** | — | gRPC (facade) | ✅ Wired; SDK write/read only |
| **Cloud Monitoring** | — | gRPC (facade) | ✅ Wired; SDK write/read only |
| **Cloud IAM** | — | gRPC (facade) | ✅ Wired; permissive policy stub |

---

## Gaps (Not Terraform-compatible)

These services are emulated but **not yet compatible** with the Terraform Google provider.

### 1. Cloud KMS

- **Resources:** `google_kms_key_ring`, `google_kms_crypto_key`
- **Issue:** The `KmsRestService` uses Armeria `@Post("/.../cryptoKeys/{key}:decrypt")` style paths. Armeria's `ParameterizedPathMapping` rejects `:verb` patterns because `:` is the path-parameter regex delimiter.
- **Coverage:** Unit tested via `KmsRestServiceTest` (create, list, get, encrypt, decrypt, destroy, restore).
- **Fix needed:** Register KMS under a different path prefix that avoids `:verb` conflicts, or use manual route registration (regex-based) instead of annotated services.
- **Priority:** Medium — encrypt/decrypt roundtrip works via direct REST, just not via Terraform provider.

### 2. Vertex AI

- **Resources:** `google_vertex_ai_*` (models, endpoints, etc.)
- **Issue:** Same Armeria `:verb` path conflict — `/publishers/*/models/*:generateContent`, `/models/*:countTokens`, etc.
- **Coverage:** Unit tested via `VertexAiRestServiceTest`. The `generateContent` stub produces valid Gemini-compatible responses.
- **Fix needed:** Same as KMS — manual regex routes or alternate path prefix.
- **Priority:** Low — most users interact via SDK, not Terraform.

### 3. Cloud Logging

- **Resources:** `google_logging_project_sink`, `google_logging_metric`, `google_logging_project_exclusion`
- **Issue:** Log ingestion works (gRPC transcoding on `/v2/entries:write` and `/v2/entries:list`), but Terraform's sink/metric resources use different API paths not yet mapped.
- **Coverage:** SDK-level tested via `GrpcTranscodingIntegrationTest` and `TerraformCompatibilityIntegrationTest`.
- **Fix needed:** Implement `LoggingRestService` with explicit REST handlers for sink/metric CRUD.
- **Priority:** Low — SDK-based log writes work.

### 4. Cloud Monitoring

- **Resources:** `google_monitoring_alert_policy`, `google_monitoring_notification_channel`, `google_monitoring_uptime_check_config`
- **Issue:** Metric write/list works (gRPC transcoding on `/v3/projects/*/timeSeries`, `/v3/projects/*/metricDescriptors`), but alert policy/notification channel resources use different API paths.
- **Coverage:** SDK-level tested via `GrpcTranscodingIntegrationTest` and `TerraformCompatibilityIntegrationTest`.
- **Fix needed:** Implement `MonitoringRestService` with explicit REST handlers for alert policy CRUD.
- **Priority:** Low — SDK-based metric writes work.

---

## Known Limitations

| Service | Limitation | Impact |
|---------|-----------|--------|
| **BigQuery** | DuckDB-backed SQL; dialect parity partial | Complex queries may differ from real BigQuery |
| **Spanner** | LevelDB persistence race condition on restart | Data may not survive container restarts |
| **Compute Engine** | Docker-based; falls back to simulated mode | Full container lifecycle requires Docker socket |
| **GKE** | k3d integration optional; metadata-only by default | Real Kubernetes API not emulated |
| **Cloud Run** | Metadata-only; no actual container invocation | Trigger routing is Pub/Sub subscription auto-create only |
| **Dataproc** | Requires Spark installed at `SPARK_HOME` | Job submission only works with local Spark |
| **All facades** | Permissive IAM (no role validation) | `testIamPermissions` returns ALL permissions |
| **All facades** | No GCP billing/quotas | No resource limits or cost simulation |

---

## How to Run the Test

```bash
# Full end-to-end test (apply → verify → destroy → stop)
./terraform/examples/terraform-test.sh

# Skip destroy (inspect resources after test)
./terraform/examples/terraform-test.sh --no-destroy

# Just stop the container
./terraform/examples/terraform-test.sh --stop-only
```

Or manually:
```bash
docker compose up -d
eval $(curl -s 'http://localhost:8080/env?format=terraform')
cd terraform/examples
terraform init
terraform apply -auto-approve
terraform output
terraform destroy -auto-approve
```
