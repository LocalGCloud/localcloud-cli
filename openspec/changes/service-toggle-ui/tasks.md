## 1. Database Schema & Repository

- [x] 1.1 Add `service_config` table creation to `SchemaManager.java` — columns: `service_id TEXT PRIMARY KEY`, `enabled BOOLEAN NOT NULL`, `updated_at TIMESTAMP DEFAULT NOW()`
- [x] 1.2 Create `ServiceConfigRepository.java` with methods: `findAll()`, `findByServiceId(String)`, `upsert(String serviceId, boolean enabled)`, `upsertAll(Map<String, Boolean>)`
- [ ] 1.3 Write unit tests for `ServiceConfigRepository`

## 2. Startup Config Merge

- [x] 2.1 Modify `LocalCloudConfig` to load persisted config from `ServiceConfigRepository` on init
- [x] 2.2 Implement precedence logic: `LOCALCLOUD_SERVICES` env > individual `LOCALCLOUD_ENABLE_*` env > persisted `service_config` > `services.yaml` defaultEnabled
- [x] 2.3 Track config source per service (`env`, `persisted`, `default`) in a `Map<String, String>` field on `LocalCloudConfig`
- [ ] 2.4 Write unit tests for config merge precedence (deferred — needs test infrastructure changes)

## 3. Admin API — Persist on Toggle

- [x] 3.1 Inject `ServiceConfigRepository` into `AdminApiService`
- [x] 3.2 Modify enable endpoint to call `serviceConfigRepository.upsert(serviceId, true)` after successful toggle
- [x] 3.3 Modify disable endpoint to call `serviceConfigRepository.upsert(serviceId, false)` after successful toggle
- [x] 3.4 Add `enabledSource` field to the `GET /_localcloud/services` response for each service

## 4. Admin API — Config Endpoint

- [x] 4.1 Add `GET /_localcloud/config/services` endpoint returning `{ serviceId: { enabled, source, locked } }` for all services
- [x] 4.2 Add `PUT /_localcloud/config/services` endpoint accepting `{ serviceId: boolean }` pairs — persists to DB and toggles services
- [ ] 4.3 Write unit tests for config endpoints (deferred — needs test infrastructure changes)

## 5. Console UI — Toggle Switches

- [x] 5.1 Add CSS toggle switch component styles to the console stylesheet
- [x] 5.2 Add toggle switch to each row in `Services.jsx` table, wired to `api.enableService()`/`api.disableService()`
- [x] 5.3 Add visual dimming (opacity 0.5) for disabled service rows
- [x] 5.4 Show "disabled" status badge (neutral color) instead of "unhealthy" for disabled services
- [x] 5.5 Add locked state — disable toggle + show lock icon when `source === "env"`
- [x] 5.6 Add optimistic toggle with error rollback — revert toggle and show error message on API failure
- [x] 5.7 Update Dashboard cards to show dimmed state for disabled services (already implemented)

## 6. Default Changes

- [x] 6.1 Change `defaultEnabled` to `false` for `spanner` and `bigquery` in `services.yaml`
- [x] 6.2 Verify existing `LOCALCLOUD_SERVICES` env var in `docker-compose.yml` still explicitly enables both (so current users are unaffected)

## 7. Build & Test

- [x] 7.1 Run Java server tests: `cd localcloud-server && ./gradlew test`
- [x] 7.2 Build console: `cd localcloud-console && npm run build`
- [x] 7.3 Build Docker image and verify all services start correctly
- [ ] 7.4 Manual test: toggle service off in UI → restart container → verify service stays off
- [ ] 7.5 Manual test: set `LOCALCLOUD_ENABLE_PUBSUB=true` env var → verify UI toggle is locked
