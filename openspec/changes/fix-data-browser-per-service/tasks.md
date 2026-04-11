## 1. Pub/Sub — Message Browsing, Seed Messages, and CRUD

- [x] 1.1 Add Pub/Sub message browsing endpoint in `BrowseService.java` — implement `browseMessages(subscription)` that calls `POST /v1/{subscription}:pull` with `maxMessages: 100, returnImmediately: true`, decodes base64 message data, returns messages with id, publishTime, data, attributes. Do NOT acknowledge messages.
- [x] 1.2 Add Pub/Sub mutation handler in `MutateService.java` — implement `mutatePubSub()` for: `topics` (PUT to create topic), `topics/delete` (DELETE topic), `messages` (POST publish message with base64-encoded data and attributes)
- [x] 1.3 Add Pub/Sub seed messages in `seed.yaml` — add `messages` array under each topic: 5 user-events (login, signup, profile-update, password-change, logout) and 5 order-events (created, confirmed, shipped, delivered, cancelled) with realistic JSON payloads
- [x] 1.4 Implement Pub/Sub message publishing in `SeedService.java` — in `seedPubSub()`, after creating topics, publish messages via `POST /v1/projects/{project}/topics/{topic}:publish` with base64-encoded data
- [x] 1.5 Update `DataBrowser.jsx` Pub/Sub view — add "Messages" tab/section that fetches messages for the first subscription of each topic. Add "Publish Message" button with data (textarea) and attributes (key-value pairs) fields. Add "Create Topic" and "Delete Topic" buttons.
- [x] 1.6 Add Pub/Sub browse and mutate proxy routes in Flask `app.py` if not already covered by generic routes

## 2. Firestore — Fix Seed Data Insertion

- [x] 2.1 Debug and fix `seedFirestore()` in `SeedService.java` — verify the Firestore emulator REST API endpoint URL (port 8086 may not support REST, try port 8080 or check emulator docs). Test PATCH request manually. If REST fails, fall back to direct HTTP with correct Firestore REST URL format.
- [x] 2.2 Verify Firestore Data Browser shows seeded collections and documents after fix — test end-to-end: seed → browse → see data

## 3. BigQuery — Add Missing CRUD UI Buttons

- [x] 3.1 Add "Add Row" button to BigQuery table data view in `DataBrowser.jsx` — when viewing table data, show button that opens CrudModal with column names from the browse response as fields. On submit, call `api.mutate('bigquery', 'rows', { dataset, table, row: formData })`
- [x] 3.2 Add "Delete" button per row in BigQuery table data view — show delete icon per row. On click, open DeleteConfirmation. On confirm, call `api.mutate('bigquery', 'rows/delete', { dataset, table, whereClause })`. Build WHERE clause from primary key columns.

## 4. Cloud Tasks — Fix Queue Creation and Add Seed Data

- [x] 4.1 Add Cloud Tasks mutation handler in `MutateService.java` — implement `mutateCloudTasks()` for: `queues` (INSERT into PostgreSQL `task_queues` table with project_id, queue_id, location_id, state='RUNNING'), `queues/delete` (DELETE from `task_queues`). Match the same PostgreSQL schema used by `browseCloudTasks()`.
- [x] 4.2 Add Cloud Tasks seed data in `seed.yaml` — add `cloudtasks` section with 3 queues: `email-queue` (max_attempts: 5), `payment-queue` (max_attempts: 10), `notification-queue` (max_attempts: 3)
- [x] 4.3 Implement Cloud Tasks seeding in `SeedService.java` — add `seedCloudTasks()` method that inserts queues into `task_queues` PostgreSQL table. Wire into seed() and reset() dispatch.

## 5. Spanner — Add Missing CRUD UI Buttons

- [x] 5.1 Add "Add Row" button to Spanner table data view in `DataBrowser.jsx` — when viewing table data (after drill-down to instances → databases → tables → data), show button that opens CrudModal with column names from browse response. On submit, call `api.mutate('spanner', 'rows', { instance, database, table, columns, values })`.
- [x] 5.2 Add "Edit" and "Delete" buttons per row in Spanner table data view — Edit opens CrudModal pre-filled, Delete opens DeleteConfirmation. Call appropriate mutation endpoints.

## 6. Bigtable — Fix AddRow Handler

- [x] 6.1 Fix Bigtable mutation handler in `MutateService.java` — replace the TODO response with actual PostgreSQL operations: `rows` → INSERT into `bigtable_data` (instance_id, table_name, row_key, cells as JSONB), `rows/delete` → DELETE from `bigtable_data` WHERE row_key matches. Match the same schema used by `browseBigtable()`.

## 7. Build & Verify

- [x] 7.1 Build Java server (`./gradlew build`) — ensure all new/modified handlers compile and tests pass
- [x] 7.2 Build console frontend (`npm run build`) — ensure updated DataBrowser.jsx builds
- [ ] 7.3 Verify each service end-to-end: seed data loads, Data Browser shows data, CRUD operations work
