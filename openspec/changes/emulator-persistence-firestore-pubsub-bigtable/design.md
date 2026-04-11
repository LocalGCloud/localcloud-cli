## Context

LocalCloud has 11 database services. After enabling persistence for Spanner (LevelDB fork) and BigQuery (SQLite `--database`), 8 services persist across restarts. Three remain in-memory: Firestore, Pub/Sub, and Bigtable. All three use Google's closed-source gcloud emulator binaries with no persistence flags.

Current persistence status:
```
PERSISTENT:  GCS (filesystem), Spanner (LevelDB), BigQuery (SQLite),
             Secret Manager, Cloud Tasks, Logging, Monitoring,
             Memorystore (all PostgreSQL)
EPHEMERAL:   Firestore, Pub/Sub, Bigtable (gcloud in-memory emulators)
```

## Goals / Non-Goals

**Goals:**
- All 11 database services persist across Docker restarts (Phase 1)
- Minimize risk to SDK compatibility
- Use the least-effort approach that's production-reliable

**Non-Goals:**
- Replacing all gcloud emulators with facades (Phase 2, separate effort)
- Message-level persistence for Pub/Sub (messages are transient by nature)
- Real-time listeners for Firestore (Listen/Write streaming RPCs)

## Decisions

### D1: Firestore — REST export/import with `--seed_from_export`

**Approach**: Periodic REST-based document export + JAR's native import flag.

```
┌─────────────────────────────────────────────────────────────┐
│                    Docker Container                          │
│                                                             │
│  Firestore Emulator JAR (port 8086)                        │
│       │                                                     │
│       │ --seed_from_export=/var/lib/localcloud/firestore/   │
│       │     (loads on startup)                              │
│       │                                                     │
│  FirestorePersistenceService (Java, in gateway)            │
│       │                                                     │
│       ├─ Every 30s: GET all docs via REST API              │
│       └─ Write to /var/lib/localcloud/firestore/export/    │
│                                                             │
│  /var/lib/localcloud/firestore/  ← Docker volume           │
│       └── export/                                           │
│           ├── .overall_export_metadata                      │
│           └── firestore_export/                             │
│               └── all_namespaces/                           │
│                   └── kind_<collection>/                    │
│                       └── all_namespaces_kind_<coll>.export │
└─────────────────────────────────────────────────────────────┘
```

**Export mechanism**: The Firestore emulator exposes an internal clear endpoint (`DELETE /emulator/v1/projects/{project}/databases/(default)/documents`). It likely also has an export endpoint accessible via the Firebase CLI hub pattern. If the internal export endpoint is not discoverable, fall back to REST-based document traversal:

1. List all collections via `POST /v1/{parent}:runQuery` or `ListCollectionIds`
2. For each collection, `GET /v1/{parent}/documents/{collectionId}` recursively
3. Serialize all documents to JSON files on the Docker volume
4. On startup, import via the seed mechanism (`seedFirestore()` in SeedService)

**Alternative if `--seed_from_export` format is too complex**: Skip the binary export format entirely. Use the simpler approach of exporting documents as JSON via REST, then re-importing via `PATCH` calls on startup (which SeedService already does). This is less efficient but guaranteed to work.

**Why not Firebase CLI**: Adding Node.js to the Docker image just for Firebase CLI is heavyweight. The JAR's `--seed_from_export` flag works without the CLI if we can produce the export format.

### D2: Pub/Sub — PostgreSQL config sync with gcloud emulator

**Approach**: Keep gcloud emulator for message processing. Mirror topic/subscription config to PostgreSQL. Restore on startup.

```
┌─────────────────────────────────────────────────────────────┐
│                    Docker Container                          │
│                                                             │
│  gcloud Pub/Sub Emulator (port 8085)                       │
│       │                                                     │
│       │ handles all gRPC (publish, pull, StreamingPull)     │
│       │                                                     │
│  PubSubPersistenceSync (Java, background thread)           │
│       │                                                     │
│       ├─ Every 30s: GET /v1/projects/.../topics             │
│       ├─ Every 30s: GET /v1/projects/.../subscriptions      │
│       └─ Upsert to PostgreSQL tables                       │
│                                                             │
│  PostgreSQL (port 5432)                                     │
│       ├── pubsub_topics (project_id, topic_name, config)   │
│       └── pubsub_subscriptions (project_id, sub_name, ...) │
│                                                             │
│  On startup:                                                │
│       1. Wait for Pub/Sub emulator ready                   │
│       2. Read topics/subs from PostgreSQL                  │
│       3. PUT /v1/.../topics/{name} for each topic          │
│       4. PUT /v1/.../subscriptions/{name} for each sub     │
│       5. Open gateway for traffic                          │
└─────────────────────────────────────────────────────────────┘
```

**PostgreSQL schema**:
```sql
CREATE TABLE pubsub_topics (
    project_id VARCHAR(255) NOT NULL,
    topic_name VARCHAR(255) NOT NULL,
    labels JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, topic_name)
);

CREATE TABLE pubsub_subscriptions (
    project_id VARCHAR(255) NOT NULL,
    subscription_name VARCHAR(255) NOT NULL,
    topic_name VARCHAR(255) NOT NULL,
    ack_deadline_seconds INT DEFAULT 10,
    push_config JSONB DEFAULT '{}',
    retain_acked_messages BOOLEAN DEFAULT FALSE,
    message_retention_duration VARCHAR(50) DEFAULT '604800s',
    filter TEXT DEFAULT '',
    dead_letter_policy JSONB,
    retry_policy JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, subscription_name)
);
```

**Why hybrid over full facade**: The Pub/Sub emulator's StreamingPull implementation is extremely complex (bidirectional streaming, flow control, ack deadline management, message redelivery). Reimplementing this in Java would take 3-5 weeks and risk subtle behavioral differences. The hybrid approach gets config persistence in 3 days with zero risk to message processing correctness.

### D3: Bigtable — Replace cbtemulator with little_bigtable

**Approach**: Drop-in replacement with [`bitly/little_bigtable`](https://github.com/bitly/little_bigtable), an open-source fork of Google's `bttest` that adds SQLite persistence.

```
┌─────────────────────────────────────────────────────────────┐
│                    Docker Container                          │
│                                                             │
│  little_bigtable (port 8087, Go binary)                    │
│       │                                                     │
│       │ --db-path=/var/lib/localcloud/bigtable-data/bt.db  │
│       │                                                     │
│       │ Same gRPC API as cbtemulator                       │
│       │ SQLite3 file on Docker volume                      │
│                                                             │
│  /var/lib/localcloud/bigtable-data/                        │
│       └── bt.db  (SQLite database)                         │
└─────────────────────────────────────────────────────────────┘
```

**Build considerations**: `little_bigtable` is a Go binary. Two options for the Docker image:
1. **Multi-stage build**: Add a Go build stage that compiles `little_bigtable` from source
2. **Pre-built binary**: Download the release binary from GitHub (available for linux/amd64 and linux/arm64)

Option 2 (pre-built binary) is simpler and avoids adding Go toolchain to the Docker build.

**Impact on Data Browser**: The existing `BrowseService.browseBigtable()` and `MutateService.mutateBigtable()` currently read/write the `bigtable_data` PostgreSQL table. With `little_bigtable`, the real data lives in SQLite. Two options:
1. Rewrite browse/mutate to proxy gRPC calls to `little_bigtable` (more correct, more work)
2. Keep the PostgreSQL browse/mutate as a separate view, sync from SQLite periodically (simpler, less consistent)
3. Remove the PostgreSQL `bigtable_data` table entirely and proxy all browse operations through gRPC (cleanest)

Recommendation: Option 3 — rewrite browse to proxy gRPC, remove the PostgreSQL table. This keeps one source of truth.

### D4: Data directory structure

```
/var/lib/localcloud/           ← Docker volume mount
├── pgdata/                    ← PostgreSQL data
├── gcs-data/                  ← GCS filesystem backend
├── spanner-data/              ← Spanner LevelDB
├── bigquery-data/             ← BigQuery SQLite
│   └── bigquery.db
├── firestore-data/            ← Firestore export (NEW)
│   └── export/
├── bigtable-data/             ← Bigtable SQLite (NEW)
│   └── bigtable.db
└── (pubsub config in PostgreSQL, not on filesystem)
```

## Risks / Trade-offs

**Firestore export format may be undocumented** → The `--seed_from_export` binary format is used by Firebase CLI internally. If it's too complex to produce, fall back to JSON export + REST re-import (SeedService already does this). Slower but guaranteed to work.

**Pub/Sub sync has a 30-second window** → Topics created in the last 30 seconds before a crash won't be persisted. Mitigation: use gateway interceptor for synchronous persistence on admin operations (create/delete). This eliminates the window for operations that go through the LocalCloud gateway.

**little_bigtable is a small project (22 stars)** → Risk of abandonment. Mitigation: it's Apache 2.0 licensed and forkable. The core logic is from Google's own `bttest` package. If the project dies, we can maintain our own fork. Alternatively, skip to Phase 2 (Java facade) if this risk is unacceptable.

**SQLite for Bigtable breaks single-database pattern** → Unlike other facade services that use PostgreSQL, Bigtable data would be in SQLite. This means the Export service can't query Bigtable data from PostgreSQL. Mitigation: phase 2 replaces with PostgreSQL facade, or add a sync layer.

**Firestore REST export is slow for large datasets** → Recursive document traversal via REST is O(n) round-trips. For a typical local dev dataset (<10,000 documents), this takes <5 seconds. Acceptable for periodic export.
