- **Blocker — PENDING write streams become visible on `FinalizeWriteStream` instead of `BatchCommitWriteStreams`**
  - **Affected:** `src/bigquery_emulator/api/grpc/write_server.py::FinalizeWriteStream`
  - **Exact diff evidence:**

    ```diff
    +        if state.pending_rows:
    +            _insert_rows(state, state.pending_rows)
    +            state.committed_rows += len(state.pending_rows)
    +            state.pending_rows.clear()
    +            self._pending_buffers[stream_name] = state.pending_rows
             state.finalized = True
    -        total = state.committed_rows + len(state.pending_rows)
    +        state.committed = True
    +        _persist_stream_state(stream_name, "COMMITTED")
    ```

    and:

    ```diff
    +            # FinalizeWriteStream already flushes PENDING buffers; BatchCommit is idempotent.
    ```

  - **Impact:** BigQuery Storage Write API pending streams are finalized before batch commit; making rows visible during finalize breaks atomic multi-stream commit, can expose data from streams that are never batch-committed, and can cause partial writes after client failures.
  - **Suggested fix:** Make `FinalizeWriteStream` only mark the stream finalized and return buffered row count. Move visibility/insertion for PENDING streams to `BatchCommitWriteStreams`, ideally committing all requested finalized streams atomically and keeping commit idempotent there.

- **Major — AppendRows offset idempotency accepts overlapping/out-of-order data**
  - **Affected:** `src/bigquery_emulator/api/grpc/write_server.py::AppendRows`
  - **Exact diff evidence:**

    ```diff
    +            if offset is not None and offset in committed_offsets:
    +                yield bqs_types.AppendRowsResponse(
    +                    append_result=bqs_types.AppendRowsResponse.AppendResult(
    +                        offset={"value": offset},
    +                    ),
    +                )
    +                continue
    +            if offset is not None and state.last_offset is not None and offset < state.last_offset:
    +                yield bqs_types.AppendRowsResponse(
    +                    error={"code": 6, "message": f"Offset before committed stream position: {offset}"}
    +                )
    ```

    and:

    ```diff
    +            if offset is not None:
    +                state.last_offset = max(offset, state.last_offset) if state.last_offset is not None else offset
    +                state.committed_offsets.add(offset)
    ```

  - **Impact:** The stream tracks only the starting offset, not the next expected row offset. After appending N rows at offset 0, an append at offset 1 is accepted because `1 < 0` is false, allowing overlapping duplicates/corruption. Empty or failed appends can also reserve offsets because the offset is recorded outside the row-decoding branch.
  - **Suggested fix:** Track `next_expected_offset = offset + len(decoded_rows)` after successful appends; reject non-duplicate offsets that do not equal the expected position. Only mark an offset committed after successful decode/insert/buffer, and treat duplicate offsets according to Storage Write API semantics.

- **Blocker — REST jobs can read URLs/local files and write arbitrary local paths**
  - **Affected:** `src/bigquery_emulator/api/rest/jobs.py::_read_load_uri`, `_handle_export_data`, `_handle_extract_job`, `_handle_external_query`
  - **Exact diff evidence:**

    ```diff
    +        if resolved.startswith(("http://", "https://")):
    +            with urlopen(resolved) as response:  # noqa: S310 - emulator/local developer URI
    +                return response.read()
    +    elif parsed.scheme in ("http", "https"):
    +        with urlopen(uri) as response:  # noqa: S310 - emulator/local developer URI
    +            return response.read()
    +
    +    path = _local_path_from_uri(uri)
    +    with open(path, "rb") as f:
    +        return f.read()
    ```

    ```diff
    +    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    +            with open(path, "w", encoding="utf-8") as f:
    +            conn.execute(f"COPY ({duckdb_sql}) TO ? (FORMAT CSV, HEADER true)", [path])
    ```

    ```diff
    +    ext = sqlite3.connect(db_path)
    ```

  - **Impact:** Any caller with BigQuery REST access can trigger SSRF (`http(s)`), read arbitrary local files via load URIs, write arbitrary local paths via EXPORT/EXTRACT, and open arbitrary SQLite files. In containerized deployments this can expose secrets, metadata endpoints, host-mounted volumes, or overwrite application files.
  - **Suggested fix:** Restrict load/export/extract/external-query paths to a configured emulator data directory or fake-GCS root after `realpath` normalization, reject `..`/absolute paths outside that root, disallow `http(s)` by default, and gate local file/SQLite access behind an explicit unsafe-dev flag.

- **Major — `selectedFields` is interpolated into SQL as an unescaped identifier list**
  - **Affected:** `src/bigquery_emulator/api/rest/tabledata.py::list_table_data`
  - **Exact diff evidence:**

    ```diff
    +    if selectedFields:
    +        selected_cols = [f.strip().split(".", 1)[0] for f in selectedFields.split(",") if f.strip()]
    +        selected_cols = list(dict.fromkeys(selected_cols))
    ...
    +        if selected_cols:
    +            select_list = ", ".join(f'"{col}"' for col in selected_cols)
    ...
    +            f'SELECT {select_list} FROM "{schema_name}"."{table_id}" LIMIT {maxResults} OFFSET {startIndex}'
    ```

  - **Impact:** A crafted field name containing `"` can break out of the quoted identifier and alter the generated SQL, causing SQL injection or request-time failures. It also silently truncates nested paths at the first dot, which can return the wrong columns for nested schemas.
  - **Suggested fix:** Validate every selected field as a supported BigQuery identifier/path before building SQL, escape embedded quotes if quoting is retained, or use the existing identifier sanitizer. Reject unsupported nested paths rather than truncating them.

- **Major — `insertAll` deduplication key includes row content and grows without bounds**
  - **Affected:** `src/bigquery_emulator/api/rest/tabledata.py::insert_all`
  - **Exact diff evidence:**

    ```diff
    +_seen_insert_ids: set[tuple[str, str, str, str, str]] = set()
    ...
    +                dedupe_key = (project_id, dataset_id, table_id, str(insert_id), str(json_row))
    +                if dedupe_key in _seen_insert_ids:
    +                    continue
    +                _seen_insert_ids.add(dedupe_key)
    ```

  - **Impact:** BigQuery best-effort dedupe is keyed by `insertId` for the destination, not by row payload. Retries with the same `insertId` but different key order/content will insert duplicates. The global in-memory set is also unbounded, creating a memory-exhaustion risk and losing dedupe on restart.
  - **Suggested fix:** Key dedupe by `(project_id, dataset_id, table_id, insertId)` only, store it in a bounded TTL/LRU cache (or persistent table if restart behavior matters), and document emulator limits.

- **Major — FARM_FINGERPRINT fallback contradicts the new test and still returns incompatible hashes**
  - **Affected:** `src/bigquery_emulator/engine/custom_functions.py::_register_farm_fingerprint`; `tests/test_stubs.py::test_farm_fingerprint_without_pyfarmhash_raises_install_error`
  - **Exact diff evidence:**

    ```diff
         except ImportError:
             conn.execute("CREATE OR REPLACE MACRO FARM_FINGERPRINT(val) AS hash(val::VARCHAR)")
             logger.warning(
    ```

    while the added test expects:

    ```diff
    +        with pytest.raises(Exception, match="FARM_FINGERPRINT requires pyfarmhash"):
    +            conn.execute("SELECT FARM_FINGERPRINT('abc')").fetchone()
    ```

  - **Impact:** If `farmhash` import fails, the implementation registers a DuckDB `hash()` fallback instead of raising the expected error, so the added test will fail under its monkeypatched missing-dependency path. In real environments where the package is absent/broken, the emulator silently returns non-BigQuery-compatible fingerprints.
  - **Suggested fix:** Replace the fallback macro with a function/macro that raises a clear “FARM_FINGERPRINT requires pyfarmhash” error, or remove/update the test if silent fallback is still desired. Prefer fail-closed because `pyfarmhash` is now a core dependency.

- **Major — Row access policy enforcement is a regex SQL splice that breaks joins and can enforce the wrong scope**
  - **Affected:** `src/bigquery_emulator/api/rest/jobs.py::_apply_row_access_policies`
  - **Exact diff evidence:**

    ```diff
    +        table_pat = rf'FROM\s+((?:"{re.escape(schema)}"\.)?(?:"{re.escape(table)}"|{re.escape(table)}))(\s+AS\s+"?\w+"?)?'
    +        predicate = " AND ".join(f"({p})" for p in policies.values())
    +        if re.search(table_pat, result, re.IGNORECASE):
    +            if re.search(r"\bWHERE\b", result, re.IGNORECASE):
    +                result = re.sub(r"\bWHERE\b", f"WHERE {predicate} AND ", result, count=1, flags=re.IGNORECASE)
    +            else:
    +                result = re.sub(
    +                    table_pat,
    +                    lambda m: f'FROM {m.group(1)}{m.group(2) or ""} WHERE {predicate}',
    ```

  - **Impact:** For `SELECT ... FROM table JOIN ...` without an existing `WHERE`, this inserts `WHERE` before the `JOIN`, producing invalid SQL. With an existing `WHERE`, it modifies the first `WHERE` in the query, which may be inside a subquery rather than the protected table scope. This can deny valid queries, leak rows, or apply predicates under the wrong alias.
  - **Suggested fix:** Enforce row policies with an AST rewrite or by wrapping the protected table reference as a filtered subquery. Until then, limit enforcement to simple single-table SELECTs and return an explicit unsupported error for joins/subqueries.

```acceptance-report
{
  "criteriaSatisfied": [
    {
      "id": "criterion-1",
      "status": "satisfied",
      "evidence": "Reviewed only the supplied unified diff and wrote a single review artifact; no repository source files were opened."
    },
    {
      "id": "criterion-2",
      "status": "satisfied",
      "evidence": "Each finding includes severity, affected file/function, exact diff evidence, impact, and suggested fix."
    }
  ],
  "changedFiles": [
    "/Users/jsenjaliya/src/AI/localcloud/_bmad-output/implementation-artifacts/review-blind-hunter.md"
  ],
  "testsAddedOrUpdated": [],
  "commandsRun": [
    {
      "command": "git diff --cached --quiet --exit-code",
      "result": "passed",
      "summary": "Verified there are no staged files after writing the review artifact."
    }
  ],
  "validationOutput": [
    "Reviewed /Users/jsenjaliya/src/AI/localcloud/_bmad-output/implementation-artifacts/bq-emulator-full-review.diff only.",
    "Identified 7 actionable findings: 2 blockers and 5 majors."
  ],
  "residualRisks": [
    "No tests were run because the task was blind diff review only.",
    "Findings are limited to risks visible in the unified diff; repository context was intentionally not inspected."
  ],
  "noStagedFiles": true,
  "diffSummary": "Diff changes Docker/package metadata, BigQuery REST/gRPC read/write behavior, jobs/load/export/extract handling, custom functions, information schema, scripting/transpiler/type mapping, and associated tests.",
  "reviewFindings": [
    "blocker: src/bigquery_emulator/api/grpc/write_server.py::FinalizeWriteStream - PENDING stream rows are inserted/committed during finalize instead of batch commit.",
    "major: src/bigquery_emulator/api/grpc/write_server.py::AppendRows - offset tracking uses only starting offsets and accepts overlapping/out-of-order appends.",
    "blocker: src/bigquery_emulator/api/rest/jobs.py - load/export/extract/external-query paths allow SSRF and arbitrary local file read/write/open.",
    "major: src/bigquery_emulator/api/rest/tabledata.py::list_table_data - selectedFields is interpolated into SQL without identifier validation/escaping.",
    "major: src/bigquery_emulator/api/rest/tabledata.py::insert_all - insertId dedupe includes row content and uses an unbounded in-memory set.",
    "major: src/bigquery_emulator/engine/custom_functions.py::_register_farm_fingerprint - missing pyfarmhash fallback contradicts added test and returns incompatible hashes.",
    "major: src/bigquery_emulator/api/rest/jobs.py::_apply_row_access_policies - regex WHERE injection breaks joins/subqueries and can enforce predicates in the wrong scope."
  ],
  "manualNotes": "Acceptance artifact intentionally reports review output only; no code fixes were applied."
}
```
