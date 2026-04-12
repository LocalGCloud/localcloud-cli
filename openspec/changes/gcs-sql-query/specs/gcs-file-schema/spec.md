## ADDED Requirements

### Requirement: File schema detection endpoint
The system SHALL provide an endpoint `GET /_localcloud/gcs/file-schema` that auto-detects the schema (column names and types) of a queryable GCS file by querying the BigQuery emulator with a `LIMIT 0` query.

#### Scenario: Detect Parquet file schema
- **WHEN** client calls `GET /_localcloud/gcs/file-schema?bucket=my-bucket&object=data.parquet`
- **THEN** the endpoint SHALL return a JSON response with `{ "columns": [{ "name": "id", "type": "BIGINT" }, { "name": "name", "type": "VARCHAR" }, ...] }`
- **AND** the types SHALL reflect DuckDB's inferred types

#### Scenario: Detect CSV file schema
- **WHEN** client calls `GET /_localcloud/gcs/file-schema?bucket=reports&object=monthly.csv`
- **THEN** the endpoint SHALL return detected columns with types inferred from CSV content (using DuckDB's `auto_detect=true`)

#### Scenario: Detect JSON file schema
- **WHEN** client calls `GET /_localcloud/gcs/file-schema?bucket=logs&object=events.jsonl`
- **THEN** the endpoint SHALL return detected columns based on JSON key analysis

#### Scenario: Unsupported file format
- **WHEN** client calls `GET /_localcloud/gcs/file-schema?bucket=assets&object=logo.png`
- **THEN** the endpoint SHALL return a 400 error with message indicating the file format is not queryable

#### Scenario: File does not exist
- **WHEN** client calls `GET /_localcloud/gcs/file-schema?bucket=missing&object=nope.parquet`
- **THEN** the endpoint SHALL return a 404 error with descriptive message

### Requirement: File format detection by extension
The system SHALL determine the query format based on file extension. The following mappings SHALL be used:
- `.parquet` → `read_parquet()`
- `.csv` → `read_csv()`
- `.json` → `read_json()`
- `.jsonl`, `.ndjson` → `read_json()`

#### Scenario: Standard extensions
- **WHEN** a file has extension `.parquet`, `.csv`, `.json`, `.jsonl`, or `.ndjson`
- **THEN** the system SHALL use the corresponding DuckDB reader function

#### Scenario: Unknown extension
- **WHEN** a file has an extension not in the supported list (e.g., `.txt`, `.xml`, `.avro`)
- **THEN** the system SHALL treat the file as non-queryable

### Requirement: Schema displayed in explorer tree
When a file's schema is detected, the explorer tree SHALL display column names and types beneath the file node, similar to how table columns appear under database tables in the standard SQL Editor.

#### Scenario: File with detected schema
- **WHEN** user expands a Parquet file node in the explorer tree
- **THEN** the system SHALL call the schema detection endpoint and display each column with its name and type

#### Scenario: Schema loading state
- **WHEN** the schema detection endpoint is still loading
- **THEN** the file node SHALL show a loading indicator

#### Scenario: Schema detection failure
- **WHEN** schema detection fails (e.g., corrupt file)
- **THEN** the file node SHALL show an error indicator and the file SHALL still be clickable for manual querying
