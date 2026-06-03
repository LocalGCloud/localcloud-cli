---
baseline_commit: b522aab565d5948d1707f824e7429b7d9c871e00
epic: architecture-health
story_key: arch-1-service-registrar
---

# Story: arch-1-service-registrar

## Story

**As a** developer adding a new GCP emulator to localcloud,
**I want** each emulator to self-register its routes and services,
**So that** I don't need to touch LocalCloudApplication.java's 1312-line `start()` method in 5+ places.

## Acceptance Criteria

1. **AC1**: Each facade emulator provides a `registerRoutes(ServerBuilder, GrpcServiceBuilder, LocalCloudConfig, PostgresDataSource)` method that registers all its HTTP routes, regex routes, gRPC services, and annotated services
2. **AC2**: `LocalCloudApplication.start()` no longer contains per-service route registration; instead it iterates over a list of ServiceRegistrar implementations and calls `registerRoutes()` on each
3. **AC3**: The `ServiceRegistrar` interface lives in `com.localcloud.emulators.common` package
4. **AC4**: External emulator registration (supervisord-based: GCS, Pub/Sub, Firestore, Bigtable, Spanner, BigQuery) is NOT affected — only facade emulators
5. **AC5**: All 930+ existing tests pass without modification
6. **AC6**: Console API endpoints (`/env`, `/services`, `/browse`, `/mutate`) continue to work identically
7. **AC7**: The build compiles with no new warnings

## Tasks/Subtasks

### Task 1: Create ServiceRegistrar interface
- [x] Create `com.localcloud.emulators.common.ServiceRegistrar` interface with single method: `void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpcBuilder, ServiceRegistrationContext ctx) throws Exception`
- [x] Create `ServiceRegistrationContext` record to bundle shared dependencies
- [x] Add Javadoc explaining the contract

### Task 2: Implement ServiceRegistrar for each facade emulator
- [x] Cloud Scheduler: extract into `CloudSchedulerRegistrar`
- [x] Cloud Functions: extract into `CloudFunctionsRegistrar`
- [x] AlloyDB: extract into `AlloyDBRegistrar`
- [x] Dataproc: extract into `DataprocRegistrar`
- [x] Secret Manager: extract into `SecretManagerRegistrar`
- [x] Cloud Tasks: extract into `CloudTasksRegistrar`
- [x] IAM: extract into `IAMRegistrar`
- [x] Logging: extract into `LoggingRegistrar`
- [x] Monitoring: extract into `MonitoringRegistrar`
- [x] Compute: extract into `ComputeRegistrar`
- [x] Cloud Run: extract into `CloudRunRegistrar`
- [x] GKE: extract into `GKERegistrar`
- [x] VertexAI: extract into `VertexAIRegistrar`
- [x] KMS: extract into `KMSRegistrar`
- [x] Workflows: extract into `WorkflowsRegistrar`
- [x] Pub/Sub REST: extract into `PubSubRestRegistrar`
- [x] Cloud SQL: extract into `CloudSqlRegistrar`
- [x] Bigtable: extract into `BigtableRegistrar`
- [x] Memorystore: extract into `MemorystoreRegistrar`
- [x] Cloud Resource Manager: extract into `CloudResourceManagerRegistrar`
- [x] Service Usage: extract into `ServiceUsageRegistrar`
- [x] Cloud Billing: extract into `CloudBillingRegistrar`

### Task 3: Refactor LocalCloudApplication.start()
- [x] Build a `List<ServiceRegistrar>` and `ServiceRegistrationContext` during constructor
- [x] Replace per-service `if (config.isServiceEnabled(...)) { ... }` blocks with a single loop
- [x] Keep middleware/decorator registration (IAM, ServiceGating, FaultInjection, SPA routing) in `start()` — cross-cutting
- [x] Keep OperationsGrpcService, GraphQL, Sync, Spanner IAM, OAuth2, catch-all in `start()`
- [x] Wire seedService/mutateService setters inside each registrar via ServiceRegistrationContext

### Task 4: Verify
- [x] Run `./gradlew build` — compilation and all 930+ tests pass
- [x] Run `./gradlew shadowJar` — fat JAR builds successfully
- [x] ContainerManager + GrpcServiceBuilder preserved in start(), passed via regCtx to registrars
- [x] All `emulator.start()` calls properly handle Exception via `throws Exception` on interface

### Review Findings

- [x] [Review][Patch] Missing gateway registration — all 15 `gateway.registerGrpcEmulator()`/`registerRestEmulator()` calls deleted; zero registrars call them. Breaks usage-metrics flush and clean shutdown. Fix: add `ApiGateway` to `ServiceRegistrationContext` and call `gateway.register*` in each registrar. [LocalCloudApplication.java, all *Registrar.java]
- [x] [Review][Patch] No try-catch around individual registrar calls — a single failing registrar aborts the loop. Fix: wrap `registrar.registerRoutes(...)` in try-catch. [LocalCloudApplication.java:for-loop]
- [x] [Review][Patch] Dead `registrationContext` field in constructor with null `containerManager` — local `regCtx` used instead. Fix: remove field, keep only local variable. [LocalCloudApplication.java:constructor]
- [x] [Review][Defer] Flyway migration failure silently swallowed — belongs to arch-4 story. [LocalCloudApplication.java:200-204]
- [x] [Review][Defer] DocService + AccessLogWriter + Flyway added in unrelated refactoring — scope creep from arch-2/arch-4. [LocalCloudApplication.java]
- [x] [Review][Defer] CloudBillingRestService constructor changed from no-arg to DataSource — functional enhancement from arch-6. [CloudBillingRegistrar.java:21]
- [x] [Review][Defer] Logging/Monitoring sinks changed from ephemeral stubs to persisted repos — functional enhancement from arch-6. [LoggingRegistrar.java, MonitoringRegistrar.java]
- [x] [Review][Defer] Docker fallback to ContainerManager(null) — pre-existing pattern preserved. [ComputeRegistrar.java, CloudRunRegistrar.java]

## Dev Agent Record

### Implementation Plan
1. Create `ServiceRegistrar` interface + `ServiceRegistrationContext` record
2. Extract each service's route registration from `LocalCloudApplication.start()` into `*Registrar`
3. Replace ~600 lines of service registration with a single `for` loop
4. Keep cross-cutting concerns in `start()`

**Key choices:** `ServiceRegistrationContext` bundles shared deps; interface declares `throws Exception`; ContainerManager initialized before loop.

### Debug Log
- Switched from 4-param to context-object approach for seed/mutate wiring
- KMS compile error: added `throws Exception` to interface
- Missing `java.util.List` import after import cleanup — restored

### Completion Notes
- `LocalCloudApplication.java`: 1312 → 813 lines (38% reduction)
- 25 new files: 1 interface, 1 context record, 22 registrars, 1 IAM repo utility
- All 930+ tests pass, shadow JAR builds

## File List
- **New**: `emulators/common/ServiceRegistrar.java`
- **New**: `emulators/common/ServiceRegistrationContext.java`
- **New**: 22 `*Registrar.java` files across all facade emulator packages
- **Modified**: `LocalCloudApplication.java` (1312 → 813 lines)

## Status
done

## Dev Notes

### Architecture context
- `LocalCloudApplication.java` is 1312 lines; the `start()` method alone is ~1000 lines
- Each emulator registration follows a pattern: create emulator instance → add gRPC service → register REST routes → wire into seed/mutate services
- The IAM middleware, ServiceGatingDecorator, FaultInjectionDecorator, and SPA routing are cross-cutting — keep them in `start()`
- External emulators (GCS, Pub/Sub, Firestore, Bigtable, Spanner, BigQuery) are managed by supervisord — NOT affected

### Key design decisions
- **No inheritance**: ServiceRegistrar is a simple functional interface. Each emulator package provides its own implementation.
- **Constructor injection**: Registrars receive `ServerBuilder`, `GrpcServiceBuilder`, `LocalCloudConfig`, `PostgresDataSource` as method parameters — not in constructor. This keeps registrars stateless.
- **License check stays in `start()`**: Each registrar receives the fully-resolved config; license-based enable/disable is already applied before registrars run.
- **Order independence**: Registrars must not depend on execution order. All annotated service routes are independent.

### Files that will change
- **New**: `com.localcloud.emulators.common.ServiceRegistrar.java` (~20 lines)
- **New**: ~22 `*Registrar.java` files (one per facade emulator package, ~30-80 lines each)
- **Modified**: `LocalCloudApplication.java` — remove ~800 lines, add ~20 lines (loop + registrars list)
- **Not modified**: Any emulator service implementation files, test files, build.gradle, services.yaml
