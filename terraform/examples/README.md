# LocalCloud Terraform Integration Test

## Overview

End-to-end Terraform test that provisions ALL supported LocalCloud services.
Run it against a Docker container to verify full Terraform compatibility.

## Files

| File | Purpose |
|------|---------|
| `all-services.tf` | Main Terraform config — 17 services, 40+ resources |
| `terraform-test.sh` | Automated test runner (start → apply → verify → destroy) |
| `TERRAFORM_COMPATIBILITY.md` | Full compatibility report with gaps analysis |

## Quick Start

```bash
# 1. Build and start LocalCloud
cd ../..  # project root
docker compose up -d

# 2. Run the full test
./terraform-test.sh

# Or run manually:
eval $(curl -s 'http://localhost:8080/env?format=terraform')
terraform init
terraform apply -auto-approve
terraform output
terraform destroy -auto-approve
```

## Services Tested

| # | Service | Terraform Resources | Status |
|---|---------|-------------------|--------|
| 1 | Cloud Storage | `google_storage_bucket`, `google_storage_bucket_object` | ✅ |
| 2 | Pub/Sub | `google_pubsub_topic`, `google_pubsub_subscription` | ✅ |
| 3 | BigQuery | `google_bigquery_dataset`, `google_bigquery_table` | ✅ |
| 4 | Spanner | `google_spanner_instance`, `google_spanner_database` | ✅ |
| 5 | Secret Manager | `google_secret_manager_secret`, `google_secret_manager_secret_version` | ✅ |
| 6 | Cloud Tasks | `google_cloud_tasks_queue` | ✅ |
| 7 | Memorystore | `google_redis_instance` | ✅ |
| 8 | Cloud SQL | `google_sql_database_instance`, `google_sql_database`, `google_sql_user` | ✅ |
| 9 | AlloyDB | `google_alloydb_cluster`, `google_alloydb_instance` | ✅ |
| 10 | Bigtable | `google_bigtable_instance`, `google_bigtable_table` | ✅ |
| 11 | Cloud Functions | `google_cloudfunctions2_function` | ✅ |
| 12 | Cloud Scheduler | `google_cloud_scheduler_job` | ✅ |
| 13 | Dataproc | `google_dataproc_cluster` | ✅ |
| 14 | Workflows | `google_workflows_workflow` | ✅ |
| 15 | Cloud Run | `google_cloud_run_v2_service` | ✅ |
| 16 | GKE | `google_container_cluster` | ✅ |
| 17 | Compute Engine | `google_compute_instance` | ✅ |

## Known Gaps

### Cloud KMS (`google_kms_key_ring`, `google_kms_crypto_key`)
Armeria `@Post` annotation conflicts with `:verb` path patterns (`:encrypt`, `:decrypt`).
**Workaround:** Unit tested via `KmsRestServiceTest`. Direct REST calls work.
**Fix:** Manual regex route registration instead of annotated services.

### Vertex AI (`google_vertex_ai_*`)
Same Armeria `:verb` conflict (`:generateContent`, `:countTokens`).
**Workaround:** Unit tested via `VertexAiRestServiceTest`. SDK calls work.
**Fix:** Same as KMS.

### Cloud Logging / Monitoring
Terraform resources for sinks/alert policies not yet mapped.
**Workaround:** SDK-based write/read works via gRPC transcoding.
