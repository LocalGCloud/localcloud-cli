## Why

LocalCloud's BigQuery emulator (DuckDB-based) and Spanner emulator don't support 100% of production SQL syntax. Users discover gaps only after running a query and getting a cryptic DuckDB or Spanner error. This wastes time and creates frustration — especially when there's a simple workaround.

## What Changes

- Add live SQL linting in the console's SQL editor that underlines unsupported keywords/functions with yellow warning squiggles as the user types
- Enrich backend error messages to translate raw DuckDB/Spanner errors into actionable messages with suggested alternatives
- Ship a curated compatibility data file mapping unsupported features to alternatives for both BigQuery and Spanner emulators

## Capabilities

### New Capabilities
- `sql-live-linting`: CodeMirror lint extension that checks SQL against a hardcoded compatibility map and shows yellow warning underlines with hover tooltips for unsupported syntax.
- `sql-error-enrichment`: Backend error message translation that catches common DuckDB/Spanner error patterns and returns user-friendly messages with alternatives.
- `sql-compatibility-data`: Curated JSON data file listing supported and unsupported SQL features per emulator, used by both linting and documentation.

### Modified Capabilities

## Impact

- **Frontend (Solid.js)**: New `compatibility.js` data file, modified `CodeEditor.jsx` to wire up `@codemirror/lint` (already installed), modified `ServiceExplorer.jsx` to pass compatibility data to editor.
- **Backend (Java)**: Modified `QueryService.java` to enrich error messages before returning to client.
- **Dependencies**: None new — `@codemirror/lint` is already in package.json but unused.
