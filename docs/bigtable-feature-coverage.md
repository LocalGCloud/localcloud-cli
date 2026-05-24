# Bigtable Feature Coverage Report

**Date:** 2026-04-26
**Scope:** Production Cloud Bigtable vs LocalCloud's current Bigtable coverage

Legend: **Y** = supported | **P** = partial / only via one path | **N** = not supported | **U** = unverified

---

## Executive Summary

LocalCloud Bigtable is currently best described as **official Bigtable emulator coverage plus a separate PostgreSQL-backed console mirror**.

The SDK path is:

```text
BIGTABLE_EMULATOR_HOST=localhost:8087
        -> cbtemulator
        -> in-memory Bigtable test emulator
```

The console/admin browse path is:

```text
/browse/bigtable
        -> PostgreSQL bigtable_data
        -> persisted seed/browser rows, not the emulator data plane
```

That split is the main architectural gap. Application code using the Bigtable SDK talks to `cbtemulator`; the Data Browser and seed path read/write `bigtable_data`. These are not a single source of truth.

**Supported with reasonable fidelity:** local SDK testing for table creation, column families, `ReadRows`, row mutations, row ranges, most filters, and basic table admin operations.

**Not supported:** production control plane, instance/cluster management, replication, app profiles, autoscaling, backups, IAM/security, CMEK, change streams, GoogleSQL for Bigtable, Data Boost, authorized views, schema bundles, materialized views, observability features, and durable emulator persistence.

**High-priority gap:** Bigtable persistence and single-source-of-truth. The existing OpenSpec change already identifies replacing `cbtemulator` with `little_bigtable` as the short-term path, but none of those tasks are implemented.

---

## Evidence Snapshot

### LocalCloud Implementation

| Area | Evidence | Interpretation |
|------|----------|----------------|
| Service registry | `services.yaml` configures Bigtable as external gRPC on port `8087`, `BIGTABLE_EMULATOR_HOST`, TCP health check. | LocalCloud delegates Bigtable to an external emulator. |
| Runtime binary | `Dockerfile` copies `/google-cloud-sdk/platform/bigtable-emulator/cbtemulator` to `/usr/local/bin/cbtemulator`. | Runtime is Google's Bigtable emulator binary, not an in-process Java facade. |
| Process manager | `supervisord.conf` runs `/usr/local/bin/cbtemulator -host 0.0.0.0 -port 8087`. | Bigtable is a sidecar process inside the container. |
| Java facade | No `localcloud-server/src/main/java/com/localcloud/emulators/bigtable` implementation exists. | The original spec's completed Java facade tasks are stale or aspirational. |
| Console/browser storage | `SchemaManager` creates `bigtable_data(project_id, instance_id, table_name, row_key, cells JSONB)`. | Console data is persisted separately in PostgreSQL. |
| Browse API | `BrowseService.browseBigtable()` lists rows from `bigtable_data`, not from `cbtemulator`. | Browser may not reflect SDK-created emulator data. |
| Mutate API | `MutateService.mutateBigtable()` inserts/deletes rows in `bigtable_data`, not via Bigtable gRPC. | Browser mutations do not affect application SDK reads. |
| Seed API | `SeedService.seedBigtable()` inserts seed rows into `bigtable_data`, not the emulator. | Seeded Bigtable data is visible in console, not necessarily to SDK clients. |
| Sync adapter | `BigtableSyncAdapter` can browse/read remote Bigtable and builds `ReadRows`/`MutateRows` REST-shaped payloads. | Sync support exists but is helper-level and not validated against local gRPC `cbtemulator` in tests. |

### Official Bigtable / Emulator Baseline

Google documents the Bigtable emulator as **local, in-memory, non-production, non-persistent**, usable with Cloud Bigtable client libraries, without secure connection, and without administrative APIs for instances and clusters. It supports all filters except the Sink filter.

Production Bigtable exposes a much larger surface:

- Data API: `ReadRows`, `MutateRow`, `MutateRows`, `CheckAndMutateRow`, `ReadModifyWriteRow`, `SampleRowKeys`, `ExecuteQuery`, `PrepareQuery`, `ReadChangeStream`, change-stream partition APIs, and metadata warmup.
- Admin API: instance, cluster, app profile, table, backup, authorized view, schema bundle, logical view, materialized view, IAM, operations, and locations APIs.
- Platform capabilities: replication, app profile routing, autoscaling, Data Boost, backups/restore, IAM, CMEK, audit logging, authorized views, GoogleSQL, change streams, monitoring, Key Visualizer, hot tablets, table stats, import/export, and ecosystem connectors.

Primary sources:

- [Bigtable emulator docs](https://docs.cloud.google.com/bigtable/docs/emulator)
- [Bigtable Data API reference](https://docs.cloud.google.com/bigtable/docs/reference/data/rpc)
- [Bigtable Admin API reference](https://docs.cloud.google.com/bigtable/docs/reference/admin/rpc)
- [Bigtable overview](https://docs.cloud.google.com/bigtable/docs/overview)
- [Bigtable filters](https://docs.cloud.google.com/bigtable/docs/filters)
- [Bigtable writes](https://docs.cloud.google.com/bigtable/docs/writes)
- [GoogleSQL for Bigtable reference](https://docs.cloud.google.com/bigtable/docs/reference/sql/googlesql-reference-overview)

---

## Coverage Matrix

### Core Data Model and Data Plane

| Production capability | LocalCloud status | Notes |
|-----------------------|:---:|------|
| Tables | **Y** | Supported through `cbtemulator` table admin APIs and client libraries. Console only shows tables present in `bigtable_data`. |
| Column families | **Y** | Supported through emulator/client libraries. Console seed stores row cells but does not model column family metadata separately. |
| Rows, row keys, sorted key access | **Y** | SDK path supports row keys and row ranges. Console path stores row key as PostgreSQL text. |
| Cells with column family, qualifier, timestamped versions | **P** | SDK path supports Bigtable cell model. Console path stores arbitrary JSONB cells and loses some native semantics. |
| `ReadRows` | **Y** | Supported by emulator/client SDK. Console browse is PostgreSQL query, not `ReadRows`. |
| Row ranges / row key prefix scans | **Y** | Demo and sync adapter cover ranges/prefix-style reads. |
| `MutateRow` | **Y** | Supported by emulator/client SDK. Console mutation writes only PostgreSQL mirror. |
| `MutateRows` batch writes | **Y** | Supported by production API and expected emulator behavior; sync adapter constructs batches. |
| `CheckAndMutateRow` conditional writes | **Y** | Listed as supported in LocalCloud developer guide and covered by emulator baseline; no local integration test found. |
| `ReadModifyWriteRow` append/increment | **P** | Production API supports it; original LocalCloud spec lists it, but current local tests only cover helper request construction, not emulator behavior. |
| `SampleRowKeys` | **P** | Original spec lists it; no local tests or console usage found. |
| `DropRowRange` | **P** | Sync adapter calls REST-shaped local endpoint, but current runtime is gRPC `cbtemulator`; this path is suspect without integration verification. |
| Aggregate cells / `AddToCell` / `MergeToCell` | **U** | Production writes support aggregate updates. No LocalCloud docs/tests confirm emulator coverage. |
| Data API `PingAndWarm` / route lookup | **N** | Not surfaced or tested in LocalCloud. |

### Read Filters

| Production capability | LocalCloud status | Notes |
|-----------------------|:---:|------|
| Row key regex / row ranges | **Y** | Emulator supports filters; sync adapter builds row-range prefix requests. |
| Column family regex | **Y** | Emulator supports filters; sync adapter builds `familyNameRegexFilter`. |
| Column qualifier regex/range | **Y** | Expected through emulator. Not exposed in LocalCloud console. |
| Cells per column / row limits and offsets | **Y** | Emulator docs say all filters except Sink; Python demo uses latest-cell filter. |
| Timestamp range / value range / value regex | **Y** | Expected through emulator. Not exposed in console. |
| Strip value / apply label | **Y** | Expected through emulator. |
| Chain / interleave / condition filters | **Y** | Expected through emulator. |
| Sink filter | **N** | Official emulator explicitly excludes Sink. |
| Console/Admin browse filtering | **N** | Browse endpoint returns up to 50 rows by table from PostgreSQL, no native filter model. |

### Table Admin

| Production capability | LocalCloud status | Notes |
|-----------------------|:---:|------|
| `CreateTable` | **Y** | Supported through client libraries against emulator. |
| `GetTable` / `ListTables` | **Y** | Supported through emulator; console list uses PostgreSQL mirror instead. |
| `DeleteTable` | **Y** | Supported through emulator/client SDK. |
| `ModifyColumnFamilies` | **Y** | Supported through emulator/client SDK; demo adds a column family. |
| `UpdateTable` | **N** | Not documented or tested locally. |
| `GenerateConsistencyToken` / `CheckConsistency` | **N** | Replication consistency has no local meaning. |
| `UndeleteTable` | **N** | No table-deletion recovery semantics. |
| Backups: create/list/get/update/delete/copy/restore | **N** | Not supported by emulator or LocalCloud. |
| Authorized views | **N** | Not supported. |
| Schema bundles / protobuf schemas | **N** | Not supported. |
| IAM policy on table resources | **N** | Not supported. |

### Instance, Cluster, Routing, and Replication

| Production capability | LocalCloud status | Notes |
|-----------------------|:---:|------|
| `CreateInstance`, `GetInstance`, `ListInstances`, update/delete instance | **N** | Official emulator does not provide instance management. Any project/instance name can be used after startup. |
| Cluster create/list/update/delete | **N** | Official emulator does not provide cluster management. |
| Node counts and manual scaling | **N** | No nodes/clusters in emulator. |
| Autoscaling | **N** | Not applicable locally. |
| SSD/HDD storage types | **N** | No production storage tiers in emulator. |
| Tiered storage / in-memory tier | **N** | Not supported. |
| Multi-cluster replication | **N** | Not supported. |
| App profiles | **N** | No app profile management or routing behavior. |
| Single-cluster / multi-cluster routing policies | **N** | Not modeled locally. |
| Failover controls | **N** | Not supported. |
| Data Boost app profiles | **N** | Not supported. |

### Persistence and LocalCloud Console

| Capability | LocalCloud status | Notes |
|------------|:---:|------|
| Emulator data persists across container restart | **N** | `cbtemulator` is in-memory. |
| Console Bigtable data persists in PostgreSQL | **P** | `bigtable_data` persists, but it is not the emulator's data plane. |
| Seed data visible to SDK clients | **N** | Current `seedBigtable()` inserts PostgreSQL rows only. |
| SDK-created data visible in console | **N** | Current `browseBigtable()` reads PostgreSQL only. |
| Console row add/delete affects SDK clients | **N** | Current `mutateBigtable()` writes PostgreSQL only. |
| Single source of truth for Bigtable | **N** | Split-brain between emulator memory and `bigtable_data`. |
| Proposed persistent Bigtable path | **P** | OpenSpec proposes `little_bigtable` with SQLite persistence, but tasks are unchecked. |

### SQL, Views, and Query Layer

| Production capability | LocalCloud status | Notes |
|-----------------------|:---:|------|
| GoogleSQL `ExecuteQuery` | **N** | Production Data API supports it; LocalCloud does not. |
| GoogleSQL `PrepareQuery` | **N** | Not supported. |
| Bigtable Studio / saved queries | **N** | Not supported. |
| Logical views | **N** | Not supported. |
| Continuous materialized views | **N** | Not supported. |
| Asynchronous secondary indexes | **N** | Not supported. |
| Structured row key queries | **N** | Not supported. |
| Protobuf schemas / typed queries | **N** | Not supported. |
| Data type enforcement | **N** | Not supported. |
| Service Explorer PostgreSQL query over `bigtable_data` | **P** | Useful for debugging LocalCloud's mirror table, but not Bigtable SQL compatibility. |

### CDC, Integration, Import/Export, and Ecosystem

| Production capability | LocalCloud status | Notes |
|-----------------------|:---:|------|
| Change streams / CDC | **N** | Production Data API supports change-stream read APIs; emulator/local does not. |
| Dataflow change stream templates | **N** | Not supported. |
| BigQuery federation / querying Bigtable from BigQuery | **N** | Not supported locally. |
| Import/export workflows | **N** | Not implemented for Bigtable. |
| Beam connector against local emulator | **U** | Could work for basic SDK operations if connector honors `BIGTABLE_EMULATOR_HOST`; not verified. |
| Spark/Flink/Kafka connectors | **U** | Not verified; LocalCloud does not explicitly support connector-specific behavior. |
| HBase API / HBase shell | **N** | Production supports HBase integrations; official emulator docs state the HBase shell is not supported. |
| Terraform Bigtable resources | **P** | LocalCloud exposes `GOOGLE_BIGTABLE_CUSTOM_ENDPOINT`, but service notes say Bigtable REST admin API is not served, so standard Terraform resource coverage is not credible today. |

### Security, Governance, and Operations

| Production capability | LocalCloud status | Notes |
|-----------------------|:---:|------|
| IAM at project/instance/table/authorized-view levels | **N** | Emulator has no secure connection and LocalCloud does not enforce Bigtable IAM. |
| Authorized-view-level access control | **N** | Not supported. |
| CMEK | **N** | Not supported. |
| Encryption-at-rest semantics | **N** | Local Docker volume/database behavior only. No Cloud KMS model. |
| Tags / org policy | **N** | Not supported. |
| Audit logging | **N** | No Bigtable Data/Admin audit log emulation. |
| Monitoring metrics | **N** | LocalCloud has generic service usage metrics, not Bigtable production metrics. |
| Key Visualizer | **N** | Not supported. |
| Hot tablets | **N** | Not supported. |
| Table stats / query stats | **N** | Not supported. |
| Regional endpoints | **N** | Single local endpoint only. |
| SLA / durability / automatic maintenance | **N** | Not applicable to local emulator. |

---

## Highest-Value Gaps

### P0: Fix the Bigtable source-of-truth split

Today, the SDK and console/admin paths are disconnected:

```text
SDK-created data  -> cbtemulator memory      -> invisible to console
Seed/browser data -> PostgreSQL bigtable_data -> invisible to SDK
```

This is the most important gap because it undermines developer confidence. A user can create a table with the SDK and see an empty console, or seed data and fail to read it from their application.

Recommended direction:

1. Short-term: implement the existing OpenSpec `little_bigtable` plan and make browse/mutate/seed call the emulator gRPC API instead of `bigtable_data`.
2. Medium-term: delete or demote `bigtable_data` to an optional cache.
3. Long-term: consider a Java/PostgreSQL Bigtable facade only if LocalCloud needs deep inspection, deterministic persistence, or custom failure injection beyond what `little_bigtable` provides.

### P1: Add integration tests for the actual SDK surface

Current test coverage is mostly helper-level. Add tests that run against the local Bigtable endpoint and verify:

- create table
- modify column families
- mutate row
- batch mutate rows
- read row / range
- filters
- conditional write
- read-modify-write
- sample row keys
- delete table / drop row range
- restart persistence once `little_bigtable` is adopted

### P2: Make capability docs precise

The current docs overstate Bigtable in some places and understate the split in others. Update user-facing docs to say:

- Bigtable SDK compatibility is provided by the official emulator.
- Instance/cluster/admin production features are not supported.
- Bigtable data is currently in-memory unless the persistence change is implemented.
- Console Bigtable browse currently reflects `bigtable_data`, not authoritative emulator state.

### P3: Decide whether Bigtable SQL is in scope

Production Bigtable now has GoogleSQL via `ExecuteQuery` and `PrepareQuery`. LocalCloud currently has only PostgreSQL querying over `bigtable_data`, which is a debugging convenience, not GoogleSQL for Bigtable.

If SQL compatibility matters, it should be tracked as a separate change. It is not a small extension of the current `cbtemulator` setup.

---

## Coverage Summary

| Category | Status |
|----------|:---:|
| SDK data-plane CRUD | **Y** |
| Bigtable read filters | **Y** except Sink |
| Table admin basics | **Y** |
| Console browse/mutate fidelity | **N** for SDK truth, **P** for seed mirror |
| Persistence | **N** for emulator, **P** for PostgreSQL mirror |
| Instance/cluster/admin control plane | **N** |
| Replication/app profiles/autoscaling | **N** |
| Backups/restore | **N** |
| IAM/security/CMEK | **N** |
| GoogleSQL for Bigtable | **N** |
| Change streams | **N** |
| Observability/Key Visualizer/hot tablets | **N** |
| Terraform resource compatibility | **P/N**; endpoint exists, API coverage is insufficient |

Bottom line: **LocalCloud Bigtable is suitable for basic local application tests using Bigtable client libraries. It is not a production-control-plane emulator and currently has a correctness gap between SDK-visible emulator data and console-visible PostgreSQL data.**
