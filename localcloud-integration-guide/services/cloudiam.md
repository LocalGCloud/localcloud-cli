# Cloud IAM integration guide

- **Service ID:** `cloudiam`
- **Generated test environment:** `IAM_EMULATOR_HOST`
- **Protocol/port:** `grpc` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_IAM_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `IAM_EMULATOR_HOST`.

## Supported and partial operations

- `policies.permissions`: getIamPolicy/setIamPolicy/testIamPermissions (partial)

## CI guidance

Use permissive mode only unless strict IAM specs are explicitly enabled.

## Limitations

- Role validation, conditions, and deny policies are not complete.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List all IAM policies:**
  ```http
  GET http://localhost:8080/browse/cloudiam?project={projectId}
  ```
- **Get the IAM metadata catalog (resource types + role registry):**
  ```http
  GET http://localhost:8080/browse/cloudiam/metadata?project={projectId}
  ```
