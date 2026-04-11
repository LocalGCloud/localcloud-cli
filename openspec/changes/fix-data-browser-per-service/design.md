## Context

The previous change (`seed-data-and-data-browser-crud`) created the MutateService, CrudModal, and DeleteConfirmation components, but several services have gaps:

| Service | Browse | Seed | CRUD Handler | UI Buttons | Status |
|---------|--------|------|-------------|-----------|--------|
| Pub/Sub | Topics only | Topics/subs only | Missing | Missing | No message browsing |
| Firestore | Implemented | Data defined but not inserted | Implemented | Implemented | Seed broken |
| BigQuery | Full drill-down | Working | Implemented | **Missing** | UI gap |
| Cloud Tasks | Queues from PG | None | **Missing** | Exists but fails | Handler gap |
| Spanner | Full drill-down | Working | Implemented | **Missing** | UI gap |
| Bigtable | Tables/rows from PG | Working | **Returns TODO** | Exists but fails | Handler gap |

## Goals / Non-Goals

**Goals:**
- Every database service has working seed data, functional data browsing, and CRUD operations
- Pub/Sub messages are browsable (pull from subscriptions, display decoded data)
- All existing mutation handlers are wired to UI buttons
- Broken handlers (Cloud Tasks, Bigtable) are fixed

**Non-Goals:**
- Real-time Pub/Sub streaming (pull-based is sufficient)
- Pub/Sub push delivery configuration from the UI
- Full Bigtable gRPC integration (PostgreSQL-backed approach is acceptable for local dev)

## Decisions

### D1: Pub/Sub message browsing via pull with immediate nack

To browse Pub/Sub messages without consuming them, use the pull API with `returnImmediately: true` and do NOT acknowledge the messages. This allows repeated browsing without losing messages.

The Pub/Sub emulator's REST API:
- `POST /v1/{subscription}:pull` with `{"maxMessages": 100, "returnImmediately": true}`
- Response contains `receivedMessages` array with `message.data` (base64), `message.attributes`, `message.publishTime`, `message.messageId`

Messages are decoded from base64 for display. If the data is valid JSON, display it formatted.

### D2: Pub/Sub seed messages in seed.yaml

Add a `messages` section under each topic in seed.yaml:
```yaml
pubsub:
  topics:
    - name: "user-events"
      subscriptions:
        - name: "user-events-processor"
          ackDeadlineSeconds: 30
      messages:
        - data: '{"event":"user.login","userId":"1","timestamp":"2026-04-08T10:00:00Z"}'
          attributes: {"source": "auth-service"}
```

SeedService publishes messages via `POST /v1/projects/{project}/topics/{topic}:publish` with base64-encoded data.

### D3: Cloud Tasks mutation via PostgreSQL

Cloud Tasks queues are facade-managed (stored in PostgreSQL). The mutation handler will INSERT/DELETE directly from the `task_queues` table, matching how browseCloudTasks() reads from it.

### D4: Bigtable mutations via PostgreSQL

Since the Bigtable emulator is gRPC-only and browse already uses PostgreSQL (`bigtable_data` table), mutations will also use PostgreSQL. Replace the TODO response with actual INSERT/DELETE on `bigtable_data`.

### D5: Fix Firestore seeding — verify REST API endpoint

The `seedFirestore()` method uses PATCH on the Firestore REST API. The issue may be:
- Wrong port (Firestore may not expose REST on 8086)
- Wrong URL path format
- Firestore emulator requiring database creation first

Fix: Verify the Firestore emulator's REST API endpoint. If REST is not available, use direct gRPC or store in PostgreSQL as a fallback (like Bigtable).

### D6: BigQuery and Spanner UI buttons — use existing CrudModal pattern

The CrudModal and DeleteConfirmation components already exist. For BigQuery and Spanner table data views, add:
- "Add Row" button that opens CrudModal with columns as fields
- "Delete" button per row that opens DeleteConfirmation
- For Spanner: "Edit" button per row that opens CrudModal pre-filled

The column names come from the browse response metadata (already returned by BrowseService).

## Risks / Trade-offs

**Pub/Sub pull without ack** → Messages remain in the subscription and may be redelivered. For local development browsing, this is acceptable — the alternative (peek API) doesn't exist in the emulator.

**Firestore REST may not work** → The emulator may only support gRPC. Mitigation: Test REST endpoint, fall back to PostgreSQL storage if needed.

**Cloud Tasks via direct PostgreSQL** → Bypasses any in-memory state in CloudTasksStore. Acceptable because browseCloudTasks already reads from PostgreSQL directly.
