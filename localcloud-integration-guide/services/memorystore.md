# Memorystore (Redis/Valkey) integration guide

- **Service ID:** `memorystore`
- **Generated test environment:** `REDIS_HOST`
- **Protocol/port:** `redis` on `6379`
- **Terraform endpoint variable:** `GOOGLE_REDIS_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `REDIS_HOST`.

## Supported and partial operations

- `resp.commands`: RESP commands (partial)
- `admin-api`: Cloud Redis admin API (unsupported)

## CI guidance

Use for Redis-compatible data path tests.

## Limitations

- Pub/Sub, Lua, streams, and MULTI/EXEC are not supported.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **Scan keys in default database (DB 0):**
  ```http
  GET http://localhost:8080/browse/memorystore?project={projectId}
  ```
- **Scan keys in a specific Redis database index (with optional prefix):**
  ```http
  GET http://localhost:8080/browse/memorystore/db/{dbIndex}/keys?prefix={prefix}&project={projectId}
  ```
- **List Memorystore instance metadata (PostgreSQL-backed):**
  ```http
  GET http://localhost:8080/browse/memorystore/instances?project={projectId}
  ```
