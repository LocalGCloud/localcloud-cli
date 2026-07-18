# Cloud SQL integration guide

- **Service ID:** `cloudsql`
- **Generated test environment:** `CLOUD_SQL_EMULATOR_HOST`
- **Protocol/port:** `rest` on `8080 (gateway)` (control-plane postgres `5432`, mysql `3306`)
- **Terraform endpoint variable:** `GOOGLE_SQL_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `CLOUD_SQL_EMULATOR_HOST`.

## Supported and partial operations

- `sql-admin.lifecycle`: instances/databases/users (partial)
- `mysql-managed-runtime`: MySQL data plane, replicas, backups, PSC (unsupported)

## CI guidance

Use for PostgreSQL-oriented local Cloud SQL workflows.

## Limitations

- MySQL data plane, read replicas, PSC, and backup/restore are not complete.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List SQL instances:**
  ```http
  GET http://localhost:8080/browse/cloudsql?project={projectId}
  ```
- **Get instance details (databases + users):**
  ```http
  GET http://localhost:8080/browse/cloudsql/instances/{instanceId}?project={projectId}
  ```
- **List all databases across instances:**
  ```http
  GET http://localhost:8080/browse/cloudsql/databases?project={projectId}
  ```
