# Terraform Compatibility Guide

LocalCloud supports Terraform's Google provider via `GOOGLE_*_CUSTOM_ENDPOINT` environment variables. No changes to `.tf` files are needed.

## Prerequisites

- **Provider version**: `~> 6.0` (v7.x hangs on authentication with `/dev/null` credentials).

### DNS Configuration (REQUIRED)

Google Cloud client libraries perform a **pre-flight project validation** by calling `https://serviceusage.googleapis.com` before any API call. This goes to real Google servers and fails for LocalCloud projects.

You MUST redirect `serviceusage.googleapis.com` to localhost. On macOS/Linux:

```bash
# One-time setup:
echo "127.0.0.1 serviceusage.googleapis.com" | sudo tee -a /etc/hosts
```

**Why this is needed**: The Go client library's internal transport layer resolves `serviceusage.googleapis.com` via the system DNS resolver. No environment variable can intercept this — it happens below the Terraform provider level. Without this redirect, all resources except Pub/Sub and Bigtable will fail with `SERVICE_DISABLED` or `billing account disabled` errors.

### Port 443 Forwarding

The Service Usage API runs on HTTPS port 443. The LocalCloud Docker container must map this to the gateway:

```bash
docker run -d --name localcloud \
  -p 443:8080 \              # ← Required for Service Usage validation
  -p 8080:8080 \
  ...
```

## Quick Start

```bash
# Start LocalCloud with required port mappings
docker run -d --name localcloud \
  -p 443:8080 -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 8087:8087 -p 9010:9010 -p 9020:9020 -p 9050:9050 \
  -p 6379:6379 -m 4g \
  -v localcloud-data:/var/lib/localcloud \
  localcloud/localcloud:latest

# Point Terraform at LocalCloud
eval $(curl -s 'http://localhost:8080/env?format=terraform')
export GOOGLE_PROJECT=tf-local-project

# Run Terraform
terraform init
terraform plan
terraform apply
```

## Supported Resources

### Fully Supported (verified with Terraform)

| Resource | Status | Notes |
|----------|--------|-------|
| `google_project` | ✅ Supported | Create, read, update via Cloud Resource Manager v3 API |
| `google_pubsub_topic` | ✅ Supported | Full CRUD via Pub/Sub emulator (port 8085) |
| `google_pubsub_subscription` | ✅ Supported | With ack_deadline, message_retention |
| `google_bigtable_instance` | ✅ Supported | Create, read, delete via Bigtable emulator (port 8087) |
| `google_bigtable_table` | ✅ Supported | Column family definition |
| `google_storage_bucket` | ✅ Supported | Create, read, update, delete via fake-gcs-server (port 4443) |
| `google_storage_bucket_object` | ✅ Supported | Upload/download objects |
| `google_bigquery_dataset` | ✅ Supported | Create, read, delete via DuckDB emulator (port 9050) |
| `google_bigquery_table` | ✅ Supported | Schema definition, create, delete |
| `google_spanner_instance` | ✅ Supported | Create, read via Spanner emulator |
| `google_spanner_database` | ✅ Supported | DDL, create, delete |
| `google_secret_manager_secret` | ✅ Supported | REST + gRPC via gateway (port 8080) |
| `google_secret_manager_secret_version` | ✅ Supported | Add, access secret versions |
| `google_cloud_tasks_queue` | ✅ Supported | REST + gRPC via gateway |
| `google_workflows_workflow` | ✅ Supported | gRPC facade via gateway |
| `google_cloud_scheduler_job` | ✅ Supported | gRPC facade via gateway |
| `google_cloudfunctions2_function` | ✅ Supported | gRPC facade via gateway |
| `google_alloydb_cluster` / `google_alloydb_instance` | ✅ Supported | gRPC facade + PostgreSQL wire-level |
| `google_dataproc_cluster` | ✅ Supported | gRPC facade + local spark-submit |
| `google_redis_instance` | ✅ Supported | Memorystore (Redis) emulator (port 6379) |
| `google_sql_database_instance` | ✅ Supported | Cloud SQL facade via gateway |
| `google_cloud_run_v2_service` | ✅ Supported | Cloud Run facade via gateway |
| `google_container_cluster` | ✅ Supported | GKE facade via gateway |
| `google_compute_instance` | ✅ Supported | Compute Engine facade via gateway |

## Authentication

LocalCloud runs in permissive IAM mode — no authentication required. The Terraform env output includes:

```bash
export GOOGLE_APPLICATION_CREDENTIALS="/dev/null"
```

This tells the Google provider to skip real GCP authentication.

## How It Works

### Architecture

```
┌─ Terraform ──────────────────────────────────────────────┐
│ Google Provider v6.x                                     │
│   ├─ Per-service custom endpoints ──► LocalCloud emulators│
│   └─ Pre-flight validation                               │
│       └─ serviceusage.googleapis.com:443                 │
│              │                                           │
│    /etc/hosts: 127.0.0.1 ◄── DNS redirect                │
│              │                                           │
│    docker -p 443:8080  ◄── Port forward to gateway       │
│              │                                           │
│              ▼                                           │
│    Gateway /v1/projects/{p}/services/{s} → {"state":"ENABLED"}│
└──────────────────────────────────────────────────────────┘
```

### Service Usage Emulation

The gateway runs a Service Usage API emulator that returns `{"state": "ENABLED"}` for all services. When the Terraform provider checks if, say, Secret Manager is enabled for a project, it calls:

```
https://serviceusage.googleapis.com/v1/projects/tf-local-project/services/secretmanager.googleapis.com
    │
    ▼  (DNS redirect + port forward)
    │
http://localhost:8080/v1/projects/tf-local-project/services/secretmanager.googleapis.com
    │
    ▼
{"state": "ENABLED"}  → Provider proceeds with the actual API call
```

### Cloud Billing Emulation

Similarly, the `cloudbilling.googleapis.com` endpoint returns `{"billingEnabled": true}` for all projects, preventing "billing account disabled" errors.

## Troubleshooting

### "SERVICE_DISABLED" or "billing account disabled" errors

**Cause**: DNS redirect for `serviceusage.googleapis.com` is not configured.

**Fix**:
```bash
grep -q "serviceusage.googleapis.com" /etc/hosts || \
  echo "127.0.0.1 serviceusage.googleapis.com" | sudo tee -a /etc/hosts
```

Verify it works:
```bash
curl -k https://serviceusage.googleapis.com/v1/projects/test/services/storage.googleapis.com
# Should return: {"state":"ENABLED",...}
```

### "ACCESS_TOKEN_TYPE_UNSUPPORTED"

**Cause**: `GOOGLE_OAUTH_ACCESS_TOKEN` is set to an invalid value. This env var is optional — the provider works without it.

**Fix**: Unset `GOOGLE_OAUTH_ACCESS_TOKEN`.

### Provider hangs / timeouts

**Cause**: Provider version v7.x is incompatible with `/dev/null` credentials.

**Fix**: Pin provider to v6.x:
```hcl
terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }
}
```

### "Project not found" errors

**Cause**: The project must exist in LocalCloud's PostgreSQL database before Terraform can create resources in it.

**Fix**: Create the project first via the API or include a `google_project` resource:
```bash
curl -X POST http://localhost:8080/v3/projects \
  -H "Content-Type: application/json" \
  -d '{"project_id":"my-project","name":"My Project"}'
```

## CI/CD Integration

See `terraform/examples/` for complete Terraform configurations.

The environment setup in CI/CD workflows must include the DNS redirect step.

Example GitHub Actions setup:
```yaml
- name: Configure DNS for LocalCloud
  run: echo "127.0.0.1 serviceusage.googleapis.com" | sudo tee -a /etc/hosts
- name: Start LocalCloud
  run: docker run -d -p 443:8080 -p 8080:8080 ... localcloud/localcloud:latest
- name: Setup Terraform
  run: eval $(curl -s http://localhost:8080/env?format=terraform)
- name: Apply
  run: terraform apply -auto-approve
```
