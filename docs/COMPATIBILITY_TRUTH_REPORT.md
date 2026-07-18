# Compatibility Truth Audit Report

**Date:** 2026-06-22  
**Scope:** Cross-reference of all 26 service YAML registries vs. Terraform E2E tests, integration guides, and live API probe.  
**Schema version:** 2026-06-06

---

## Executive Summary

The compatibility data pipeline has three layers:
1. **YAML source files** (`compatibility/services/*.yaml`) — canonical truth
2. **Java registry** (`CompatibilityRegistry.java`) — loads YAML at startup, validates, serves `/compatibility` API
3. **Generated Markdown** (`COMPATIBILITY.md`, `SERVICE_STATUS.md`) — display layer

The YAML source files have now been corrected. A **server rebuild** is required for the runtime API to reflect these updates.

---

## Changes Made

### YAML Source Corrections

| File | Change | Rationale |
|------|--------|-----------|
| `secretmanager.yaml` | `secrets.lifecycle` and `versions.lifecycle` upgraded `partial` → `supported` | Terraform E2E passes for both `google_secret_manager_secret` and `google_secret_manager_secret_version`. Full CRUD with auto-version creation, idempotent enable/disable/destroy. Added explicit `advanced-secret-management: unsupported` operation for rotation/CMEK/IAM. |
| `alloydb.yaml` | `cluster-instance.lifecycle` upgraded `partial` → `supported` | Terraform E2E passes for `google_alloydb_cluster` and `google_alloydb_instance`. REST facade with proto→JSON conversion, synchronous LRO. |
| `bigtable.yaml` | `admin.lifecycle` upgraded `partial` → `supported`; `coverage_status` upgraded `partial` → `supported` | Terraform E2E passes for `google_bigtable_instance` and `google_bigtable_table`. Column family modify routes registered. |
| `dataproc.yaml` | Split `clusters-jobs.lifecycle` into `clusters.crud` (supported) + `jobs.execution` (partial) | Cluster CRUD passes Terraform E2E with explicit REST facade. Job submission requires Spark on host — genuinely partial. |
| `manual-verifications.yaml` | Fixed `manual:pubsub-advanced-2026-06-22` source from text description to `terraform/test-api-compat.sh` | Test `evidenceSourceFilesExistWhenTheyAreRepoPaths` requires repo-relative file paths. |

### Already Correct (No Change Needed)

| Service | Operation | Status | Evidence |
|---------|-----------|--------|----------|
| `pubsub` | `advanced-delivery` (schemas, snapshots, seek, DLQ) | `supported` | `manual:pubsub-advanced-2026-06-22`, `terraform:api-compat-script` |
| `gcs` | all core ops | `supported` | multiple |
| `cloudresourcemanager` | `projects.lifecycle` | `supported` | multiple |

**Note on PubSub:** The YAML source file (`pubsub.yaml`) already correctly marks `advanced-delivery` as `supported` with evidence. The live API response currently returns `partial` because the running server was built before the YAML update. Rebuild to sync.

---

## Service-by-Service Status After Corrections

| Service | Overall Coverage | TF Resources | Core CRUD | Advanced Features |
|---------|-----------------|--------------|-----------|-------------------|
| **alloydb** | partial | ✅ supported | ✅ supported | 🔴 backup/restore, PSC |
| **bigquery** | partial | 🟡 partial | ✅ supported (datasets/tables, scripting, MVs, external tables) | 🟡 SQL dialect parity, ⚠ type coercion |
| **bigtable** | supported ↑ | ✅ supported | ✅ supported (instances/tables, data plane, filters, MVs, persistence) | 🔴 snapshots/backups/IAM (prod_only clusters) |
| **cloudbilling** | partial | 🟡 partial | 🟡 REST facade exists | 🔴 real billing |
| **cloudfunctions** | partial | ✅ supported | 🟡 metadata CRUD only | 🔴 build/container execution |
| **cloudiam** | partial | — | 🟡 permissive mode | 🔴 role validation, conditions |
| **cloudresourcemanager** | supported ✅ | ✅ supported | ✅ supported | 🔴 org/folder hierarchy |
| **cloudrun** | partial | planned | 🟡 service metadata | 🔴 container routing, domains |
| **cloudscheduler** | partial | ✅ supported | 🟡 CRUD + pause/resume | 🟡 timezone rules |
| **cloudsql** | partial | ✅ supported | 🟡 PostgreSQL data plane | 🔴 MySQL, replicas |
| **cloudtasks** | partial | ✅ supported | 🟡 queue CRUD | 🟡 task dispatch |
| **compute** | partial | planned | 🟡 Docker-backed instances | 🔴 disks, networking |
| **dataproc** | partial | ✅ supported | ✅ supported (clusters) | 🟡 Spark job execution |
| **firestore** | partial | — | 🟡 doc CRUD | 🟡 indexes, queries |
| **gcs** | supported ✅ | ✅ supported | ✅ supported | 🔴 IAM/lifecycle (prod_only) |
| **gke** | partial | planned | 🟡 k3d metadata | 🔴 node pools, autoscaling |
| **kms** | partial | 🟡 partial | 🟡 key CRUD + encrypt/decrypt | 🔴 HSM, EKM |
| **logging** | partial | ✅ supported | 🟡 write/list | 🔴 metrics, audit logs |
| **memorystore** | partial | ✅ supported | 🟡 RESP commands | 🔴 Pub/Sub, Lua, streams |
| **monitoring** | partial | ✅ supported | 🟡 time series | 🔴 alerting, dashboards |
| **pubsub** | partial | ✅ supported | ✅ supported (topics/subs/publish) | ✅ advanced delivery (emulator) |
| **secretmanager** | partial | ✅ supported | ✅ supported ↑ | 🔴 rotation, CMEK, per-secret IAM |
| **serviceusage** | partial | — | 🟡 service toggles | 🔴 quotas |
| **spanner** | partial | ✅ supported | ✅ supported (full DDL/DML, transactions, Partitioned DML) | 🔴 change streams |
| **vertexai** | partial | planned | 🟡 GenAI stubs | 🔴 model platform |
| **workflows** | partial | ✅ supported | 🟡 deploy/list/get/delete | 🟡 execution, checkpointing |

↑ = upgraded in this audit.

---

## Feature Implementation Feasibility

### Group 1: Immediately Implementable (low/medium effort)

These features are marked `partial` or `unsupported` but could be implemented with reasonable effort:

| # | Feature | Service | Current Status | Effort | Approach |
|---|---------|---------|---------------|--------|----------|
| 1 | **Cloud Tasks task dispatch** | cloudtasks | partial | Medium | Add in-process scheduler to execute tasks at dispatch deadline against HTTP/PubSub targets |
| 2 | **Cloud Scheduler cron execution** | cloudscheduler | partial | Medium | Wrap cron-utils with a persistent scheduler; execute HTTP/PubSub/AppEngine targets |
| 3 | **PubSub gateway advanced routes** | pubsub | partial (gateway) | Medium | Expose schema, snapshot, seek endpoints on gateway REST facade (already on emulator) |
| 4 | **Cloud Functions Framework local execution** | cloudfunctions | partial | Medium | Add `--source` mode that wraps Functions Framework in a subprocess |
| 5 | **Secret Manager rotation policies** | secretmanager | unsupported | Medium | Add periodic rotation scheduler with automatic version creation |
| 6 | **Bigtable persistence hardening** | bigtable | partial | Medium | Implement PostgreSQL-backed persistence layer for the external emulator |
| 7 | **Spanner DDL/DML parity hardening** | spanner | partial | High | Extend SQL coverage for more Spanner dialect features |
| 8 | **Workflows execution checkpointing** | workflows | partial | Medium | Serialize step state to PostgreSQL every N steps; resume on restart |
| 9 | **IAM role validation** | cloudiam | partial | High | Bundle a minimal role catalog with predefined/permissive validation |
| 10 | **Memorystore Lua + streams** | memorystore | partial | High | Extend Valkey configuration or swap for a fuller Redis-compatible engine |
| 11 | **Logging metrics/exclusions** | logging | partial | Medium | Add basic metric aggregation and log exclusion filters |
| 12 | **Monitoring alerting** | monitoring | partial | High | Add alert rule evaluation with notification dispatch |

### Group 2: Requires Production Infrastructure (cannot emulate)

These features fundamentally depend on Google's global infrastructure — hardware, networking, or managed services:

| # | Feature | Service | Why It Requires Production |
|---|---------|---------|---------------------------|
| 1 | **Cloud HSM** | kms | Physical HSM devices with FIPS 140-2 Level 3 certification |
| 2 | **External Key Manager (EKM)** | kms | Integration with external third-party key management providers |
| 3 | **Private Service Connect (PSC)** | alloydb, cloudsql, gcs | Global Google networking fabric, VPC peering infrastructure |
| 4 | **Cross-region replication** | alloydb, cloudsql, spanner | Multi-region synchronous replication with Google's network backbone |
| 5 | **GKE node pool autoscaling** | gke | GCP-managed cluster autoscaler tied to regional instance groups |
| 6 | **Cloud Run custom domains + global routing** | cloudrun | Google Front End (GFE) load balancers, managed TLS certificates |
| 7 | **Compute Engine persistent disks, snapshots, live migration** | compute | Hypervisor-level storage and VM management |
| 8 | **Exactly-once delivery** | pubsub | Distributed consensus across regional PubSub clusters |
| 9 | **BigQuery/GCS subscriptions** | pubsub | Integration with BigQuery/GCS ingestion pipelines |
| 10 | **Real billing and cost export** | cloudbilling | Integration with Google's billing infrastructure and Cloud Billing API |
| 11 | **Vertex AI model training/tuning** | vertexai | TPU/GPU clusters, distributed training infrastructure |
| 12 | **Cloud SQL read replicas** | cloudsql | Managed replication with automatic failover |
| 13 | **Organization/folder hierarchy** | cloudresourcemanager | GCP org policy engine, resource hierarchy enforcement |
| 14 | **Cloud Run scale-to-zero** | cloudrun | GCP-managed request-based scaling with cold start infrastructure |

### Group 3: Feasible with Host Dependencies

These features could work locally but require specific host software or hardware:

| # | Feature | Service | Dependency |
|---|---------|---------|------------|
| 1 | **Dataproc Spark job execution** | dataproc | Apache Spark installed at `SPARK_HOME` |
| 2 | **GKE k3d runtime** | gke | k3d/k3s installed on host |
| 3 | **Cloud Run container execution** | cloudrun | Docker/Podman runtime with container networking |
| 4 | **Compute Engine Docker instances** | compute | Docker runtime on host |
| 5 | **Vertex AI GenAI (real models)** | vertexai | Ollama or local LLM server |

---

## Verification

```bash
# Unit tests pass with updated YAML
cd localcloud-server && ./gradlew test --tests "com.localcloud.admin.*"
# → BUILD SUCCESSFUL

# Compatibility truth check passes
bash scripts/checkCompatibilityTruth.sh
# → Compatibility truth registry looks consistent.
```

### To sync runtime API after rebuild:

```bash
# Rebuild and restart the server
docker compose build && docker compose up -d

# Verify the updated response
curl -s http://localhost:8080/compatibility | jq '.services[] | select(.service_id=="pubsub") | .operations[] | select(.id=="advanced-delivery")'
# Expected: { "status": "supported", "evidence": ["manual:pubsub-advanced-2026-06-22", "terraform:api-compat-script"] }

curl -s http://localhost:8080/compatibility | jq '.services[] | select(.service_id=="secretmanager") | .operations'
# Expected: secrets.lifecycle and versions.lifecycle both "supported"
```
