---
title: 'Cloud Tasks Emulator — Developer-Critical Gaps & Quality Fixes'
type: 'feature'
created: '2026-06-02'
status: 'done'
baseline_commit: 'f02bc69'
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The Cloud Tasks emulator is missing UpdateQueue, PurgeQueue, full RetryConfig, queue-level HttpTarget, task persistence, dispatchDeadline, and maxBurstSize. Tasks are held in-memory (lost on restart). buildQueue()/buildTask() return incomplete protos. MutateService and CloudTasksStore diverge on queue creation.

**Approach:** Add the missing gRPC + REST methods, add retry config / httpTarget / rate limit columns to `task_queues` via a new Flyway migration, persist tasks to the existing `cloud_tasks` table on create/update (hybrid in-memory + DB), unify queue creation paths, and flesh out proto responses. Exclude UI/console, BufferTask, CMEK, App Engine targets, OAuth/OIDC.

## Boundaries & Constraints

**Always:** gRPC and REST parity for all new methods. Tasks survive restart via DB persistence. H2 in PostgreSQL compatibility mode for tests (no ON CONFLICT). Expose rate limits, retry config, and httpTarget in Queue responses — using protobuf field names. End-to-end integration tests for full lifecycle.

**Ask First:** Whether to add a separate Flyway migration file (V2) vs modifying V1 (use V2 — existing DBs need it). Whether to emit PurgeQueue from MutateService console path (skip for now — gRPC/REST only).

**Never:** UI/console changes. BufferTask. CMEK encryption. App Engine routing/targets. OAuth/OIDC token auth. Production hardening (30-day TTL, rate limit enforcement accuracy). Modifying the H2-only `ON CONFLICT` fallback patterns — keep them as-is for now.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| UpdateQueue with partial fields | Queue with only maxAttempts set | Only maxAttempts updated; other fields unchanged | missing name → INVALID_ARGUMENT |
| UpdateQueue on non-existent queue | Queue not in DB | gRPC NOT_FOUND / REST 404 | Proper status code |
| PurgeQueue | Queue has 5 tasks | All 5 tasks deleted from DB + memory; queue returned with same state | Queue not found → NOT_FOUND |
| CreateTask with queue-level httpTarget fallback | Queue has httpTargetUri set; task has no URL | Task inherits queue httpTargetUri, httpTargetMethod | No URL at queue or task level → INVALID_ARGUMENT |
| Task dispatched uses queue retry config | Queue has maxDoublings=5, minBackoff=1s | Dispatcher reads these values; exponential backoff respects 5 doublings | N/A |
| Server restart recovers tasks | Emulator stopped/started | Previously created tasks reloaded from cloud_tasks table into in-memory map | Missing tasks → logged warning |
| Duplicate queue create (idempotency) | Queue already exists | gRPC: ALREADY_EXISTS; REST via MutateService: ON CONFLICT DO NOTHING | Clean idempotent response |
| Delete queue cascades to tasks | Queue has tasks | FK ON DELETE CASCADE removes related cloud_tasks rows | N/A |
</frozen-after-approval>

## Code Map

- `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksStore.java` — queue + task storage (DB + in-memory hybrid). Add UpdateQueue, PurgeQueue, persistTask, reloadTasks, full config read/write.
- `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksEmulator.java` — gRPC service. Add UpdateQueue, PurgeQueue handlers. Enhance buildQueue/buildTask protos.
- `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksRestService.java` — REST endpoints. Add PATCH /queues/{q}, POST /queues/{q}:purge. Enhance GET responses with full fields.
- `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/TaskDispatcher.java` — reads full retry config and httpTarget from store; respects dispatchDeadline.
- `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java` — enhance create queue to accept rate limits, retry config, httpTarget. Unify with CloudTasksStore.
- `localcloud-server/src/main/java/com/localcloud/admin/SeedService.java` — enhance seed to accept full queue config.
- `localcloud-server/src/main/resources/db/migration/V4__cloud_tasks_queue_config.sql` — NEW migration. Add retry + httpTarget columns to task_queues. Add dispatch_deadline, first_attempt_time, last_attempt_time to cloud_tasks.
- `localcloud-server/src/test/java/com/localcloud/integration/CloudTasksIntegrationTest.java` — end-to-end tests: UpdateQueue, PurgeQueue, task persistence, retry config flow, httpTarget inheritance.
- `localcloud-server/src/test/java/com/localcloud/emulators/cloudtasks/CloudTasksStoreTest.java` — add DB-backed tests for task persistence, UpdateQueue, PurgeQueue.
- `localcloud-server/src/test/java/com/localcloud/emulators/cloudtasks/CloudTasksRestServiceTest.java` — add PATCH and purge endpoint tests.

## Tasks & Acceptance

**Execution:**
- [x] `V4__cloud_tasks_queue_config.sql` — add columns: task_queues (max_burst_size, min_backoff, max_backoff, max_doublings, max_retry_duration, http_target_uri, http_target_method) + cloud_tasks (dispatch_deadline, first_attempt_time, last_attempt_time) — schema must support new features
- [x] `CloudTasksStore.java` — add full queue config read/write (retry, httpTarget, maxBurstSize), UpdateQueue, PurgeQueue, persistTask to cloud_tasks table, reloadTasks on startup, getQueueFullConfig — hybrid DB + memory model
- [x] `CloudTasksEmulator.java` — add UpdateQueue, PurgeQueue gRPC methods; enhance buildQueue (rateLimits maxBurstSize+maxConcurrentDispatches, retryConfig) and buildTask (createTime, scheduleTime, dispatchDeadline, firstAttempt, lastAttempt) — API completeness
- [x] `CloudTasksRestService.java` — add PATCH /queues/{q}, POST /queues/{q}:purge; enhance GET/LIST responses with rateLimits, retryConfig, httpTarget — REST parity
- [x] `TaskDispatcher.java` — read retry config from store (backoff/doublings/duration), read queue httpTarget as fallback for tasks without URL, respect dispatchDeadline — correct dispatch behavior
- [x] `MutateService.java` — enhance create queue to parse and forward rate limits, retry config, httpTarget — unified with gRPC path
- [x] `SeedService.java` — enhance seed to accept full queue config fields — parity with MutateService
- [x] `CloudTasksIntegrationTest.java` — add tests: UpdateQueue gRPC+REST, PurgeQueue gRPC+REST, task persistence/reload, retry config on dispatch, full queue config create+read, full lifecycle — end-to-end coverage (30 tests)
- [x] `CloudTasksStoreTest.java` — existing tests pass (19 tests); Updated persistTask, reloadTasks, queue config — unit coverage for storage layer
- [x] `CloudTasksRestServiceTest.java` — existing tests pass (7 tests); Updated PATCH queue with retryConfig, POST purge, GET queue returns full config — REST contract tests

**Acceptance Criteria:**
- Given a queue exists, when UpdateQueue is called with new maxAttempts=5, then getQueue returns maxAttempts=5 with other fields unchanged
- Given a queue with 3 tasks, when PurgeQueue is called, then all tasks are deleted from DB and memory, queue state is preserved
- Given a task is created with persistTask, when emulator restarts, then reloadTasks() recovers the task into the in-memory dispatch map
- Given a queue has httpTargetUri="http://worker:8080/handle", when task is created without URL, then dispatcher uses the queue-level URL
- Given a queue has retryConfig maxDoublings=3 minBackoff=1s, when a task fails, then backoff doubles at most 3 times before growing linearly
- Given UpdateQueue gRPC succeeds, then the same operation via REST PATCH also succeeds with identical behavior
- Given buildQueue is called, then RateLimits includes maxDispatchesPerSecond, maxBurstSize, maxConcurrentDispatches; RetryConfig includes all 5 fields; HttpTarget is present when configured
- Given an existing database upgraded via V2 migration, then existing queues retain their data with new columns at defaults

## Spec Change Log

### 2026-06-02: Review loop 1
- **Finding:** REST PATCH overwrites unspecified fields with defaults (data-loss bug).
- **Amended:** `CloudTasksRestService.updateQueue` to read current config first, then merge only provided JSON fields.
- **Avoids:** Silently resetting rate limits when user only updates retry config.
- **KEEP:** gRPC FieldMask partial update pattern (merge from current DB state) — works correctly.

## Design Notes

### Hybrid Task Storage
Tasks are written to the `cloud_tasks` table on create AND held in a ConcurrentHashMap for zero-DB-latency dispatch. On startup, all non-terminal tasks are reloaded from DB. On state change (RUNNING → COMPLETED/FAILED), the DB row is updated. This avoids the "lost on restart" problem while keeping dispatch fast.

### Retry Config in TaskDispatcher
Current code hardcodes `Math.pow(2, dispatchCount)` backoff. Replace with: start at minBackoff, double up to maxDoublings times (capped at maxBackoff), then grow linearly to maxBackoff. Respect maxRetryDuration: if time since firstAttempt exceeds it, don't retry.

### Queue-Level HttpTarget Fallback
In dispatchTask(), if task.httpUrl is null/empty, look up the queue's httpTargetUri and httpTargetMethod from the store. If both task and queue lack a URL, mark the task FAILED.

### Rate Limits in Proto Responses
`buildQueue()` currently only sets maxDispatchesPerSecond. Add maxBurstSize (output-only in GCP) and maxConcurrentDispatches. RateLimits.newBuilder().setMaxDispatchesPerSecond(rate).setMaxBurstSize(burst).setMaxConcurrentDispatches(concurrent).

## Verification

**Commands:**
- `cd localcloud-server && ./gradlew test --tests "com.localcloud.emulators.cloudtasks.*"` — expected: all CloudTasksStoreTest + CloudTasksRestServiceTest pass
- `cd localcloud-server && ./gradlew test --tests "com.localcloud.integration.CloudTasksIntegrationTest"` — expected: all integration tests pass (requires Docker for full env)
- `cd localcloud-server && ./gradlew compileJava` — expected: BUILD SUCCESSFUL, no new warnings

## Suggested Review Order

**Migration & Schema**

- Adds retry config, HTTP target, rate limit, and timing columns — foundation for all new features
  [`V4__cloud_tasks_queue_config.sql:1`](../../localcloud-server/src/main/resources/db/migration/V4__cloud_tasks_queue_config.sql#L1)

**Hybrid Storage (DB + Memory)**

- New QueueConfig holder and full config CRUD in store; merge-from-current-DB for partial updates
  [`CloudTasksStore.java:79`](../../localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksStore.java#L79)

- Task persistence: persistTask writes to cloud_tasks table, reloadTasks recovers on startup
  [`CloudTasksStore.java:298`](../../localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksStore.java#L298)

- Update queue with dynamic SQL builder; partial updates only touch provided fields
  [`CloudTasksStore.java:117`](../../localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksStore.java#L117)

**gRPC API**

- Entry point: createQueue with full config, UpdateQueue with FieldMask merge, PurgeQueue, plus enhanced proto responses
  [`CloudTasksEmulator.java:96`](../../localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksEmulator.java#L96)

- FieldMask-aware partial update — merges into current DB config, not fresh defaults
  [`CloudTasksEmulator.java:153`](../../localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksEmulator.java#L153)

- buildQueue returns full RateLimits (burst+concurrent) and RetryConfig (5 fields); buildTask includes createTime, attempts, dispatchDeadline
  [`CloudTasksEmulator.java:629`](../../localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksEmulator.java#L629)

**REST API**

- PATCH /queues/{q} merges JSON body into current config (prevents data-loss when only updating one field)
  [`CloudTasksRestService.java:127`](../../localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksRestService.java#L127)

- POST /queues/{q}:purge deletes all tasks; enhanced GET/LIST return full rateLimits + retryConfig
  [`CloudTasksRestService.java:151`](../../localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksRestService.java#L151)

**Task Dispatcher**

- Exponential backoff using queue's minBackoff/maxBackoff/maxDoublings; maxRetryDuration enforcement; dispatchDeadline gating
  [`TaskDispatcher.java:163`](../../localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/TaskDispatcher.java#L163)

- Queue-level HTTP target fallback when task has no URL; terminal task eviction prevents memory leak
  [`TaskDispatcher.java:121`](../../localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/TaskDispatcher.java#L121)

**Admin API (Console-backed)**

- Enhanced create queue to accept full config (rate limits, retry, httpTarget) via MutateService
  [`MutateService.java:2518`](../../localcloud-server/src/main/java/com/localcloud/admin/MutateService.java#L2518)

- Seed YAML now accepts all queue config fields
  [`SeedService.java:1897`](../../localcloud-server/src/main/java/com/localcloud/admin/SeedService.java#L1897)

**Tests**

- 30 integration tests covering full lifecycle: create/get/list/update/delete/purge/pause/resume via gRPC+REST, FieldMask partial updates, error cases
  [`CloudTasksIntegrationTest.java:79`](../../localcloud-server/src/test/java/com/localcloud/integration/CloudTasksIntegrationTest.java#L79)

- Test schema updated with new columns matching production V4 migration
  [`TestDataSource.java:107`](../../localcloud-server/src/test/java/com/localcloud/integration/TestDataSource.java#L107)
