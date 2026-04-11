# Implementation Plan: Spanner Emulator Persistent Storage

**Branch**: `003-spanner-storage-extensibility` | **Date**: 2026-04-08 | **Spec**: [spec.md](spec.md)

## Summary

Fork Google's Cloud Spanner Emulator (`GoogleCloudPlatform/cloud-spanner-emulator`) into `external_deps/cloud-spanner-emulator/` and add a LevelDB-backed `PersistentStorage` class implementing the existing `Storage` interface. When `--data_dir` is set, data persists across emulator restarts. Build the fork locally and reference the modified binaries in the LocalCloud Docker image.

## Technical Context

**Language**: C++ (emulator backend + storage), Go (gateway), Bazel (build system)
**Fork Location**: `../local_cloud_dependencies/cloud-spanner-emulator/` (sister directory, outside localcloud repo)
**New Dependency**: LevelDB 1.23 (via Bazel `http_archive`)
**Storage Interface**: `backend/storage/storage.h` — 9-method pure virtual class (Lookup, Read, Write, Delete, + GC/mark methods)
**Injection Point**: `backend/database/database.cc` line 70 — single `make_unique<InMemoryStorage>()` call
**Build Output**: Modified `emulator_main` and `gateway_main` binaries

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Google-Cloud-in-a-Box | PASS | Same Spanner API, persistence is transparent to SDK clients |
| II. Single Docker Deployment | PASS | Fork builds inside Docker, LevelDB data on Docker volume |
| III. Core Functionality First | PASS | Existing emulator functionality unchanged; persistence is additive |
| IV. Transparent Limitations | PASS | `--data_dir` flag clearly controls behavior; no silent changes |
| V. Lightweight Console | N/A | No console changes |

## Project Structure

### Fork Location

```text
external_deps/cloud-spanner-emulator/     # Forked from GoogleCloudPlatform/cloud-spanner-emulator
├── WORKSPACE                              # Modified: add LevelDB dependency
├── backend/
│   ├── storage/
│   │   ├── storage.h                      # UNCHANGED (existing interface)
│   │   ├── in_memory_storage.h            # UNCHANGED
│   │   ├── in_memory_storage.cc           # UNCHANGED
│   │   ├── persistent_storage.h           # NEW: LevelDB-backed Storage implementation
│   │   ├── persistent_storage.cc          # NEW
│   │   ├── persistent_storage_test.cc     # NEW: parametric tests
│   │   ├── key_codec.h                    # NEW: order-preserving key serialization
│   │   ├── key_codec.cc                   # NEW
│   │   ├── key_codec_test.cc             # NEW: 15 test cases
│   │   ├── value_codec.h                  # NEW: zetasql::Value serialization
│   │   ├── value_codec.cc                # NEW
│   │   ├── value_codec_test.cc           # NEW: 5 test cases
│   │   └── BUILD                          # Modified: add new targets
│   └── database/
│       ├── database.h                     # Modified: StorageFactory parameter
│       └── database.cc                    # Modified: use factory instead of hardcoded InMemoryStorage
├── frontend/
│   └── persistence/                       # NEW directory
│       ├── metadata_store.h               # NEW: instance/database/DDL persistence
│       ├── metadata_store.cc              # NEW
│       └── BUILD                          # NEW
├── common/
│   ├── config.h                           # Modified: add --data_dir flag
│   └── config.cc                          # Modified
├── gateway/
│   └── gateway.go                         # Modified: forward --data_dir flag
└── binaries/
    └── emulator_main.cc                   # Modified: read --data_dir, pass to Database::Create
```

### LocalCloud Integration

```text
local_cloud_dependencies/
└── cloud-spanner-emulator/                # The fork (sister directory to localcloud/)

localcloud/
├── Dockerfile                             # Modified: build fork, copy binaries
├── supervisord.conf                       # Modified: pass --data_dir to spanner emulator
└── docker-compose.yml                     # Already mounts /var/lib/localcloud volume
```

## Phases

### Phase 1: Fork Setup + Key/Value Codecs (Foundation)

1. Fork Google Spanner emulator into `external_deps/cloud-spanner-emulator/`
2. Add LevelDB as Bazel dependency in WORKSPACE
3. Implement order-preserving key codec (`key_codec.h/.cc`) with 15 unit tests
4. Implement value codec (`value_codec.h/.cc`) with 5 unit tests
5. Verify Bazel build works: `bazel test //backend/storage:key_codec_test //backend/storage:value_codec_test`

### Phase 2: PersistentStorage Implementation

6. Implement `PersistentStorage` class (Lookup, Read, Write, Delete, GC methods)
7. Implement LevelDB key format: `[table_id]\x00[encoded_key]\x00[column_id]\x00[timestamp]`
8. Implement metadata keys for dropped tables/columns and retention period
9. Create parametric test suite (run existing InMemoryStorage tests against PersistentStorage)
10. Add 8 persistence-specific tests (restart, MVCC, multi-table, concurrent)

### Phase 3: Emulator Wiring

11. Add `--data_dir` ABSL_FLAG to `common/config.h`
12. Modify `Database::Create` to accept StorageFactory — use PersistentStorage when `--data_dir` set
13. Implement metadata persistence for instances, databases, DDL history, ID counters
14. Implement startup restore: on launch with `--data_dir`, rebuild state from disk

### Phase 4: Gateway + Docker Integration

15. Forward `--data_dir` flag in Go gateway (`gateway.go`)
16. Update `Dockerfile` to build fork and copy modified binaries
17. Update `supervisord.conf` to pass `--data_dir=/var/lib/localcloud/spanner-data`
18. 9 integration tests (end-to-end with Spanner client library)

## Key Design Decisions

- **LevelDB over SQLite/PostgreSQL**: LevelDB is embedded (no server process), supports sorted iteration (needed for range scans), has Bazel build support, and is maintained by Google (same ecosystem)
- **Order-preserving key encoding**: Required because LevelDB iterates in byte order — keys must sort the same way as Spanner's `Key::Compare()`
- **Single injection point**: Only `database.cc:70` changes — minimal fork diff, easy upstream merges
- **MVCC in LevelDB**: Timestamp encoded in key suffix, seek-then-prev for point-in-time reads

## Complexity Tracking

| Principle | Deviation | Justification |
|-----------|-----------|---------------|
| Technical Constraints: "Go is excluded" | T024 modifies `gateway/gateway.go` in the upstream fork | The Go gateway is pre-existing upstream code, not new LocalCloud code. We modify 1 line to forward the `--data_dir` flag. Writing a replacement gateway in Java would be a full rewrite of the fork's entry point with no benefit. |
| Technical Constraints: "PostgreSQL for structured data" | PersistentStorage uses LevelDB | LevelDB runs inside the C++ emulator fork — it cannot use LocalCloud's containerized PostgreSQL. LevelDB is embedded, has no server process, and is maintained by Google (same ecosystem as the emulator). |
| II. Single Docker Deployment: "State MUST persist by default" | Raw emulator binary defaults to in-memory | The constitution applies to LocalCloud's Docker deployment, not the standalone emulator binary. T026 sets `--data_dir=/var/lib/localcloud/spanner-data` in supervisord.conf, ensuring persistence is on by default in the container. The raw binary default of in-memory preserves upstream compatibility. |
