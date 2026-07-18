# BigQuery integration guide

- **Service ID:** `bigquery`
- **Generated test environment:** `BIGQUERY_EMULATOR_HOST`
- **Protocol/port:** `rest` on `9050` (control-plane grpc `9060`)
- **Terraform endpoint variable:** `GOOGLE_BIGQUERY_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 1 — env auto-detection. See [COMMON_GUIDE §6.1](../COMMON_GUIDE.md#61-level-1--environment-auto-detection) for the SDK list. The `BigQuery` SDK reads `BIGQUERY_EMULATOR_HOST` (Python SDK v3.1.0+) and diverts traffic without code changes. Level 2 fallback with `client_options={"api_endpoint": os.environ["BIGQUERY_EMULATOR_HOST"]}` also works when auth requires `AnonymousCredentials()`.

## Supported and partial operations

- `datasets.lifecycle`: datasets.create/list/delete (supported)
- `tables.lifecycle`: tables.create/list/delete (supported)
- `sql.query`: insert rows and query SQL (partial)

## CI guidance

Use for local and CI smoke tests that stay within listed supported operations; add a coverage assertion for this service.

## Limitations

- SQL is DuckDB-backed, so BigQuery dialect parity is partial.
- Scripting, BQML, GEOGRAPHY, and partitioning execution are limited.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List datasets:**
  ```http
  GET http://localhost:8080/browse/bigquery?project={projectId}
  ```
- **List tables in a dataset:**
  ```http
  GET http://localhost:8080/browse/bigquery/datasets/{datasetId}?project={projectId}
  ```
- **INFORMATION_SCHEMA tables (DuckDB-backed):**
  ```http
  GET http://localhost:8080/browse/bigquery/INFORMATION_SCHEMA/tables?project={projectId}
  ```
- **INFORMATION_SCHEMA columns (per dataset):**
  ```http
  GET http://localhost:8080/browse/bigquery/INFORMATION_SCHEMA/columns?dataset={datasetId}&project={projectId}
  ```
- **List table data (preview rows):**
  ```http
  GET http://localhost:8080/browse/bigquery/datasets/{datasetId}/tables/{tableId}/data?project={projectId}
  ```
