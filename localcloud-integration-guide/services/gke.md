# GKE integration guide

- **Service ID:** `gke`
- **Generated test environment:** `GKE_EMULATOR_HOST`
- **Protocol/port:** `grpc` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_CONTAINER_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `GKE_EMULATOR_HOST`.

## Supported and partial operations

- `clusters.lifecycle`: clusters CRUD (partial)
- `nodepools.runtime`: node pools, autoscaling, upgrades (unsupported)

## CI guidance

Use only for targeted local workflows until runtime tests are added.

## Limitations

- Kubernetes runtime parity depends on host runtime/k3d integration.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List clusters across locations:**
  ```http
  GET http://localhost:8080/browse/gke?project={projectId}
  ```
