# Dataproc integration guide

- **Service ID:** `dataproc`
- **Generated test environment:** `DATAPROC_EMULATOR_HOST`
- **Protocol/port:** `grpc` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_DATAPROC_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `DATAPROC_EMULATOR_HOST`.

## Supported and partial operations

- `clusters-jobs.lifecycle`: clusters CRUD and jobs submit/list/get (partial)

## CI guidance

Requires Spark for job execution; otherwise use metadata-only tests.

## Limitations

- Autoscaling and YARN/Kubernetes cluster mode are not complete.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List clusters:**
  ```http
  GET http://localhost:8080/browse/dataproc?project={projectId}
  ```
- **List jobs across all clusters (cluster_id is ignored):**
  ```http
  GET http://localhost:8080/browse/dataproc/jobs?project={projectId}
  ```
- **Get job driver output (5-segment path; {jobId} must be the 4th segment):**
  ```http
  GET http://localhost:8080/browse/dataproc/jobs/{any}/{jobId}/{any}?project={projectId}
  ```
