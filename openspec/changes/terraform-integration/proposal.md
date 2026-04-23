## Why

LocalCloud targets developers, but CI/CD pipelines also need local GCP infrastructure. Teams use Terraform to provision GCP resources (buckets, topics, datasets, instances) — and they need a fast, isolated environment to test those Terraform scripts without touching real GCP.

The Google Terraform provider already supports `GOOGLE_*_CUSTOM_ENDPOINT` environment variables that redirect API calls to custom URLs. LocalCloud's emulators already serve most of the same REST/gRPC APIs. The gap: a `format=terraform` option on the env endpoint, REST API compatibility verification, and documentation.

**The goal:** `eval $(curl http://localhost:8080/_localcloud/env?format=terraform)` + `terraform apply` — zero .tf file changes, zero wrappers. Same Terraform code works against GCP or LocalCloud by toggling env vars.

## What Changes

- Add `format=terraform` to the `/_localcloud/env` endpoint that outputs `GOOGLE_*_CUSTOM_ENDPOINT` environment variables for all 15 services
- Verify and fix REST API compatibility between what Terraform's Google provider expects and what each LocalCloud emulator serves (Phase 1: storage, Pub/Sub, BigQuery, Spanner — Phase 2: Secret Manager, Cloud Tasks — Phase 3: Compute, Cloud Run, GKE)
- Add gRPC-REST transcoding for facade services (Secret Manager, Cloud Tasks) so Terraform can call them via REST
- Skip authentication — LocalCloud's permissive IAM mode accepts all requests, Terraform uses `GOOGLE_APPLICATION_CREDENTIALS=/dev/null`
- Ship example Terraform configs and CI/CD pipeline examples (GitHub Actions, GitLab CI)
- Add a Terraform compatibility status page in the console showing which resources are supported

## Capabilities

### New Capabilities
- `terraform-env-export`: Add `format=terraform` to the `/_localcloud/env` endpoint that outputs `GOOGLE_*_CUSTOM_ENDPOINT` exports for all services. Includes `GOOGLE_APPLICATION_CREDENTIALS=/dev/null` to bypass auth.
- `terraform-api-compat`: Verify and fix REST API compatibility for Terraform resource CRUD operations. Phase 1: GCS, Pub/Sub, BigQuery, Spanner (likely already working via emulators). Phase 2: Secret Manager, Cloud Tasks (need REST transcoding). Phase 3: Compute, Cloud Run, GKE (need CRUD endpoints).
- `terraform-docs`: Example Terraform configs, CI/CD pipeline templates, and a compatibility matrix documenting which `google_*` resources work with LocalCloud.

### Modified Capabilities
- `service-enable-disable`: The env endpoint's `format=terraform` output should only include endpoints for enabled services.

## Impact

- **Backend (Java)**: Modify `AdminApiService.env()` to support `format=terraform`. May need REST transcoding endpoints for Secret Manager and Cloud Tasks.
- **Frontend**: Optional compatibility status page in console.
- **Documentation**: New `terraform/` directory with example configs, CI/CD templates, and compatibility matrix.
- **Dependencies**: None — all Terraform integration is via standard REST APIs and env vars.
- **Testing**: Terraform must be installed to verify end-to-end. Automated tests can use `terraform plan` in dry-run mode.
