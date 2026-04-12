## ADDED Requirements

### Requirement: GCS SQL Editor shows file-aware workspace
The Service Explorer SHALL display a functional SQL Editor when Cloud Storage is selected, instead of the "SQL Editor not available" fallback. The editor SHALL use the BigQuery SQL dialect and route queries through the BigQuery emulator.

#### Scenario: User opens SQL Editor for Cloud Storage
- **WHEN** user navigates to Cloud Storage in the Service Explorer and clicks the SQL Editor tab
- **THEN** the system SHALL display the SQL workspace with a file explorer panel on the left and a query editor on the right
- **AND** the dialect badge SHALL display "BIGQUERY SQL"

#### Scenario: User switches from BigQuery to Cloud Storage SQL Editor
- **WHEN** user is on BigQuery SQL Editor and clicks Cloud Storage in the sidebar
- **THEN** the SQL Editor SHALL reactively update to show the GCS file explorer and reset the query text

### Requirement: File explorer shows queryable GCS files
The SQL Editor explorer panel for Cloud Storage SHALL display a tree of buckets and their objects, filtered to show only queryable file formats (.parquet, .csv, .json, .jsonl, .ndjson). Non-queryable files SHALL be hidden or visually distinguished.

#### Scenario: Bucket with mixed file types
- **WHEN** a GCS bucket contains files `data.parquet`, `report.csv`, `events.json`, `logo.png`, and `readme.txt`
- **THEN** the explorer tree SHALL show `data.parquet`, `report.csv`, and `events.json` as queryable files
- **AND** `logo.png` and `readme.txt` SHALL be hidden or shown as non-queryable

#### Scenario: Empty bucket
- **WHEN** a GCS bucket contains no queryable files
- **THEN** the explorer SHALL show the bucket with a "No queryable files" indicator

#### Scenario: Multiple buckets
- **WHEN** the GCS emulator has multiple buckets
- **THEN** the explorer SHALL show each bucket as a top-level node with its queryable files nested underneath

### Requirement: Clicking a file generates a sample query
When a user clicks a queryable file in the explorer, the system SHALL auto-generate a sample SQL query using the appropriate DuckDB reader function and populate the editor.

#### Scenario: Click a Parquet file
- **WHEN** user clicks `data.parquet` in bucket `my-bucket`
- **THEN** the editor SHALL populate with `SELECT * FROM read_parquet('gs://my-bucket/data.parquet') LIMIT 100`

#### Scenario: Click a CSV file
- **WHEN** user clicks `report.csv` in bucket `analytics`
- **THEN** the editor SHALL populate with `SELECT * FROM read_csv('gs://analytics/report.csv', auto_detect=true, header=true) LIMIT 100`

#### Scenario: Click a JSON file
- **WHEN** user clicks `events.jsonl` in bucket `logs`
- **THEN** the editor SHALL populate with `SELECT * FROM read_json('gs://logs/events.jsonl', auto_detect=true) LIMIT 100`

#### Scenario: User has already typed a custom query
- **WHEN** user has manually edited the query text and clicks a file
- **THEN** the system SHALL replace the query with the new sample query (since clicking a file signals intent to explore that file)

### Requirement: GCS queries execute through BigQuery emulator
GCS file queries SHALL be routed to the existing `/_localcloud/query` endpoint with `service: "bigquery"`. The SQL SHALL contain DuckDB `read_*()` functions with `gs://` URIs that the BigQuery emulator resolves via `STORAGE_EMULATOR_HOST`.

#### Scenario: Successful Parquet query
- **WHEN** user runs `SELECT * FROM read_parquet('gs://bucket/data.parquet') LIMIT 10`
- **THEN** the query SHALL execute against the BigQuery emulator
- **AND** results SHALL display in the results table with correct column names and data

#### Scenario: Query with no LIMIT
- **WHEN** user runs a query without a LIMIT clause on a large file
- **THEN** the query SHALL execute but the system MAY display a performance warning

#### Scenario: Query against non-existent file
- **WHEN** user runs a query referencing a file that does not exist in GCS
- **THEN** the system SHALL display the error from the BigQuery emulator in the error status bar

#### Scenario: Query against malformed file
- **WHEN** user runs a query against a file whose content does not match its extension
- **THEN** the system SHALL display the DuckDB parse error in the error status bar
