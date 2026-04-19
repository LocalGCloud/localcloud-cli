## 1. Database Schema

- [x] 1.1 Add `telemetry_queue` table to `SchemaManager.java` — `id SERIAL PRIMARY KEY, event_json TEXT NOT NULL, created_at TIMESTAMP DEFAULT NOW()`

## 2. TelemetryService Queue Logic

- [x] 2.1 Add `PostgresDataSource` dependency to `TelemetryService` constructor
- [x] 2.2 Add `enqueueEvent(String eventJson)` method — INSERT into `telemetry_queue`, then DELETE oldest when count > 168
- [x] 2.3 Add `drainQueue()` method — SELECT oldest events, attempt to send each, DELETE on success, stop on first failure
- [x] 2.4 Modify `sendHeartbeat()` → `heartbeatCycle()` — call `drainQueue()` before collecting new stats; on send failure call `enqueueEvent()`
- [x] 2.5 Modify `recordServiceError()` — on send failure call `enqueueEvent()`
- [x] 2.6 Extract `sendEvent()` → `trySend()` returning boolean + `buildEventJson()` for serialization

## 3. Wire Up

- [x] 3.1 Pass `PostgresDataSource` to `TelemetryService` in `LocalCloudApplication.java`

## 4. Configurable API Key (bonus)

- [x] 4.1 Read `LOCALCLOUD_POSTHOG_API_KEY` from env var instead of hardcoded constant
- [x] 4.2 Read `LOCALCLOUD_POSTHOG_URL` from env var (default: `https://app.posthog.com/capture`)
- [x] 4.3 Skip telemetry if API key is empty (no-op, no errors)
- [x] 4.4 Add `LOCALCLOUD_POSTHOG_API_KEY` to Dockerfile ENV defaults

## 5. Build & Test

- [x] 5.1 Run Java tests: `cd localcloud-server && ./gradlew test`
- [x] 5.2 Build shadow JAR
