## Context

Terraform's Google Cloud provider (`hashicorp/google`) makes REST API calls to `https://[service].googleapis.com`. It supports per-service endpoint overrides via:
- Provider block: `storage_custom_endpoint = "http://localhost:4443"`
- Environment variables: `GOOGLE_STORAGE_CUSTOM_ENDPOINT=http://localhost:4443`

LocalCloud already emulates the same REST/gRPC APIs. The integration is mostly wiring — no new emulator logic.

## Goals / Non-Goals

**Goals:**
- `eval $(curl localhost:8080/_localcloud/env?format=terraform)` + `terraform apply` works
- Zero changes to existing .tf files
- Document which `google_*` resources are supported
- Phase 1 (storage, Pub/Sub, BigQuery, Spanner) verified working end-to-end

**Non-Goals:**
- Custom Terraform provider (`terraform-provider-localcloud`)
- Wrapper CLI (`tflocalcloud`)
- Terraform state management or locking
- IAM policy enforcement for Terraform operations
- Full compatibility with every `google_*` resource (there are 500+)

## Decisions

### D1: Environment variable approach — no .tf changes needed

**Choice:** Output `GOOGLE_*_CUSTOM_ENDPOINT` env vars from the existing `/_localcloud/env` endpoint with `format=terraform`. Users source them before running Terraform.

**Why:** The Google provider reads these env vars automatically. No wrapper binary, no .tf modifications, no provider override blocks. The same Terraform code runs against real GCP (without env vars) or LocalCloud (with env vars).

### D2: Authentication bypass via /dev/null credentials

**Choice:** Include `GOOGLE_APPLICATION_CREDENTIALS=/dev/null` in the terraform env output. LocalCloud's IamMiddleware in permissive mode (default) accepts all requests without token validation.

**Why:** Terraform's Google provider requires a credential source. `/dev/null` is a valid file that produces empty credentials, which the provider accepts when the endpoint doesn't validate tokens.

### D3: Phased API compatibility verification

**Choice:** Verify in phases based on which emulators already serve compatible REST APIs:

| Phase | Services | Expected Status |
|-------|----------|----------------|
| 1 | GCS, Pub/Sub, BigQuery, Spanner | Likely already working — external emulators serve Google-compatible REST APIs |
| 2 | Secret Manager, Cloud Tasks | Need Armeria gRPC-REST transcoding — currently gRPC-only facades |
| 3 | Compute, Cloud Run, GKE, Firestore, Bigtable | Need REST CRUD endpoints matching Google API surface |

**Why:** Phase 1 covers the most-used CI/CD resources with minimal work. Phase 2 requires REST transcoding (Armeria supports this). Phase 3 requires new endpoint implementations.

### D4: Endpoint URL mapping

```
GOOGLE_STORAGE_CUSTOM_ENDPOINT       = http://localhost:4443
GOOGLE_PUBSUB_CUSTOM_ENDPOINT        = http://localhost:8085
GOOGLE_BIGQUERY_CUSTOM_ENDPOINT      = http://localhost:9050
GOOGLE_FIRESTORE_CUSTOM_ENDPOINT     = http://localhost:8086
GOOGLE_SPANNER_CUSTOM_ENDPOINT       = http://localhost:9020  (REST port, not gRPC 9010)
GOOGLE_BIGTABLE_CUSTOM_ENDPOINT      = http://localhost:8087
GOOGLE_SECRET_MANAGER_CUSTOM_ENDPOINT = http://localhost:8080
GOOGLE_CLOUD_TASKS_CUSTOM_ENDPOINT   = http://localhost:8080
GOOGLE_COMPUTE_CUSTOM_ENDPOINT       = http://localhost:8080
GOOGLE_CLOUD_RUN_CUSTOM_ENDPOINT     = http://localhost:8080
GOOGLE_CONTAINER_CUSTOM_ENDPOINT     = http://localhost:8080  (GKE)
GOOGLE_LOGGING_CUSTOM_ENDPOINT       = http://localhost:8080
GOOGLE_MONITORING_CUSTOM_ENDPOINT    = http://localhost:8080
GOOGLE_APPLICATION_CREDENTIALS       = /dev/null
```

## Risks / Trade-offs

- **[Risk] Terraform provider expects specific response fields not returned by emulators** → Mitigated by Phase 1 verification. GCS (fake-gcs-server) and Pub/Sub emulators are well-tested against Google client libraries which use the same APIs.
- **[Risk] Terraform state drift if emulator responses differ slightly** → Mitigated by documenting known differences. Most resources only need create + read + delete.
- **[Risk] Auth bypass may not work with all provider versions** → Mitigated by testing with latest hashicorp/google provider. `/dev/null` approach is used by other emulator projects.
