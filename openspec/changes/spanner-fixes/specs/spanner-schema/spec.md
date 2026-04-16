## ADDED Requirements

### Requirement: Schema endpoint returns Spanner table metadata
The `/_localcloud/schema/spanner` endpoint SHALL return table names and column definitions by querying the Spanner REST API for DDL statements and parsing CREATE TABLE statements.

#### Scenario: Schema returns tables and columns
- **WHEN** client calls `GET /_localcloud/schema/spanner?instance=local-instance&database=users_db`
- **THEN** response contains `{"tables": [{"name": "Persons", "columns": [{"name": "Id", "type": "STRING(36)"}, ...]}]}`

#### Scenario: Schema for empty database
- **WHEN** database has no tables
- **THEN** response contains `{"tables": []}`
