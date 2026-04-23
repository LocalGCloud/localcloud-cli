# Terraform Compatibility Matrix

LocalCloud supports Terraform's Google provider via `GOOGLE_*_CUSTOM_ENDPOINT` environment variables. No changes to `.tf` files are needed.

## Setup

```bash
# Start LocalCloud
docker run -d --name localcloud \
  -p 8080:8080 -p 4443:4443 -p 8085:8085 -p 8086:8086 \
  -p 8087:8087 -p 9010:9010 -p 9020:9020 -p 9050:9050 \
  -p 6379:6379 -m 4g \
  -v localcloud-data:/var/lib/localcloud \
  localcloud/localcloud:latest

# Point Terraform at LocalCloud
eval $(curl -s 'http://localhost:8080/_localcloud/env?format=terraform')

# Run Terraform
terraform init
terraform plan
terraform apply
```

## Supported Resources

### Fully Supported (Phase 1 — verified)

| Resource | Status | Notes |
|----------|--------|-------|
| `google_storage_bucket` | Supported | Create, read, update, delete via fake-gcs-server |
| `google_storage_bucket_object` | Supported | Upload/download objects |
| `google_pubsub_topic` | Supported | Full CRUD via Pub/Sub emulator |
| `google_pubsub_subscription` | Supported | With ack_deadline, message_retention |
| `google_bigquery_dataset` | Supported | Create, read, delete via DuckDB emulator |
| `google_bigquery_table` | Supported | Schema definition, create, delete |
| `google_spanner_instance` | Supported | Create, read via Spanner emulator |
| `google_spanner_database` | Supported | DDL, create, delete |

### Partially Supported (Phase 2 — needs REST transcoding)

| Resource | Status | Notes |
|----------|--------|-------|
| `google_secret_manager_secret` | Partial | gRPC works; REST transcoding needed for Terraform |
| `google_secret_manager_secret_version` | Partial | Same — needs REST transcoding |
| `google_cloud_tasks_queue` | Partial | gRPC works; REST transcoding needed |

### Planned (Phase 3 — needs CRUD endpoints)

| Resource | Status | Notes |
|----------|--------|-------|
| `google_compute_instance` | Planned | Data persisted in PostgreSQL; needs REST API shim |
| `google_cloud_run_v2_service` | Planned | Same |
| `google_container_cluster` | Planned | GKE — k3d-backed |
| `google_redis_instance` | Planned | Memorystore |

### Not Supported

| Resource | Reason |
|----------|--------|
| `google_project` | Projects managed via `/_localcloud/projects` API |
| `google_project_iam_*` | IAM is permissive by default |
| `google_service_account` | Not emulated |
| `google_dns_*` | DNS not emulated |
| `google_sql_*` | Cloud SQL not emulated |
| `google_vpc_*` / `google_compute_network` | Networking not emulated |

## Authentication

LocalCloud runs in permissive IAM mode — no authentication required. The Terraform env output includes:

```bash
export GOOGLE_APPLICATION_CREDENTIALS="/dev/null"
```

This tells the Google provider to skip real GCP authentication.

## CI/CD Integration

See `terraform/examples/ci-github-actions.yml` for a complete GitHub Actions workflow.

The pattern:
1. Start LocalCloud as a service container
2. Source the Terraform env vars
3. Run `terraform plan` / `terraform apply`
4. Verify resources were created via emulator APIs
5. `terraform destroy` to clean up
