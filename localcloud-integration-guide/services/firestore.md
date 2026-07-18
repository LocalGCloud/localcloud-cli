# Firestore integration guide

- **Service ID:** `firestore`
- **Generated test environment:** `FIRESTORE_EMULATOR_HOST`
- **Protocol/port:** `grpc` on `8086`
- **Terraform endpoint variable:** `GOOGLE_FIRESTORE_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 1 — env auto-detection. See [COMMON_GUIDE §6.1](../COMMON_GUIDE.md#61-level-1--environment-auto-detection) for the SDK list. The `Firestore` SDK reads `FIRESTORE_EMULATOR_HOST` and diverts traffic without code changes.

## Supported and partial operations

- `documents.crud`: documents.create/read/update/delete (partial)
- `queries.indexes`: queries/index behavior (partial)

## CI guidance

Use only for targeted local workflows until SDK and provisioning tests are added.

## Limitations

- Seed and browser parity is not fully hardened.
- Index/query behavior is unverified.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List root collections and documents:**
  ```http
  GET http://localhost:8080/browse/firestore?project={projectId}
  ```
- **List documents in a collection:**
  ```http
  GET http://localhost:8080/browse/firestore/{collection}?project={projectId}
  ```
- **Get a single document:**
  ```http
  GET http://localhost:8080/browse/firestore/{collection}/{documentId}?project={projectId}
  ```
