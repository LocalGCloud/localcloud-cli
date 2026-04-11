## 1. MetadataStore Implementation (T021)

- [ ] 1.1 Create `frontend/persistence/metadata_store.h` — define `MetadataStore` class with: constructor taking `data_dir` path, `Save()` method, `Load()` method returning a struct of instances/databases/DDL/counters, `AddInstance()`, `RemoveInstance()`, `AddDatabase()`, `RemoveDatabase()`, `UpdateDdl()`, `UpdateIdCounters()` methods
- [ ] 1.2 Create `frontend/persistence/metadata_store.cc` — implement JSON serialization using `nlohmann/json` (already available in the emulator's deps) or `rapidjson`. The `metadata.json` schema:
  ```json
  {
    "version": 1,
    "instances": {
      "<instance-name>": {
        "displayName": "...",
        "config": "emulator-config",
        "nodeCount": 1,
        "state": "READY",
        "createTime": "...",
        "databases": {
          "<db-name>": {
            "dialect": "GOOGLE_STANDARD_SQL",
            "ddlStatements": ["CREATE TABLE ...", "ALTER TABLE ..."],
            "idCounters": { "tableId": N, "columnId": N, "indexId": N }
          }
        }
      }
    }
  }
  ```
- [ ] 1.3 Implement atomic writes in `Save()` — write to `metadata.json.tmp`, then `std::rename()` to `metadata.json`. Include error handling for write failures.
- [ ] 1.4 Implement `Load()` — parse `metadata.json`, return structured data. If file doesn't exist, return empty state (first run). If file is corrupt, log error and return empty state (safe fallback).
- [ ] 1.5 Create `frontend/persistence/BUILD` (T023) — add `metadata_store` library target with deps on JSON library, absl, and relevant frontend headers. Add test target.
- [ ] 1.6 Create `frontend/persistence/metadata_store_test.cc` — unit tests: save/load roundtrip, atomic write (verify .tmp is cleaned up), corrupt file handling, empty file handling, multiple instances/databases

## 2. Integration Hooks — Wire MetadataStore into Emulator Operations

- [ ] 2.1 Hook into `CreateInstance` — after successful instance creation in `InstanceManager`, call `metadata_store->AddInstance(instance)` and `metadata_store->Save()`. Find the RPC handler in `frontend/handlers/` that calls `InstanceManager::CreateInstance`.
- [ ] 2.2 Hook into `DeleteInstance` — after successful deletion, call `metadata_store->RemoveInstance(name)` and `metadata_store->Save()`
- [ ] 2.3 Hook into `CreateDatabase` — after successful database creation in `DatabaseManager`, call `metadata_store->AddDatabase(instance, db_name, dialect, ddl_statements)` and `metadata_store->Save()`
- [ ] 2.4 Hook into `DropDatabase` — after successful drop, call `metadata_store->RemoveDatabase(instance, db_name)`, delete LevelDB directory (`--data_dir/{db_name}/`), and `metadata_store->Save()` (T023a)
- [ ] 2.5 Hook into `UpdateDatabaseDdl` — after successful DDL update via `SchemaUpdater`, call `metadata_store->UpdateDdl(instance, db_name, new_statements)` and `metadata_store->Save()`
- [ ] 2.6 Hook into ID counter updates — when `Database` assigns new table/column/index IDs, persist the current counter values. This may hook into `Database::Create` or `SchemaUpdater` after ID allocation.

## 3. Startup Restore (T022)

- [ ] 3.1 In `emulator_main.cc`, after parsing `--data_dir` flag, create `MetadataStore` and call `Load()`. If metadata exists, enter restore mode.
- [ ] 3.2 Implement instance restore — for each instance in metadata, call `InstanceManager::CreateInstance()` with the persisted config (displayName, nodeCount, etc.)
- [ ] 3.3 Implement database restore — for each database under each instance:
  1. Call `CreateDatabase` with the first DDL statement (the `CREATE DATABASE` statement)
  2. Apply remaining DDL statements via `UpdateDatabaseDdl` (replays CREATE TABLE, ALTER TABLE, CREATE INDEX in order)
  3. LevelDB is automatically opened by `PersistentStorage` via the existing `StorageFactory` (no extra work)
- [ ] 3.4 Implement ID counter restore — after DDL replay, set each database's ID generators (TableIDGenerator, ColumnIDGenerator, IndexIDGenerator) to the persisted counter values. This prevents new schema objects from getting IDs that collide with existing LevelDB keys.
- [ ] 3.5 Add startup logging — log the number of instances and databases restored, and any errors encountered during restore

## 4. Gateway Flag Forwarding (T024)

- [ ] 4.1 In `gateway/gateway.go`, add a `--data_dir` flag using Go's `flag` package (or the existing flag mechanism used for `--host_port`)
- [ ] 4.2 When constructing the emulator subprocess command, append `--data_dir=<value>` to the emulator args if the flag is non-empty
- [ ] 4.3 Verify the gateway passes the flag correctly — start gateway with `--data_dir=/tmp/test`, check that emulator subprocess has the flag in its args (visible via `ps aux`)

## 5. Integration Tests

- [ ] 5.1 Test: create instance + database + table + insert rows → restart emulator → `ListInstances` returns the instance, `ListDatabases` returns the database, `SELECT *` returns the rows
- [ ] 5.2 Test: create database with DDL, `ALTER TABLE ADD COLUMN`, restart → `GetDatabaseDdl` returns all DDL, new column is queryable
- [ ] 5.3 Test: create 2 databases in 1 instance, restart → both databases present with their data
- [ ] 5.4 Test: create database, drop database, restart → database does NOT reappear, LevelDB directory is deleted
- [ ] 5.5 Test: start without `--data_dir` → no `metadata.json` created, fully ephemeral behavior
- [ ] 5.6 Test: create tables (get IDs 0,1,2), restart, create new table → gets ID 3 (not 0)
- [ ] 5.7 Test: create interleaved tables, insert parent + child rows, restart → parent-child relationships and data preserved
- [ ] 5.8 Test: create secondary index, insert data, restart → index-based queries return correct results
