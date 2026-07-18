# AlloyDB integration guide

- **Service ID:** `alloydb`
- **Generated test environment:** `ALLOYDB_EMULATOR_HOST`
- **Protocol/port:** `grpc` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_ALLOYDB_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `ALLOYDB_EMULATOR_HOST`.

## Supported and partial operations

- `cluster-instance.lifecycle`: clusters/instances.create/list/get/delete (partial)

## CI guidance

Use for metadata and local PostgreSQL connection workflows.

## Limitations

- Backup/restore, PSC, and cross-region replication are not complete.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List clusters:**
  ```http
  GET http://localhost:8080/browse/alloydb?project={projectId}
  ```
- **List instances in a cluster:**
  ```http
  GET http://localhost:8080/browse/alloydb/instances/{clusterId}?project={projectId}
  ```
- **List databases in a cluster:**
  ```http
  GET http://localhost:8080/browse/alloydb/databases/{clusterId}?project={projectId}
  ```
- **List backups:**
  ```http
  GET http://localhost:8080/browse/alloydb/backups?project={projectId}
  ```
- **List tables in a database:**
  ```http
  GET http://localhost:8080/browse/alloydb/tables/{clusterId}/{databaseId}?project={projectId}
  ```
- **List rows in a table (LIMIT 100):**
  ```http
  GET http://localhost:8080/browse/alloydb/rows/{clusterId}/{databaseId}/{tableName}?project={projectId}
  ```
