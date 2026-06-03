---
baseline_commit: b522aab565d5948d1707f824e7429b7d9c871e00
epic: architecture-health
story_key: arch-6-persist-stubs
---

# Story: arch-6-persist-stubs

## Story

**As a** developer using Terraform with localcloud,
**I want** logging sinks and monitoring alert policies to persist across restarts,
**So that** `terraform plan` doesn't show false drift when no actual changes were made.

## Acceptance Criteria

1. **AC1**: Logging sink CRUD endpoints (`POST/GET/DELETE /v2/projects/{project}/sinks`) read and write from a `logging_sinks` PostgreSQL table
2. **AC2**: Monitoring alert policy CRUD endpoints (`POST/GET/DELETE /v3/projects/{project}/alertPolicies`) read and write from a `monitoring_alert_policies` PostgreSQL table
3. **AC3**: Created sinks survive server restart — `GET` returns the same sink
4. **AC4**: Created alert policies survive server restart — `GET` returns the same policy
5. **AC5**: Deleted sinks and policies return 404 on subsequent GET requests
6. **AC6**: Terraform `google_logging_project_sink` resource: `terraform apply` followed by `terraform plan` shows no changes
7. **AC7**: Terraform `google_monitoring_alert_policy` resource: `terraform apply` followed by `terraform plan` shows no changes
8. **AC8**: Existing hardcoded JSON responses are removed — all responses come from the database

## Tasks/Subtasks

### Task 1: Create logging_sinks table
- [ ] Add `CREATE TABLE IF NOT EXISTS logging_sinks` to SchemaManager (or Flyway migration V{N+1})
- [ ] Columns: `id BIGSERIAL PRIMARY KEY`, `project_id VARCHAR(255) NOT NULL`, `sink_id VARCHAR(255) NOT NULL`, `destination VARCHAR(1024) NOT NULL DEFAULT 'bigquery.googleapis.com'`, `filter TEXT`, `writer_identity VARCHAR(512)`, `created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`
- [ ] Unique constraint: `UNIQUE (project_id, sink_id)`

### Task 2: Create monitoring_alert_policies table
- [ ] Add `CREATE TABLE IF NOT EXISTS monitoring_alert_policies` to SchemaManager (or Flyway migration V{N+2})
- [ ] Columns: `id BIGSERIAL PRIMARY KEY`, `name VARCHAR(512) NOT NULL` (full resource name), `display_name VARCHAR(255) NOT NULL`, `project_id VARCHAR(255) NOT NULL`, `policy_id VARCHAR(255) NOT NULL`, `enabled BOOLEAN DEFAULT TRUE`, `conditions_json TEXT`, `created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`
- [ ] Unique constraint: `UNIQUE (project_id, policy_id)`

### Task 3: Create LoggingSinkRepository
- [ ] Create `LoggingSinkRepository(PostgresDataSource)` in `com.localcloud.emulators.logging`
- [ ] Methods: `create(String projectId, String destination)` → returns full sink JSON, `find(String projectId, String sinkId)` → returns JSON or null, `delete(String projectId, String sinkId)` → boolean, `list(String projectId)` → returns list
- [ ] Generate `sink_id` as a short UUID (8 chars, same pattern as current hardcoded response)

### Task 4: Create MonitoringAlertPolicyRepository
- [ ] Create `MonitoringAlertPolicyRepository(PostgresDataSource)` in `com.localcloud.emulators.monitoring`
- [ ] Methods: `create(String projectId, String displayName)` → returns full policy JSON, `find(String projectId, String policyId)` → returns JSON or null, `delete(String projectId, String policyId)` → boolean, `list(String projectId)` → returns list
- [ ] Generate `policy_id` as a short UUID (8 chars)

### Task 5: Update LoggingEmulator routes
- [ ] Inject `LoggingSinkRepository` into `LoggingEmulator`
- [ ] Replace hardcoded JSON in `POST /v2/projects/{project}/sinks` with repository.create()
- [ ] Replace hardcoded JSON in `GET /v2/projects/{project}/sinks/{sink}` with repository.find() — return 404 if not found
- [ ] Replace hardcoded `{}` response in `DELETE /v2/projects/{project}/sinks/{sink}` with repository.delete() — return 404 if not found
- [ ] Keep response format identical (same JSON structure) to maintain Terraform compatibility

### Task 6: Update MonitoringEmulator routes
- [ ] Inject `MonitoringAlertPolicyRepository` into `MonitoringEmulator`
- [ ] Replace hardcoded JSON in `POST /v3/projects/{project}/alertPolicies` with repository.create() — also parse request body for `displayName`
- [ ] Replace hardcoded JSON in `GET /v3/projects/{project}/alertPolicies/{policy}` with repository.find() — return 404 if not found
- [ ] Replace hardcoded `{}` response in `DELETE /v3/projects/{project}/alertPolicies/{policy}` with repository.delete() — return 404 if not found

### Task 7: Update LocalCloudApplication wiring
- [ ] Construct `LoggingSinkRepository` and `MonitoringAlertPolicyRepository` with `PostgresDataSource`
- [ ] Pass repositories to `LoggingEmulator` and `MonitoringEmulator` constructors
- [ ] Update `SeedService` if seeds include logging/monitoring data

### Task 8: Add tests
- [ ] `LoggingSinkRepositoryTest` — CRUD operations
- [ ] `MonitoringAlertPolicyRepositoryTest` — CRUD operations
- [ ] Update `LoggingEmulatorTest` — verify persistence across restart simulation
- [ ] Update `MonitoringEmulatorTest` — verify persistence

### Task 9: Verify
- [ ] Run `./gradlew build` — all tests pass
- [ ] Start server, create a sink: `POST /v2/projects/test/sinks`
- [ ] GET the sink by ID — verify returns correct data
- [ ] Restart server, GET the sink — verify it still exists
- [ ] Delete the sink, GET again — verify 404
- [ ] Repeat for alert policies
- [ ] Terraform smoke test: `terraform apply` resources → `terraform plan` shows no changes

## Dev Notes

### Architecture context
- Logging and Monitoring emulators currently return hardcoded JSON for sink and alert policy CRUD
- These are "Terraform compatibility" stubs — Terraform's `google_logging_project_sink` and `google_monitoring_alert_policy` resources hit these endpoints
- The stubs work for `terraform apply` but fail on `terraform plan` (state drift because data doesn't persist)

### Key design decisions
- **Minimal schema**: Store only what Terraform reads back. No need to fully emulate GCP's sink/policy models.
- **Same response format**: The JSON structure must match what the current hardcoded responses return — Terraform expectations are baked in
- **Short UUIDs**: Same 8-char UUID pattern as current stubs — keeps response format identical
- **GET returns 404 for deleted/missing**: Consistent with REST semantics and Terraform's resource detection

### Response format (must preserve)
```json
// Logging sink response (unchanged structure, now from DB)
{
  "name": "projects/{project}/sinks/{sink_id}",
  "destination": "bigquery.googleapis.com",
  "writerIdentity": "serviceAccount:cloud-logs@localcloud.iam.gserviceaccount.com"
}

// Monitoring alert policy response (unchanged structure, now from DB)
{
  "name": "projects/{project}/alertPolicies/{policy_id}",
  "displayName": "{from_request_body_or_default}",
  "enabled": true
}
```

### Files that will change
- **New**: `logging_sinks` table (in SchemaManager or Flyway migration)
- **New**: `monitoring_alert_policies` table
- **New**: `LoggingSinkRepository.java` (~80 lines)
- **New**: `MonitoringAlertPolicyRepository.java` (~80 lines)
- **Modified**: `LoggingEmulator.java` — replace hardcoded JSON with repository calls
- **Modified**: `MonitoringEmulator.java` — replace hardcoded JSON with repository calls
- **Modified**: `LocalCloudApplication.java` — wire repositories into emulators
- **New**: `LoggingSinkRepositoryTest.java`
- **New**: `MonitoringAlertPolicyRepositoryTest.java`
