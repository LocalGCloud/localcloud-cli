## Context

LocalCloud's Service Explorer has a SQL Editor tab for each service. Services backed by PostgreSQL or BigQuery already support SQL queries through the `/_localcloud/query` endpoint in `QueryService.java`. Cloud Storage (GCS) currently shows a "SQL Editor not available" fallback because it has no database backing.

However, the BigQuery emulator v2 (built in-house at `local_cloud_dependencies/bigquery-emulator-v2/`) already supports **external tables** via `engine/external_table.py`. It can query Parquet, CSV, and JSON files at `gs://` URIs using DuckDB's `read_parquet()`, `read_csv()`, and `read_json()` functions. The GCS emulator (fake-gcs-server) runs at port 4443, and the BQ emulator resolves `gs://` URIs via `STORAGE_EMULATOR_HOST` to the GCS emulator's HTTP download URL.

The `QueryService.java` already routes `service: "bigquery"` queries to the BQ emulator REST API at port 9050. The schema endpoint also fetches BigQuery dataset/table metadata.

## Goals / Non-Goals

**Goals:**
- Let users query Parquet, CSV, and JSON/JSONL files in GCS buckets using SQL from the Service Explorer
- Auto-detect file schemas (column names and types) when a file is selected
- Reuse the existing BigQuery emulator v2 infrastructure — no new query engine
- Provide a file-aware explorer tree showing buckets and queryable files
- Generate sample queries automatically when a file is clicked

**Non-Goals:**
- Querying non-structured files (images, binaries, text)
- Writing query results back to GCS files
- Joining across multiple GCS files in a single query (future enhancement)
- Querying files when GCS is routed to real Google Cloud (future — would need credentials)
- Adding DuckDB or any new query engine dependency

## Decisions

### 1. Route GCS queries through BigQuery emulator as `service: "bigquery"`

**Decision**: GCS file queries will be sent to the existing `/_localcloud/query` endpoint with `service: "bigquery"`. The SQL will use DuckDB's `read_parquet('gs://...')` / `read_csv('gs://...')` / `read_json('gs://...')` functions directly in the query — no temporary external table creation needed.

**Rationale**: The BQ emulator's DuckDB engine already handles these reader functions natively. Sending `SELECT * FROM read_parquet('gs://my-bucket/data.parquet') LIMIT 100` as a BigQuery query works out of the box because SQLGlot passes unrecognized functions through and DuckDB resolves `gs://` URIs via `STORAGE_EMULATOR_HOST`. This avoids the complexity of creating/cleaning temporary external tables.

**Alternative considered**: Creating temporary external tables via `CREATE EXTERNAL TABLE` for each query. Rejected because it adds DDL overhead, requires cleanup, and the `read_*()` function approach is simpler and stateless.

### 2. Schema detection via a new lightweight endpoint

**Decision**: Add a new endpoint `GET /_localcloud/gcs/file-schema?bucket={bucket}&object={object}` that:
1. Determines the file format from the extension
2. Sends a `SELECT * FROM read_parquet('gs://...') LIMIT 0` query to the BQ emulator
3. Extracts column names and types from the response schema
4. Returns `{ columns: [{ name, type }] }`

**Rationale**: Using `LIMIT 0` fetches the schema without reading any data. The BQ emulator already returns schema metadata in query responses. This is cheaper than creating temporary external tables just for schema detection.

### 3. Frontend reuses existing SQL Editor with GCS-specific explorer

**Decision**: Remove `gcs` from `NON_SQL_SERVICES` and add it to `SQL_SERVICES` with dialect `bigquery` and a custom explorer mode. The explorer tree will show buckets → queryable files (filtered by extension) instead of database → tables → columns. When a file is clicked, the schema detection endpoint is called and columns are shown under the file node.

**Rationale**: Reusing the existing SQL Editor component means all query execution, results rendering, history, and keyboard shortcuts work unchanged. Only the explorer panel and placeholder generation differ.

### 4. File format detection by extension

**Decision**: Detect file format using file extension: `.parquet` → Parquet, `.csv` → CSV, `.json` → JSON, `.jsonl`/`.ndjson` → NEWLINE_DELIMITED_JSON. Non-queryable files are shown greyed out in the explorer.

**Rationale**: Extension-based detection is simple and reliable for emulator use. Magic-byte detection would require downloading file headers, adding latency. Users control file naming in their local environment.

### 5. Query wrapping in the frontend, not backend

**Decision**: The frontend generates the full `read_*()` SQL. When a user types `SELECT * FROM data LIMIT 10` after clicking a file, the frontend will have already set the placeholder to `SELECT * FROM read_parquet('gs://bucket/data.parquet') LIMIT 10`. The backend receives and executes the complete BigQuery-compatible SQL.

**Rationale**: Keeps the backend simple — no special GCS query translation layer. The frontend already knows the file path and format. Users can also write arbitrary SQL with `read_*()` functions for advanced use.

## Risks / Trade-offs

- **Large files**: DuckDB will attempt to scan the full file if no LIMIT is used. **Mitigation**: The placeholder always includes `LIMIT 100`. The frontend can show a warning for queries without LIMIT.
- **GCS emulator connectivity**: The BQ emulator must be able to reach the GCS emulator via `STORAGE_EMULATOR_HOST`. **Mitigation**: Both run in the same Docker container; the env var is already configured in supervisord.conf.
- **Format mismatch**: A `.csv` file that isn't actually CSV will produce an error. **Mitigation**: DuckDB's `auto_detect=true` handles many edge cases. Errors surface clearly in the SQL results pane.
- **No schema caching**: Schema detection queries the BQ emulator each time a file is clicked. **Mitigation**: `LIMIT 0` queries are fast (~10ms). If needed, the frontend can cache schemas per file path.
