## ADDED Requirements

### Requirement: Instance registry persists across restarts
The emulator SHALL persist the instance registry (instance names, display names, config, node count, state) to a JSON file under `--data_dir` whenever an instance is created or deleted. On startup with an existing `--data_dir`, the emulator SHALL restore all previously created instances.

#### Scenario: Instance survives restart
- **WHEN** a Spanner instance is created via `CreateInstance` RPC, the emulator is stopped, and restarted with the same `--data_dir`
- **THEN** the instance appears in `ListInstances` without re-creation

#### Scenario: Deleted instance stays deleted after restart
- **WHEN** a Spanner instance is deleted via `DeleteInstance` RPC and the emulator is restarted
- **THEN** the instance does not reappear in `ListInstances`

#### Scenario: No data_dir means no persistence
- **WHEN** the emulator is started without `--data_dir`
- **THEN** no metadata files are written and behavior matches upstream (fully in-memory)

### Requirement: Database registry and dialect persist across restarts
The emulator SHALL persist the database registry (database names, parent instance, database dialect) alongside the instance registry. On startup, databases SHALL be restored under their parent instances with the correct dialect (GOOGLE_STANDARD_SQL or POSTGRESQL).

#### Scenario: Database survives restart
- **WHEN** a database is created via `CreateDatabase` RPC, data is inserted, the emulator is restarted with the same `--data_dir`
- **THEN** the database appears in `ListDatabases`, and `SELECT *` returns the previously inserted rows

#### Scenario: PostgreSQL dialect database survives restart
- **WHEN** a PostgreSQL-dialect database is created, the emulator is restarted
- **THEN** the database is restored with PostgreSQL dialect and PostgreSQL-syntax queries work

### Requirement: DDL history persists across restarts
The emulator SHALL persist the DDL statement history (CREATE TABLE, ALTER TABLE, CREATE INDEX, etc.) for each database. On startup, DDL statements SHALL be replayed against the `SchemaUpdater` / `VersionedCatalog` to rebuild the schema catalog.

#### Scenario: Tables survive restart
- **WHEN** tables are created via DDL in a database, the emulator is restarted
- **THEN** `GetDatabaseDdl` returns the same DDL statements and table schemas are intact

#### Scenario: Schema changes survive restart
- **WHEN** `ALTER TABLE ADD COLUMN` is executed, the emulator is restarted
- **THEN** the new column exists and can be queried

#### Scenario: Secondary indexes survive restart
- **WHEN** `CREATE INDEX` is executed, the emulator is restarted
- **THEN** the index exists and index-based queries return correct results

### Requirement: ID generator counters persist across restarts
The emulator SHALL persist the current values of all ID generators (TableIDGenerator, ColumnIDGenerator, IndexIDGenerator, etc.) per database. On startup, generators SHALL be initialized to their persisted values to prevent ID collisions with existing schema objects.

#### Scenario: New tables get non-conflicting IDs after restart
- **WHEN** 3 tables are created (assigned IDs 0,1,2), the emulator is restarted, and a 4th table is created
- **THEN** the 4th table gets ID 3 (not 0), preventing conflicts with existing LevelDB data

### Requirement: Disk cleanup on database drop
The emulator SHALL delete the LevelDB directory (`--data_dir/{database_name}/`) from disk when a database is dropped via `DropDatabase` RPC. This prevents orphaned data from accumulating on disk.

#### Scenario: Dropped database data removed from disk
- **WHEN** a database is dropped via `DropDatabase` RPC while `--data_dir` is set
- **THEN** the corresponding `--data_dir/{database_name}/` directory and all its contents are deleted

#### Scenario: Dropped database does not reappear after restart
- **WHEN** a database is dropped, the emulator is restarted
- **THEN** the database does not reappear in `ListDatabases` and no LevelDB files exist for it

### Requirement: Gateway forwards data_dir flag
The Go gateway (`gateway_main`) SHALL accept a `--data_dir` flag and forward it to the C++ emulator subprocess (`emulator_main`). This allows the gateway to be the single entry point for configuration.

#### Scenario: Gateway passes data_dir to emulator
- **WHEN** the gateway is started with `--data_dir=/path/to/data`
- **THEN** the emulator subprocess receives `--data_dir=/path/to/data` in its command-line arguments
