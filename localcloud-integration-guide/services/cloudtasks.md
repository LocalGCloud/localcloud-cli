# Cloud Tasks integration guide

- **Service ID:** `cloudtasks`
- **Generated test environment:** `CLOUD_TASKS_EMULATOR_HOST`
- **Protocol/port:** `grpc` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_CLOUD_TASKS_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `CLOUD_TASKS_EMULATOR_HOST`.

## Supported and partial operations

- `queues.lifecycle`: queues.create/list/delete (partial)
- `tasks.lifecycle`: tasks.create/list/delete (partial)

## CI guidance

Use for local and CI smoke tests that stay within listed supported operations; add a coverage assertion for this service.

## Limitations

- App Engine tasks and OAuth token generation are not complete.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List queues:**
  ```http
  GET http://localhost:8080/browse/cloudtasks?project={projectId}
  ```
- **List tasks in a queue:**
  ```http
  GET http://localhost:8080/browse/cloudtasks/queues/{queueId}?project={projectId}
  ```
