- **Blocker — PENDING Storage Write streams are committed during `FinalizeWriteStream` instead of `BatchCommitWriteStreams`**
  - **Affected file/function:** `src/bigquery_emulator/api/grpc/write_server.py::FinalizeWriteStream`; test expectation in `tests/test_grpc_storage.py::test_pending_stream_rows_visible_after_finalize`.
  - **Evidence:** `FinalizeWriteStream` inserts `state.pending_rows` directly into DuckDB and sets `state.committed = True` (`src/bigquery_emulator/api/grpc/write_server.py:193-200`), while `BatchCommitWriteStreams` is reduced to idempotent cleanup (`src/bigquery_emulator/api/grpc/write_server.py:229-235`). The new test explicitly expects rows to become visible after finalize (`tests/test_grpc_storage.py:256-288`).
  - **Impact:** BigQuery Storage Write PENDING stream semantics are broken: finalized streams become visible before batch commit, multi-stream batch commits cannot be atomic, and a failed/omitted `BatchCommitWriteStreams` still leaks rows.
  - **Suggested fix:** Make `FinalizeWriteStream` only mark the stream finalized and return the pending row count. Move insertion of all finalized PENDING streams into `BatchCommitWriteStreams`, validate all streams first, then commit in a single transaction (or all-or-nothing best effort) and mark committed only after success.

- **Major — Append offset bookkeeping can drop valid rows after empty/schema-only requests and does not validate row-position conflicts**
  - **Affected file/function:** `src/bigquery_emulator/api/grpc/write_server.py::AppendRows`.
  - **Evidence:** Rows are decoded only inside `if rows_data and rows_data.rows and rows_data.rows.serialized_rows` (`src/bigquery_emulator/api/grpc/write_server.py:136-153`), but offsets are marked committed outside that block (`src/bigquery_emulator/api/grpc/write_server.py:155-159`). Later, any request with the same offset is returned as idempotent success without comparing row count/content (`src/bigquery_emulator/api/grpc/write_server.py:120-127`).
  - **Impact:** A first request that carries only schema or no rows with `offset=0` burns offset 0; a subsequent real append at offset 0 is silently skipped. Conflicting duplicate offsets with different rows are also accepted, causing data loss and BigQuery Storage compatibility issues.
  - **Suggested fix:** Track committed offset ranges only after successfully appending rows; do not record offsets for empty requests. Validate `offset == current_stream_row_count` for new appends and return an offset/conflict error for duplicate offsets whose row payload/range is not known to be the same. For PENDING streams, defer persistence of committed offsets until batch commit or persist them as pending-only state.

- **Major — Storage Read selected field order can corrupt Avro/Arrow row values**
  - **Affected file/function:** `src/bigquery_emulator/api/grpc/read_server.py::_get_bq_schema_fields`, `ReadRows`, `_read_avro`/`_read_arrow`.
  - **Evidence:** `ReadRows` selects columns in request order (`cols = ", ".join(f'"{f}"' for f in state.selected_fields)`, `src/bigquery_emulator/api/grpc/read_server.py:166-168`), but `_get_bq_schema_fields` filters stored table schema with a `set`, preserving table schema order instead of request/query order (`src/bigquery_emulator/api/grpc/read_server.py:394-397`). Serialization then maps row values to schema fields by index (`src/bigquery_emulator/api/grpc/read_server.py:225-229`).
  - **Impact:** If a client requests selected fields in a different order than the table schema, values are assigned to the wrong field names/types in Avro/Arrow output.
  - **Suggested fix:** Ensure the SELECT list and `schema_fields` use the exact same order. Either select columns in `schema_fields` order or build `schema_fields` in `selected_fields` order; additionally, serialize by column name rather than positional index where possible.

- **Major — REST LOAD jobs can leave partial data when multiple source URIs are used**
  - **Affected file/function:** `src/bigquery_emulator/api/rest/jobs.py::_handle_load_job`.
  - **Evidence:** `_handle_load_job` loops over `source_uris` and calls `_load_data` for each URI immediately (`src/bigquery_emulator/api/rest/jobs.py:1401-1405`), then only marks the job failed in the outer `except` (`src/bigquery_emulator/api/rest/jobs.py:1419-1420`). There is no transaction, staging table, or rollback.
  - **Impact:** If URI 1 loads successfully and URI 2 fails, the job is reported failed but the destination table has already been mutated. This violates BigQuery load-job atomicity and is especially dangerous with `WRITE_TRUNCATE`.
  - **Suggested fix:** Read/validate all sources before mutating the destination, or load into a temporary staging table and swap/append only after every source succeeds. Wrap destination mutation in a transaction where supported, and add a negative test with two URIs where the second fails.

- **Major — REST LOAD job default source format is incompatible with BigQuery**
  - **Affected file/function:** `src/bigquery_emulator/api/rest/jobs.py::_handle_load_job`.
  - **Evidence:** The new REST load path defaults missing `sourceFormat` to `NEWLINE_DELIMITED_JSON` (`src/bigquery_emulator/api/rest/jobs.py:1395`). Added tests only cover explicit JSON formats (`tests/test_integration.py:402-404`, `tests/test_developer_parity_gaps.py:370-372`, `tests/test_developer_parity_gaps.py:419-421`).
  - **Impact:** BigQuery clients that omit `sourceFormat` for a standard CSV load will be parsed as JSON and fail or load incorrectly.
  - **Suggested fix:** Use BigQuery's REST load default of `CSV` for `configuration.load` jobs, while keeping upload-specific defaults separate if needed. Add a load-job test that omits `sourceFormat` and loads CSV.

- **Major — COPY `WRITE_EMPTY` and unsupported dispositions are treated as successful no-ops**
  - **Affected file/function:** `src/bigquery_emulator/api/rest/jobs.py::_handle_copy_job`.
  - **Evidence:** `write_disposition` is read from the request (`src/bigquery_emulator/api/rest/jobs.py:1305-1307`), but only `WRITE_TRUNCATE` and `WRITE_APPEND` are special-cased; all other values use `CREATE TABLE IF NOT EXISTS ... AS ...` (`src/bigquery_emulator/api/rest/jobs.py:1336-1342`). The job then reports success and `copiedRows` from the destination row count (`src/bigquery_emulator/api/rest/jobs.py:1343-1349`).
  - **Impact:** `WRITE_EMPTY` succeeds against an existing non-empty destination without copying anything or reporting the required error. Unknown write dispositions also silently succeed, hiding client misconfiguration.
  - **Suggested fix:** Handle `WRITE_EMPTY` explicitly: fail if destination exists and has rows; if it exists empty, insert; if absent, create. Reject unsupported `writeDisposition` values with a BigQuery-style invalid error.

- **Minor — `insertAll` insertId dedupe is keyed by row content and dict string order**
  - **Affected file/function:** `src/bigquery_emulator/api/rest/tabledata.py::insert_all`.
  - **Evidence:** The dedupe key includes both `insertId` and `str(json_row)` (`src/bigquery_emulator/api/rest/tabledata.py:70-75`).
  - **Impact:** Retrying the same logical insertId with fields serialized in a different order, or with changed row content, bypasses dedupe and can double-insert rows. This weakens BigQuery streaming insert compatibility for client retries.
  - **Suggested fix:** Key dedupe on `(project_id, dataset_id, table_id, insertId)` only, ideally with a bounded TTL/LRU cache to avoid unbounded memory growth. Add retry tests with same insertId and reordered JSON keys.
