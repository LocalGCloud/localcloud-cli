# Cloud Monitoring integration guide

- **Service ID:** `monitoring`
- **Generated test environment:** `CLOUD_MONITORING_EMULATOR_HOST`
- **Protocol/port:** `grpc` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_MONITORING_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `CLOUD_MONITORING_EMULATOR_HOST`.

## Supported and partial operations

- `metrics.time-series`: create/list time series and descriptors (partial)

## CI guidance

Use for local metric write/list smoke tests.

## Limitations

- Alerting, uptime checks, and dashboards are partial.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List metric descriptors / time-series summary:**
  ```http
  GET http://localhost:8080/browse/monitoring?project={projectId}
  ```
