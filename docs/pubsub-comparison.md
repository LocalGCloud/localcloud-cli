# Pub/Sub: LocalCloud runtime vs gateway facade vs Google Cloud

LocalCloud currently exposes Pub/Sub through two surfaces:

- `PUBSUB_EMULATOR_HOST` / port `8085`: the external Google Pub/Sub emulator. This is the authoritative SDK/gRPC endpoint and the verified advanced-delivery surface.
- LocalCloud gateway `/v1` / port `8080`: a REST facade used by dashboard and Terraform routing for core topic/subscription CRUD. It does not expose every advanced Pub/Sub management route.

## Compatibility matrix

| Feature | External Pub/Sub emulator (`localhost:8085`) | LocalCloud gateway REST/Terraform (`localhost:8080`) | Production GCP |
|---------|:-:|:-:|:-:|
| **Core API surface** | | | |
| Create/Get/List/Update/Delete topics | ✅ | ✅ core facade | ✅ |
| Create/Get/List/Update/Delete subscriptions | ✅ | ✅ core facade | ✅ |
| Publish, pull, acknowledge | ✅ | ❌ not gateway data plane | ✅ |
| StreamingPull | ✅ | ❌ not gateway data plane | ✅ |
| ModifyAckDeadline | ✅ | ❌ not gateway data plane | ✅ |
| ModifyPushConfig | ✅ | ❌ not gateway data plane | ✅ |
| **Advanced delivery** | | | |
| SchemaService create/get/list/delete | ✅ verified 2026-06-22 | ❌ route not exposed | ✅ |
| Schema validation on publish | ✅ verified 2026-06-22 | ❌ route not exposed | ✅ |
| Create/Get/List/Delete snapshots | ✅ verified 2026-06-22 | ❌ route not exposed | ✅ |
| Seek to snapshot | ✅ verified 2026-06-22 | ❌ route not exposed | ✅ |
| Dead-letter policy | ✅ verified 2026-06-22 | ⚠️ metadata path only; behavior verified on external endpoint | ✅ |
| Retry policy | ✅ | ⚠️ metadata path only; behavior verified on external endpoint | ✅ |
| Message ordering keys | ⚠️ accepted, not full production ordering | ❌ not gateway data plane | ✅ |
| Exactly-once delivery | ❌ not supported | ❌ not supported | ✅ |
| Subscription filter expressions | ⚠️ emulator behavior differs from production | ❌ not gateway data plane | ✅ |
| BigQuery subscriptions | ❌ not supported | ❌ not supported | ✅ |
| Cloud Storage subscriptions | ❌ not supported | ❌ not supported | ✅ |
| **Management** | | | |
| Client SDKs (Python, Java, Go, Node.js) | ✅ via `PUBSUB_EMULATOR_HOST` | ❌ use emulator endpoint | ✅ |
| Terraform `google_pubsub_topic` | ✅ when pointed at emulator-compatible REST | ✅ core facade | ✅ |
| Terraform `google_pubsub_subscription` | ✅ when pointed at emulator-compatible REST | ✅ core facade | ✅ |
| gcloud CLI | ⚠️ partial/unverified in LocalCloud compatibility registry | ⚠️ partial/unverified | ✅ |
| Console/data browser | ⚠️ topic/subscription browse only | ⚠️ topic/subscription browse only | ✅ |
| **Persistence** | | | |
| Data survives container restart | ❌ external emulator is in-memory | ⚠️ facade metadata only | ✅ GCP-managed |
| Topics/subscriptions/messages persist | ❌ external emulator session only | ⚠️ facade metadata only | ✅ |
| Snapshots persist | ❌ external emulator session only | ❌ route not exposed | ✅ |
| **Monitoring & observability** | | | |
| Request counting | ⚠️ gateway-visible routes only | ✅ UsageMetricsRepository | ✅ Cloud Monitoring |
| Publisher/subscriber metrics | ❌ | ❌ | ✅ |
| Dead-letter metrics | ❌ | ❌ | ✅ |

## Status summary

### Advanced delivery is supported on the active Pub/Sub emulator endpoint

The compatibility registry now marks `advanced-delivery` as supported because a live probe against `localhost:8085` verified schemas, schema validation, snapshots, seek-to-snapshot replay, and dead-letter forwarding after `maxDeliveryAttempts`.

### Gateway REST/Terraform facade remains a narrower surface

The LocalCloud gateway `/v1` Pub/Sub facade is for core topic/subscription CRUD used by dashboard and Terraform paths. Schema, snapshot, and seek routes are not exposed there, so API consumers should target `PUBSUB_EMULATOR_HOST` for advanced-delivery workflows.

### Why overall Pub/Sub coverage is still `partial`

Overall service coverage remains `partial` because not every LocalCloud surface has the same Pub/Sub coverage:

- SDK/gRPC emulator endpoint: advanced delivery verified.
- Gateway/Terraform facade: core topic/subscription CRUD only.
- gcloud and console paths: still partial/unverified for advanced workflows.

## Legend

- ✅ = Supported on that surface.
- ⚠️ = Implemented or accepted with surface-specific limitations.
- ❌ = Not supported on that surface.
