# LocalCloud Developer and CI/CD Gap Analysis

**Date:** 2026-05-23  
**Status:** Draft  
**Audience:** Product, engineering, docs, roadmap planning  
**Primary reference:** `.specify/memory/constitution.md`

## Executive Summary

LocalCloud is usable today for selected local development and CI workflows, especially when applications use the already-covered happy paths for Cloud Storage, Pub/Sub, BigQuery, Spanner, Secret Manager, Cloud Tasks, Memorystore, Logging, Monitoring, and Workflows. It is not yet complete as a full "private Google Cloud project on a laptop" or as a broad CI/CD substitute where tests can create any required resources, load data, run SDK code, inspect behavior, and tear everything down with the same lifecycle developers expect from Google Cloud.

The biggest gaps are not just missing services. The bigger product gaps are lifecycle parity, deterministic state management, SDK/gcloud/Terraform compatibility breadth, unified data visibility, app runtime parity, IAM behavior, and debugging depth. To make LocalCloud fully credible for developer laptops and CI/CD, the roadmap should focus on these capabilities first:

1. Truthful service/API coverage and SDK compatibility tests.
2. Deterministic provision, seed, snapshot, reset, export, import, and teardown workflows.
3. Broader resource creation support through SDKs, REST/gRPC APIs, gcloud-compatible paths, and Terraform.
4. Unified state across SDKs, seed data, console browsing, mutation APIs, export, and reset.
5. Metadata server, credentials, IAM, endpoint routing, and spawned-container runtime parity.
6. Deep observability: request inspector, structured logs, traces, event replay, query consoles, and change diffs.
7. CI-first tooling: `localcloud wait`, service profiles, Testcontainers helpers, examples, artifacts, and clear failure diagnostics.

## Target Scenarios

### Developer Laptop

A developer should be able to start one LocalCloud container and treat it like a private GCP project. Their existing application should run with Google Cloud SDKs after endpoint/env configuration, create resources, write and read data, run background services, inspect local cloud state, reset or restore state, and debug failures without touching real GCP.

### CI/CD Test Environment

A CI job should be able to start LocalCloud, wait until it is ready, provision resources through the same tooling used by the application or IaC layer, load test data, run tests against SDKs/endpoints, collect logs and state artifacts on failure, destroy or reset resources, and exit deterministically. Parallel jobs must not share state.

## Usability Verdict

| Area | Current usability | Gap summary |
|---|---|---|
| Local SDK happy paths | Partial to good | Several services support common operations, but coverage is uneven and not exposed as a formal SDK/API matrix. |
| Full GCP-like lifecycle | Partial | Start/env/seed/reset exist, but snapshots, init hooks, service-scoped lifecycle, and teardown contracts need hardening. |
| CI/CD provisioning | Partial | Terraform covers a Phase 1 subset; many resources need REST/transcoding or CRUD endpoint work. |
| Data plane testing | Partial | Core data operations exist for many services, but advanced behavior and unified persistence are not consistent enough for broad test confidence. |
| Runtime parity | Limited | Metadata server, service accounts, IAM, default credentials, DNS routing, and spawned-container behavior are incomplete. |
| Debugging | Partial | Health, logs, usage, and browsing exist; full request inspection, traces, payload capture, replay, and diffs are still roadmap items. |
| Tear down/reset | Partial | Global reset exists; service-scoped reset, snapshot restore, Terraform destroy parity, and exportable CI artifacts need stronger contracts. |
| Production API parity | Not complete by design | This is acceptable only if limitations are visible, precise, tested, and returned as structured unsupported-operation errors. |

## Developer Lifecycle Gap Analysis

| Lifecycle step | Expected experience | Current signal | Gap | Priority |
|---|---|---|---|---|
| Start local cloud | One command starts all required local services with persistent state. | Docker run and compose paths exist; services are registry-driven in `services.yaml`. | CLI lifecycle needs to be first-class: profiles, readiness, logs, service enablement, clean failure messages. | P0 |
| Discover configuration | Developer gets all env vars/endpoints for their project and enabled services. | `/env` supports shell/Terraform formats. | Needs stronger project/region/profile awareness and language/framework snippets. | P1 |
| Provision resources | App, SDK, gcloud, or Terraform can create needed resources locally. | Many service CRUD APIs exist; Terraform Phase 1 is documented. | REST/transcoding, gcloud paths, and Terraform CRUD coverage are incomplete across services. | P0 |
| Load test data | Developer can seed buckets, topics, datasets, tables, secrets, docs, tasks, workflows, and cache keys. | Seed YAML exists and supports flat/nested formats. | Every seed path must write to SDK-visible state; complex data imports and init hooks are missing. | P0 |
| Run application | Existing app code runs with endpoint changes only. | Constitution requires SDK compatibility; many service endpoints exist. | Metadata server, credentials, IAM, DNS routing, Cloud Run/GKE/Compute container behavior, and service account flows are incomplete. | P0 |
| Inspect state | Console and APIs show the same state the SDK sees. | Data browser and browse APIs exist. | Some services have source-of-truth risks; browser/mutate/seed/export must use the same backing state as SDKs. | P0 |
| Debug behavior | Developer sees requests, responses, logs, traces, data changes, and failed operations. | Logs/usage/request summaries exist; OpenSpec has debug-console plans. | Need payload capture, cURL replay, cross-service traces, query consoles, event replay, and change diffs. | P1 |
| Reset or restore | Developer can return to a known baseline instantly. | Global reset and seed restore exist. | Need named snapshots, service-scoped reset, export/import, and restore with version metadata. | P0 |
| Understand limitations | Unsupported GCP behavior is clear before and during use. | Some docs list unsupported items. | Need generated coverage, runtime unsupported responses, and console-visible limitations per service. | P0 |

## CI/CD Gap Analysis

| CI step | Expected experience | Current signal | Gap | Priority |
|---|---|---|---|---|
| Start service container | CI starts LocalCloud reliably with selected services and bounded memory. | Docker run examples exist; default image is single-container. | Need CI profiles, lightweight service subsets, better startup diagnostics, and documented resource budgets. | P0 |
| Wait for readiness | Pipeline blocks until all required services are actually ready. | Health endpoint exists. | Need `localcloud wait --services ... --timeout ...` and machine-readable readiness/failure reasons. | P0 |
| Provision infrastructure | Terraform/gcloud/SDK setup creates resources the test needs. | Terraform supports GCS, Pub/Sub, BigQuery, Spanner subset. | Need wider Terraform/gcloud/API compatibility for Secret Manager, Cloud Tasks, Compute, Cloud Run, GKE, KMS, Cloud SQL, IAM-like resources, and service accounts. | P0 |
| Create datasets and fixtures | CI loads structured test data into each service deterministically. | Seed endpoint exists. | Need import formats, service-scoped seed, larger fixture support, init hooks, and snapshot import. | P0 |
| Run tests | SDK tests behave like they would against GCP for supported operations. | Service coverage exists but is not complete. | Need automated SDK compatibility suites by language and formal operation coverage gates. | P0 |
| Inspect failures | CI uploads logs, request traces, service state, and coverage diagnostics as artifacts. | Logs and request counts exist. | Need `localcloud diagnose/export-artifacts`, request payload capture, state export, and failure bundles. | P1 |
| Tear down | Terraform destroy, service reset, or container removal leaves no shared residue. | Container isolation and reset exist. | Need reliable service-scoped reset, destroy parity, snapshot restore, and volume cleanup guidance. | P0 |
| Parallelize | Many CI jobs run with isolated projects/ports/state. | Separate containers can isolate state. | Need dynamic ports, project isolation, deterministic resource naming, and Testcontainers helpers. | P1 |

## Feature Categories

### P0: Foundation for Complete Developer and CI Use

| Feature | One-liner |
|---|---|
| Service coverage matrix | Publish exactly which services, operations, SDKs, gcloud commands, Terraform resources, and console paths work. |
| Unsupported-operation contract | Return consistent, explicit errors for unsupported APIs instead of silent success, hangs, or generic failures. |
| SDK compatibility suites | Run Python, Java, Go, and Node.js smoke tests against every claimed operation. |
| Lifecycle CLI | Provide reliable `start`, `stop`, `restart`, `status`, `wait`, `logs`, `env`, `seed`, `reset`, and `diagnose` commands. |
| Service profiles | Let developers and CI start named subsets such as `data`, `events`, `serverless`, `ai`, or custom service lists. |
| Readiness gate | Make readiness machine-readable and service-specific so CI can fail early with useful diagnostics. |
| Unified service state | Ensure SDK writes, seed data, console browse/mutate, export, and reset operate on the same state. |
| Service-scoped reset | Reset one service or project without destroying the whole container state. |
| State export/import | Save and restore all or selected service state for local reproduction and CI artifacts. |
| Named snapshots | Capture known-good states that can be restored during development or before CI test runs. |
| Init hooks | Run mounted scripts or seed/Terraform files during boot/start/ready/shutdown lifecycle stages. |
| Terraform compatibility expansion | Make more `google_*` resources work through normal provider configuration and custom endpoints. |
| REST/gRPC transcoding coverage | Expose REST paths needed by gcloud and Terraform for facade services. |
| Seed-to-SDK parity | Guarantee every seeded resource is visible through the official SDK for that service. |
| CI artifact export | Export logs, traces, request payloads, coverage, and state snapshots when tests fail. |

### P1: GCP-Like Runtime and Provisioning Parity

| Feature | One-liner |
|---|---|
| Metadata server emulation | Provide project, region, zone, service account, and token metadata to apps and spawned containers. |
| Service account model | Emulate enough service account and token behavior for SDK default credential flows and tests. |
| IAM strict mode | Add optional permission enforcement so teams can test least-privilege failures locally. |
| IAM explanation | Explain denied requests and suggest the missing role or permission. |
| Local endpoint routing | Route selected Google API endpoints to LocalCloud without per-client code changes where feasible. |
| Dynamic port allocation | Let CI run multiple LocalCloud instances without hard-coded port collisions. |
| Project and region isolation | Ensure resources are consistently scoped by project, location, region, and zone. |
| gcloud-compatible workflows | Support local equivalents or documented wrappers for common `gcloud`, `bq`, and storage commands. |
| Testcontainers helpers | Provide language-specific helpers to start LocalCloud, wait for services, and inject endpoints into tests. |
| Devcontainer templates | Provide ready-to-use devcontainer and compose templates for local teams. |
| Remote routing safety | Keep local/remote routing visible and prevent accidental real GCP writes unless explicitly enabled. |

### P1: Service/API Coverage Gaps

| Feature | One-liner |
|---|---|
| Secret Manager REST compatibility | Add REST/transcoding so Terraform/gcloud paths can manage secrets and versions. |
| Cloud Tasks REST compatibility | Add REST/transcoding so queues and tasks can be provisioned outside gRPC-only clients. |
| Compute CRUD parity | Expand instance, disk, network, image, operation, and metadata API behavior needed by tests and Terraform. |
| Cloud Run runtime parity | Support service deployment, revisions, env vars, traffic basics, logs, and local container invocation. |
| GKE lifecycle parity | Support cluster CRUD, kubeconfig generation, basic node pool modeling, and predictable k3d integration. |
| Bigtable persistence parity | Make Bigtable SDK data durable and visible through browse, seed, mutate, export, and reset. |
| Firestore seed/browser parity | Ensure seeded documents, SDK writes, query results, and console browsing are consistent. |
| Pub/Sub advanced developer features | Clarify filters, ordering, gateway snapshot/seek/schema routes, DLQ, and retry semantics across emulator, Terraform, and console surfaces. |
| BigQuery compatibility hardening | Convert known silent SQL mismatches into correct behavior or explicit compatibility warnings. |
| Spanner compatibility hardening | Expand DDL/DML, metadata, and restart persistence coverage for common app and Terraform flows. |
| Memorystore protocol coverage | Add commonly used Redis commands and persistence/reset semantics needed by app tests. |
| Workflows durability | Recover or fail in-flight executions predictably after restart and persist revision history. |
| Cloud SQL MVP completion | Provide credible Admin API plus PostgreSQL data plane and explicit MySQL/OpenHalo limitations. |
| Cloud KMS MVP completion | Provide local crypto-backed key rings, keys, versions, encrypt/decrypt, and Terraform/gcloud-compatible APIs. |
| Vertex AI GenAI MVP completion | Provide deterministic and optional local-backend responses for common Gemini-on-Vertex SDK flows. |
| Cloud Scheduler | Emulate scheduled HTTP/Pub/Sub invocations for local event-driven app tests. |
| Eventarc | Emulate event routing so GCS/Pub/Sub/Cloud Run style event flows can be tested locally. |
| IAM/STS | Provide enough identity token and access token behavior for local auth and strict-mode tests. |

### P2: Debugging and Developer Confidence

| Feature | One-liner |
|---|---|
| Request inspector | Show every request and response with headers, bodies, timing, status, and cURL replay. |
| Structured log explorer | Filter and inspect logs by service, severity, trace, request ID, time, and text. |
| Cross-service tracing | Correlate flows across GCS, Pub/Sub, Tasks, Workflows, Cloud Run, Logging, and Monitoring. |
| Query consoles | Provide BigQuery SQL, Spanner SQL, Redis CLI, Pub/Sub publish/pull, and Workflows execution tools. |
| Change diff view | Show what resources, rows, objects, messages, and logs changed after a test or operation. |
| Event recording | Record service events and callbacks so developers can replay failures after code fixes. |
| Fault injection | Inject latency, timeouts, and selected error responses per service or operation. |
| Diagnostics bundle | Produce one archive with config, service health, logs, requests, traces, and selected state. |
| Coverage warnings in console | Warn when a user enters an unsupported or partially supported service/API path. |

### P2: CI/CD Ergonomics

| Feature | One-liner |
|---|---|
| GitHub Actions template | Provide a complete workflow for start, wait, seed/provision, test, export artifacts, and teardown. |
| GitLab/CircleCI templates | Provide equivalent CI examples for common runners. |
| JUnit/Pytest helpers | Add test helpers for endpoint env setup, resource cleanup, snapshot restore, and diagnostics on failure. |
| Parallel test isolation | Support unique project IDs, dynamic ports, and isolated volumes per test worker. |
| Fixture import tools | Load CSV, JSON, Avro/Parquet, SQL, YAML, Redis dumps, and object directories into services. |
| Test data cleanup contracts | Document and enforce how tests should delete resources or reset services. |
| Exit-code discipline | Ensure CLI/admin operations return deterministic non-zero exits for CI failures. |

### P3: Product Completeness and Ecosystem

| Feature | One-liner |
|---|---|
| LocalCloud MCP server | Let AI agents manage lifecycle, deploy resources, inspect logs, run commands, and export diagnostics through structured tools. |
| IDE integration | Add VS Code/JetBrains tasks for start, stop, env export, logs, and resource browsing. |
| Docker Desktop integration | Make container status, logs, ports, snapshots, and console links discoverable from Docker Desktop. |
| Scenario packs | Ship prebuilt local cloud environments for data pipeline, event-driven app, backend API, AI app, and workshop labs. |
| Service topology graph | Generate a visual graph of resources and event paths from local state and traces. |
| Remote data mirror | Safely import selected real GCP resources into local state with redaction and explicit read-only controls. |
| Usage and cost insights | Show local API usage and estimated avoided GCP cost for teams and CI pipelines. |
| Extension points | Let teams add custom seeders, emulators, policy checks, resource browsers, and diagnostics. |
| Hosted ephemeral environments | Offer short-lived remote LocalCloud instances for support, workshops, or browser-only demos. |

## Roadmap Recommendation

| Phase | Theme | Deliverable |
|---|---|---|
| Phase 0 | Truth and lifecycle | Coverage matrix, unsupported-operation contract, CLI wait/status/diagnose, service profiles. |
| Phase 1 | Deterministic state | Unified service state, service reset, state export/import, snapshots, init hooks, seed-to-SDK parity. |
| Phase 2 | CI viability | Terraform expansion, REST/transcoding, CI templates, Testcontainers helpers, diagnostics artifacts. |
| Phase 3 | Runtime parity | Metadata server, service accounts, IAM strict mode, project/region isolation, endpoint routing. |
| Phase 4 | Debugging | Request inspector, structured logs, traces, query consoles, change diffs, event replay, fault injection. |
| Phase 5 | Service breadth | Cloud SQL, KMS, Vertex AI, Scheduler, Eventarc, IAM/STS, and hardening of Bigtable/Firestore/Pub/Sub/BigQuery/Spanner. |
| Phase 6 | Ecosystem | MCP server, IDE/Docker integrations, scenario packs, remote mirror, hosted ephemeral environments. |

## Success Criteria

- A new developer can start LocalCloud, configure env vars, create resources, seed data, run an SDK app, inspect state, and reset to baseline in under 10 minutes.
- A CI job can start LocalCloud, wait for readiness, provision resources, load fixtures, run tests, export diagnostics on failure, and tear down deterministically.
- Every claimed service operation has an automated compatibility test or an explicit documented limitation.
- Terraform compatibility covers the common resources for storage, messaging, data, secrets, tasks, serverless, compute, KMS, SQL, and workflows.
- Seeded data is visible through official SDKs for every service that supports seeding.
- Console state always matches SDK-visible state for claimed services.
- Unsupported operations return structured errors with service, operation, reason, and workaround.
- Parallel CI runs can operate with isolated project IDs, ports, and volumes.

## Source Notes

- LocalCloud constitution: `.specify/memory/constitution.md`
- Current service registry: `services.yaml`
- Current product/use-case docs: `README.md`, `DEVELOPER_GUIDE.md`, `docs/product-use-cases.md`
- Current Terraform matrix: `terraform/COMPATIBILITY.md`
- Current gap/planning docs: `docs/TECH_DEBT.md`, `docs/bigquery-coverage-gaps.md`, `docs/pubsub-comparison.md`, `docs/bigtable-feature-coverage.md`, `docs/spanner-emulator-feature-gaps.md`
- Relevant OpenSpec changes: `openspec/changes/developer-experience-console`, `openspec/changes/terraform-integration`, `openspec/changes/remote-cloud-browser`, `openspec/changes/seed-data-and-data-browser-crud`
