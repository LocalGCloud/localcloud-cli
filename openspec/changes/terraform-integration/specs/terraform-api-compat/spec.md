## ADDED Requirements

### Requirement: Phase 1 Terraform resources verified working
The following Terraform resources SHALL work against LocalCloud emulators: `google_storage_bucket`, `google_pubsub_topic`, `google_pubsub_subscription`, `google_bigquery_dataset`, `google_bigquery_table`, `google_spanner_instance`, `google_spanner_database`.

#### Scenario: Create and destroy a storage bucket
- **WHEN** `terraform apply` is run with a `google_storage_bucket` resource pointing at LocalCloud
- **THEN** the bucket SHALL be created in the GCS emulator and `terraform destroy` SHALL remove it

#### Scenario: Create Pub/Sub topic and subscription
- **WHEN** `terraform apply` creates a `google_pubsub_topic` and `google_pubsub_subscription`
- **THEN** both SHALL appear in the Pub/Sub emulator and be accessible via gRPC clients

### Requirement: Phase 2 REST transcoding for facade services
Secret Manager and Cloud Tasks SHALL expose REST endpoints compatible with the Terraform Google provider, using Armeria gRPC-REST transcoding.

#### Scenario: Create a secret via Terraform
- **WHEN** `terraform apply` creates a `google_secret_manager_secret`
- **THEN** the secret SHALL be created in LocalCloud and visible via `/_localcloud/browse/secretmanager`

### Requirement: Phase 3 compute resource CRUD
Compute Engine, Cloud Run, and GKE resources SHALL support basic CRUD operations via REST endpoints matching the Google API surface.

#### Scenario: Create a compute instance via Terraform
- **WHEN** `terraform apply` creates a `google_compute_instance`
- **THEN** the instance record SHALL be persisted in LocalCloud's PostgreSQL and visible in the console
