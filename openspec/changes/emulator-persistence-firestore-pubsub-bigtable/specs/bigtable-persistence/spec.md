## ADDED Requirements

### Requirement: Bigtable tables and row data persist across Docker restarts
The system SHALL persist all Bigtable tables (with column families) and row data across Docker container restarts. When the container is restarted with the same Docker volume, all previously created tables and their data MUST be available.

#### Scenario: Tables and rows survive restart
- **WHEN** a developer creates a Bigtable table, writes rows, restarts the container
- **THEN** the table exists with its column families and all rows are readable via `ReadRows`

#### Scenario: Column family changes survive restart
- **WHEN** a developer adds a column family to a table, restarts the container
- **THEN** the new column family is present and writable

#### Scenario: Drop-in replacement for cbtemulator
- **WHEN** the developer's application uses `BIGTABLE_EMULATOR_HOST=localhost:8087`
- **THEN** the persistent emulator accepts the same gRPC API calls as the original `cbtemulator` with no client code changes

### Requirement: Replace cbtemulator with little_bigtable
The system SHALL use the `little_bigtable` open-source emulator (Apache 2.0, Go, SQLite-backed) instead of Google's closed-source `cbtemulator`. The binary SHALL be included in the Docker image and configured with a SQLite database path on the persistent Docker volume.

#### Scenario: SQLite file on Docker volume
- **WHEN** the container starts with `little_bigtable`
- **THEN** a SQLite database file is created at `/var/lib/localcloud/bigtable-data/bigtable.db`

#### Scenario: Data accessible after restart
- **WHEN** the container is restarted with the same Docker volume
- **THEN** `little_bigtable` opens the existing SQLite file and all tables/rows are immediately available
