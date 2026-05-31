# LocalCloud Terraform Integration Test

## Overview

End-to-end Terraform test that provisions LocalCloud services.
Run it against a Docker container to verify Terraform compatibility.

**Current status:** ~19/35 resources pass E2E apply against provider `~> 6.0`.
See `GAP_ANALYSIS.md` for detailed tracking.

## Prerequisites

Before running Terraform, you MUST configure DNS and port forwarding:

```bash
# 1. Add DNS redirect (required by Google provider pre-flight check)
echo "127.0.0.1 serviceusage.googleapis.com" | sudo tee -a /etc/hosts

# 2. Start LocalCloud with port 443 mapped (required for Service Usage API)
docker run -d --name localcloud \
  -p 443:8080 -p 8080:8080 -p 4443:4443 -p 8085:8085 \
  -p 8086:8086 -p 8087:8087 -p 9010:9010 -p 9020:9020 \
  -p 9050:9050 -p 6379:6379 -m 4g \
  localcloud/localcloud:latest

# 3. Verify readiness
curl http://localhost:8080/terraform/readiness
```

## Files

| File | Purpose |
|------|---------|
| `all-services.tf` | Main Terraform config — services, resources |
| `terraform-test.sh` | Automated test runner (start → apply → verify → destroy) |
| `TERRAFORM_COMPATIBILITY.md` | Full compatibility report with gaps analysis |
| `GAP_ANALYSIS.md` | Detailed tracking of each resource's status |

## Quick Start

```bash
# 1. Build and start LocalCloud
cd ../..  # project root
docker compose up -d

# 2. Verify Terraform readiness
curl http://localhost:8080/terraform/readiness

# 3. Run the full test
./terraform-test.sh

# Or run manually:
eval $(curl -s 'http://localhost:8080/env?format=terraform')
cd terraform/examples
terraform init
terraform apply -auto-approve
terraform output
terraform destroy -auto-approve
```

## Services Tested

### Passing (E2E verified)

| # | Service | Terraform Resources | Status |
|---|---------|-------------------|--------|
| 1 | Cloud Storage | `google_storage_bucket`, `google_storage_bucket_object` | ✅ |
| 2 | Pub/Sub | `google_pubsub_topic`, `google_pubsub_subscription` | ✅ |
| 3 | Secret Manager | `google_secret_manager_secret` | ✅ |
| 4 | Cloud Tasks | `google_cloud_tasks_queue` | ✅ |
| 5 | Memorystore | `google_redis_instance` | ✅ |
| 6 | AlloyDB | `google_alloydb_cluster` | ✅ |
| 7 | Cloud Scheduler | `google_cloud_scheduler_job` | ✅ |
| 8 | Dataproc | `google_dataproc_cluster` | ✅ |
| 9 | Workflows | `google_workflows_workflow` | ✅ |
| 10 | Cloud SQL | `google_sql_database_instance` | ✅ (slow, default-off) |

### Partially passing / Inconsistent

| # | Service | Terraform Resources | Status |
|---|---------|-------------------|--------|
| 11 | BigQuery | `google_bigquery_dataset`, `google_bigquery_table` | ⚠️ Works in full suite, may fail isolated |
| 12 | Spanner | `google_spanner_instance`, `google_spanner_database` | ⚠️ 404 in isolated tests |
| 13 | Cloud Resource Manager | `google_project` | ⚠️ "Root object present but now absent" |

### Child resources (not E2E verified)

| # | Service | Terraform Resources | Status |
|---|---------|-------------------|--------|
| 14 | Secret Manager | `google_secret_manager_secret_version` | Not tested |
| 15 | Cloud SQL | `google_sql_database`, `google_sql_user` | Not tested |
| 16 | AlloyDB | `google_alloydb_instance` | Not tested |
| 17 | Bigtable | `google_bigtable_table` | Not tested |
| 18 | Cloud Functions | `google_cloudfunctions2_function` | Not tested |

### Disabled (DNS/TLS prerequisite not met)

| # | Service | Terraform Resources | Status |
|---|---------|-------------------|--------|
| 19 | Cloud Run | `google_cloud_run_v2_service` | Disabled — needs `*.googleapis.com` DNS + TLS |
| 20 | GKE | `google_container_cluster` | Disabled — needs `*.googleapis.com` DNS + TLS |
| 21 | Compute Engine | `google_compute_instance` | Disabled — needs `*.googleapis.com` DNS + TLS |

### Blocked (routing / upstream issues)

| # | Service | Terraform Resources | Status |
|---|---------|-------------------|--------|
| 22 | Cloud KMS | `google_kms_key_ring`, `google_kms_crypto_key` | Blocked — Armeria `:verb` path conflict |
| 23 | Vertex AI | `google_vertex_ai_*` | Blocked — Armeria `:verb` path conflict |
| 24 | Logging | `google_logging_project_sink` | Blocked — no REST handler |
| 25 | Monitoring | `google_monitoring_alert_policy` | Blocked — no REST handler |

## Known Issues

### `google_project` — "Root object was present, but now absent"
Create and read both return HTTP 200, but Terraform reports inconsistent result.
**Status:** Under investigation — likely v1 vs v3 CRM path mismatch on read.
See `GAP_ANALYSIS.md` Issue 1.

### Cloud KMS / Vertex AI — Armeria `:verb` path conflict
`KmsRestService` uses `@Post("...:decrypt")` paths. Armeria rejects `:verb` in parameterized paths.
**Workaround:** Unit tested via `KmsRestServiceTest`. Direct REST calls work.
**Fix:** Manual regex route registration instead of annotated services.

### Cloud Logging / Monitoring — no Terraform resource mapping
Ingest works via gRPC transcoding. Terraform resources use different API paths not yet mapped.
**Workaround:** SDK-based writes work.

## CI/CD Integration

See `ci-github-actions.yml` for the GitHub Actions workflow.
Ensure DNS redirect is configured in CI before running `terraform-test.sh`.
