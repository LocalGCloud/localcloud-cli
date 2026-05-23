# LocalCloud — Tech Debt Register

> Generated: 2026-05-02
> Last updated: 2026-05-02
> Scope: All 16 OpenSpec changes + existing codebase
> Total items: 30 (18 active, 12 resolved)

---

## Priority Legend

| Priority | Meaning |
|----------|---------|
| P0 | Immediate code fix — broken or misleading |
| P1 | Deferred feature with user-visible gap |
| P2 | Missing implementation in existing feature |
| P3 | Deferred test coverage |
| P4 | Architectural concern — plan for future |

---

## 1. Immediate Code Fixes (P0)

### 1.1 Bigtable mutation handler ~~returns TODO~~ ✅ RESOLVED
- **Location**: `MutateService.java`
- **Description**: ~~`rows` and `rows/delete` operations return TODO placeholder instead of actual PostgreSQL INSERT/DELETE operations on `bigtable_data` table.~~
- **Status**: Already implemented using `BigtableGrpcClient.mutateRow()` and `BigtableGrpcClient.deleteRow()` (lines 808-831). Uses gRPC to communicate with `little_bigtable` emulator.
- **Fix**: ~~Implement actual `INSERT INTO bigtable_data` and `DELETE FROM bigtable_data WHERE row_key = ?` operations matching the `browseBigtable()` schema.~~ **NOT NEEDED**

### 1.2 Flask references in design docs
- **Location**: `openspec/changes/developer-experience-console/design.md` (D1, D3)
- **Description**: D1 references "Flask backend" and D3 says "Flask backend proxies this or implements its own SSE". Flask was removed from the architecture — Armeria Java gateway handles all endpoints.
- **Fix**: Update D1 and D3 to reference Armeria.

### 1.3 Stale architecture description
- **Location**: `openspec/changes/developer-experience-console/design.md:3`
- **Description**: States "Solid.js + Flask" but current architecture is Armeria Java gateway + Solid.js only.
- **Fix**: Update to "Solid.js + Armeria Java gateway".

---

## 2. Deferred Features (P1)

### 2.1 REST transcoding for Secret Manager, Cloud Tasks
- **Deferred from**: `terraform-integration` (Phase 2)
- **Description**: These facade services are gRPC-only and need Armeria gRPC-REST transcoding for Terraform's Google provider to call them via REST custom endpoints.
- **Estimated effort**: 1-2 weeks
- **Dependency**: Armeria gRPC-JSON transcoding support

### 2.2 Compute/Cloud Run/GKE CRUD endpoints
- **Deferred from**: `terraform-integration` (Phase 3)
- **Description**: Need new REST endpoint implementations matching the Google Compute/Cloud Run/GKE API surface for Terraform resource lifecycle (create, read, update, delete).
- **Estimated effort**: 3-5 weeks
- **Scope**: ~50+ REST endpoints across 3 services

### 2.3 gRPC remote proxy for hybrid connectivity
- **Deferred from**: `hybrid-cloud-connectivity`
- **Description**: Phase 1 implements REST-only remote proxy (GCS, BigQuery). gRPC proxy for Pub/Sub, Spanner requires Armeria gRPC client forwarding with TLS.
- **Estimated effort**: 2-3 weeks
- **Services affected**: Pub/Sub (port 8085), Spanner (port 9010)

### 2.4 Workload Identity Federation
- **Deferred from**: `hybrid-cloud-connectivity`
- **Description**: Not implemented. Users must use ADC (`application_default_credentials.json`) or SA key files. No WIF token exchange.
- **Impact**: CI/CD environments using WIF cannot use hybrid connectivity.

### 2.5 Metadata server emulation (169.254.169.254)
- **Deferred from**: `hybrid-cloud-connectivity`
- **Description**: Spawned containers get file-based credentials (`GOOGLE_APPLICATION_CREDENTIALS=/credentials/gcp.json`) instead of GCP metadata server emulation. This differs from real GCP behavior.
- **Impact**: SDKs that only check metadata server (not file creds) won't work in spawned containers.

### 2.6 Multi-file GCS SQL joins
- **Deferred from**: `gcs-sql-query`
- **Description**: Single-file queries only (`read_parquet('gs://...')`). Joining across multiple GCS files in a single SQL query is a future enhancement.
- **Workaround**: Users can write manual `UNION` queries.

### 2.7 Workflow revision history table
- **Deferred from**: `cloud-workflows-emulator`
- **Description**: No dedicated `workflow_revisions` table. Only `revision_id` column increments on `UpdateWorkflow`. GCP tracks full revision history for rollback.
- **Schema impact**: Can be added later without migration issues.

### 2.8 Durable execution checkpointing for Workflows
- **Deferred from**: `cloud-workflows-emulator`
- **Description**: If container restarts, in-flight executions are lost (state remains `ACTIVE` in PostgreSQL but no thread is running them). No recovery sweep on startup.
- **Future fix**: Add startup recovery that marks orphaned `ACTIVE` executions as `FAILED` with "container restarted" error.

### 2.9 Detailed per-record change diffs
- **Deferred from**: `developer-experience-console`
- **Description**: Change diff tracking starts with record counts before/after only. Detailed per-record diffs (actual field-level changes) come later.
- **Impact**: Users see "5 rows changed" but not what changed.

### 2.10 Replace gcloud emulators with Java facades
- **Deferred from**: `emulator-persistence` (Phase 2)
- **Description**: Firestore, Pub/Sub, Bigtable currently use Google's closed-source gcloud emulator binaries. Phase 2 replaces all three with in-house Java facades (matching Secret Manager, Cloud Tasks pattern).
- **Estimated effort**: 6-10 weeks
- **Benefit**: Eliminates dependency on Google's closed-source emulator binaries.

### 2.11 Bigtable SQLite → PostgreSQL facade
- **Deferred from**: `emulator-persistence` (Phase 2)
- **Description**: `little_bigtable` uses SQLite for persistence, breaking the single-PostgreSQL-database pattern. Export service can't query Bigtable data from PostgreSQL.
- **Options**: (a) Replace with PostgreSQL facade, or (b) add sync layer from SQLite to PostgreSQL.
- **Risk mitigation**: If `little_bigtable` project is abandoned (22 stars), this becomes urgent.

### 2.12 Additional services for remote cloud browser
- **Deferred from**: `remote-cloud-browser`
- **Description**: Phase 1 only supports GCS (list buckets, objects, metadata) and BigQuery (datasets, tables, preview, queries). Other 12 services can be added later using the same proxy pattern.

---

## 3. Missing Implementations (P2)

### 3.1 Pub/Sub message browsing
- **Change**: `fix-data-browser-per-service`
- **Description**: Pub/Sub shows topics but cannot browse messages within topics.

### 3.2 Firestore seed data not inserted
- **Change**: `fix-data-browser-per-service`
- **Description**: Firestore seed data exists in seed.yaml but UI shows empty collections. SeedService likely not calling Firestore REST API correctly.

### 3.3 BigQuery/Spanner UI mutation buttons ✅ RESOLVED
- **Change**: `fix-data-browser-per-service`
- **Description**: ~~Backend mutation handlers exist and work, but frontend Data Browser has no Add/Edit/Delete buttons for these services.~~
- **Status**: Already implemented in `DataBrowser.jsx` — BigQueryView has Add Row (line 561) and Delete (line 602), SpannerView has Add Row (line 1093), Edit (line 1139), and Delete buttons.

### 3.4 Cloud Tasks queue creation ~~fails~~ ✅ RESOLVED
- **Change**: `fix-data-browser-per-service`
- **Description**: ~~Mutation handler is missing for Cloud Tasks queue creation. POST to create queue returns error.~~
- **Status**: Already implemented in `MutateService.java:843-859` — INSERT into `task_queues` table with proper field mapping.

### 3.5 Request body inspection
- **Change**: `data-browser-workflow-reliability`
- **Description**: Deferred to `developer-experience-console` change. Data Browser tabs show health but not request/response body capture.

### 3.6 Query consoles
- **Change**: `data-browser-workflow-reliability`
- **Description**: Deferred to `developer-experience-console`. No interactive SQL editors in Data Browser tabs.

### 3.7 Fault injection
- **Change**: `data-browser-workflow-reliability`
- **Description**: Deferred to `developer-experience-console`. No ability to inject errors or latency per service.

---

## 4. Deferred Tests (P3)

### 4.1 Config merge precedence unit tests
- **Location**: `service-toggle-ui/tasks.md:2.4`
- **Description**: Deferred — needs test infrastructure changes.
- **Risk**: Config merge logic (user overrides vs. default vs. detected) is untested.

### 4.2 Config endpoints unit tests
- **Location**: `service-toggle-ui/tasks.md:4.3`
- **Description**: Deferred — needs test infrastructure changes.
- **Risk**: Config CRUD endpoints are untested.

---

## 5. Architectural Concerns (P4)

### 5.1 30-second polling everywhere ✅ RESOLVED
- **Appears in**: `developer-experience-console` (log polling), `emulator-persistence` (Pub/Sub sync), `data-browser-workflow-reliability` (health checks)
- **Description**: ~~Three independent 30-second polling loops.~~ Health polling remains global (configurable in Settings). Logs and Usage now have per-page configurable intervals with auto-refresh toggles. DataBrowser redundant health polling removed (uses app.jsx global polling).
- **Status**: Configurable intervals stored in `localStorage`. Toggle on/off per page. Settings → Preferences shows all interval controls.
- **Fix**: Made polling page-aware and configurable.

### 5.2 localStorage vs PostgreSQL inconsistency
- **Instances**:
  - Routing overrides → localStorage (`service-routing-indicator`)
  - Routing mode → PostgreSQL `service_routing` table (`hybrid-cloud-connectivity`)
  - Settings tab selection → localStorage (`settings-tabbed-layout`)
- **Concern**: No clear rule for what goes where.
- **Recommendation**: Standardize — user UI preferences → localStorage, operational/service state → PostgreSQL.

### 5.3 Phase 2 debt tracking
- **Concern**: Multiple changes defer work to "Phase 2" without a central backlog or tracking mechanism.
- **Items tracked as Phase 2**: #2.1, #2.2, #2.3, #2.10, #2.11
- **Recommendation**: Create a `TECH_DEBT.md` (this file) and reference it from each change's proposal.

### 5.4 `little_bigtable` abandonment risk
- **Concern**: The `bitly/little_bigtable` project has 22 stars and hasn't seen significant recent updates. It's Apache 2.0 licensed and forkable, but bus factor is 1.
- **Mitigation**: Fork it now to `localcloud/little_bigtable` while it's still compatible. If project dies, we maintain our fork.

### 5.5 Spanner LevelDB race condition ✅ RESOLVED
- **Source**: AGENTS.md
- **Description**: Known LevelDB race condition in Spanner emulator persistence — data may not survive restarts reliably.
- **Fix**: Added graceful shutdown settings in supervisord (`stopsignal=TERM`, `stopwaitsecs=15`). Added startup validation in `docker-entrypoint.sh` that detects corrupted LevelDB data (missing MANIFEST/CURRENT) and restores from periodic backup. Background process snapshots Spanner data every 60 seconds to `/var/lib/localcloud/spanner-data-backup`.

### 5.6 Regex DDL parsing for Spanner schema ✅ RESOLVED
- **Location**: `QueryService.java` (lines 1172-1265)
- **Description**: ~~Schema extraction uses simple regex parsing of `CREATE TABLE` statements from DDL response.~~ Replaced with JSqlParser 5.0 in new `SpannerDdlParser.java`. Handles Spanner-specific constructs: `INTERLEAVE IN PARENT`, `OPTIONS()`, generated columns, `TOKENLIST` types, `HIDDEN` columns. Falls back to regex parser if JSqlParser fails.
- **Status**: JSqlParser added as dependency. `SpannerDdlParser.parse()` used in `QueryService.java` with fallback to legacy regex parser.

---

## Summary by Priority

| Priority | Count | Total Est. Effort |
|----------|-------|-------------------|
| P0 — Immediate fixes | 0 active (3 resolved) | — |
| P1 — Deferred features | 12 | 16-26 weeks |
| P2 — Missing implementations | 1 active (6 resolved) | Remaining: 1-2 weeks |
| P3 — Deferred tests | 2 | 2-3 days |
| P4 — Architectural concerns | 3 active (3 resolved) | Ongoing |
| **Total** | **18 active** (12 resolved) | |

### Resolved Items
- ✅ **1.1** Bigtable mutation handler — already implemented via `BigtableGrpcClient`
- ✅ **1.2** Flask references in design docs — updated to Armeria
- ✅ **1.3** Stale architecture description — updated to "Solid.js + Armeria Java gateway"
- ✅ **3.1** Pub/Sub message browsing — implemented in `BrowseService.java` via ephemeral subscriptions
- ✅ **3.2** Firestore seed fix — added emulator readiness retry loop in `SeedService.java`
- ✅ **3.3** BigQuery/Spanner UI mutation buttons — already implemented in DataBrowser.jsx
- ✅ **3.4** Cloud Tasks queue creation — already implemented in MutateService.java
- ✅ **3.5** Request body inspection — extended `RequestLogger.java` with traceId, body capture, truncation
- ✅ **5.1** Polling loops — made configurable + page-aware (Logs, Usage), removed redundant DataBrowser polling
- ✅ **5.5** Spanner LevelDB race — added backup/restore mechanism + graceful shutdown in supervisord
- ✅ **5.6** Regex DDL parsing — replaced with JSqlParser 5.0 in `SpannerDdlParser.java`

---

## Dependencies Between Items

```
2.10 (Replace gcloud emulators) ──depends-on──> 2.11 (Bigtable SQLite→PG)
2.3 (gRPC remote proxy) ──enables──> Full hybrid connectivity for all 14 services
5.1 (Unified event bus) ──replaces──> All 30s polling loops ~~(partially resolved: made configurable)~~
2.1 (REST transcoding) ──prerequisite──> 2.2 (Compute/CloudRun/GKE CRUD)
5.6 (SQL parser) ──improves──> Spanner schema extraction ~~(resolved: JSqlParser)~~
```

---

## Cross-Cutting Observations

1. **PostgreSQL as single source of truth** — Consistent across all designs. Good foundation.
2. **Seed data idempotency** — Addressed well in `seed-data-and-data-browser-crud`. Apply this principle to `gcp-workflow-migration` workflow imports too.
3. **Subsumed changes** — `service-routing-indicator` is subsumed by `hybrid-cloud-connectivity`. `settings-tabbed-layout` and `settings-content-cleanup` are intimately coupled. Consider merging these to avoid duplicate PRs.
4. **Two stub changes** — `fix-data-browser-per-service` and `remote-cloud-browser` in `localcloud-console-gemini/openspec/` only have `.openspec.yaml` files with no design/proposal/tasks. These appear to be abandoned or duplicated by root-level changes.
