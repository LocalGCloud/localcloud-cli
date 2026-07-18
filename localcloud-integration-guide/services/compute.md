# Compute Engine integration guide

- **Service ID:** `compute`
- **Generated test environment:** `COMPUTE_EMULATOR_HOST`
- **Protocol/port:** `rest` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_COMPUTE_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `COMPUTE_EMULATOR_HOST`.

## Supported and partial operations

- `instances.lifecycle`: instances.create/get/list/start/stop/delete (partial)
- `disks-networking`: disks, snapshots, templates, networking (unsupported)

## CI guidance

Use only for targeted local workflows until runtime tests are added.

## Limitations

- Disks, snapshots, templates, and networking are not yet emulated.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List instances across zones:**
  ```http
  GET http://localhost:8080/browse/compute?project={projectId}
  ```
