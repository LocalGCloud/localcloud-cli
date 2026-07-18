# Cloud Billing integration guide

- **Service ID:** `cloudbilling`
- **Generated test environment:** `CLOUD_BILLING_EMULATOR_HOST`
- **Protocol/port:** `rest` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_CLOUD_BILLING_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `CLOUD_BILLING_EMULATOR_HOST`.

## Supported and partial operations

- `billing.budgets`: billing accounts and budgets CRUD (partial)

## CI guidance

Use only for local metadata compatibility tests.

## Limitations

- Real billing, budget enforcement, and cost export are not implemented.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List billing accounts and linked projects:**
  ```http
  GET http://localhost:8080/browse/cloudbilling?project={projectId}
  ```
- **List budgets:**
  ```http
  GET http://localhost:8080/browse/cloudbilling/budgets?project={projectId}
  ```
