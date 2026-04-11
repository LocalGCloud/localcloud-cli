# Tasks: Spanner Emulator Persistent Storage

**Input**: Design documents from `/specs/003-spanner-storage-extensibility/`
**Prerequisites**: plan.md, spec.md

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to

### Phase Mapping

| Spec Phase | Plan Phase | Tasks Phase(s) | Scope |
|------------|------------|----------------|-------|
| Phase 1 | Phase 1 + 2 | Phase 1-4 | Fork + codecs + PersistentStorage + tests |
| Phase 2 | Phase 3 | Phase 5 | Emulator wiring (--data_dir, metadata) |
| Phase 3 | Phase 4 | Phase 6-7 | Gateway + Docker + integration tests |
| — | — | Phase 8 | Polish (docs, services.yaml) |

---

## Phase 1: Fork Setup

**Purpose**: Fork the Google Spanner emulator and set up the build environment

- [x] T001 Fork `GoogleCloudPlatform/cloud-spanner-emulator` into `../local_cloud_dependencies/cloud-spanner-emulator/` (sister directory, outside localcloud repo) — clone the repo, verify Bazel build works (`bazel build //binaries:emulator_main`)
- [x] T002 Add LevelDB 1.23 as Bazel dependency in `../local_cloud_dependencies/cloud-spanner-emulator/WORKSPACE` — add `http_archive` for `com_google_leveldb`, resolve Snappy/CRC32C deps
- [x] T003 Verify LevelDB builds with existing deps: create a minimal `bazel test` target that links LevelDB

**Checkpoint**: `bazel build //binaries:emulator_main` succeeds with LevelDB available.

---

## Phase 2: Key/Value Codecs (Foundational)

**Purpose**: Order-preserving key serialization and value serialization — required before PersistentStorage

- [x] T004 [P] Create `backend/storage/key_codec.h` and `key_codec.cc` — implement order-preserving key encoding for all Spanner types (BOOL, INT64, DOUBLE, STRING, BYTES, TIMESTAMP, DATE, NUMERIC) with ascending/descending support, NULL handling, and special keys (Empty, Infinity, PrefixLimit)
- [x] T005 [P] Create `backend/storage/key_codec_test.cc` — 15 unit tests (KC1-KC15): roundtrip encoding for all types, sort order verification for ascending/descending/null-first/null-last, composite keys, special keys, strings with null bytes
- [x] T006 [P] Create `backend/storage/value_codec.h` and `value_codec.cc` — serialize/deserialize `zetasql::Value` to bytes using type-prefix + protobuf serialization, handle Invalid values as sentinel
- [x] T007 [P] Create `backend/storage/value_codec_test.cc` — 5 unit tests (VC1-VC5): roundtrip all types, nulls, arrays, invalid values, empty strings
- [x] T008 Update `backend/storage/BUILD` — add `key_codec`, `value_codec` library targets and test targets with deps on `//backend/datamodel`, `@com_google_zetasql`, `@com_google_leveldb`

**Checkpoint**: `bazel test //backend/storage:key_codec_test //backend/storage:value_codec_test` — all 20 tests pass.

---

## Phase 3: PersistentStorage Class (US1 — P1)

**Goal**: LevelDB-backed Storage implementation that passes the same tests as InMemoryStorage

**Independent Test**: Write data, destroy storage, create new storage from same path → data readable

### Implementation

- [x] T009 [US1] Create `backend/storage/persistent_storage.h` — class definition extending `Storage`, with `leveldb::DB*` handle, constructor taking `db_path`, all 9 interface methods declared
- [x] T010 [US1] Implement `PersistentStorage::Write` in `persistent_storage.cc` — construct LevelDB key (`[table_id]\x00[encoded_key]\x00[column_id]\x00[timestamp]`), write `_exists=true` + column values using `WriteBatch`
- [x] T011 [US1] Implement `PersistentStorage::Lookup` — seek-then-prev pattern for each column, check `_exists` at timestamp
- [x] T012 [US1] Implement `PersistentStorage::Read` — range scan using LevelDB iterator, collect keys in range, check existence, return `FixedRowStorageIterator`
- [x] T013 [US1] Implement `PersistentStorage::Delete` — iterate range, write tombstone values (`_exists=false`)
- [x] T014 [US1] Implement GC methods: `CleanUpDeletedTables`, `CleanUpDeletedColumns`, `MarkDroppedTable`, `MarkDroppedColumn`, `SetVersionRetentionPeriod` — use `\xFF\xFF` prefixed metadata keys in LevelDB
- [x] T015 [US1] Update `backend/storage/BUILD` — add `persistent_storage` target with deps on `key_codec`, `value_codec`, `@com_google_leveldb`

**Checkpoint**: `PersistentStorage` compiles and links.

---

## Phase 4: Parametric Test Suite (US1)

**Goal**: Prove PersistentStorage is behaviorally equivalent to InMemoryStorage

- [x] T016 [US1] Refactor `in_memory_storage_test.cc` to parametric form — use `TYPED_TEST` or `TEST_P` with factory parameter, run all existing tests against both `InMemoryStorage` and `PersistentStorage`
- [x] T017 [US1] Create `persistent_storage_test.cc` — 8 persistence-specific tests (PS1-PS8): restart-write, restart-delete, restart-MVCC, restart-multi-table, restart-retention, restart-dropped-table, empty-open-close, concurrent-read-write

**Checkpoint**: `bazel test //backend/storage:persistent_storage_test` — all parametric + persistence tests pass.

---

## Phase 5: Emulator Wiring (US2 — P2)

**Goal**: Connect PersistentStorage to the emulator via `--data_dir` flag

- [x] T018 [US2] Add `--data_dir` ABSL_FLAG to `common/config.h` and `common/config.cc` — empty string = in-memory (default), non-empty = persistent storage path
- [x] T019 [US2] Modify `backend/database/database.h` — add `StorageFactory` typedef (`std::function<std::unique_ptr<Storage>(const std::string& db_path)>`) and update `Database::Create` to accept it
- [x] T020 [US2] Modify `backend/database/database.cc` — replace `make_unique<InMemoryStorage>()` with factory call, pass `--data_dir/{instance}/{database}/` as db_path when persistent
- [ ] T021 [US2] Create `frontend/persistence/metadata_store.h` and `metadata_store.cc` — persist instance registry, database registry + dialect, DDL history per database, ID generator counters to JSON files under `--data_dir`
- [ ] T022 [US2] Implement startup restore in `emulator_main.cc` — when `--data_dir` is set, read metadata files, recreate instances/databases from persisted state, replay DDL to rebuild schema catalog
- [ ] T023 [US2] Update `frontend/persistence/BUILD` — add metadata_store target
- [ ] T023a [US2] Implement disk cleanup on database drop — when a database is dropped via `DropDatabase` RPC while `--data_dir` is set, delete the corresponding `--data_dir/{instance}/{database}/` LevelDB directory from disk. Verify with T031 (IT4)

**Checkpoint**: `bazel build //binaries:emulator_main` with `--data_dir=/tmp/test` — emulator starts, persists data, survives restart.

---

## Phase 6: Gateway + Docker Integration (US1/US2)

**Goal**: Wire persistence through the Go gateway and Docker packaging

- [ ] T024 Modify `gateway/gateway.go` — add `--data_dir` flag to Go flag set, forward to `emulator_main` subprocess invocation
- [x] T025 Update `Dockerfile` — use `spanner-emulator-build:latest` (fork) for emulator_main, upstream for gateway_main, add spanner-data dir and wrapper script
- [x] T026 Update `supervisord.conf` — use wrapper script to pass `--data_dir=/var/lib/localcloud/spanner-data` to the spanner-emulator
- [x] T027 Update `docker-compose.yml` — verified `/var/lib/localcloud` volume already covers spanner-data subdirectory (no changes needed)

**Checkpoint**: `docker compose up -d` — Spanner emulator starts with persistence enabled.

---

## Phase 7: Integration Tests

**Goal**: Verify end-to-end persistence with real Spanner client library

- [x] T028 [P] Create integration test IT1: `EndToEnd_CreateAndRestart` — create instance/database/table, insert rows, restart emulator, query → same rows
- [x] T029 [P] Create integration test IT2: `EndToEnd_SchemaChangeAndRestart` — ALTER TABLE, restart → schema preserved
- [x] T030 [P] Create integration test IT3: `EndToEnd_MultipleDBRestart` — two databases, restart → both present
- [x] T031 [P] Create integration test IT4: `EndToEnd_DropDatabaseAndRestart` — drop DB, restart → gone
- [x] T032 [P] Create integration test IT5: `EndToEnd_NoDataDir` — no flag → ephemeral (upstream behavior, skipped when persistence enabled)
- [x] T033 Create integration test IT6: `EndToEnd_DockerVolume` — Docker volume data directory check
- [ ] T034 [P] Create integration test IT7-IT9: interleaved tables, secondary indexes, PostgreSQL dialect

**Checkpoint**: All 9 integration tests pass.

---

## Phase 8: Polish

- [ ] T035 Update `DEVELOPER_GUIDE.md` — add Spanner persistence documentation (--data_dir flag, volume mount, restart behavior)
- [ ] T036 Update `README.md` — mention Spanner persistence in features table
- [x] T037 Update `services.yaml` — add `persistence: true` and `dataDir` annotation for Spanner service definition

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Fork Setup)**: No dependencies
- **Phase 2 (Codecs)**: Depends on Phase 1 (LevelDB available)
- **Phase 3 (PersistentStorage)**: Depends on Phase 2 (codecs)
- **Phase 4 (Tests)**: Depends on Phase 3
- **Phase 5 (Wiring)**: Depends on Phase 3
- **Phase 6 (Docker)**: Depends on Phase 5
- **Phase 7 (Integration)**: Depends on Phase 6
- **Phase 8 (Polish)**: Depends on Phase 7

### Parallel Opportunities

- T004, T005, T006, T007 can run in parallel (different codec files)
- T028-T032, T034 integration tests can run in parallel
- Phase 4 and Phase 5 can partially overlap (tests can start while wiring begins)

---

## Implementation Strategy

### MVP (US1 — persistent row data): T001-T017

1. Fork + LevelDB setup (T001-T003)
2. Key/Value codecs (T004-T008)
3. PersistentStorage class (T009-T015)
4. Parametric tests (T016-T017)
5. **STOP**: Verify all storage tests pass with both backends

### Full delivery: T001-T037

Add emulator wiring (T018-T023), Docker integration (T024-T027), integration tests (T028-T034), polish (T035-T037).

### Estimated Effort

| Phase | Tasks | Effort |
|-------|-------|--------|
| Fork setup | T001-T003 | 1-2 days |
| Codecs | T004-T008 | 3-4 days |
| PersistentStorage | T009-T015 | 3-4 days |
| Parametric tests | T016-T017 | 1-2 days |
| Emulator wiring | T018-T023 | 3-4 days |
| Docker integration | T024-T027 | 1-2 days |
| Integration tests | T028-T034 | 2-3 days |
| Polish | T035-T037 | 1 day |
| **Total** | **37 tasks** | **15-20 days** |
