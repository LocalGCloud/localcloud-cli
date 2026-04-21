## Context

The SQL editor uses CodeMirror 6 with `@codemirror/lang-sql` for syntax highlighting and `@codemirror/autocomplete` for completions. `@codemirror/lint` is installed (package.json) but never configured. The editor supports multiple dialects (postgresql, bigquery, googlesql) mapped in CodeEditor.jsx.

Backend errors from DuckDB and Spanner are returned as raw strings (e.g., `Catalog Error: Function APPROX_COUNT_DISTINCT does not exist`).

## Goals / Non-Goals

**Goals:**
- Warn users about unsupported syntax before they run the query (live linting)
- Provide actionable alternatives when queries fail (enriched errors)
- Curate a compatibility map for BigQuery (DuckDB) and Spanner emulators

**Non-Goals:**
- Full SQL parsing/AST validation (too complex, overkill)
- Auto-rewriting queries to supported syntax
- Runtime compatibility testing against the emulator

## Decisions

### D1: Keyword/function-level regex linting, not AST parsing

**Choice:** Match unsupported keywords and function names using word-boundary regex against the SQL text. No SQL parser needed.

**Why:** Catches 80% of cases (unsupported functions, types, clauses) with zero additional dependencies. AST parsing would require shipping a full SQL parser to the frontend.

### D2: Warning severity, not error

**Choice:** Yellow underline (warning), not red (error). Tooltip shows the unsupported feature name and alternative.

**Why:** Some "unsupported" features may partially work or work in certain contexts. Warning is appropriate for "this might not work" vs "this definitely won't work."

### D3: Compatibility data hardcoded in frontend JS

**Choice:** A `compatibility.js` module exporting a map per emulator. Updated manually when emulators improve.

**Why:** No backend API needed. Data changes infrequently (only when emulator versions change). Frontend can use it synchronously for linting without async fetch.

### D4: Error enrichment via pattern matching in QueryService

**Choice:** A `enrichErrorMessage(service, rawError)` method that maps known error patterns to friendly messages.

**Why:** Minimal change — wraps existing error handling. Common DuckDB errors like "Catalog Error: Function X does not exist" are easy to pattern-match.

## Risks / Trade-offs

- **[Risk] False positives in linting** → Mitigated by using word-boundary regex and warning (not error) severity. Users can ignore warnings.
- **[Risk] Compatibility map goes stale** → Mitigated by keeping the map small (only confirmed gaps). Users can report false warnings via GitHub issues.
