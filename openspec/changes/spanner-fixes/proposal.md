## Why

The Spanner service in LocalCloud has three broken features that make the SQL editor and data management unusable:

1. **SQL Editor always fails** — Every Spanner query returns "Spanner queries require 'instance' parameter" because the frontend never passes `instance` and `database` to the query API. Users cannot execute any SQL against Spanner.
2. **Schema metadata is empty** — The schema endpoint (`/_localcloud/schema/spanner`) returns `{"tables": []}` because there's no Spanner handler. The SQL editor sidebar shows no tables, no columns, and no autocomplete.
3. **Cannot create instances or tables from UI** — MutateService only supports row operations (insert/update/delete). There's no way to create Spanner instances, databases, or tables from the console. DDL operations are completely missing.

## What Changes

- **Fix SQL editor query dispatch** — Add instance/database selector dropdowns to the Spanner SQL editor. Auto-select the first instance/database. Pass `instance` and `database` parameters to `api.query()` in `runQuery()`.
- **Fix schema endpoint** — Add `schemaSpanner()` handler in QueryService.java that fetches instances, databases, and DDL from the Spanner REST API (port 9020) and returns schema in the standard `{tables: [{name, columns}]}` format.
- **Add DDL operations to MutateService** — Add `createInstance`, `createDatabase`, `executeDdl` (CREATE TABLE, DROP TABLE, ALTER TABLE) operations to `mutateSpanner()`.
- **Add create UI to console** — Add "Create Instance", "Create Database", "Create Table" buttons to the Spanner Data Explorer with modal forms.

## Capabilities

### New Capabilities

- `spanner-ddl-operations`: MutateService support for Spanner DDL — create instance, create database, execute DDL statements (CREATE TABLE, DROP TABLE). Uses Spanner REST API on port 9020.
- `spanner-console-create`: Console UI for creating Spanner instances, databases, and tables. Modal forms with validation, integrated into the Data Explorer view.

### Modified Capabilities

- `spanner-sql-editor`: Fix query dispatch to pass instance/database parameters. Add instance/database selector dropdowns populated from browse API.
- `spanner-schema`: Fix schema endpoint to return table/column metadata from Spanner DDL. Wire schema tree in SQL editor sidebar.

## Impact

- **QueryService.java** — Add `schemaSpanner()` method, fix `executeSpannerQuery()` to work with frontend
- **MutateService.java** — Add DDL operations to `mutateSpanner()`
- **ServiceExplorer.jsx** — Add instance/database selectors for Spanner SQL editor, pass params to `runQuery()`
- **DataBrowser.jsx** — Add Create Instance/Database/Table buttons and modals for Spanner
- **api.js** — Ensure `query()` passes instance/database params correctly (already supported, just unused)
