---
baseline_commit: b522aab565d5948d1707f824e7429b7d9c871e00
epic: architecture-health
story_key: arch-2-split-admin-api
---

# Story: arch-2-split-admin-api

## Story

**As a** developer working on the admin API,
**I want** AdminApiService split into focused, single-responsibility service classes,
**So that** I can understand, test, and modify each domain independently without navigating a 700-line file.

## Acceptance Criteria

1. **AC1**: `AdminApiService` is replaced by four Armeria annotated services: `EnvService`, `DiagnosticsService`, `ProjectsApiService`, `ServicesConfigService`
2. **AC2**: Each new service has its own file in `com.localcloud.admin` package
3. **AC3**: All existing endpoints continue to work at the same paths with identical behavior
4. **AC4**: `LocalCloudApplication.start()` registers each new service with `sb.annotatedService("/", ...)` 
5. **AC5**: Common dependencies (`ObjectMapper`, `LocalCloudConfig`, `errorResponse()` helper) are extracted to a shared utility class: `AdminApiSupport`
6. **AC6**: All existing admin API tests pass
7. **AC7**: `GET /env` with `format=terraform` continues to produce the same output format

## Tasks/Subtasks

### Task 1: Create AdminApiSupport utility
- [ ] Create `com.localcloud.admin.AdminApiSupport` with:
  - Shared `ObjectMapper` instance (Jackson with JavaTimeModule)
  - Static `errorResponse(Exception e)` method
  - Constants: `DEFAULT_REQUEST_LIMIT`, `MAX_REQUEST_LIMIT`, `UPGRADE_URL`
- [ ] Move `SUPERVISOR_PROGRAM_NAMES` map to this class

### Task 2: Create EnvService
- [ ] Extract endpoints: `GET /env`, `POST /oauth2/token`, `GET /oauth2/auth`, `GET /profiles`
- [ ] This service handles environment variable generation and OAuth2 token stubs
- [ ] Constructor: `(LocalCloudConfig config)`

### Task 3: Create DiagnosticsService
- [ ] Extract endpoints: `GET /capabilities`, `GET /coverage`, `GET /coverage/{service}`, `GET /diagnostics`, `GET /diagnostics/archive`, `GET /requests`
- [ ] This service handles diagnostics, coverage, capabilities, and request logging
- [ ] Constructor: `(LocalCloudConfig config, RequestLogger requestLogger, FaultInjectionRegistry faultInjectionRegistry)`

### Task 4: Create ProjectsApiService
- [ ] Extract endpoints: `GET /projects`, `POST /projects`, `DELETE /projects/{id}`
- [ ] This service handles project CRUD
- [ ] Constructor: `(LocalCloudConfig config, ProjectService projectService)`

### Task 5: Create ServicesConfigService
- [ ] Extract endpoints: `GET /routing`, `PUT /routing/{service}`, `GET /credentials`, `POST /services/{id}/enable`, `POST /services/{id}/disable`, `GET /config/services`, `PUT /config/services`, `PUT /config/iam`
- [ ] This service handles service enable/disable, routing, credentials, and runtime config
- [ ] Constructor: `(LocalCloudConfig config, ProjectService projectService, ServiceRoutingRepository routingRepository, CredentialBroker credentialBroker, ServiceConfigRepository serviceConfigRepository, LicenseTierProvider tierProvider, FaultInjectionRegistry faultInjectionRegistry)`

### Task 6: Update LocalCloudApplication
- [ ] Remove `adminApiService` field and construction
- [ ] Construct and register `EnvService`, `DiagnosticsService`, `ProjectsApiService`, `ServicesConfigService` individually
- [ ] Update any references to `adminApiService` (scan for usages)

### Task 7: Update tests
- [ ] Update `AdminApiServiceTest` to test individual services (or split into per-service test files)
- [ ] Verify all test assertions still pass

### Task 8: Verify
- [ ] Run `./gradlew build` — all tests pass
- [ ] Start server: `GET /env?format=terraform` returns valid output
- [ ] `POST /projects` creates a project
- [ ] `POST /services/gcs/enable` enables GCS
- [ ] `GET /diagnostics` returns valid JSON

## Dev Notes

### Architecture context
- `AdminApiService.java` is ~700 lines handling 4 distinct domains
- All endpoints are registered at root (`/`) via `sb.annotatedService("/", adminApiService)`
- The split is mechanical extraction — no behavioral changes, no new tests needed beyond what exists

### Key design decisions
- **No inheritance hierarchy**: Each service is a standalone annotated service class. No base class.
- **Shared AdminApiSupport**: Static utility for Jackson mapper and error responses to avoid duplication
- **Same registration pattern**: Each service is registered with `sb.annotatedService("/", new XxxService(...))` in `start()`
- **No DI framework**: Constructor injection, manual wiring — consistent with the rest of localcloud

### Endpoint mapping

| Old (AdminApiService) | New Service | Endpoints |
|----------------------|-------------|-----------|
| env, oauth2, profiles | EnvService | `/env`, `/oauth2/token`, `/oauth2/auth`, `/profiles` |
| capabilities, coverage, diagnostics, requests | DiagnosticsService | `/capabilities`, `/coverage`, `/coverage/{service}`, `/diagnostics`, `/diagnostics/archive`, `/requests` |
| projects CRUD | ProjectsApiService | `/projects`, `/projects` (POST), `/projects/{id}` (DELETE) |
| routing, services enable/disable, config, credentials, IAM config | ServicesConfigService | `/routing`, `/routing/{service}`, `/credentials`, `/services/{id}/enable`, `/services/{id}/disable`, `/config/services`, `/config/iam` |

### Files that will change
- **New**: `AdminApiSupport.java` (~50 lines)
- **New**: `EnvService.java` (~200 lines)
- **New**: `DiagnosticsService.java` (~250 lines)
- **New**: `ProjectsApiService.java` (~120 lines)
- **New**: `ServicesConfigService.java` (~400 lines)
- **Modified**: `AdminApiService.java` → deleted or deprecated
- **Modified**: `LocalCloudApplication.java` → update references
- **Modified**: Test files (if they reference AdminApiService directly)
