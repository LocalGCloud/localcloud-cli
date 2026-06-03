---
id: SPEC-localcloud
companions:
  - services.md
  - architecture.md
  - personas.md
  - use-cases.md
  - license-model.md
  - emulator-notes.md
  - glossary.md
sources:
  - docs/ARCHITECTURE.md
  - docs/GLOSSARY.md
  - docs/product/product-marketing.md
  - docs/product-use-cases.md
  - docs/license-server-prd.md
  - AGENTS.md
  - services.yaml
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate. Source documents listed in frontmatter are for traceability only — consult them only if you need narrative rationale or prose color this contract intentionally omits.

# LocalCloud — GCP Local Emulator

## Why

**Pain + Opportunity.** Google Cloud developers face a multi-faceted bottleneck: real GCP is slow (2–5s API latency), costly ($5–50/day per CI pipeline, $10K–$100K+/month per team), and burdensome (accounts, credentials, IAM, billing setup per developer). Google provides official emulators for only 3 services (Pub/Sub, Firestore, Spanner), leaving teams to mock or stub the rest. LocalCloud solves this by providing 23+ GCP services in a single Docker container — same SDKs, same APIs, zero code changes, zero cloud dependencies. It targets the inner development loop, CI/CD pipelines, Terraform IaC validation, training, and demos. The project also has a licensing/revenue model via a license server that enforces tiered access (Community free, Pro/Team/Enterprise paid), making it sustainable as a product beyond an open-source tool.

The force is a combination of **pain to solve** (developers stuck on slow, expensive, credential-heavy GCP workflows), **opportunity to capture** (gap in Google's emulator coverage for BigQuery, Bigtable, Memorystore, and 14+ facade services), and **vision to realize** (one `docker run` for a complete local GCP, with a web console, seed data, and Terraform compatibility).

## Capabilities

- id: CAP-1
  intent: User can run 23+ GCP services locally via a single Docker container with zero cloud dependencies, using the same GCP SDKs and protocols (gRPC/REST) with no code changes.
  success: A developer runs `docker compose up -d`, sets exported env vars, and their existing GCP SDK code (Python, Java, Go, Node.js) executes against LocalCloud without modification.

- id: CAP-2
  intent: Developers can seed all services to a deterministic state from a YAML file, ensuring identical data across every developer machine and CI run.
  success: Running `localcloud seed seed.yaml` populates all 23 services with the defined data; a subsequent query confirms exact match.

- id: CAP-3
  intent: Platform engineers can run CI/CD pipelines (GitHub Actions, GitLab CI, Jenkins) using LocalCloud as a sidecar container, replacing real GCP resources and eliminating per-run cloud costs.
  success: A CI pipeline that uses BigQuery, Pub/Sub, GCS, Firestore, and Secret Manager completes against LocalCloud with zero real GCP API calls, and the pipeline cost is $0 beyond compute.

- id: CAP-4
  intent: Developers can validate Terraform configurations locally using `terraform plan`, `apply`, and `destroy` against LocalCloud emulators via `GOOGLE_*_CUSTOM_ENDPOINT` env vars.
  success: A `terraform apply` against LocalCloud succeeds end-to-end for GCS, Pub/Sub, BigQuery, Spanner, and Cloud Run resources defined in `.tf` files.

- id: CAP-5
  intent: Users interact with all emulated services through a built-in web console providing a SQL editor, data explorer, log viewer, and project switcher.
  success: A developer opens `http://localhost:8080`, switches between projects, browses BigQuery tables, runs SQL queries, and sees results in the browser.

- id: CAP-6
  intent: Service endpoints can be selectively routed to real GCP per service (hybrid mode) — e.g., BigQuery to cloud, everything else local.
  success: When BigQuery routing is set to remote, a BigQuery query reaches real GCP while a Pub/Sub publish reaches the local emulator.

- id: CAP-7
  intent: License server enforces tiered access — Community (free, limited services), Pro (individual, all services), Team (multi-seat), Enterprise (air-gapped, offline keys).
  success: A user with a Community license key can access Memorystore and Pub/Sub but receives a 403 when attempting to use BigQuery or AlloyDB.

- id: CAP-8
  intent: Developers can reset all services to a clean state (with optional seed restoration) via a single admin API call.
  success: `POST /reset` with `{"restore_seed": true}` clears all data and reloads the seed YAML; all services show seed data only.

## Constraints

- Single PostgreSQL 17 instance as primary persistence for all facade services and admin data.
- Docker container must fit within a 4GB memory limit for stable operation on developer laptops.
- All external emulators (GCS, Pub/Sub, Firestore, Bigtable, Spanner, BigQuery, Memorystore) must run as separate processes managed by supervisord inside the container.
- Armeria gateway serves as the single entry point (port 8080) for admin API, gRPC transcoding, and console static files.
- Seed data format is YAML with `services:` wrapper; both flat (`gcs: ...`) and nested (`services: { gcs: ... }`) formats supported.
- Java 21 LTS for the gateway server; JRE bundled via jlink (~72 MB).
- ARM64 native (Apple Silicon) — no Rosetta/QEMU dependency.
- `/services` endpoint returns array format with id, name, status, port, protocol, endpoint, env_var, env_value, request_count.
- Usage metrics persisted via in-memory counters flushed to PostgreSQL every 30 seconds (UPSERT by project+service).

## Non-goals

- Production cloud replacement. LocalCloud targets development, testing, CI/CD, and demos — not production workloads.
- Full API surface coverage for every emulated service. Emulators provide enough surface for development workflows; undocumented edge cases are out of scope.
- Multi-node or distributed deployment. Single Docker container is the only supported topology.
- 100% behavioral parity with real GCP for emulated services. Facade services store metadata and return plausible responses; they do not execute real cloud operations.
- IAM policy enforcement beyond the permissive stub. `testIamPermissions` returns ALL permissions as allowed.
- Graphical workflow designer for Cloud Workflows. Execution engine exists; visual designer is out of scope.
- Hosting of the license server (`api.localcloud.dev`) — the license server component is built and included, but its hosted operation is a separate business concern.

## Success signal

A developer on a fresh laptop runs `docker compose up -d`, `localcloud seed seed.yaml`, and within 5 minutes has a working GCP environment where their existing SDK code compiles, runs, and produces correct results against 23+ services — with zero cloud accounts, zero credentials, and zero cost. This experience is reproducible on macOS (ARM64), Linux (AMD64), and in CI/CD runners.

## Assumptions

- Developers have Docker installed and can run containers locally.
- The primary target audience writes code against GCP SDKs (Java, Python, Go, Node.js).
- Teams will adopt LocalCloud first through free Community tier, then convert to paid tiers for CI/CD and full service access.
- External emulator binaries (fake-gcs-server, little_bigtable, bigquery-emulator v2, valkey-server) remain available and maintainable as separate dependencies.
- Spark is installed on the host for Dataproc job submission (not bundled in the container).
- Terraform users are comfortable setting `GOOGLE_*_CUSTOM_ENDPOINT` environment variables.

## Open Questions

- Should the Community tier limit be enforced by service count, feature restrictions, or rate limiting? Current design uses service gating via license tier.
- Is the offline/air-gapped license validation flow (Enterprise tier) fully specified, or does it need deeper design for key rotation and expiry?
- Should the BigQuery emulator aim for 99% SQL coverage, or is 96% sufficient for general development use?
- Does the Dataproc emulator need to bundle a Spark distribution, or is host-installed Spark acceptable long-term?
- Should there be a managed cloud-hosted version (SaaS) beyond the self-hosted Docker model?
