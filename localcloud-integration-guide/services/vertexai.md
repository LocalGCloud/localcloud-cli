# Vertex AI integration guide

- **Service ID:** `vertexai`
- **Generated test environment:** `AIPLATFORM_EMULATOR_HOST`
- **Protocol/port:** `rest` on `8080 (gateway)`
- **Terraform endpoint variable:** `GOOGLE_VERTEX_AI_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 2 — conditional client endpoint. See [COMMON_GUIDE §6.2](../COMMON_GUIDE.md#62-level-2--code-endpoint) for the pattern. The test helper guards the client constructor with `LOCALCLOUD_URL` or `AIPLATFORM_EMULATOR_HOST`.

## Supported and partial operations

- `genai.stub`: generateContent, streamGenerateContent, embedContent, countTokens (partial)
- `model-platform`: models, tuning, batch prediction (unsupported)

## CI guidance

Use only for deterministic GenAI stub workflows.

## Limitations

- Broader Vertex AI training, prediction, and model management are out of current scope.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List models / endpoints (deterministic GenAI stub):**
  ```http
  GET http://localhost:8080/browse/vertexai?project={projectId}
  ```
