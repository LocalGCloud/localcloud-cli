# LocalCloud Service Status Matrix

> **Last updated:** 2026-05-26
> **Source of truth:** `services.yaml`

Canonical reference for every emulated GCP service — type, port, tier, protocol, API coverage, and limitation summary.

---

## Legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Supported — full CRUD and primary workflow |
| 🟡 | Partial — core operations work, advanced features missing |
| 🔴 | Not supported |
| — | Not applicable |

---

## Service Matrix

### Data & Storage

| Service | Type | Port(s) | Tier | Protocol | Coverage | Key Limitations |
|---------|------|---------|------|----------|----------|-----------------|
| **Cloud Storage** | external | 4443 | Community | HTTP | ✅ | Versioning, lifecycle execution, CMEK |
| **BigQuery** | external | 9050 (REST), 9060 (gRPC) | Community | REST + gRPC | 🟡 ~96% SQL | Scripting, BQML, GEOGRAPHY, partitioning execution |
| **Spanner** | external | 9010 (gRPC), 9020 (REST) | Pro | gRPC + REST | 🟡 | Partitioned DML, change streams; LevelDB race condition on restart |
| **Bigtable** | external | 8087 | Pro | gRPC | ✅ SDK data plane | Console/sync split from SDK state; instance/cluster admin not emulated |
| **Firestore** | external | 8086 | Community | gRPC | 🟡 | Composite indexes, aggregation queries, seed/browser parity gaps |
| **Memorystore (Redis/Valkey)** | external | 6379 | Community | RESP2 | 🟡 | Pub/Sub, Lua, streams, MULTI/EXEC not supported |
| **AlloyDB** | facade | 8080 | Community | gRPC | 🟡 Metadata + PostgreSQL wire | Backup/restore, PSC, cross-region replication |
| **Cloud SQL** | facade | 8080 | Pro | REST | 🟡 PostgreSQL data plane only | MySQL data plane, read replicas, backup/restore |

### Messaging & Events

| Service | Type | Port(s) | Tier | Protocol | Coverage | Key Limitations |
|---------|------|---------|------|----------|----------|-----------------|
| **Pub/Sub** | external | 8085 | Community | gRPC | ✅ | Schema validation, BQ/GCS subscriptions, exactly-once delivery |
| **Cloud Tasks** | facade | 8080 | Community | gRPC | ✅ | App Engine tasks, OAuth token generation |
| **Cloud Scheduler** | facade | 8080 | Community | gRPC | ✅ | Timezone rules beyond cron-utils support |

### Serverless & Compute

| Service | Type | Port(s) | Tier | Protocol | Coverage | Key Limitations |
|---------|------|---------|------|----------|----------|-----------------|
| **Cloud Workflows** | facade | 8080 | Community | gRPC | ✅ | No execution checkpointing (lost on restart); no KMS or IAM enforcement |
| **Cloud Functions (2nd gen)** | facade | 8080 | Community | gRPC | 🟡 Metadata CRUD only | Build/container execution not emulated (use Functions Framework) |
| **Cloud Run** | facade | 8080 | Pro | gRPC | 🟡 Service CRUD, revisions | Traffic splitting, custom domains, Jobs |
| **Compute Engine** | facade | 8080 | Pro | REST | 🟡 Instance CRUD via Docker | Disks, snapshots, templates, networking |
| **GKE** | facade | 8080 | Pro | gRPC | 🟡 Cluster CRUD via k3d | Node pools, auto-scaling, upgrades |
| **Dataproc** | facade | 8080 | Community | gRPC | 🟡 Metadata + local spark-submit | Autoscaling, YARN/K8s cluster mode; requires Spark on host |

### Security & Identity

| Service | Type | Port(s) | Tier | Protocol | Coverage | Key Limitations |
|---------|------|---------|------|----------|----------|-----------------|
| **Secret Manager** | facade | 8080 | Community | gRPC | ✅ | Rotation, CMEK, per-secret IAM |
| **Cloud IAM** | facade | 8080 | Community | gRPC | 🟡 Permissive (allow all) | Role validation, conditions, deny policies |
| **Cloud KMS** | facade | 8080 | Pro | REST | 🟡 Key CRUD + local encrypt/decrypt | HSM, EKM, import jobs |

### Observability

| Service | Type | Port(s) | Tier | Protocol | Coverage | Key Limitations |
|---------|------|---------|------|----------|----------|-----------------|
| **Cloud Logging** | facade | 8080 | Community | gRPC | 🟡 Write/List/Delete | Metrics, sinks, exclusions, audit logs |
| **Cloud Monitoring** | facade | 8080 | Community | gRPC | 🟡 Time series, descriptors | Alerting, uptime checks, dashboards |

### AI/ML

| Service | Type | Port(s) | Tier | Protocol | Coverage | Key Limitations |
|---------|------|---------|------|----------|----------|-----------------|
| **Vertex AI** | facade | 8080 | Pro | REST | 🔴 Metadata stubs | Model serving, prediction, training |

---

## Quick Reference by Count

| Dimension | Count |
|-----------|-------|
| Total services in registry | 23 |
| Enabled by default | 17 |
| Disabled by default | 6 (GKE, Compute, Cloud Run, Vertex AI, KMS, Cloud SQL) |
| Community tier | 15 |
| Pro tier | 8 |
| Facade (in-process on gateway) | 17 |
| External (separate process) | 6 (GCS, Pub/Sub, Firestore, Bigtable, Spanner, BigQuery) + Memorystore (Valkey) |
| gRPC protocol | 18 |
| REST protocol | 4 (GCS, BigQuery, Compute, Vertex AI, KMS, Cloud SQL) |
| RESP2 protocol | 1 (Memorystore) |

---

## Tier Mapping

### Community Tier (Free — 15 services)

GCS, Pub/Sub, Firestore, BigQuery, Secret Manager, Cloud Tasks, Cloud Scheduler, Cloud Functions, AlloyDB, Dataproc, Cloud IAM, Logging, Monitoring, Memorystore, Cloud Workflows

### Pro Tier (8 services)

Spanner, Bigtable, GKE, Compute Engine, Cloud Run, Vertex AI, Cloud KMS, Cloud SQL

> **Note:** Spanner and Bigtable are Pro-tier but enabled by default. They can be disabled via `LOCALCLOUD_ENABLE_SPANNER=false` and `LOCALCLOUD_ENABLE_BIGTABLE=false`.

---

## Service Architecture Types

| Type | Description | Examples |
|------|-------------|----------|
| **external** | Separate process managed by supervisord. Communicates via its own port. | GCS (fake-gcs-server), Pub/Sub (in-process facade, port 8085), Firestore (JAR), Spanner (Go binary), BigQuery (Python+DuckDB), Bigtable (Go binary), Memorystore (Valkey) |
| **facade** | In-process Java implementation on Armeria gateway. Shares port 8080. PostgreSQL-backed. | All 17 remaining services |

---

## See Also

- [services.yaml](../services.yaml) — Machine-readable service registry (single source of truth)
- [DEVELOPER_GUIDE.md](../DEVELOPER_GUIDE.md) — Detailed usage and connection docs
- [terraform/COMPATIBILITY.md](../terraform/COMPATIBILITY.md) — Terraform resource support matrix
- Individual service coverage docs: [bigquery-coverage-gaps.md](bigquery-coverage-gaps.md), [pubsub-comparison.md](pubsub-comparison.md), [bigtable-feature-coverage.md](bigtable-feature-coverage.md), [spanner-emulator-feature-gaps.md](spanner-emulator-feature-gaps.md)
