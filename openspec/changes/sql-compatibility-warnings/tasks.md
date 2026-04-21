## 1. Compatibility Data

- [x] 1.1 Create `localcloud-console/src/data/compatibility.js` with BigQuery unsupported features map: functions (APPROX_COUNT_DISTINCT, SAFE_DIVIDE, SAFE_CAST, SAFE_MULTIPLY, SAFE_NEGATE, SAFE_ADD, SAFE_SUBTRACT, NET.IP_FROM_STRING, NET.IP_TO_STRING, GENERATE_UUID, SESSION_USER, ERROR), types (GEOGRAPHY, BIGNUMERIC, INTERVAL), clauses (TABLESAMPLE, PIVOT, UNPIVOT, QUALIFY, FOR SYSTEM_TIME AS OF, ML.PREDICT, ML.EVALUATE)
- [x] 1.2 Add Spanner unsupported features map: MERGE, TABLESAMPLE, ML functions
- [x] 1.3 Include alternative suggestions for each entry where applicable

## 2. Live Linting (CodeMirror)

- [x] 2.1 Import `linter` and `lintGutter` from `@codemirror/lint` in `CodeEditor.jsx`
- [x] 2.2 Create `compatibilityLinter(dialect)` function that returns a CodeMirror linter — matches unsupported keywords via word-boundary regex, returns diagnostics with `severity: 'warning'`
- [x] 2.3 Register the linter extension in the CodeMirror editor setup (only for bigquery and spanner dialects, not postgresql)
- [x] 2.4 Pass the active dialect from `ServiceExplorer.jsx` to `CodeEditor.jsx` so the correct compatibility map is used
- [x] 2.5 Add lint gutter (yellow dot indicator) for lines with warnings

## 3. Error Enrichment (Backend)

- [x] 3.1 Add `enrichErrorMessage(String service, String rawError)` method to `QueryService.java`
- [x] 3.2 Map DuckDB "Catalog Error: Scalar Function with name X does not exist" → "Function X is not supported by the BigQuery emulator. [alternative]"
- [x] 3.3 Map DuckDB type errors for GEOGRAPHY, BIGNUMERIC → friendly messages
- [x] 3.4 Map Spanner unsupported feature errors → friendly messages
- [x] 3.5 Call `enrichErrorMessage` in `executeBigQueryQuery`, `executeSpannerQuery`, and BigQuery inline error handlers

## 4. Build & Test

- [x] 4.1 Build console: `cd localcloud-console && npm run build`
- [x] 4.2 Run Java tests: `cd localcloud-server && ./gradlew test`
- [ ] 4.3 Manual test: type `SELECT APPROX_COUNT_DISTINCT(x) FROM t` in BigQuery editor — verify yellow underline appears
- [ ] 4.4 Manual test: run the query — verify enriched error message returned
- [ ] 4.5 Manual test: type normal SQL in PostgreSQL editor — verify no warnings
