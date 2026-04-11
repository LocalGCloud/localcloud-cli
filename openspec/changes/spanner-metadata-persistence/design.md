## Context

The forked Spanner emulator (`local_cloud_dependencies/cloud-spanner-emulator/`) has a working `PersistentStorage` class (T009-T015) that stores row data in LevelDB under `--data_dir/{database_name}/storage/`. The `--data_dir` flag (T018), `StorageFactory` wiring (T019-T020), and Docker integration (T025-T027) are complete.

However, the emulator's **frontend layer** manages instances, databases, and DDL schemas entirely in memory:

```
frontend/
├── collections/
│   ├── instance_manager.h    ← In-memory map of instances (lost on restart)
│   ├── database_manager.h    ← In-memory map of databases (lost on restart)
│   └── session_manager.h     ← In-memory sessions (ephemeral by design)
├── entities/
│   ├── instance.h            ← Instance metadata
│   └── database.h            ← Database metadata + VersionedCatalog + ID generators
└── server/
    └── server_env.h          ← Owns InstanceManager, DatabaseManager
```

After restart, even though LevelDB files exist on disk, the `InstanceManager` and `DatabaseManager` are empty, so no instances/databases are known and the row data is orphaned.

## Goals / Non-Goals

**Goals:**
- All metadata (instances, databases, DDL, ID counters) persists alongside row data
- Startup restore rebuilds full emulator state from `--data_dir` contents
- No behavioral change when `--data_dir` is not set (upstream compatibility)
- Dropped databases are cleaned up from disk

**Non-Goals:**
- Incremental DDL persistence (full replay from statement list is sufficient at emulator scale)
- Concurrent emulator instances sharing the same `--data_dir`
- Migration between emulator versions (metadata format may change)

## Decisions

### D1: JSON metadata file at `--data_dir/metadata.json`

Store all metadata in a single JSON file:

```json
{
  "version": 1,
  "instances": {
    "my-instance": {
      "displayName": "my-instance",
      "config": "emulator-config",
      "nodeCount": 1,
      "state": "READY",
      "createTime": "2026-04-10T...",
      "databases": {
        "my-database": {
          "dialect": "GOOGLE_STANDARD_SQL",
          "ddlStatements": [
            "CREATE TABLE Users (Id INT64 NOT NULL, Name STRING(100)) PRIMARY KEY (Id)",
            "CREATE INDEX UsersByName ON Users(Name)"
          ],
          "idCounters": {
            "tableId": 3,
            "columnId": 8,
            "indexId": 2
          }
        }
      }
    }
  }
}
```

**Why single file over per-database files**: The metadata is small (a few KB even with hundreds of tables). A single atomic write avoids partial-state corruption. The file is read once at startup and written on every metadata mutation (instance/database create/drop, DDL change).

**Alternative considered**: Protobuf serialization. Rejected because JSON is human-readable and debuggable — valuable for a development tool. Performance is irrelevant at this scale.

### D2: Atomic writes via write-tmp-then-rename

Write metadata to `metadata.json.tmp`, then `rename()` to `metadata.json`. This ensures the file is never partially written. If the emulator crashes mid-write, the old file remains valid.

```cpp
void MetadataStore::Save() {
    std::string tmp_path = metadata_path_ + ".tmp";
    // Write JSON to tmp_path
    std::rename(tmp_path.c_str(), metadata_path_.c_str());
}
```

### D3: DDL replay at startup

On startup, for each database in metadata.json:
1. Create the instance (if not already created)
2. Create the database with `CreateDatabase` (DDL statements from `ddlStatements`)
3. Set ID generator counters to persisted values
4. LevelDB is automatically opened by `PersistentStorage` (already implemented)

DDL replay uses the existing `SchemaUpdater` / `VersionedCatalog` machinery — no custom schema rebuild needed. The DDL statements are replayed in order, rebuilding the exact schema state.

**Performance**: DDL replay is fast. Even 100 DDL statements replay in under 1 second. Row data is NOT replayed — LevelDB handles that natively.

### D4: MetadataStore integration points

The `MetadataStore` is owned by `ServerEnv` (or `emulator_main`). It hooks into existing operations:

| Operation | Hook point | MetadataStore action |
|-----------|-----------|---------------------|
| `CreateInstance` | `InstanceManager::CreateInstance` | Add instance, save |
| `DeleteInstance` | `InstanceManager::DeleteInstance` | Remove instance, save |
| `CreateDatabase` | `DatabaseManager::CreateDatabase` | Add database, save |
| `DropDatabase` | `DatabaseManager::DeleteDatabase` | Remove database, delete LevelDB dir, save |
| `UpdateDatabaseDdl` | `SchemaUpdater::UpdateSchema` | Append DDL statements, save |
| Startup | `emulator_main.cc` | Load metadata, replay |

### D5: Gateway flag forwarding

The Go gateway spawns the emulator binary as a subprocess. Currently it passes `--host_port` and other flags. Add `--data_dir` to the forwarded flags:

```go
// In gateway.go, where emulator args are constructed:
if *dataDir != "" {
    emulatorArgs = append(emulatorArgs, "--data_dir="+*dataDir)
}
```

Currently, LocalCloud works around this by using a wrapper script (`spanner-emulator-wrapper`) that injects `--data_dir`. After T024, the wrapper can be simplified to a passthrough, or the `--data_dir` can be passed directly via the gateway.

## Risks / Trade-offs

**DDL replay order matters** → DDL statements must be stored and replayed in the exact order they were executed. If a CREATE INDEX references a table that hasn't been created yet, replay fails. Mitigation: Store statements in execution order (append-only list).

**ID counter desync** → If ID counters are saved after DDL but the row data write fails, the counter on disk is ahead of the actual state. Mitigation: Save counters atomically with DDL updates. On startup, verify counter values against actual LevelDB contents (optional hardening).

**metadata.json corruption** → Power loss during write could corrupt the file. Mitigation: Atomic write-tmp-rename pattern. Consider adding a checksum field for validation.

**Concurrent emulator processes** → Two emulators using the same `--data_dir` will corrupt state. Mitigation: File lock on `metadata.json` at startup. Log a clear error if lock acquisition fails.
