# Feature Specification: Spanner Emulator Storage Extensibility

**Feature Branch**: `003-spanner-storage-extensibility`
**Created**: 2026-04-08
**Status**: In Progress — LevelDB selected as persistent backend, Phases 1-4 complete (T001-T020)
**Input**: User description: "Fork and extend Google Spanner emulator so that we can add more storage mechanisms."

# Cloud Spanner Emulator — Persistent Storage Implementation Spec

## Overview

The Google Cloud Spanner Emulator (`GoogleCloudPlatform/cloud-spanner-emulator`) stores all data in-memory. When the emulator process terminates, all state — instances, databases, schemas, and row data — is lost. This project adds an optional disk-backed storage implementation behind the existing `Storage` abstract interface, enabling data to persist across emulator restarts.

The emulator is maintained as an external git fork. All custom persistence code lives within the fork and is designed to minimize merge conflicts with upstream releases (~monthly cadence from Google).

---

## Architecture Context

### Emulator Layer Structure

The emulator has three layers, each in its own directory:

```
gateway/        → Go REST gateway (grpc-gateway shim)
frontend/       → C++ gRPC frontend (Spanner Admin + Data APIs)
  ├── server/         → ServerEnv, gRPC handler registration
  ├── handlers/       → Per-RPC handler implementations
  ├── entities/       → Instance, Database, Session, Transaction objects
  └── collections/    → InstanceManager, DatabaseManager, SessionManager
backend/        → C++ database engine
  ├── storage/        → Storage interface + InMemoryStorage ← THIS IS THE TARGET
  ├── database/       → Database class (ties subsystems together)
  ├── transaction/    → ReadOnlyTransaction, ReadWriteTransaction
  ├── schema/         → DDL parsing, catalog, versioned schema
  ├── query/          → ZetaSQL query engine integration
  ├── actions/        → Write validators (FK, interleave, unique index)
  ├── datamodel/      → Key, KeyRange, KeySet, Value types
  ├── locking/        → Lock manager
  └── common/         → IDs, utility types
binaries/       → Entry points (emulator_main.cc, gateway_main)
common/         → Config flags, clock, errors
```

### The Storage Interface (ALREADY EXISTS)

**File: `backend/storage/storage.h`**

```cpp
class Storage {
 public:
  virtual ~Storage() {}
  virtual absl::Status Lookup(absl::Time timestamp, const TableID& table_id,
                              const Key& key,
                              const std::vector<ColumnID>& column_ids,
                              std::vector<zetasql::Value>* values) const = 0;
  virtual absl::Status Read(absl::Time timestamp, const TableID& table_id,
                            const KeyRange& key_range,
                            const std::vector<ColumnID>& column_ids,
                            std::unique_ptr<StorageIterator>* itr) const = 0;
  virtual absl::Status Write(absl::Time timestamp, const TableID& table_id,
                             const Key& key,
                             const std::vector<ColumnID>& column_ids,
                             const std::vector<zetasql::Value>& values) = 0;
  virtual absl::Status Delete(absl::Time timestamp, const TableID& table_id,
                              const KeyRange& key_range) = 0;
  virtual void SetVersionRetentionPeriod(absl::Duration version_retention_period) = 0;
  virtual void CleanUpDeletedTables(absl::Time timestamp) = 0;
  virtual void CleanUpDeletedColumns(absl::Time timestamp) = 0;
  virtual void MarkDroppedTable(absl::Time timestamp, TableID dropped_table_id) = 0;
  virtual void MarkDroppedColumn(absl::Time timestamp, TableID dropped_table_id,
                                 ColumnID dropped_column_id) = 0;
};
```

This is a pure virtual interface with 9 methods. `InMemoryStorage` is the only implementation.

### The InMemoryStorage Data Model

**File: `backend/storage/in_memory_storage.h`**

```cpp
class InMemoryStorage : public Storage {
 private:
  using Cell = std::map<absl::Time, zetasql::Value>;      // MVCC versions
  using Row = absl::flat_hash_map<ColumnID, Cell>;          // columns
  using Table = std::map<Key, Row>;                         // sorted by PK
  using Tables = absl::flat_hash_map<TableID, Table>;       // all tables

  mutable absl::Mutex mu_;
  Tables tables_ ABSL_GUARDED_BY(mu_);
  std::map<absl::Time, TableID> dropped_tables_ ABSL_GUARDED_BY(mu_);
  std::map<absl::Time, std::pair<TableID, ColumnID>> dropped_columns_ ABSL_GUARDED_BY(mu_);
  absl::Duration version_retention_period_ = absl::Hours(1);
};
```

Key characteristics:
- **MVCC**: Each cell stores multiple timestamped versions. Reads at a given timestamp find the latest version ≤ that timestamp via `std::map::upper_bound`.
- **Existence tracking**: A special column `"_exists"` (string literal) marks whether a row is alive at a given timestamp. Deletes write `_exists = false` rather than erasing the row.
- **Sorted keys**: `Table` is `std::map<Key, Row>`, so keys are sorted by `Key::operator<` which delegates to `Key::Compare()`. Range scans use `lower_bound`.
- **GC**: `CleanUpDeletedTables` and `CleanUpDeletedColumns` remove expired data older than `version_retention_period`.

### The Single Injection Point

**File: `backend/database/database.cc`, line 70:**

```cpp
database->storage_ = std::make_unique<InMemoryStorage>();
```

The `Database` class holds `std::unique_ptr<Storage> storage_` — already polymorphic. This single line is the only place `InMemoryStorage` is instantiated for runtime use. All other code accesses storage exclusively through the `Storage*` pointer.

### ID Types

**File: `backend/common/ids.h`**

```cpp
using TableID = std::string;    // e.g., "0", "1", "2" (sequential)
using ColumnID = std::string;   // e.g., "0", "1", "2" (sequential)
```

These are simple strings generated by `UniqueIdGenerator<T>` which produces `"{next_seq_++}"` or `"{prefix}:{next_seq_++}"`.

### Key Type

**File: `backend/datamodel/key.h`**

`Key` contains:
- `std::vector<zetasql::Value> columns_` — the column values
- `std::vector<bool> is_descending_` — per-column sort direction
- `std::vector<bool> is_nulls_last_` — per-column null ordering
- `bool is_infinity_`, `bool is_prefix_limit_` — special sentinel flags

Valid key column types (from `key.cc` LogicalBytesInternal): `BOOL`, `INT64`, `DOUBLE`, `STRING`, `BYTES`, `TIMESTAMP`, `DATE`, `NUMERIC`.

### Configuration Flags

**File: `common/config.h` / `common/config.cc`**

Uses `ABSL_FLAG` macro pattern. Existing flags: `--host_port`, `--log_requests`, `--enable_fault_injection`, etc.

### Existing Test Patterns

**File: `backend/storage/in_memory_storage_test.cc`** (835 lines)

Uses Google Test with `ZETASQL_EXPECT_OK` and `zetasql::values::*` helpers. Test fixture creates an `InMemoryStorage` directly and calls Lookup/Read/Write/Delete. Tests cover: basic CRUD, multi-table, multi-version MVCC, delete-then-read, range scans, timestamp ordering, version retention cleanup, dropped table/column cleanup.

### Build System

Bazel with `rules_cc`. The `backend/storage/BUILD` file defines targets: `storage` (interface), `in_memory_storage` (implementation), `iterator`, `in_memory_iterator`, and their tests. Dependencies include ZetaSQL, absl, gRPC, and Google Test.

---

## What Needs to Persist (Scope)

### In Scope — Must Persist

| Component | Where it lives | Persistence strategy |
|-----------|---------------|---------------------|
| Row data (all MVCC versions) | `Storage` (backend/storage/) | LevelDB via new `PersistentStorage` class |
| Dropped table/column tracking | `Storage` | LevelDB metadata keys |
| Version retention period | `Storage` | LevelDB metadata key |
| Instance registry | `InstanceManager` (frontend/collections/) | Metadata file (JSON/protobuf) |
| Database registry + dialect | `DatabaseManager` (frontend/collections/) | Metadata file |
| DDL history per database | `VersionedCatalog` via `SchemaUpdater` | DDL statements file per database |
| ID generator counters | `Database` (TableIDGenerator, ColumnIDGenerator, etc.) | Metadata file per database |

### Out of Scope — Ephemeral by Design

| Component | Reason |
|-----------|--------|
| Sessions | Short-lived, recreated by clients |
| Active transactions | Cannot survive process restart |
| Lock state | Tied to active transactions |
| Long-running operations | Recreated on demand |

---

## Implementation Plan

See [plan.md](plan.md) for the full implementation plan including phases, file structure, and design decisions.

**Key design choice**: LevelDB-backed `PersistentStorage` class implementing the existing `Storage` interface, with order-preserving key encoding for sorted iteration. Single injection point at `database.cc:70`.

---

## User Stories

### US1 — Persistent Row Data
**As a** developer using LocalCloud's Spanner emulator,
**I want** row data, schemas, and MVCC history to survive emulator restarts,
**so that** I don't have to re-seed my database every time I restart the container.

**Acceptance criteria:**
- Write rows via Spanner client, restart emulator with same `--data_dir`, SELECT returns same rows
- MVCC reads at past timestamps return correct historical values after restart
- Deleted rows remain deleted after restart

### US2 — Transparent Configuration
**As a** developer using LocalCloud,
**I want** persistence to be enabled by default in the Docker deployment and configurable via a single flag,
**so that** I get persistence without extra setup, but can opt out for ephemeral testing.

**Acceptance criteria:**
- `--data_dir` flag controls storage backend (empty = in-memory, path = persistent)
- LocalCloud Docker image sets `--data_dir` automatically via supervisord.conf
- Instance/database metadata, DDL history, and ID counters persist alongside row data

---

## Definition of Done

### Functional Requirements

- [ ] **F1:** When `--data_dir` is not set, the emulator behaves identically to upstream (in-memory only, all state lost on restart). No behavioral change for the default case.
- [ ] **F2:** When `--data_dir=/path`, row data written via the Spanner API is persisted to disk under that path.
- [ ] **F3:** After stopping and restarting the emulator with the same `--data_dir`, all previously created instances, databases, schemas, and row data are available without re-creation.
- [ ] **F4:** DDL changes (CREATE TABLE, ALTER TABLE, CREATE INDEX, etc.) are preserved across restarts.
- [ ] **F5:** MVCC reads work correctly across restarts — a read at a past timestamp returns the same data it would have returned before the restart.
- [ ] **F6:** Delete operations are preserved — a row deleted before restart remains deleted after restart.
- [ ] **F7:** Multiple databases within the same instance are independently persisted.
- [ ] **F8:** Multiple instances are independently persisted.
- [ ] **F9:** Dropping a database removes its persisted data from disk.
- [ ] **F10:** The `--data_dir` directory can be a Docker volume mount.

### Non-Functional Requirements

- [ ] **NF1:** The existing `in_memory_storage_test.cc` test suite passes without modification against `InMemoryStorage`.
- [ ] **NF2:** The same test suite, run parametrically against `PersistentStorage`, passes.
- [ ] **NF3:** The emulator's existing integration tests (in `tests/`) pass in both in-memory and persistent modes.
- [ ] **NF4:** Startup with a populated `--data_dir` completes in < 5 seconds for databases with < 100 tables and < 100K total rows.
- [ ] **NF5:** Write throughput on `PersistentStorage` is within 10x of `InMemoryStorage` for typical emulator workloads (acceptable since the emulator is not perf-tested).
- [ ] **NF6:** The fork diff from upstream is contained to: `backend/storage/` (new files), `backend/database/database.h` + `database.cc` (minor modifications), `common/config.h` + `config.cc` (new flag), `frontend/persistence/` (new directory), and `WORKSPACE` (LevelDB dependency). Upstream files are not deleted or restructured.

### Test Cases

#### Unit Tests — Key Codec (`key_codec_test.cc`)

| # | Test | Description |
|---|------|-------------|
| KC1 | `EncodeDecodeRoundtrip_Int64` | Encode Key({Int64(42)}), decode, verify equal |
| KC2 | `EncodeDecodeRoundtrip_String` | Encode Key({String("hello")}), decode, verify equal |
| KC3 | `EncodeDecodeRoundtrip_AllTypes` | One key per supported type (BOOL, INT64, DOUBLE, STRING, BYTES, TIMESTAMP, DATE, NUMERIC), roundtrip each |
| KC4 | `EncodeDecodeRoundtrip_CompositeKey` | Key({Int64(1), String("abc"), Bool(true)}), roundtrip |
| KC5 | `EncodeDecodeRoundtrip_NullValues` | Key with NULL columns of each type |
| KC6 | `SortOrder_Int64Ascending` | Encode Int64(-1), Int64(0), Int64(1), Int64(MAX) → verify byte ordering matches Key ordering |
| KC7 | `SortOrder_Int64Descending` | Same with `is_descending_=true` → verify reversed byte ordering |
| KC8 | `SortOrder_StringAscending` | Encode String(""), String("a"), String("ab"), String("b") → verify byte ordering |
| KC9 | `SortOrder_NullFirst` | Encode NULL then non-NULL → verify NULL byte < non-NULL byte |
| KC10 | `SortOrder_NullLast` | With `is_nulls_last_=true`: Encode NULL then non-NULL → verify non-NULL byte < NULL byte |
| KC11 | `SortOrder_CompositeKey` | Key({Int64(1), String("b")}) < Key({Int64(1), String("c")}) → verify encoded ordering |
| KC12 | `SpecialKeys_Empty` | Encode Key::Empty(), verify it sorts before all other keys |
| KC13 | `SpecialKeys_Infinity` | Encode Key::Infinity(), verify it sorts after all other keys |
| KC14 | `SpecialKeys_PrefixLimit` | Encode key.ToPrefixLimit(), verify it sorts after all keys with same prefix |
| KC15 | `StringWithNullBytes` | Key({String("ab\x00cd")}) roundtrips correctly and sorts correctly |

#### Unit Tests — Value Codec (`value_codec_test.cc`)

| # | Test | Description |
|---|------|-------------|
| VC1 | `RoundtripAllTypes` | Each of BOOL, INT64, DOUBLE, STRING, BYTES, TIMESTAMP, DATE, NUMERIC, JSON → serialize then deserialize → verify equal |
| VC2 | `RoundtripNull` | Null values of each type |
| VC3 | `RoundtripArray` | ARRAY<INT64> with values |
| VC4 | `RoundtripInvalidValue` | Invalid `zetasql::Value` (is_valid()==false) roundtrips as invalid |
| VC5 | `RoundtripEmptyString` | String("") and Bytes("") |

#### Unit Tests — PersistentStorage (`persistent_storage_test.cc`)

All tests from `in_memory_storage_test.cc` run parametrically against both implementations. Additionally:

| # | Test | Description |
|---|------|-------------|
| PS1 | `PersistAcrossRestart_Write` | Write rows, destroy storage, create new storage from same path → Lookup returns written values |
| PS2 | `PersistAcrossRestart_Delete` | Write then delete rows, restart → deleted rows remain deleted |
| PS3 | `PersistAcrossRestart_MVCC` | Write at t1, update at t2, restart → read at t1 returns old value, read at t2 returns new value |
| PS4 | `PersistAcrossRestart_MultiTable` | Write to multiple tables, restart → all tables readable |
| PS5 | `PersistAcrossRestart_VersionRetention` | Set retention period, restart → retention period preserved |
| PS6 | `PersistAcrossRestart_DroppedTable` | Mark table dropped, restart → cleanup still works |
| PS7 | `EmptyDatabase_OpenClose` | Create storage, close, reopen → no errors, empty reads |
| PS8 | `ConcurrentReadWrite` | Parallel reads while writing → no crashes or data corruption |

#### Integration Tests

| # | Test | Description |
|---|------|-------------|
| IT1 | `EndToEnd_CreateAndRestart` | Start emulator with `--data_dir`, create instance + database + table, insert rows via Spanner client, stop emulator, restart with same `--data_dir` → SELECT returns same rows |
| IT2 | `EndToEnd_SchemaChangeAndRestart` | Start, create DB, ALTER TABLE ADD COLUMN, insert with new column, restart → new column and data present |
| IT3 | `EndToEnd_MultipleDBRestart` | Create two databases, insert into both, restart → both databases and data present |
| IT4 | `EndToEnd_DropDatabaseAndRestart` | Create DB, drop DB, restart → DB does not reappear |
| IT5 | `EndToEnd_NoDataDir` | Start without `--data_dir` → emulator works exactly like upstream (in-memory) |
| IT6 | `EndToEnd_DockerVolume` | Docker run with `-v` mount, write data, stop container, start new container with same volume → data present |
| IT7 | `EndToEnd_InterleavedTables` | Create parent + interleaved child tables, insert data, restart → parent-child relationships and data preserved |
| IT8 | `EndToEnd_SecondaryIndexes` | Create table with secondary index, insert data, restart → index queries return correct results |
| IT9 | `EndToEnd_PostgresDialect` | Create PostgreSQL-dialect database, insert data, restart → data and dialect preserved |

---

## Risk Register

| Risk | Impact | Mitigation |
|------|--------|------------|
| Key encoding bug causes wrong sort order | Data corruption, wrong query results | Exhaustive encoding tests (KC1-KC15) + parametric storage tests |
| ZetaSQL Value serialization fails for edge-case types (STRUCT, ARRAY of ARRAY) | Data loss for specific types | Test all ZetaSQL type kinds; fall back to protobuf serialization for complex types |
| LevelDB Bazel integration conflicts with existing deps | Build failures | Test LevelDB integration in isolation first; resolve version conflicts with existing abseil/protobuf |
| Upstream release changes Storage interface | Fork rebase breaks | Interface has been stable since 2020 (9 methods, unchanged); low risk but monitor releases |
| Upstream release changes Database::Create signature | Merge conflict | Small, contained change; easy manual rebase |
| ID generator counters desync after restart | Schema update failures, duplicate IDs | Persist counters atomically with DDL changes; verify on startup |
| Metadata.json corruption | All state lost | Write atomically (write tmp → rename); consider checksums |
| Large dataset causes slow startup | Poor DX | DDL replay is fast (DDL is small); row data is already on disk (no replay needed for data). Only schema rebuild happens at startup. |

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Spanner data survives container restarts with zero manual intervention when persistent storage is configured
- **SC-002**: All existing Spanner demo tests (12/12 operations) pass with both in-memory and persistent backends
- **SC-003**: Switching between storage backends requires changing only one configuration value
- **SC-004**: The persistent backend adds less than 2 seconds to Spanner operation latency compared to in-memory

## Assumptions

- The initial implementation targets LevelDB as the persistent backend (selected for embedded operation, sorted iteration, Bazel support, and Google ecosystem alignment)
- The Spanner emulator's SQL dialect and transaction semantics remain unchanged — only the storage layer is abstracted
- This feature does not add new Spanner API surface — it only changes where data is stored
- Performance of the persistent backend is secondary to correctness for local development use
- Storage mechanism decided: LevelDB 1.23 (embedded, sorted iteration, Bazel-native, Google-maintained)
