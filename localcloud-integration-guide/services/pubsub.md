# Pub/Sub integration guide

- **Service ID:** `pubsub`
- **Generated test environment:** `PUBSUB_EMULATOR_HOST`
- **Protocol/port:** `grpc` on `8085`
- **Terraform endpoint variable:** `GOOGLE_PUBSUB_CUSTOM_ENDPOINT`

See [COMMON_GUIDE.md](../COMMON_GUIDE.md) for the runtime contract, container image, fixed port mapping, `LOCALCLOUD_SERVICES`, DNS guidance, and SDK integration levels. Do not edit production source outside the test-helper guard.

## Connection approach

Level 1 — env auto-detection. See [COMMON_GUIDE §6.1](../COMMON_GUIDE.md#61-level-1--environment-auto-detection) for the SDK list. The `Pub/Sub` SDK reads `PUBSUB_EMULATOR_HOST` and diverts traffic without code changes.

## Supported and partial operations

- `topics.lifecycle`: topics.create/list/delete (supported)
- `subscriptions.lifecycle`: subscriptions.create/list/delete (supported)
- `message.workflow`: publish/pull/ack (supported)
- `advanced-delivery`: schemas, snapshots, seek, dead-letter policy (supported on `PUBSUB_EMULATOR_HOST`)
- `gateway.facade`: REST/Terraform route coverage for advanced delivery (partial; core topic/subscription CRUD only)

## CI guidance

Use for local and CI smoke tests across core publish/pull plus advanced-delivery smoke checks on `PUBSUB_EMULATOR_HOST`. Keep Terraform tests within core topic/subscription CRUD unless gateway advanced routes are added.

## Limitations

- The external Pub/Sub emulator endpoint supports schemas, schema validation, snapshots, seek, and dead-letter policy.
- The LocalCloud gateway REST/Terraform facade does not expose schema, snapshot, or seek endpoints; use `PUBSUB_EMULATOR_HOST` for those workflows.

## Resource verification

After the test passes, verify resources exist via these GET endpoints. **Always check the JSON body for `error: true`** — see [COMMON_GUIDE §5](../COMMON_GUIDE.md#5-resource-verification). All endpoints accept `?project={projectId}` (default: `LOCALCLOUD_PROJECT`).

### Assertion Endpoints

- **List topics:**
  ```http
  GET http://localhost:8080/browse/pubsub/topics?project={projectId}
  ```
- **List subscriptions:**
  ```http
  GET http://localhost:8080/browse/pubsub/subscriptions?project={projectId}
  ```
- **Pull messages from a subscription (no ack):**
  ```http
  GET http://localhost:8080/browse/pubsub/messages/{subscriptionId}?project={projectId}
  ```
- **Browse topic messages (temporary subscription):**
  ```http
  GET http://localhost:8080/browse/pubsub/topics/{topicId}/messages?project={projectId}
  ```
