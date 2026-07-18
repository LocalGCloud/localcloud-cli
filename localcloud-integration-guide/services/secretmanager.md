# Secret Manager integration guide

- **Service ID:** `secretmanager`
- **Generated test environment:** `SECRET_MANAGER_EMULATOR_HOST`
- **Protocol/port:** `grpc` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_SECRET_MANAGER_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `SECRET_MANAGER_EMULATOR_HOST`.

## Supported and partial operations

- `secrets.lifecycle`: secrets.create/list/get/delete (partial)
- `versions.lifecycle`: versions.add/access/list (partial)

## CI guidance

Use for local and CI smoke tests that stay within listed supported operations; add a coverage assertion for this service.

## Limitations

- Rotation, CMEK, and per-secret IAM are not complete.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List secrets:**
  ```http
  GET http://localhost:8080/browse/secretmanager?project={projectId}
  ```
- **List versions of a secret:**
  ```http
  GET http://localhost:8080/browse/secretmanager/versions/{secretId}?project={projectId}
  ```
- **Get the payload of a specific version:**
  ```http
  GET http://localhost:8080/browse/secretmanager/versions/{secretId}/{versionNumber}?project={projectId}
  ```
