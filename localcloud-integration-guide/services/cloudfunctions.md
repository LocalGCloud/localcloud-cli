# Cloud Functions (2nd Gen) integration guide

- **Service ID:** `cloudfunctions`
- **Generated test environment:** `CLOUD_FUNCTIONS_EMULATOR_HOST`
- **Protocol/port:** `grpc` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_CLOUD_FUNCTIONS_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `CLOUD_FUNCTIONS_EMULATOR_HOST`.

## Supported and partial operations

- `functions.metadata`: functions.create/list/get/delete/update (partial)

## CI guidance

Use for metadata and trigger wiring tests, not function build parity.

## Limitations

- Build and container execution are metadata-only; use Functions Framework locally.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List functions:**
  ```http
  GET http://localhost:8080/browse/cloudfunctions?project={projectId}
  ```
- **Get a single function:**
  ```http
  GET http://localhost:8080/browse/cloudfunctions/functions/{locationId}/{functionId}?project={projectId}
  ```
