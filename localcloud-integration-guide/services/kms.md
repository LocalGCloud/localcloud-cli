# Cloud KMS integration guide

- **Service ID:** `kms`
- **Generated test environment:** `CLOUD_KMS_EMULATOR_HOST`
- **Protocol/port:** `rest` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_KMS_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `CLOUD_KMS_EMULATOR_HOST`.

## Supported and partial operations

- `keys.lifecycle`: key rings, crypto keys, versions (partial)
- `crypto.operations`: encrypt/decrypt/sign/verify (partial)

## CI guidance

Use for local key CRUD and crypto smoke tests.

## Limitations

- HSM, EKM, import jobs, and Cloud HSM level enforcement are not implemented.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List key rings:**
  ```http
  GET http://localhost:8080/browse/kms?project={projectId}
  ```
- **List crypto keys in a key ring:**
  ```http
  GET http://localhost:8080/browse/kms/keys/{keyRingId}?project={projectId}
  ```
- **List versions of a crypto key:**
  ```http
  GET http://localhost:8080/browse/kms/versions/{keyRingId}/{cryptoKeyId}?project={projectId}
  ```
