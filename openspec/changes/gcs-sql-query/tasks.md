## 1. Backend — File Schema Detection Endpoint

- [x] 1.1 Add `GET /_localcloud/gcs/file-schema` endpoint to `QueryService.java` (or new `GcsQueryService.java`) that accepts `bucket` and `object` query params
- [x] 1.2 Implement format detection from file extension (.parquet → read_parquet, .csv → read_csv, .json/.jsonl/.ndjson → read_json)
- [x] 1.3 Build a `LIMIT 0` query using the appropriate `read_*('gs://bucket/object')` function and proxy it to BigQuery emulator at port 9050
- [x] 1.4 Parse the BigQuery response schema fields and return `{ columns: [{ name, type }] }` JSON
- [x] 1.5 Handle error cases: unsupported format (400), file not found (404), parse failure (500 with message)

## 2. Backend — GCS File Listing Enhancement

- [x] 2.1 Verify existing `/_localcloud/browse/gcs` endpoint returns object names with extensions (needed for client-side filtering)
- [x] 2.2 If needed, add file size to the browse response so the frontend can show size info and warn on large files

## 3. Frontend — GCS SQL Service Configuration

- [x] 3.1 Remove `gcs` from `NON_SQL_SERVICES` set in `ServiceExplorer.jsx`
- [x] 3.2 Add GCS to `SQL_SERVICES` array with `id: 'gcs'`, `dialect: 'bigquery'`, `dialectLabel: 'BigQuery SQL'`, and a placeholder query using `read_parquet`
- [x] 3.3 Add GCS to `SERVICE_SCHEMAS` with an empty tables array (schemas will be loaded dynamically from files)

## 4. Frontend — GCS File Explorer Tree

- [x] 4.1 Create a new `GcsFileExplorer` component (or conditional branch in SQLEditor) that replaces the database/table tree with a bucket/file tree
- [x] 4.2 Fetch GCS bucket and object listing from `/_localcloud/browse/gcs` on mount
- [x] 4.3 Filter objects client-side to only show queryable extensions (.parquet, .csv, .json, .jsonl, .ndjson)
- [x] 4.4 Render tree: bucket nodes (folder icon) → file nodes (file icon with format badge)
- [x] 4.5 Show non-queryable file count as a greyed-out label per bucket (e.g., "+3 other files")

## 5. Frontend — File Schema Detection & Display

- [x] 5.1 When a file node is expanded in the explorer tree, call `/_localcloud/gcs/file-schema?bucket=X&object=Y`
- [x] 5.2 Display detected columns under the file node (same column icon and type badge as table columns)
- [x] 5.3 Show loading spinner while schema is being detected
- [x] 5.4 Show error indicator if schema detection fails; keep file clickable for manual querying

## 6. Frontend — Sample Query Generation

- [x] 6.1 When a file is clicked, generate the appropriate `read_*()` query based on format:
  - Parquet: `SELECT * FROM read_parquet('/var/lib/.../file.parquet') LIMIT 100`
  - CSV: `SELECT * FROM read_csv('/var/lib/.../file.csv', auto_detect=true, header=true) LIMIT 100`
  - JSON: `SELECT * FROM read_json('/var/lib/.../file.json', auto_detect=true) LIMIT 100`
- [x] 6.2 Populate the editor with the generated query (replacing current text)
- [x] 6.3 Ensure queries route through the existing `api.query('bigquery', sql)` path since GCS queries execute on the BigQuery emulator

## 7. Frontend — SQL Editor Integration

- [x] 7.1 Detect when active service is `gcs` in the SQLEditor component and switch to file explorer mode instead of schema tree mode
- [x] 7.2 Ensure the dialect badge shows "BIGQUERY SQL" for GCS
- [x] 7.3 Ensure query history entries for GCS queries are labeled with "Cloud Storage" service name
- [x] 7.4 Ensure Clear button resets to a generic GCS placeholder

## 8. Testing & Verification

- [x] 8.1 Upload test files to GCS emulator: a .parquet, .csv, and .jsonl file
- [x] 8.2 Verify schema detection works for all three formats via the endpoint
- [x] 8.3 Verify SQL queries execute and return correct results in the browser
- [x] 8.4 Verify file explorer shows correct file tree with format filtering
- [x] 8.5 Verify error handling: query non-existent file, query corrupt file, query unsupported format
