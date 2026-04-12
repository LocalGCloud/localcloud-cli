## Why

The Cloud Storage SQL Editor currently shows a "not available" fallback, but GCS is one of the most data-heavy services in LocalCloud. Users upload Parquet, CSV, and JSON files and need to inspect and query them. The BigQuery emulator v2 already has DuckDB-powered external table support that can query files via `gs://` URIs — we just need to connect the GCS console UI to it. Zero new engine dependencies required.

## What Changes

- Remove Cloud Storage from the non-SQL service list in the Service Explorer
- Add a file-aware SQL workspace for GCS that shows buckets and queryable files (.parquet, .csv, .json, .jsonl) in the explorer tree
- Add a backend endpoint that proxies GCS file queries through the BigQuery emulator's external table mechanism (temp external table creation, query execution, cleanup)
- Add a schema detection endpoint that auto-discovers column names and types from GCS files
- Auto-generate sample queries when a file is selected (e.g., `SELECT * FROM read_parquet('gs://bucket/file.parquet') LIMIT 100`)
- Display the BigQuery SQL dialect badge since queries route through the BQ emulator

## Capabilities

### New Capabilities
- `gcs-file-query`: SQL querying of Parquet, CSV, and JSON/JSONL files stored in GCS buckets via the BigQuery emulator's external table support
- `gcs-file-schema`: Auto-detection of file schemas (columns and types) for queryable GCS objects

### Modified Capabilities
<!-- No existing spec-level requirement changes -->

## Impact

- **Backend (Java gateway)**: New REST endpoints for GCS file queries and schema detection, proxying to BigQuery emulator at port 9050
- **Frontend (ServiceExplorer.jsx)**: GCS removed from `NON_SQL_SERVICES`, new file-browser explorer tree, query generation logic
- **BigQuery emulator v2**: No changes needed — already supports external tables with `gs://` URIs and `STORAGE_EMULATOR_HOST`
- **GCS emulator**: No changes — existing browse API reused for file listing
- **Dependencies**: None new — all infrastructure already exists
