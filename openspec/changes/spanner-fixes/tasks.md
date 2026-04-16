## 1. Fix SQL Editor Query Dispatch (Critical Bug)

- [x] 1.1 In ServiceExplorer.jsx `SQLEditor` component, add `spannerInstance` and `spannerDatabase` signals that auto-populate from browse API when service is Spanner
- [x] 1.2 Add instance/database dropdown selectors to the SQL toolbar (only shown when service is Spanner)
- [x] 1.3 Pass `instance` and `database` params to `api.query()` in `runQuery()` when service is Spanner
- [x] 1.4 Show "No Spanner instances" message when no instances exist

## 2. Fix Schema Endpoint (Critical Bug)

- [x] 2.1 Add `schemaSpanner()` method to QueryService.java — fetch DDL from Spanner REST API, parse CREATE TABLE statements to extract table names and columns
- [x] 2.2 Accept `instance` and `database` query params in the schema endpoint for Spanner
- [x] 2.3 Pass instance/database from the SQL editor to the schema fetch call in ServiceExplorer.jsx
- [x] 2.4 Remove hardcoded empty `spanner: { tables: [] }` from SERVICE_SCHEMAS and replace with dynamic schema from API

## 3. Add DDL Operations to MutateService

- [x] 3.1 Add `createInstance` operation — POST to Spanner REST API `/v1/projects/{project}/instances`
- [x] 3.2 Add `createDatabase` operation — POST to Spanner REST API `/v1/projects/{project}/instances/{instance}/databases`
- [x] 3.3 Add `ddl` operation — PATCH to Spanner REST API `/v1/projects/{project}/instances/{instance}/databases/{database}/ddl`

## 4. Add Console UI for Creating Spanner Resources

- [x] 4.1 Add "Create Instance" button + modal to Spanner Data Explorer (when viewing instance list)
- [x] 4.2 Add "Create Database" button + modal (when viewing database list inside an instance)
- [x] 4.3 Add "Create Table" button + DDL textarea modal (when viewing table list inside a database)

## 5. Testing & Verification

- [x] 5.1 Verify SQL editor works: select instance/database, run SELECT query, see results
- [x] 5.2 Verify schema sidebar shows tables and columns for Spanner
- [x] 5.3 Verify create instance/database/table from console works
- [x] 5.4 Build console: `cd localcloud-console && npm run build`
- [x] 5.5 Compile server: `cd localcloud-server && ./gradlew compileJava`
