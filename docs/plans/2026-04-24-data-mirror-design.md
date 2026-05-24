# Data Mirror — Sync Production Data to LocalCloud

**Date:** 2026-04-24
**Status:** Approved
**Author:** Jay Senjaliya + Claude

## Problem

LocalCloud emulates GCP services locally with mock/seed data. Researchers need to query real production data for ad-hoc analysis. Currently they hit production directly — expensive (BigQuery $5/TB scan, Firestore per-doc reads) and wasteful for iterative research where the same dataset gets queried hundreds of times with different queries.

## Solution

**Data Mirror** — explicit sync of production data subsets into LocalCloud emulators, then 100% local querying at $0.

Researcher connects to GCP project via console UI, browses remote data, syncs filtered subsets into local emulators with one click. After sync, all existing query tools (SQL Editor, Data Explorer, client SDKs) work unchanged against synced data.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                    Researcher                         │
│             Console UI / Existing Tools               │
└───────────────┬───────────────────┬──────────────────┘
                │ sync (one-time)   │ query (free, unlimited)
                ▼                   ▼
┌───────────────────────┐  ┌───────────────────────────┐
│   SyncService         │  │   Existing Emulators       │
│   (new component)     │  │   BigQuery → DuckDB        │
│                       │  │   Spanner → emulator       │
│   CredentialBroker ───┤  │   Firestore → emulator     │
│   + SyncAdapters      │  │   GCS → filesystem         │
│                       │  │   Bigtable → emulator      │
└───────────┬───────────┘  └───────────────────────────┘
            │ one-time pull (read-only)
            ▼
┌───────────────────────┐
│   Real GCP Project    │
│   (production)        │
└───────────────────────┘
```

Key principle: sync is a data movement step separate from querying. Once data lands in emulators, everything works unchanged.

## Target Services

All five data services:
- **BigQuery** — biggest cost saver ($5/TB scan)
- **Firestore** — per-doc read charges
- **Cloud Storage** — per-operation + egress charges
- **Spanner** — per-read charges
- **Bigtable** — per-row read charges

## Sync Mechanics

### Single Command, No Background Jobs

Sync is researcher-initiated, runs to completion, no scheduler or queue needed.

### Per-Service Data Flow

**BigQuery:**
1. Build SELECT query with user's WHERE filters and LIMIT
2. Call BQ REST API `jobs.query` (paginated, maxResults=10000)
3. Auto-create local dataset + table with matching schema if missing
4. Insert rows into local BQ emulator (DuckDB) via emulator insert API
5. Record manifest

**Firestore:**
1. Build structured query with field filters and limit
2. Stream documents via `documents:runQuery` REST API in batches of 500
3. Write each doc to local Firestore emulator via gRPC BatchWrite
4. Record manifest

**GCS:**
1. List objects matching prefix via GCS JSON API
2. Download each object to local filesystem
3. Register bucket + objects in local GCS emulator
4. Record manifest

**Spanner:**
1. Read DDL from source to auto-create matching schema locally
2. Execute SQL read with filters via Spanner REST API
3. Insert rows into local Spanner emulator in batches
4. Record manifest

**Bigtable:**
1. Read table metadata + column families
2. Read rows matching row prefix filter
3. Write to local Bigtable emulator
4. Record manifest

### Filtered Subsets

Users specify filters per-service type:
- BigQuery/Spanner: schema-aware column filters (column + operator + value) + row limit
- Firestore: field filters + document limit
- GCS: prefix + file type + max files
- Bigtable: row prefix + column family selection + row limit

### Pre-Sync Cost Estimate

Before executing, system shows estimated cost:
- BigQuery: uses `dryRun: true` (returns `totalBytesProcessed` without scanning)
- Firestore/GCS/Bigtable/Spanner: count queries for estimation
- Configurable cost ceiling (default $1.00 per sync) — blocks if estimate exceeds

## Console UI — Per-Service Remote Sync Tab

### Tab Placement

New "Remote Sync" tab added to each service page alongside existing Data Explorer and SQL Editor tabs.

```
[ Data Explorer ]  [ SQL Editor ]  [ Remote Sync ]
```

### Split Layout

**Left panel** — three stacked sections:

1. **Remote Explorer** (top) — navigational tree tagged with ☁ icon
   - BigQuery: dataset → tables
   - Firestore: collections → subcollections
   - GCS: buckets → prefix folders
   - Spanner: instance → database → tables
   - Bigtable: instance → tables → column families
   - Metadata inline: row count, size, last modified
   - Badges: ✓ synced, ⚠ stale (>24h), 🔒 no access

2. **Schema** (middle) — columns, types, constraints for selected resource
   - Updates on selection change
   - Same display as SQL Editor schema panel

3. **Sync History** (bottom) — past syncs for this service
   - Resource name, when synced, row count, full/filtered status
   - Click entry → right panel shows details

**Right panel** — context-sensitive content:

- **Table selected:** preview (first 5 rows) + "Sync to Local" button
- **"Sync to Local" clicked:** filter form with schema-aware column dropdowns, operator matching column type, row limit, live cost estimate
- **Sync running:** progress bar with rows transferred, bytes, elapsed, ETA, cancel button
- **History entry clicked:** sync details (filter, rows, cost, timestamp) + Resync / Remove buttons
- **Not connected:** auth options inline (Sign in with Google / Upload Service Account)

### Shared SchemaExplorer Component

Single reusable component across all three tabs:

```
SchemaExplorer(source: "local" | "remote", serviceId, onSelect)
```

- `source="local"` → calls existing `/browse/{service}` APIs
- `source="remote"` → calls new `/sync/{service}/browse` API
- Same tree structure, expand/collapse, schema display
- Only visual difference: source badge and sync-related badges
- Both sources return same JSON response shape

### Filter Builder

Schema-aware filter UI:
- Column dropdown populated from actual remote schema
- Operator dropdown changes per column type:
  - STRING: `=`, `!=`, `LIKE`, `IN`
  - TIMESTAMP/DATE: `>=`, `<=`, `=`, `BETWEEN`
  - INTEGER/FLOAT: `=`, `!=`, `>`, `<`, `>=`, `<=`
  - BOOL: `=`
- Multiple filters combined with AND
- Cost estimate auto-refreshes on filter change (debounced 500ms)

## Authentication — UI-Only

No new CLI commands. Everything managed through console UI.

### Method 1: Google OAuth (recommended)

1. User clicks "Sign in with Google" in console
2. Server generates OAuth URL with `cloud-platform.read-only` scope
3. Console opens Google sign-in in new tab
4. User authenticates, Google redirects to `localhost:8080/sync/auth/callback`
5. Server exchanges code for access_token + refresh_token, stores encrypted
6. Console auto-updates to connected state
7. Project dropdown auto-populated via Cloud Resource Manager API

### Method 2: Service Account Key Upload

- Drag & drop or file browser for JSON key
- Project extracted from key's `project_id` field
- Key stored encrypted (AES-256) in `sync_credentials` table

### Auth Accessible From

- Settings > Remote Connection (global config)
- Remote Sync tab inline (when not connected) — no redirect to Settings needed

### Token Lifecycle

- Access token: 1h expiry, auto-refreshes via refresh_token
- Console polls auth status every 5m, updates header badge
- If refresh fails: re-auth banner shown inline

### Read-Only Enforcement

- OAuth scope: `cloud-platform.read-only`
- SyncAdapters only use GET/query operations — no writes to production
- Recommended IAM roles shown when permission errors occur:
  - BigQuery: `roles/bigquery.dataViewer` + `roles/bigquery.jobUser`
  - Firestore: `roles/datastore.viewer`
  - GCS: `roles/storage.objectViewer`
  - Spanner: `roles/spanner.databaseReader`
  - Bigtable: `roles/bigtable.reader`

## Backend Components

### New Java Classes

```
com.localcloud.sync/
  SyncService.java              — orchestrator: estimate, execute, track
  SyncManifestRepository.java   — CRUD for sync_manifests table
  SyncCredentialRepository.java — stores remote credentials (encrypted)

  adapters/
    SyncAdapter.java            — interface all adapters implement
    BigQuerySyncAdapter.java    — BQ REST API → DuckDB emulator
    FirestoreSyncAdapter.java   — Firestore REST → Firestore emulator
    GcsSyncAdapter.java         — GCS JSON API → local filesystem
    SpannerSyncAdapter.java     — Spanner REST → Spanner emulator
    BigtableSyncAdapter.java    — Bigtable REST → Bigtable emulator
```

### SyncAdapter Interface

```java
public interface SyncAdapter {
    BrowseResult browseRemote(String project, String accessToken);
    PreviewResult previewRemote(String project, String resource,
                                String accessToken, int limit);
    CostEstimate estimate(String project, String resource,
                          List<SyncFilter> filters, int rowLimit,
                          String accessToken);
    SyncResult sync(String project, String resource,
                    List<SyncFilter> filters, int rowLimit,
                    String accessToken, SyncProgressCallback progress);
}
```

### SyncFilter

```java
public record SyncFilter(
    String column,
    String operator,
    String value,
    String columnType
) {}
```

### PostgreSQL Schema

```sql
CREATE TABLE sync_manifests (
    id              SERIAL PRIMARY KEY,
    project_id      VARCHAR(255) NOT NULL,
    service_id      VARCHAR(50) NOT NULL,
    resource_path   VARCHAR(500) NOT NULL,
    source_project  VARCHAR(255) NOT NULL,
    filters_json    JSONB,
    row_count       BIGINT,
    bytes_synced    BIGINT,
    estimated_cost  DECIMAL(10,6),
    status          VARCHAR(20) DEFAULT 'completed',
    error_message   TEXT,
    synced_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, service_id, resource_path)
);

CREATE TABLE sync_credentials (
    id              SERIAL PRIMARY KEY,
    project_id      VARCHAR(255) NOT NULL,
    source_project  VARCHAR(255) NOT NULL,
    auth_method     VARCHAR(20) NOT NULL,
    credential_data TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, source_project)
);
```

### Admin API Endpoints

```
# Auth
POST /sync/auth/start          — generate OAuth URL
GET  /sync/auth/callback        — OAuth redirect handler
POST /sync/auth/upload-key      — upload SA JSON key
GET  /sync/auth/status          — connection status
GET  /sync/auth/projects        — list accessible GCP projects
POST /sync/auth/disconnect      — clear credentials
POST /sync/auth/refresh         — force token refresh

# Remote browsing (feeds SchemaExplorer source="remote")
GET  /sync/{service}/browse     — list remote resources
GET  /sync/{service}/preview    — preview rows/docs
GET  /sync/{service}/schema     — get schema for resource

# Sync operations
POST /sync/{service}/estimate   — dry-run cost estimate
POST /sync/{service}/start      — execute sync
GET  /sync/{service}/progress   — poll sync progress (SSE)
POST /sync/{service}/cancel     — cancel running sync

# History
GET  /sync/manifests            — all sync history
GET  /sync/{service}/manifests   — per-service history
POST /sync/resync/{id}          — re-run previous sync
DELETE /sync/manifests/{id}      — remove synced data + manifest
```

### Progress Tracking

Sync progress streamed via SSE:

```
GET /sync/{service}/progress

event: progress
data: {"rows_transferred": 441200, "rows_estimated": 1047000,
       "bytes_transferred": 374000000, "percent": 42, "elapsed_ms": 83000}

event: complete
data: {"manifest_id": 7, "rows": 1047231, "bytes": 892000000}
```

## Error Handling

### Sync Errors

| Error | Behavior |
|-------|----------|
| Network failure mid-sync | Save partial manifest, offer Resume / Remove |
| Permission denied on resource | Show required role, mark resource 🔒, don't block others |
| Token expired during sync | Auto-refresh. If refresh fails, pause + re-auth prompt |
| Rate limit (429) | Exponential backoff: 2s → 4s → 8s → 16s → 30s cap, max 5 retries |
| Disk full | Pre-check available space, warn if estimate > 80% free |
| Cost ceiling exceeded | Hard block, direct to Settings to raise limit |
| Schema changed since last sync | Warn, offer resync with new schema or keep old |
| Duplicate sync (different filters) | Prompt: Replace existing or Merge |
| Remote resource deleted | Badge warning on synced resource, preserve local data |

### Manifest Status Values

| Status | Meaning |
|--------|---------|
| `completed` | Sync finished, data available locally |
| `partial` | Interrupted, can resume |
| `failed` | Unrecoverable error |
| `in_progress` | Currently running |

### Resume on Failure

Partial syncs track progress in manifest. Resume uses:
- BigQuery: page token
- Firestore: cursor
- GCS: skip existing objects
- Spanner: offset
- Bigtable: last row key

## Security

### Cost Protection
- Mandatory cost estimate before sync
- Configurable cost ceiling (default $1.00)
- Running total tracked across all syncs

### Data Safety
- Read-only OAuth scope — cannot write to production
- Synced data shares lifecycle with seed data — `localcloud reset` clears all
- No PII in logs — only table names and row counts
- Credentials encrypted at rest (AES-256)
- Credential secrets never returned via API

## Testing Strategy

### Unit Tests (~66 tests)

- **SyncAdapter tests** (~40): mock GCP API responses, verify local emulator writes, filter application, pagination, error handling, resume logic — 8 tests per adapter × 5 adapters
- **SyncService tests** (~10): orchestration, cost ceiling enforcement, replace/merge prompts, progress callbacks
- **Repository tests** (~8): manifest CRUD, upsert semantics, credential encryption
- **Auth tests** (~8): OAuth URL generation, code exchange, token refresh, SA key validation

### Integration Tests (~10 tests)

Spin up two instances of each emulator — one "remote" source, one "local" destination. Verify end-to-end data movement without touching real GCP.

Mock Google OAuth endpoints with WireMock for auth flow testing.

### Console Frontend Tests (~20 tests)

- SchemaExplorer: local/remote source rendering, badges, selection callbacks
- RemoteSyncTab: auth states, preview, filter form, progress, history
- SyncFilterBuilder: column/operator dropdowns per type, add/remove filters

### Test Count

| Category | Count |
|----------|-------|
| New unit tests | ~66 |
| New integration tests | ~10 |
| New console tests | ~20 |
| **New total** | **~96** |
| Existing tests | 187 |
| **Project total** | **~283** |
