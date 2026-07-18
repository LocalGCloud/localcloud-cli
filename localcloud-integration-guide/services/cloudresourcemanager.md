# Cloud Resource Manager integration guide

- **Service ID:** `cloudresourcemanager`
- **Generated test environment:** `CLOUD_RESOURCE_MANAGER_EMULATOR_HOST`
- **Protocol/port:** `rest` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_RESOURCE_MANAGER_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `CLOUD_RESOURCE_MANAGER_EMULATOR_HOST`.

## Supported and partial operations

- `projects.lifecycle`: projects.create/list/get/update/delete (supported)

## CI guidance

Use for local project isolation and Terraform project tests.

## Limitations

- Organization/folder hierarchy is not modeled.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List projects (same as /projects):**
  ```http
  GET http://localhost:8080/browse/cloudresourcemanager?project={projectId}
  ```
- **Get a single project:**
  ```http
  GET http://localhost:8080/browse/cloudresourcemanager/projects/{projectId}
  ```
