# In-Process Pub/Sub Facade — Full SDK Compatibility Plan

## Scope

The plan targets **full parity with Google's official Pub/Sub emulator** — meaning every RPC the emulator implements must be implemented, so that `gcloud pubsub`, Terraform, and all gRPC client SDKs (Python, Java, Node.js, Go) work **without code changes**.

The original exception list treated SchemaService as an official-emulator gap. As of the 2026-06-22 compatibility probe, the external Pub/Sub emulator supports SchemaService; the LocalCloud gateway facade still lacks schema routes.

---

## 1. PostgreSQL Data Model

### 1.1 Tables

```sql
CREATE TABLE IF NOT EXISTS pubsub_topics (
    project_id   VARCHAR(255) NOT NULL,
    topic_id     VARCHAR(255) NOT NULL,
    labels       JSONB DEFAULT '{}',
    kms_key_name VARCHAR(500),
    message_retention_duration VARCHAR(50),
    satisfies_pzs BOOLEAN DEFAULT FALSE,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, topic_id)
);

CREATE TABLE IF NOT EXISTS pubsub_subscriptions (
    project_id             VARCHAR(255) NOT NULL,
    subscription_id        VARCHAR(255) NOT NULL,
    topic_project_id       VARCHAR(255) NOT NULL,
    topic_id               VARCHAR(255) NOT NULL,
    ack_deadline_seconds   INT DEFAULT 10,
    push_endpoint           VARCHAR(2048),
    retain_acked_messages   BOOLEAN DEFAULT FALSE,
    message_retention_duration VARCHAR(50) DEFAULT '7d',
    labels                  JSONB DEFAULT '{}',
    enable_message_ordering BOOLEAN DEFAULT FALSE,
    filter                  VARCHAR(2048),
    state                   VARCHAR(20) DEFAULT 'ACTIVE',
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, subscription_id)
);

CREATE TABLE IF NOT EXISTS pubsub_messages (
    message_id    VARCHAR(255) NOT NULL,
    project_id    VARCHAR(255) NOT NULL,
    topic_id      VARCHAR(255) NOT NULL,
    data          BYTEA,
    attributes    JSONB DEFAULT '{}',
    ordering_key  VARCHAR(255),
    published_at  BIGINT NOT NULL,
    PRIMARY KEY (message_id)
);

CREATE TABLE IF NOT EXISTS pubsub_subscription_messages (
    ack_id          VARCHAR(255) NOT NULL,
    subscription_project_id VARCHAR(255) NOT NULL,
    subscription_id VARCHAR(255) NOT NULL,
    message_id      VARCHAR(255) NOT NULL,
    ack_deadline    BIGINT,
    delivery_attempt INT DEFAULT 1,
    consumed        BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (ack_id)
);
CREATE INDEX IF NOT EXISTS idx_psm_pending ON pubsub_subscription_messages
    (subscription_project_id, subscription_id, consumed, ack_deadline)
    WHERE consumed = FALSE;
```

### 1.2 Using `FOR UPDATE SKIP LOCKED` for Pull

PostgreSQL's `FOR UPDATE SKIP LOCKED` (available since PG 9.5) is ideal for implementing `Pull` — it atomically claims messages without blocking concurrent pullers:

```sql
UPDATE pubsub_subscription_messages
SET consumed = TRUE
WHERE ack_id = (
    SELECT ack_id
    FROM pubsub_subscription_messages
    WHERE subscription_project_id = ?
      AND subscription_id = ?
      AND consumed = FALSE
      AND (ack_deadline IS NULL OR ack_deadline < EXTRACT(EPOCH FROM NOW()))
    ORDER BY created_at
    FOR UPDATE SKIP LOCKED
    LIMIT ?
)
RETURNING ack_id, message_id;
```

For **ack deadline tracking**, instead of a scheduled background job, check expiry at pull time: redeliver messages whose `ack_deadline` has passed (set `consumed = FALSE` and increment `delivery_attempt`).

### 1.3 In-Memory Notifier for StreamingPull

StreamingPull needs real-time message delivery. PostgreSQL `LISTEN`/`NOTIFY` is unsuitable (no per-subscription filtering, 8000-byte payload limit). Instead, use an in-memory `PubSubNotifier`:

```java
// Per-subscription signal
ConcurrentHashMap<String, CompletableFuture<Void>> notifiers = new ConcurrentHashMap<>();

// On publish: complete the future to wake waiting streams
void notifySubscription(String subId) {
    CompletableFuture<Void> f = notifiers.get(subId);
    if (f != null) f.complete(null);
}

// StreamingPull: wait for signal, then pull from DB
CompletableFuture<Void> f = new CompletableFuture<>();
notifiers.put(subId, f);
f.get(5, TimeUnit.SECONDS); // wakes on new message or timeout
// then pull from PostgreSQL
```

---

## 2. Full RPC Implementation Plan

### 2.1 Tier 1 — CRITICAL for SDK/CLI compatibility

These 10 RPCs are the **minimum** for Python, Java, Node.js, and Go SDKs to work.

| # | RPC | Service | Complexity | Notes |
|---|-----|---------|------------|-------|
| 1 | `CreateTopic` | Publisher | Low | INSERT, return `Topic` proto |
| 2 | `GetTopic` | Publisher | Low | SELECT by name |
| 3 | `Publish` | Publisher | Medium | INSERT messages, fan-out to sub messages, signal notifier |
| 4 | `DeleteTopic` | Publisher | Low | DELETE with cascade |
| 5 | `CreateSubscription` | Subscriber | Low | INSERT, validate topic exists |
| 6 | `GetSubscription` | Subscriber | Low | SELECT by name |
| 7 | `Pull` | Subscriber | Medium | `FOR UPDATE SKIP LOCKED` SQL |
| 8 | `StreamingPull` | Subscriber | **Hard** | Bidirectional gRPC stream + in-memory notifier |
| 9 | `Acknowledge` | Subscriber | Low | UPDATE consumed = TRUE |
| 10 | `ModifyAckDeadline` | Subscriber | Low | UPDATE ack_deadline |

### 2.2 Tier 2 — IMPORTANT for usability

| # | RPC | Service | Notes |
|---|-----|---------|-------|
| 11 | `ListTopics` | Publisher | SELECT with pagination |
| 12 | `ListTopicSubscriptions` | Publisher | SELECT from subscriptions table |
| 13 | `ListSubscriptions` | Subscriber | SELECT with pagination |
| 14 | `DeleteSubscription` | Subscriber | DELETE with message cleanup |
| 15 | `UpdateTopic` | Publisher | UPDATE labels, config |
| 16 | `UpdateSubscription` | Subscriber | UPDATE ack_deadline, push_config |
| 17 | `ModifyPushConfig` | Subscriber | UPDATE push_endpoint |

### 2.3 Tier 3 — Completeness (official emulator supports these)

| # | RPC | Service | Notes |
|---|-----|---------|-------|
| 18 | `ListTopicSnapshots` | Publisher | Return empty list |
| 19 | `DetachSubscription` | Publisher | Mark subscription detached |
| 20 | `CreateSnapshot` | Subscriber | Snapshot message positions |
| 21 | `GetSnapshot` | Subscriber | SELECT snapshots |
| 22 | `ListSnapshots` | Subscriber | SELECT snapshots |
| 23 | `UpdateSnapshot` | Subscriber | UPDATE snapshot |
| 24 | `DeleteSnapshot` | Subscriber | DELETE snapshot |
| 25 | `Seek` | Subscriber | Reset subscription to snapshot or time |

### 2.4 NOT implemented in the gateway facade or still production-only

| Feature | External emulator | Gateway facade |
|---------|-------------------|----------------|
| SchemaService (`CreateSchema`, etc.) | Supported (verified 2026-06-22) | Not implemented |
| IAM (`SetIamPolicy`, `TestIamPermissions`) | Stubbed | Stubbed |
| Exactly-once delivery | Not supported | Not supported (at-least-once) |
| BigQuery subscriptions | Not supported | Not supported |
| Cloud Storage subscriptions | Not supported | Not supported |
| OIDC token push auth | Config accepted, not minted | Same |

---

## 3. Architecture

### 3.1 Components

```
PubSubEmulator (AbstractEmulator subclass)
├── PubSubStore (PostgreSQL via PostgresDataSource)
│   ├── topics CRUD
│   ├── subscriptions CRUD
│   ├── messages insert + fan-out
│   ├── pull (FOR UPDATE SKIP LOCKED)
│   ├── acknowledge
│   ├── snapshots CRUD
│   └── seek
├── PublisherServiceImpl (PublisherGrpc.PublisherImplBase)
│   └── all Publisher RPCs
├── SubscriberServiceImpl (SubscriberGrpc.SubscriberImplBase)
│   └── all Subscriber RPCs
├── PubSubNotifier (in-memory)
│   └── per-subscription CompletableFuture for StreamingPull
└── PushDeliveryLoop (background thread, optional)
    └── polls push subscriptions, dispatches HTTP POST
```

### 3.2 Registration in Gateway

```java
if (config.isServiceEnabled("pubsub")) {
    PubSubEmulator emulator = new PubSubEmulator(dataSource);
    emulator.start();
    grpcBuilder
        .addService(emulator.getPublisherService())
        .addService(emulator.getSubscriberService());
    // Armeria transcoding auto-creates REST from google.api.http annotations
    gateway.registerGrpcEmulator(emulator,
        emulator.getPublisherService(),
        emulator.getSubscriberService());
}
```

**No separate `PubSubRestService.java` needed** — Armeria's `.enableHttpJsonTranscoding(true)` reads the `google.api.http` annotations from the protobuf definition and auto-generates all REST endpoints. A single gRPC implementation = full gRPC + REST coverage.

### 3.3 StreamingPull Implementation Detail

StreamingPull is a bidirectional gRPC stream:

```
Client → StreamingPull(subscription, stream ACKs, stream deadline mods)
Server → StreamingPull(ReceivedMessage, ...)
```

Implementation:

```java
@Override
public StreamObserver<StreamingPullRequest> streamingPull(
        StreamObserver<StreamingPullResponse> responseObserver) {
    return new StreamObserver<>() {
        // On first request: start a background task that:
        //   1. Pulls messages from PostgreSQL
        //   2. Sends StreamingPullResponse
        //   3. Waits on PubSubNotifier for new messages
        //   4. Repeats
        //
        // On subsequent requests:
        //   - If contains acks: call store.acknowledge()
        //   - If contains deadline mods: call store.modifyAckDeadline()
    };
}
```

### 3.4 Gradle Dependencies

```groovy
implementation 'com.google.api.grpc:proto-google-cloud-pubsub-v1:1.115.0'
implementation 'com.google.api.grpc:grpc-google-cloud-pubsub-v1:1.115.0'
```

These provide:
- `com.google.pubsub.v1.PublisherGrpc` — generated Publisher gRPC stub
- `com.google.pubsub.v1.SubscriberGrpc` — generated Subscriber gRPC stub
- `com.google.pubsub.v1.Topic`, `PubsubMessage`, `PullRequest`, `StreamingPullRequest`, etc.
- `google.api.http` annotations for REST transcoding

---

## 4. Implementation Phases

### Phase 1: Schema + Store (2 days)
- Add 4 PostgreSQL tables to `SchemaManager.java`
- Create `PubSubStore.java` — all CRUD operations
- `PubSubStore.pull()` using `FOR UPDATE SKIP LOCKED`

### Phase 2: Core gRPC services (3 days)
- Create `PubSubEmulator.java` with `PublisherServiceImpl` and `SubscriberServiceImpl`
- Implement Tier 1 RPCs (10 RPCs)
- `StreamingPull` with `PubSubNotifier`
- Wire into `LocalCloudApplication.java`

### Phase 3: Full API surface (2 days)
- Implement Tier 2 RPCs (list, update operations)
- Implement Tier 3 RPCs (snapshots, seek)
- Handle error cases matching Google's behavior

### Phase 4: Remove external emulator (1 day)
- Remove `[program:pubsub-emulator]` from `supervisord.conf`
- Remove JAR extraction from `Dockerfile:211`
- Change `services.yaml` — `type: facade`, remove port
- Clean up unused JDK dependency if no other service needs JAR launching
- Update admin services (SeedService, BrowseService, MutateService, QueryService, ExportService) to call gateway self-REST instead of port 8085

### Phase 5: Push delivery + polish (1-2 days)
- `PushDeliveryLoop` — background thread for push subscriptions
- Retry policy support
- Dead letter topic forwarding

---

## 5. Feature Parity Summary

| Feature | Google Emulator | Our Facade (Phase 2) | Our Facade (Phase 5) |
|---------|---------------|---------------------|---------------------|
| Topics CRUD | ✅ | ✅ | ✅ |
| Publish | ✅ | ✅ | ✅ |
| Subscriptions CRUD | ✅ | ✅ | ✅ |
| Pull | ✅ | ✅ | ✅ |
| StreamingPull | ✅ | ✅ | ✅ |
| Acknowledge | ✅ | ✅ | ✅ |
| ModifyAckDeadline | ✅ | ✅ | ✅ |
| List/Update/Delete | ✅ | ✅ | ✅ |
| Snapshots + Seek | ✅ | ❌ | ✅ |
| Push delivery | ✅ | ❌ | ✅ |
| Dead letter | ✅ | ❌ | ✅ |
| Retry policy | ✅ | ❌ | ✅ |
| **Persistence** | **❌ in-memory** | **✅ PostgreSQL** | **✅ PostgreSQL** |
| SchemaService | ✅ verified 2026-06-22 | ❌ UNIMPLEMENTED | ❌ gateway route not implemented |
| BigQuery/Storage subs | ❌ | ❌ | ❌ |

The remaining gateway facade gaps vs. the external emulator include SchemaService route coverage plus BigQuery/Storage subscriptions.

---

## 6. Total Effort: 8-11 days

| Phase | Days | Deliverable |
|-------|------|-------------|
| 1. Schema + Store | 2 | PostgreSQL tables, all CRUD, FOR UPDATE SKIP LOCKED |
| 2. Core gRPC | 3 | 10 Tier-1 RPCs, StreamingPull, wiring |
| 3. Full API | 2 | All remaining RPCs, error handling |
| 4. Remove ext | 1 | Clean up Dockerfile, supervisord, admin services |
| 5. Push + Polish | 1-2 | Push delivery, DLQ, retry |
| **Total** | **9-10** | Full parity + PostgreSQL persistence |
