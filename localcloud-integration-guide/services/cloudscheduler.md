# Cloud Scheduler integration guide

- **Service ID:** `cloudscheduler`
- **Generated test environment:** `CLOUD_SCHEDULER_EMULATOR_HOST`
- **Protocol/port:** `grpc` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_CLOUD_SCHEDULER_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `CLOUD_SCHEDULER_EMULATOR_HOST`.

## Supported and partial operations

- `jobs.lifecycle`: jobs.create/list/get/delete/pause/resume (partial)

## CI guidance

Use for targeted scheduler workflows; keep tests inside listed paths.

## Limitations

- Timezone rules beyond cron-utils support are not fully verified.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List jobs:**
  ```http
  GET http://localhost:8080/browse/cloudscheduler?project={projectId}
  ```
- **List executions of a job (exact name match):**
  ```http
  GET http://localhost:8080/browse/cloudscheduler/jobs/{jobName}/executions?project={projectId}
  ```
- **Search executions by job name fragment (LIKE %jobName%):**
  ```http
  GET http://localhost:8080/browse/cloudscheduler/executions/{jobNameFragment}?project={projectId}
  ```
