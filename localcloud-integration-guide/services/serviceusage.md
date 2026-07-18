# Service Usage integration guide

- **Service ID:** `serviceusage`
- **Generated test environment:** `SERVICE_USAGE_EMULATOR_HOST`
- **Protocol/port:** `rest` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_SERVICE_USAGE_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `SERVICE_USAGE_EMULATOR_HOST`.

## Supported and partial operations

- `services.enablement`: services.enable/disable/list (partial)

## CI guidance

Use for local service toggle tests only.

## Limitations

- Quotas and service entitlement behavior are stubs.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List enabled services for the project (derived from services.yaml):**
  ```http
  GET http://localhost:8080/browse/serviceusage?project={projectId}
  ```
