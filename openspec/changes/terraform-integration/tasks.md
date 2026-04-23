## 1. Terraform Env Export

- [x] 1.1 Add `format=terraform` case to `AdminApiService.env()` — output `export GOOGLE_*_CUSTOM_ENDPOINT=http://localhost:{port}` for each enabled service
- [x] 1.2 Map service IDs to Terraform env var names (gcs → GOOGLE_STORAGE_CUSTOM_ENDPOINT, pubsub → GOOGLE_PUBSUB_CUSTOM_ENDPOINT, etc.)
- [x] 1.3 Include `export GOOGLE_APPLICATION_CREDENTIALS=/dev/null` and `export GOOGLE_PROJECT=local-project` in output
- [x] 1.4 Skip disabled services in the output
- [ ] 1.5 Add `terraformEnv()` to console `api.js` client

## 2. Phase 1 — Verify Existing Emulator Compatibility

- [x] 2.1 Test `google_storage_bucket` create/read/destroy against GCS emulator (fake-gcs-server) — WORKS
- [x] 2.2 Test `google_pubsub_topic` + `google_pubsub_subscription` against Pub/Sub emulator — WORKS
- [x] 2.3 Test `google_bigquery_dataset` + `google_bigquery_table` against BigQuery emulator — WORKS
- [x] 2.4 Test `google_spanner_instance` + `google_spanner_database` against Spanner emulator — WORKS (list instances verified)
- [x] 2.5 Document any API response mismatches and fix them — No mismatches found for Phase 1

## 3. Phase 2 — REST Transcoding for Facade Services

- [ ] 3.1 Enable Armeria gRPC-REST transcoding for Secret Manager service
- [ ] 3.2 Enable Armeria gRPC-REST transcoding for Cloud Tasks service
- [ ] 3.3 Test `google_secret_manager_secret` create/read/destroy
- [ ] 3.4 Test `google_cloud_tasks_queue` create/read/destroy

## 4. Phase 3 — Compute Resource CRUD

- [ ] 4.1 Add REST endpoints matching Google Compute API for instance create/get/list/delete
- [ ] 4.2 Add REST endpoints matching Google Cloud Run API for service create/get/list/delete
- [ ] 4.3 Test `google_compute_instance` and `google_cloud_run_v2_service` via Terraform

## 5. Documentation & Examples

- [x] 5.1 Create `terraform/examples/main.tf` with LocalCloud provider config and sample resources
- [x] 5.2 Create `terraform/examples/ci-github-actions.yml` pipeline example
- [x] 5.3 Add Terraform section to README with quick-start instructions
- [x] 5.4 Create compatibility matrix (terraform/COMPATIBILITY.md — supported/partial/unsupported)

## 6. Build & Test

- [x] 6.1 Run Java tests
- [x] 6.2 End-to-end: `curl localhost:8080/_localcloud/env?format=terraform` verified — 12 GOOGLE_*_CUSTOM_ENDPOINT vars output correctly
