## ADDED Requirements

### Requirement: Console provides create buttons for Spanner resources
The Data Explorer SHALL show "Create Instance", "Create Database", and "Create Table" buttons when viewing Spanner.

#### Scenario: Create instance from console
- **WHEN** user clicks "Create Instance" and fills in instance name
- **THEN** the instance is created via mutate API and the data browser refreshes

#### Scenario: Create database from console
- **WHEN** user clicks "Create Database" and fills in database name
- **THEN** the database is created under the selected instance and the data browser refreshes

#### Scenario: Create table from console
- **WHEN** user clicks "Create Table" and enters a CREATE TABLE DDL statement
- **THEN** the DDL is executed against the selected database and the schema refreshes
