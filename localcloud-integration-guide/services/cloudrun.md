# Cloud Run integration guide

- **Service ID:** `cloudrun`
- **Generated test environment:** `CLOUD_RUN_EMULATOR_HOST`
- **Protocol/port:** `grpc` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_CLOUD_RUN_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `CLOUD_RUN_EMULATOR_HOST`.

## Supported and partial operations

- `services-revisions`: services/revisions (partial)
- `jobs-domains`: jobs, custom domains, production routing (unsupported)

## CI guidance

Use only for metadata workflows until host runtime execution is enabled.

## Limitations

- Container execution and routing require host runtime architecture.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List services across locations:**
  ```http
  GET http://localhost:8080/browse/cloudrun?project={projectId}
  ```
- **List revisions (most recent first):**
  ```http
  GET http://localhost:8080/browse/cloudrun/revisions?project={projectId}
  ```
