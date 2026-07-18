# Cloud Storage integration guide

- **Service ID:** `gcs`
- **Generated test environment:** `STORAGE_EMULATOR_HOST`
- **Protocol/port:** `rest` on `4443`
- **Terraform endpoint variable:** `GOOGLE_STORAGE_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 1 — env auto-detection. See [COMMON_GUIDE §6.1](../COMMON_GUIDE.md#61-level-1--environment-auto-detection) for the SDK list. The `Cloud Storage` Python SDK reads `STORAGE_EMULATOR_HOST` and auto-bypasses auth when set. Support varies by language — the env var originated in fake-gcs-server, not Google. Level 2 fallback with `client_options={"api_endpoint": os.environ["STORAGE_EMULATOR_HOST"]}` works universally.

## Supported and partial operations

- `buckets.lifecycle`: buckets.create/list/delete (supported)
- `objects.lifecycle`: objects.upload/download/list/delete (supported)
- `advanced-control-plane`: iam, lifecycle policies, notifications (unsupported)

## CI guidance

Use for local and CI smoke tests that stay within listed supported operations; add a coverage assertion for this service.

## Limitations

- No cloud IAM enforcement.
- No native project-level bucket isolation in fake-gcs-server without LocalCloud metadata.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List buckets:**
  ```http
  GET http://localhost:8080/browse/gcs/buckets?project={projectId}
  ```
- **List objects in a bucket:**
  ```http
  GET http://localhost:8080/browse/gcs/buckets/{bucketName}?project={projectId}
  ```
- **Get object content:**
  ```http
  GET http://localhost:8080/browse/gcs/object-content?bucket={bucketName}&object={objectName}&project={projectId}
  ```
