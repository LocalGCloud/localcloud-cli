---
baseline_commit: b522aab565d5948d1707f824e7429b7d9c871e00
epic: architecture-health
story_key: arch-4-flyway-migrations
---

# Story: arch-4-flyway-migrations

## Story

**As a** developer evolving the localcloud database schema,
**I want** versioned, auditable database migrations via Flyway,
**So that** I can add, modify, or rollback table schemas safely and know exactly which migrations have been applied.

## Acceptance Criteria

1. **AC1**: Flyway (`org.flywaydb:flyway-core` + `org.flywaydb:flyway-database-postgresql`) is added as a dependency in `build.gradle`
2. **AC2**: On startup, Flyway runs migrations from `src/main/resources/db/migration/` BEFORE any service registration
3. **AC3**: Existing `SchemaManager.initialize()` is converted to Flyway migration `V1__initial_schema.sql`
4. **AC4**: All ALTER TABLE ADD COLUMN IF NOT EXISTS statements become separate versioned migrations (V2, V3, etc.)
5. **AC5**: Flyway migration runs idempotently — existing databases with schema already applied work without errors
6. **AC6**: The `schema_version` table (previously created by SchemaManager) is dropped in favor of Flyway's built-in `flyway_schema_history`
7. **AC7**: A `V{next}__add_flyway_schema_history.sql` migration creates `flyway_schema_history` for clean-install databases
8. **AC8**: All existing tests pass; CI pipeline works without database pre-seeding

## Tasks/Subtasks

### Task 1: Add Flyway dependency
- [ ] Add `implementation 'org.flywaydb:flyway-core:10.x'` to build.gradle
- [ ] Add `implementation 'org.flywaydb:flyway-database-postgresql:10.x'` to build.gradle
- [ ] Run `./gradlew dependencies` to verify resolution

### Task 2: Create migration files
- [ ] Create directory: `localcloud-server/src/main/resources/db/migration/`
- [ ] Extract all `CREATE TABLE IF NOT EXISTS` statements from `SchemaManager.initialize()` into `V1__initial_schema.sql`
- [ ] Extract each `ALTER TABLE ADD COLUMN IF NOT EXISTS` statement into its own versioned migration:
  - `V2__add_project_labels.sql` — projects.labels + projects.state
  - `V3__add_secret_replication.sql` — secrets.replication + secrets.expire_at
  - (continue for each ALTER TABLE in SchemaManager)
- [ ] Add `V{N+1}__drop_legacy_schema_version.sql` to drop the old `schema_version` table
- [ ] Ensure all SQL uses `CREATE TABLE IF NOT EXISTS` (idempotent for existing DBs)

### Task 3: Update LocalCloudApplication
- [ ] Add `FlywayMigrationRunner` class in `com.localcloud.persistence`
- [ ] `FlywayMigrationRunner` takes `PostgresDataSource`, constructs Flyway instance with correct schema version table, runs `migrate()`
- [ ] In `LocalCloudApplication.start()`, call `flywayMigrationRunner.migrate()` before any service registration
- [ ] Remove `SchemaManager` class (or deprecate it)
- [ ] Log migration status on startup: version number, migration count

### Task 4: Handle backward compatibility
- [ ] Flyway's default `flyway_schema_history` table is compatible with existing databases
- [ ] Flyway detects baseline — for existing DBs, set `baselineOnMigrate = true` with `baselineVersion = "1"`
- [ ] Test against a database that already has the schema applied (from a previous localcloud version)
- [ ] Verify Flyway skips already-applied migrations via checksums

### Task 5: Update tests
- [ ] Tests that relied on `SchemaManager` directly should use Flyway test utilities or H2 in-memory DB
- [ ] Add `FlywayMigrationRunnerTest` — verifies migrations run without errors
- [ ] Ensure `PostgresDataSource` test configuration uses a test database

### Task 6: Verify
- [ ] Run `./gradlew build` — all tests pass
- [ ] Fresh start with empty database: verify all migrations apply
- [ ] Restart with existing database: verify Flyway reports "No migrations to apply"
- [ ] Verify all emulator data persists across restart

## Dev Notes

### Architecture context
- `SchemaManager.initialize()` runs raw `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD COLUMN IF NOT EXISTS` statements
- The old `schema_version` table tracks a single version number — Flyway's `flyway_schema_history` replaces this
- Flyway runs BEFORE any service registration in `LocalCloudApplication.start()`

### Key design decisions
- **Flyway over Liquibase**: Flyway is simpler (SQL files only), already used in other localcloud-adjacent projects, and doesn't require XML/YAML/JSON changelogs
- **baselineOnMigrate = true**: For backward compatibility with existing databases
- **Clean migration strategy**: Each ALTER TABLE becomes its own V file. No monolithic migration.
- **Test database**: Use `@FlywayTest` annotation or configure test properties for a separate test database

### Migration file structure
```
src/main/resources/db/migration/
├── V1__initial_schema.sql          # All CREATE TABLE IF NOT EXISTS
├── V2__add_project_labels.sql      # ALTER TABLE projects ADD COLUMN labels, state
├── V3__add_secret_fields.sql       # ALTER TABLE secrets ADD COLUMN replication, expire_at
├── V4__add_version_aliases.sql     # CREATE TABLE secret_version_aliases
├── ...
└── V{N+1}__cleanup_legacy.sql      # DROP TABLE IF EXISTS schema_version
```

### Risk: Existing databases
The biggest risk is Flyway failing on existing production databases. Mitigation:
- Set `baselineOnMigrate = true` with `baselineVersion = "1"` — Flyway treats existing schema as V1
- Migration V1 uses `CREATE TABLE IF NOT EXISTS` — idempotent on existing tables
- Migration V2+ uses `ALTER TABLE ADD COLUMN IF NOT EXISTS` — safe re-runs
- Test against a copy of an actual production database before deploying

### Files that will change
- **New**: `db/migration/V1__initial_schema.sql` through `V{N}__*.sql` (~15 files)
- **New**: `FlywayMigrationRunner.java` (~40 lines)
- **Modified**: `build.gradle` — add Flyway dependencies
- **Modified**: `LocalCloudApplication.java` — add Flyway call, remove SchemaManager
- **Deprecated**: `SchemaManager.java` — can be deleted after migration
- **New**: `FlywayMigrationRunnerTest.java`
