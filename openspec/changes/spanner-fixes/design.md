## Context

Spanner in LocalCloud runs as an external emulator (gRPC on port 9010, REST on port 9020). Data is browsed via REST API proxied through BrowseService. The SQL editor sends queries via `POST /_localcloud/query` which requires `instance` and `database` in the request body — but the frontend never sends them.

The Spanner REST API structure:
- `GET /v1/projects/{project}/instances` — list instances
- `GET /v1/projects/{project}/instances/{instance}/databases` — list databases
- `GET /v1/projects/{project}/instances/{instance}/databases/{database}/ddl` — get DDL statements
- `POST /v1/projects/{project}/instances/{instance}/databases/{database}/sessions` — create session
- `POST /v1/{session}:executeSql` — run SQL

## Goals / Non-Goals

**Goals:**
- Fix the SQL editor to work with Spanner (pass instance/database params)
- Show schema metadata in the SQL editor sidebar
- Allow creating instances, databases, and tables from the console

**Non-Goals:**
- Full Spanner admin API emulation (instance configs, IAM, etc.)
- Schema migrations or version tracking

## Decisions

1. **Instance/database selection**: Add dropdown selectors in the SQL editor toolbar (only shown when service is Spanner). Auto-select the first available instance/database. Store selection in component state.

2. **Schema endpoint**: Add `schemaSpanner()` to QueryService that calls the Spanner REST API to get DDL, then parses CREATE TABLE statements to extract table names and column definitions. Returns standard `{tables: [{name, columns: [{name, type}]}]}` format.

3. **DDL operations via MutateService**: Add operations to `mutateSpanner()`:
   - `createInstance` → `POST /v1/projects/{project}/instances` with `{instanceId, instance: {config, displayName, nodeCount}}`
   - `createDatabase` → `POST /v1/projects/{project}/instances/{instance}/databases` with `{createStatement, extraStatements[]}`
   - `ddl` → `PATCH /v1/projects/{project}/instances/{instance}/databases/{database}/ddl` with `{statements: ["CREATE TABLE ..."]}`

4. **Console UI**: Add create buttons to the Data Explorer breadcrumb area. Use existing modal pattern (same as project creation dialog).

## Risks / Trade-offs

- [DDL parsing for schema] → Simple regex parsing of CREATE TABLE statements from DDL response. Won't handle complex Spanner-specific syntax (INTERLEAVE, STORING) for column extraction, but covers the common case.
