# SQL Compatibility Warnings Design

## Goal

Warn users when they write SQL that isn't supported by the BigQuery (DuckDB) or Spanner emulators, before and after execution.

## Components

### 1. Live Linting (as you type)
- Use `@codemirror/lint` (already installed, unused) in CodeEditor.jsx
- Custom linter checks SQL text against a hardcoded compatibility map
- Yellow warning underline + tooltip on hover: "APPROX_COUNT_DISTINCT is not supported by the emulator. Use COUNT(DISTINCT ...) instead."
- Warning severity (not error) — query might still work partially

### 2. Enriched Error Messages (on execution)
- QueryService.java catches common DuckDB/Spanner error patterns
- Translates raw errors into actionable messages with alternatives

### 3. Compatibility Reference (documentation)
- A compatibility.js data file with supported/unsupported features per emulator
- Shown as a reference section in the SQL editor sidebar or service detail page

## Files

| File | Change |
|------|--------|
| **New:** `localcloud-console/src/data/compatibility.js` | Hardcoded map of unsupported keywords/functions per emulator with alternatives |
| `localcloud-console/src/components/CodeEditor.jsx` | Wire up `@codemirror/lint` with compatibility linter |
| `localcloud-server/.../QueryService.java` | Enrich error messages for common DuckDB/Spanner failures |
