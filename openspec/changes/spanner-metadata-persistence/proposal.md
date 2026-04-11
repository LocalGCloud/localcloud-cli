## Why

The forked Spanner emulator's `--data_dir` flag currently persists **row data only** (via LevelDB in `PersistentStorage`). After a restart, all instances, databases, DDL schemas, and ID generator counters are lost — even though the LevelDB files containing row data remain on disk. This means the emulator effectively starts from a blank slate after every restart, making `--data_dir` unusable for its intended purpose: surviving container restarts without re-seeding.

Tasks T001-T020 (fork setup, codecs, PersistentStorage, wiring) are complete. Tasks T021-T024 were planned but never implemented. These are the remaining tasks needed to complete the persistence story.

## What Changes

This change is scoped to the **forked Spanner emulator** project (`local_cloud_dependencies/cloud-spanner-emulator/`), not the LocalCloud project itself.

- **T021 — MetadataStore**: New `frontend/persistence/metadata_store.h/.cc` that serializes instance registry, database registry (with dialect), DDL history per database, and ID generator counters to JSON files under `--data_dir`
- **T022 — Startup restore**: Modify `binaries/emulator_main.cc` to detect existing metadata on startup, rebuild `InstanceManager`, `DatabaseManager`, and `VersionedCatalog` from persisted state, and reconnect to existing LevelDB databases
- **T023 — BUILD file**: Add `frontend/persistence:metadata_store` Bazel target
- **T023a — Disk cleanup on drop**: When `DropDatabase` is called, delete the corresponding LevelDB directory from disk
- **T024 — Gateway flag forwarding**: Forward `--data_dir` flag from the Go gateway to the C++ emulator subprocess

## Capabilities

### New Capabilities

- `spanner-metadata-persistence`: Persist Spanner emulator instance/database/DDL metadata across restarts when `--data_dir` is set, enabling full state survival without re-seeding

### Modified Capabilities

_None_

## Impact

- **Emulator fork** (`local_cloud_dependencies/cloud-spanner-emulator/`): 4 new/modified files in `frontend/persistence/`, 1 modified file in `binaries/`, 1 modified file in `gateway/`
- **LocalCloud**: No changes needed — the Dockerfile and supervisord.conf already pass `--data_dir`
- **Developer experience**: After this change, `docker compose restart` preserves all Spanner data without re-seeding
