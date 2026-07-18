# Bigtable integration guide

- **Service ID:** `bigtable`
- **Generated test environment:** `BIGTABLE_EMULATOR_HOST`
- **Protocol/port:** `grpc` on `8087`
- **Terraform endpoint variable:** `GOOGLE_BIGTABLE_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 1 — env auto-detection. See [COMMON_GUIDE §6.1](../COMMON_GUIDE.md#61-level-1--environment-auto-detection) for the SDK list. The `Bigtable` SDK reads `BIGTABLE_EMULATOR_HOST` and diverts traffic without code changes.

## Supported and partial operations

- `admin.lifecycle`: instances/tables/families (partial)
- `rows.data`: row mutations and reads (partial)

## CI guidance

Use only for targeted local workflows until SDK and provisioning tests are added.

## Limitations

- Persistence and browse/mutate/export alignment need hardening.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List instances:**
  ```http
  GET http://localhost:8080/browse/bigtable/instances?project={projectId}
  ```
- **List tables in an instance:**
  ```http
  GET http://localhost:8080/browse/bigtable/instances/{instanceId}?project={projectId}
  ```
- **List rows in a table (default instance: local-instance):**
  ```http
  GET http://localhost:8080/browse/bigtable/tables/{instanceOrTable}?project={projectId}
  ```
- **List rows in a specific instance/table:**
  ```http
  GET http://localhost:8080/browse/bigtable/tables/{instanceId}/{tableId}?project={projectId}
  ```
