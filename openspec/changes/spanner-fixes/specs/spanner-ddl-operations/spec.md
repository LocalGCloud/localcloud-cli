## ADDED Requirements

### Requirement: Create Spanner instance via mutate API
The system SHALL support creating Spanner instances via `POST /_localcloud/mutate/spanner/createInstance`.

#### Scenario: Create instance
- **WHEN** client sends `{"instance": "my-instance", "displayName": "My Instance"}`
- **THEN** a new Spanner instance is created via the Spanner REST API and status "created" is returned

### Requirement: Create Spanner database via mutate API
The system SHALL support creating databases via `POST /_localcloud/mutate/spanner/createDatabase`.

#### Scenario: Create database with DDL
- **WHEN** client sends `{"instance": "my-instance", "database": "my-db", "ddl": ["CREATE TABLE Foo (Id STRING(36) NOT NULL) PRIMARY KEY (Id)"]}`
- **THEN** a new database is created with the specified DDL statements

### Requirement: Execute DDL via mutate API
The system SHALL support executing DDL statements via `POST /_localcloud/mutate/spanner/ddl`.

#### Scenario: Create table
- **WHEN** client sends `{"instance": "my-instance", "database": "my-db", "statements": ["CREATE TABLE Bar (Id STRING(36) NOT NULL, Name STRING(100)) PRIMARY KEY (Id)"]}`
- **THEN** the DDL is executed against the Spanner database
