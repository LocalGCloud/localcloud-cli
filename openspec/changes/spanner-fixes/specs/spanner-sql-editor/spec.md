## ADDED Requirements

### Requirement: Spanner SQL editor passes instance and database parameters
The SQL editor SHALL pass `instance` and `database` parameters in the query request body when the active service is Spanner. The parameters SHALL be populated from dropdown selectors in the SQL editor toolbar.

#### Scenario: User executes a Spanner query
- **WHEN** user types a SQL query and clicks Run with Spanner selected
- **THEN** the request body includes `service: "spanner"`, `sql: "..."`, `instance: "local-instance"`, `database: "users_db"`

#### Scenario: Instance and database dropdowns auto-populate
- **WHEN** user opens the SQL editor for Spanner
- **THEN** the instance dropdown is populated from `/_localcloud/browse/spanner` and the first instance is auto-selected
- **THEN** the database dropdown is populated from the selected instance's databases and the first database is auto-selected

#### Scenario: No instances available
- **WHEN** no Spanner instances exist
- **THEN** the SQL editor shows a message "No Spanner instances. Create one from the Data Explorer."
