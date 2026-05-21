# Spanner Emulator Extended — Feature Comparison

> **Why this fork is better than the official Google Cloud Spanner Emulator**

This document catalogues every feature improvement in the
`jaysen2apache/spanner-emulator-extended` fork (branch `jay-33-persistence`)
compared to the upstream `gcr.io/cloud-spanner-emulator` image.

---

## 1. Data Persistence (LevelDB Backend)

**Upstream gap:** The official emulator is entirely in-memory. Every restart
discards all data, schemas, instances, and databases. You cannot restart the
container without full re-provisioning.

**This fork:** Full LevelDB-backed persistent storage with multi-version
concurrency control. Activated via `--data_dir=/path`.

### What persists across restarts

| Artifact | Persistence Mechanism |
|----------|----------------------|
| Row data (all tables) | LevelDB with microsecond-precision timestamps |
| Instance metadata | `metadata.json` (atomic write-tmp-then-rename) |
| Database metadata | `metadata.json` (dialect, DDL statements) |
| ID generator counters | `metadata.json` (table_id, column_id, change_stream_id, sequence_id, named_schema_id) |

### Key design decisions

- **Sort-order-preserving key encoding** — LevelDB keys are structured as
  `{table_id}{encoded_key}{column_id}{timestamp}` so range scans preserve
  Spanner's sort order.
- **Multi-version cells** — Each cell write stores a new version at the
  current timestamp. Reads seek to the latest version at or before the read
  timestamp, matching Cloud Spanner's MVCC semantics.
- **Thread safety** — `PersistentStorage` uses `absl::Mutex` for read/write
  locking. Reads use `ReaderMutexLock` for concurrent access.
- **Automatic cleanup** — Dropped tables/columns are garbage-collected after
  the version retention period (default 1 hour).

### Usage

```shell
# Without persistence (in-memory, same as upstream)
docker run -p 9010:9010 -p 9020:9020 jaysen2apache/spanner-emulator-extended

# With persistence
docker run -p 9010:9010 -p 9020:9020 \
  -v /path/to/data:/data \
  jaysen2apache/spanner-emulator-extended \
  --data_dir=/data
```

When `--data_dir` is empty (default), the emulator runs in in-memory mode
identical to upstream. Zero behaviour change for existing users.

---

## 2. Metadata Persistence

**Upstream gap:** No instance or database state survives restarts. Creating
instances and databases is a manual step every time.

**This fork:** A `MetadataStore` (JSON file + atomic writes) tracks:

- **Instances:** display_name, config, processing_units, labels, create_time
- **Databases:** dialect (GOOGLE_STANDARD_SQL or POSTGRESQL), DDL statements
- **ID counters:** table_id, column_id, change_stream_id, sequence_id,
  named_schema_id

On startup, `RestoreFromMetadata()` reconstructs the full state — no seed
scripts needed.

### Architecture

```
emulator_main.cc
  └─ RestoreFromMetadata()
       ├─ Reads metadata.json
       ├─ Calls CreateInstance() for each instance
       ├─ Calls CreateDatabase() for each database
       └─ Seeds ID generators to prevent collisions
```

Changes are persisted immediately on every mutation (CreateInstance,
CreateDatabase, UpdateDatabaseDdl, DropDatabase, DropInstance).

---

## 3. ID Generator Persistence

**Upstream gap:** After restoring data from LevelDB, the in-memory ID
generators start from zero. This means new schema objects (tables, columns,
change streams) receive IDs that collide with existing IDs in LevelDB,
corrupting the database.

**This fork:** ID generator counters are persisted in `metadata.json` and
restored on startup:

- `table_id` — unique table identifier
- `column_id` — unique column identifier
- `change_stream_id` — unique change stream identifier
- `sequence_id` — unique sequence identifier
- `named_schema_id` — unique named schema identifier

Each `UniqueIdGenerator` supports `Seed()` and `GetIdCounterValues()` methods.
The counters advance from their persisted values so new objects never collide.

---

## 4. OPTIMIZER_VERSION Statement Hint

**Upstream gap:** Production Spanner queries using
`@{OPTIMIZER_VERSION=latest}` fail on the emulator with _"invalid hint"_.

**This fork:** `optimizer_version` added to the hint whitelist in
`query_validator.cc`. Accepts both STRING (`latest`) and INT64 (`7`) values.
The hint is silently ignored (the emulator has no optimizer versioning), but
production queries run without modification.

**Code change:** +3 lines in `backend/query/query_validator.cc`.

---

## 5. Full-Text Search: `remove_diacritics` Parameter

**Upstream gap:** `TOKENIZE_FULLTEXT()` function signature is missing the
`remove_diacritics` boolean parameter. Production schemas using diacritic-
insensitive full-text indexing fail to create.

**This fork:** `remove_diacritics` parameter added to the
`TOKENIZE_FULLTEXT` function signature in
`backend/query/search/search_function_catalog.cc`. Enables:

```sql
TOKENIZE_FULLTEXT(NameSubstring, remove_diacritics => TRUE)
```

This was the **last remaining gap** in the full-text search emulation
proposal — all other FTS features already worked in the upstream base.

---

## 6. PostgreSQL Dialect Improvements

**Upstream gap:** `pg_catalog.pg_proc` table does not include user-defined
functions (UDFs), making PostgreSQL dialect tools less functional.

**This fork:**

- **UDFs in `pg_proc`**: UDFs are now discoverable through the PostgreSQL
  catalog. Added `BuildPgProcsFromUDF()` and integration into
  `FillPGProcTable()` in `pg_catalog.cc`.
- **UDF schema/namespace OID resolution**: Named schemas support UDF
  registration with proper namespace OID mapping.
- **PostgreSQL-style function lookup**: `GetProcsByNameFromUserCatalog()`
  now falls back to UDF lookup when no built-in function matches.

Files changed: `pg_catalog.cc`, `catalog_wrappers.cc`, `spangres_catalog.cc`

---

## 7. Property Graph Support

**Upstream gap:** Property Graph DDL (used by GraphSpanner) has limited
schema builder support.

**This fork:**

- `property_graph_builder.h` — expanded builder with better field support
- `prepare_property_graph_catalog.h` — refactored for cleaner integration
- DDL parser support for property graph statements
- Test coverage for property graph schemas

---

## 8. Change Stream Improvements

**Upstream gap:** Change stream tracking has edge cases with unpopulated
columns and data change record construction.

**This fork:**

- **Unpopulated column handling** — Fixed `GetNewValuesForDataChangeRecord()`
  to correctly merge populated and existing values for tracked columns
- **CRC32C GC improvements** — Better garbage collection for change stream
  CRC32C tracking
- **New test coverage** — 118+ lines of new tests in
  `change_stream_test.cc`, 30+ lines in schema updater tests

---

## 9. Enhanced Information Schema & System Catalog

**Upstream gap:** The `information_schema` and system catalogs lack entries
for certain schema objects.

**This fork:**

- `information_schema_catalog.cc` — added 24+ lines of additional catalog
  entries
- `database_options_builder.h` / `database_options.h` — new database option
  schema support
- `udf.h` — enhanced UDF catalog with determinism tracking
- `index.h` — index catalog improvements for JSON/numeric indexes
- Better schema attribute coverage for `pg_catalog`

---

## 10. Query History (LocalCloud Facade)

**Upstream gap:** No record of executed queries. If a query fails, there is no
way to see what was attempted or when.

**This fork:** Spanner queries are recorded in PostgreSQL by the LocalCloud
gateway facade. Each entry captures:

| Field | Description |
|-------|-------------|
| `sql` | The executed SQL statement |
| `success` | Boolean outcome |
| `duration_ms` | Execution duration in milliseconds |
| `row_count` | Number of rows returned |
| `error_message` | Error details on failure |
| `instance` / `database` | Target identifiers |
| `executed_at` | ISO 8601 timestamp |

**API Endpoint:**
```
GET /_localcloud/query-history?service=spanner&limit=50&offset=0
```

**Console:** A "History" sub-tab in the Spanner data browser shows a sortable
table (SQL, duration, rows, status badge) with a "Rerun" button to re-execute
any successful query.

---

## 11. IAM Policy Stubs (LocalCloud Facade)

**Upstream gap:** The official emulator has no IAM implementation. SDK code
that calls `getIamPolicy()` or `testIamPermissions()` before accessing Spanner
resources throws `Method not found` errors.

**This fork:** A lightweight IAM stub service (`SpannerIamService.java`)
intercepts IAM calls at the gateway level and returns permissive responses:

| Endpoint | Behavior |
|----------|----------|
| `POST /v1/projects/{p}/instances/{i}:setIamPolicy` | Echoes request policy back (no-op) |
| `POST /v1/projects/{p}/instances/{i}/databases/{d}:setIamPolicy` | Echoes request policy back (no-op) |
| `GET /v1/projects/{p}/instances/{i}:getIamPolicy` | Returns default permissive policy (role: `roles/spanner.admin`) |
| `GET /v1/projects/{p}/instances/{i}/databases/{d}:getIamPolicy` | Returns default permissive policy (role: `roles/spanner.admin`) |
| `POST /v1/projects/{p}/instances/{i}:testIamPermissions` | Grants all requested permissions |
| `POST /v1/projects/{p}/instances/{i}/databases/{d}:testIamPermissions` | Grants all requested permissions |

This is a permissive stub — all calls succeed and all requested permissions are
granted. It is designed to unblock SDK code that requires IAM checks to
proceed, not to enforce actual authorization rules.

---

## 12. GraphQL API (LocalCloud Facade)

**Upstream gap:** No graph-based query interface. Developers who want to
explore emulator state must use either the REST API or SDK.

**This fork:** A unified GraphQL endpoint at `/graphql` using
`armeria-graphql` (wrapping `graphql-java`). The schema provides a single
query entry point across all emulated services:

```graphql
type Query {
  spanner: SpannerQueries
  bigquery: BigQueryQueries
  logging(limit: Int, severity: String): LoggingQueries
  monitoring: MonitoringQueries
  queryHistory(limit: Int, offset: Int): [QueryHistoryEntry]
}
```

### Queryable Resources

| Service | Operations |
|---------|-----------|
| **Spanner** | `instances`, `databases(instance)`, `tables(instance, database)` |
| **BigQuery** | `datasets`, `tables(datasetId)` |
| **Logging** | `entries(limit, severity)` |
| **Monitoring** | `metricTypes`, `timeSeries(metricType)` |
| **QueryHistory** | `queryHistory(limit, offset)` — persisted SQL history across services |

The GraphQL gateway proxies to each emulator's REST API for data fetching.
All data fetchers run on a blocking executor because they make HTTP calls
to external emulator processes. WebSocket subscriptions are supported via
the graphql-protocol (built into armeria-graphql).

---

## 13. System Insights: Per-Database Storage Stats

**Upstream gap:** No visibility into database schema composition (table count,
index distribution) without parsing DDL manually.

**This fork:** A stats endpoint computes database metrics by parsing the
emulator's DDL response. Accessible via:

```
GET /_localcloud/browse/spanner/instances/{instance}/{database}/stats
```

**Response:**
```json
{
  "database": "my-db",
  "instance": "my-instance",
  "tableCount": 5,
  "indexCount": 7,
  "searchIndexCount": 1,
  "vectorIndexCount": 0,
  "totalObjects": 13,
  "details": [
    { "type": "TABLE", "name": "Users", "columnCount": 8, "hasInterleaved": false },
    { "type": "INDEX", "name": "UsersByName" },
    { "type": "SEARCH_INDEX", "name": "UsersSearchIdx" },
    ...
  ]
}
```

**Console:** A "Stats" sub-tab in the Spanner data browser displays summary
cards (Tables, Indexes, Search Indexes, Vector Indexes, Total Objects) plus
a detail table listing every object with its type and properties.

---

## 14. Integrated Web Console (via LocalCloud)

**Upstream:** CLI-only. No graphical interface.

**This fork:** Full graphical management through the LocalCloud web console
(running on port 8080):

| Feature | Console Capability |
|---------|-------------------|
| Instance management | Create, delete, and browse Spanner instances |
| Database management | Create, delete, list databases per instance |
| Table browser | Browse table schemas, columns, and indexes |
| Data viewer | View, paginate, and search table data |
| Data editor | Add, edit, and delete individual rows |
| Bulk import | CSV import with type-aware SQL escaping |
| Query runner | Execute arbitrary GoogleSQL queries |
| SQL editor | CodeMirror-based with Spanner SQL syntax highlighting |
| Schema DDL | Create tables and manage DDL through a modal editor |
| Instance tree | Hierarchical navigation (instance → database → table) |
| Query history | Browse, filter, and rerun past queries from "History" tab |
| System insights | Database object statistics (table/index/search/vector counts) |
| IAM stubs | SetIamPolicy, GetIamPolicy, TestIamPermissions for Spanner |
| GraphQL endpoint | Unified graph interface at `/graphql` across all services |

The console is served by the localcloud gateway (Armeria) on port 8080 and
communicates with the Spanner emulator via its admin REST API on port 9020.
New features (query history, IAM stubs, GraphQL, stats) are implemented as
Java services in the localcloud-server facade, not in the C++ emulator.

---

## 15. Build & CI/CD Improvements

| Feature | Upstream | This Fork |
|---------|----------|-----------|
| **Compiler** | GCC 8.4 (Ubuntu 18.04) | GCC 12 |
| **Multi-arch Docker** | linux/amd64 only | linux/amd64 + linux/arm64 |
| **Docker publishing** | Manual, no CI | Automated via GitHub Actions |
| **Docker image** | `gcr.io/cloud-spanner-emulator` | `jaysen2apache/spanner-emulator-extended` |
| **Build cache** | Full rebuild every time | BuildKit cache mounts (~2 min rebuild) |
| **OOM prevention** | None | Per-file `-O1` for heavy ZetaSQL files |
| **Offline build** | None | `build-offline.sh` + `fetch_workspace_deps.sh` |

### GCC 12 Compatibility

- Fixed `NoDestructor` initialization ambiguity in 3 files
- Fixed `optional` include for `<optional>` header
- Fixed designated initializer patterns
- Upgraded Go toolchain from 1.23.6 to 1.24.13

### Build Performance

- BAZEL_JOBS=4 and BAZEL_RAM=50% for stability
- APT retries for transient network failures
- Per-file copt patterns for Bazel 6 compatibility

---

## 16. Data Integrity Fixes

**Upstream gap:** Several edge cases in data read and write operations.

**This fork:**

- **Data read exclusion bug** — Fixed row exclusion logic in
  `persistent_storage.cc` where certain key ranges would incorrectly exclude
  valid rows. Full test coverage added.
- **Value codec bounds checking** — Added length validation in
  `value_codec.cc` to prevent out-of-bounds reads on corrupt data. Checks
  added for STRING, BYTES, NUMERIC, and JSON decodes.
- **Negative length guard** — Added `if (len < 0)` checks in value decode
  paths to prevent undefined behaviour from malformed data.
- **Timestamp precision** — Documented microsecond precision for LevelDB
  timestamps vs. nanosecond precision for in-memory storage.
- **UniqueIdGenerator move assignment** — Fixed deleted move assignment
  operator caused by `absl::Mutex` member.
- **Mutex usage in const accessors** — Fixed pattern to match codebase
  conventions.

---

## 17. Summary: Gap Closure Table

| Feature | Upstream | This Fork | Delivered By |
|---------|----------|-----------|-------------|
| Data persistence | In-memory only | LevelDB + JSON metadata | C++ fork |
| Multi-version storage | In-memory | LevelDB with MVCC | C++ fork |
| Instance/database recovery | None | Automatic from metadata.json | C++ fork |
| ID counter persistence | None | Persisted + restored | C++ fork |
| OPTIMIZER_VERSION hint | Fails with error | Accepted and ignored | C++ fork |
| TOKENIZE_FULLTEXT remove_diacritics | Missing parameter | Supported | C++ fork |
| PostgreSQL pg_proc UDFs | Missing | Supported | C++ fork |
| Property Graph DDL | Limited | Expanded | C++ fork |
| Change stream tracking | Bugs | Fixed + tests | C++ fork |
| Information schema | Incomplete | Enhanced | C++ fork |
| **Query history** | None | Recorded in PostgreSQL, browsable via console | LocalCloud facade |
| **IAM policy stubs** | None | SetIamPolicy/GetIamPolicy/TestIamPermissions (permissive) | LocalCloud facade |
| **GraphQL API** | None | Unified `/graphql` endpoint across all services | LocalCloud facade |
| **System Insights** | None | Per-database object stats (tables, indexes, search/vector) | LocalCloud facade |
| Web console | CLI-only | Full UI via LocalCloud | LocalCloud facade |
| Multi-arch support | amd64 only | amd64 + arm64 | C++ fork |
| Build cache | None | BuildKit (~2 min rebuild) | C++ fork |
| GCC version | 8.4 | 12 | C++ fork |
| Docker CI/CD | Manual | Automated multi-arch publishing | C++ fork |
| Offline build | None | Supported (corporate proxy) | C++ fork |
