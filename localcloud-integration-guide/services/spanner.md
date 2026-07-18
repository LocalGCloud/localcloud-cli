# Spanner integration guide

- **Service ID:** `spanner`
- **Generated test environment:** `SPANNER_EMULATOR_HOST`
- **Protocol/port:** `grpc` on `9010` (control-plane rest `9020`)
- **Terraform endpoint variable:** `GOOGLE_SPANNER_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 1 — env auto-detection. See [COMMON_GUIDE §6.1](../COMMON_GUIDE.md#61-level-1--environment-auto-detection) for the SDK list. The `Spanner` SDK reads `SPANNER_EMULATOR_HOST` and diverts traffic without code changes.

## Supported and partial operations

- `admin.lifecycle`: instances/databases (supported)
- `ddl-dml`: DDL and DML (partial)

## CI guidance

Use only for targeted local workflows until SDK and provisioning tests are added.

## Limitations

- REST/gRPC metadata parity remains partial.
- Partitioned DML and change streams are not supported.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List instances:**
  ```http
  GET http://localhost:8080/browse/spanner?project={projectId}
  ```
- **List databases in an instance:**
  ```http
  GET http://localhost:8080/browse/spanner/instances/{instanceId}?project={projectId}
  ```
- **Get DDL of a database:**
  ```http
  GET http://localhost:8080/browse/spanner/instances/{instanceId}/{databaseId}?project={projectId}
  ```
- **Database stats (tables / indexes / interleaved count):**
  ```http
  GET http://localhost:8080/browse/spanner/instances/{instanceId}/{databaseId}/stats?project={projectId}
  ```
- **List table data (first 50 rows):**
  ```http
  GET http://localhost:8080/browse/spanner/instances/{instanceId}/databases/{databaseId}/tables/{tableId}?project={projectId}
  ```
