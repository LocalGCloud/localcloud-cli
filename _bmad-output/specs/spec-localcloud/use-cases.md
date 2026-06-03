# Product Use Cases

> **Companion to SPEC.md.** Primary product use cases with personas, concrete examples, and value propositions.

## Use Case 1: CI/CD Pipeline Infrastructure

**Persona:** Platform Engineer / DevOps Lead

**What It Means:** Replace real GCP services in CI/CD pipelines with LocalCloud running as a sidecar container. Every pipeline run gets an isolated, deterministic GCP environment with zero cloud costs.

**Concrete Examples:**
- GitHub Actions: `services: localcloud: image: localcloud/localcloud:latest` with env vars for all services
- GitLab CI: LocalCloud as a service container, health check on `:8080/health`
- Jenkins: Docker agent with LocalCloud sidecar, pre-seeded with test data
- BigQuery integration tests run against LocalCloud's DuckDB engine instead of real BigQuery ($0 vs $5-50/pipeline run)
- Pub/Sub integration tests: publish → subscribe → assert, all local
- GCS blob operations: upload, download, signed URLs against fake-gcs-server
- Spanner schema migration tests: apply DDL, verify schema

**Value Proposition:** 70–90% reduction in CI/CD GCP costs. Pipelines that cost $5–50/day become $0. A team of 20 running 200+ pipeline executions/day saves $10K–$100K+/month.

## Use Case 2: Terraform IaC Validation

**Persona:** Infrastructure Architect / Platform Engineer

**What It Means:** Run `terraform plan`, `apply`, and `destroy` against LocalCloud emulators using `GOOGLE_*_CUSTOM_ENDPOINT` environment variables. Same `.tf` files, no code changes.

**Concrete Examples:**
- `terraform plan` reveals that a bucket name is too long — before touching real GCP
- `terraform apply` creates GCS buckets, Pub/Sub topics, BigQuery datasets, Cloud Run services locally
- `terraform destroy` cleans up resources in LocalCloud
- CI pipeline: `terraform plan` against LocalCloud → review output → `terraform apply` to real GCP
- `terratest` integration: Go tests that create/destroy resources against LocalCloud

**Value Proposition:** Catch configuration errors in seconds instead of minutes. Validate entire infrastructure-as-code before real cloud deployment. Eliminate "works on my machine" infrastructure drift.

## Use Case 3: Local Development (Inner Development Loop)

**Persona:** Individual Developer / Application Developer

**What It Means:** Run GCP services locally alongside IDE for instant feedback loops. Edit code, run tests, see results — all local, all fast, no cloud dependencies.

**Concrete Examples:**
- Python developer: `os.environ["PUBSUB_EMULATOR_HOST"] = "localhost:8085"` — existing code works
- Java developer: `spring.cloud.gcp.pubsub.emulator-host=localhost:8085` — Spring Boot auto-configures
- Go developer: Set env vars, use `cloud.google.com/go/pubsub` — same client library
- Node.js developer: `process.env.PUBSUB_EMULATOR_HOST = "localhost:8085"` — no code changes
- BigQuery SQL development: Write queries in the console SQL editor, test against local DuckDB, deploy to real BigQuery
- Cloud Functions local development: Define function → trigger via Pub/Sub → debug locally
- API latency drops from 2–5 seconds (real GCP) to <1ms (local)

**Value Proposition:** Environment setup: days → minutes. Developer onboarding: 1 week → 1 hour. No cloud account, no credentials, no IAM, no billing. Works offline.

## Use Case 4: Training & Education

**Persona:** Trainer / Educator / Bootcamp Instructor

**What It Means:** Provide every workshop participant with an identical, pre-seeded GCP environment. No cloud account setup, no billing, no IAM per student.

**Concrete Examples:**
- University course: Students run `docker compose up` and have a full GCP for coursework
- Corporate training: 30 participants each have identical BigQuery datasets with sample data
- GCP certification prep: Practice exams using real SDKs against local emulators
- Hackathons: Teams get pre-seeded environments with BigQuery, Pub/Sub, GCS ready
- Workshop: "Build a data pipeline with Pub/Sub + Dataflow + BigQuery" — all local

**Value Proposition:** Zero setup time per participant. No cloud costs for training environments. No "my account doesn't have permission" support tickets.

## Use Case 5: Demos & Sales Engineering

**Persona:** Sales Engineer / Solutions Architect

**What It Means:** Deliver reliable, offline-capable GCP demos with pre-seeded data. Never depend on conference wifi or cloud API availability.

**Concrete Examples:**
- Customer demo: Show BigQuery SQL queries against a pre-loaded dataset — runs instantly, no wifi needed
- Conference booth: Live demo of a GCP application running entirely on a laptop
- Proof of concept: Customer evaluates a GCP-based solution without creating a GCP account
- Sales call: Share screen, run queries, show results — no "let me wait for this to load"

**Value Proposition:** Demos that never fail on spotty conference wifi. Instant response times impress prospects. Eliminate "can you share your screen? ... loading ..." moments.

## Use Case Comparison Matrix

| Capability | Local Dev | CI/CD | Terraform | Training | Demos |
|-----------|-----------|-------|-----------|----------|-------|
| Zero cloud cost | ✓ | ✓ | ✓ | ✓ | ✓ |
| Deterministic state | – | ✓ (seed) | ✓ (seed) | ✓ (seed) | ✓ (seed) |
| Offline capable | ✓ | – | ✓ | ✓ | ✓ |
| Full SDK compatibility | ✓ | ✓ | ✓ | ✓ | ✓ |
| Multi-service integration | ✓ | ✓ | ✓ | ✓ | ✓ |
| Web console | ✓ | – | – | ✓ | ✓ |

## License Tier Mapping

| Tier | Use Cases | Service Access |
|------|-----------|---------------|
| Community (free) | Local dev, training | Limited (Memorystore, Pub/Sub, GCS, basic facade) |
| Pro (individual) | All use cases | All 23+ services |
| Team | CI/CD, Terraform | All services + multi-seat |
| Enterprise | All + air-gapped | All services + offline keys + audit |
