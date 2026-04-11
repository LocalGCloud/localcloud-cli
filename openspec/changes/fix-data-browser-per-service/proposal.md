## Why

After the initial Data Browser CRUD implementation, several services have gaps: Pub/Sub shows topics but can't browse messages, Firestore seed data isn't being inserted (empty UI), BigQuery and Spanner have working mutation handlers but no UI buttons, Cloud Tasks queue creation fails because the mutation handler is missing, and Bigtable addRow returns a TODO. Each service needs targeted fixes to deliver a complete data browsing and manipulation experience.

## What Changes

### Pub/Sub
- Add message browsing: pull last 100 messages from each subscription and display in Data Browser
- Add seed data: publish sample messages to each topic during seeding
- Add mutation handlers for creating/deleting topics and publishing messages

### Firestore
- Fix seed data insertion: debug/fix `seedFirestore()` so documents are actually created via REST API
- Verify Data Browser shows seeded collections and documents

### BigQuery
- Add CRUD UI buttons to DataBrowser.jsx (Add Row, Delete Row) — handlers already exist in MutateService

### Cloud Tasks
- Implement `mutateCloudTasks()` handler in MutateService.java for queue creation/deletion
- Add Cloud Tasks seed data (2-3 queues with sample tasks)

### Spanner
- Add CRUD UI buttons to DataBrowser.jsx (Add Row, Edit Row, Delete Row) — handlers already exist in MutateService
- Verify table/data drill-down is working end-to-end with seeded data

### Bigtable
- Implement actual row mutations via PostgreSQL (same approach as browse) instead of returning TODO
- Fix the addRow handler to insert into `bigtable_data` table

## Capabilities

### New Capabilities

- `pubsub-message-browsing`: Browse published messages in Pub/Sub topics via pull-based message retrieval, with seed message publication and topic/subscription CRUD

### Modified Capabilities

_None — no existing OpenSpec capabilities to modify_

## Impact

- **MutateService.java**: Add pubsub and cloudtasks mutation handlers, fix bigtable handler
- **SeedService.java**: Fix Firestore seeding, add Pub/Sub message publishing, add Cloud Tasks seed data
- **DataBrowser.jsx**: Add missing CRUD buttons for BigQuery, Spanner; fix Pub/Sub message display
- **seed.yaml**: Add Cloud Tasks queues and Pub/Sub message data
