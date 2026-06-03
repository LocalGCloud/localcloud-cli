---
date: 2026-05-31
author: Winston (System Architect)
baseline_commit: b522aab565d5948d1707f824e7429b7d9c871e00
---

# Architecture Health: Implementation Plan

## Context

Architectural review of localcloud identified six areas for improvement. All are structural (no behavioral changes to emulators), focused on reducing technical debt, improving test coverage, and making the codebase easier to extend.

## Principles

- **Boring technology**: Flyway (battle-tested), existing patterns (Armeria annotated services, repository-per-service)
- **Developer productivity is architecture**: Every recommendation reduces friction for adding new emulators
- **Rule of Three**: The God object has been touched 28+ times — it's time to refactor

## Stories (Ordered by Priority)

| # | Story | Domain | Risk | Effort |
|---|-------|--------|------|--------|
| 1 | Extract ServiceRegistrar per emulator | Gateway/emulators | Low risk, high payoff | 4-6 hours |
| 2 | Split AdminApiService | Admin | Low risk, high payoff | 2-3 hours |
| 3 | Create RegexRouteHelper | Gateway/emulators | Low risk | 1-2 hours |
| 4 | Introduce Flyway migrations | Persistence | Medium risk | 3-4 hours |
| 5 | Add test coverage for untested emulators | Tests | Low risk, time-intensive | 6-8 hours |
| 6 | Persist Logging/Monitoring stubs | Emulators | Low risk | 2-3 hours |

### Execution Order Rationale

1. **Extract ServiceRegistrar first** — it establishes the pattern all other emulator changes follow. Do it before RegexRouteHelper so the helper can be used consistently.
2. **Split AdminApiService second** — independent of emulator changes, can be done in parallel.
3. **RegexRouteHelper third** — applies to emulators refactored in step 1.
4. **Flyway fourth** — requires careful migration of existing schema, best done with a clean test baseline.
5. **Tests fifth** — add once refactoring is stable.
6. **Persist stubs sixth** — lowest priority, completes Terraform compatibility.

## Key Architecture Decisions

### Decision 1: ServiceRegistrar pattern

Each facade emulator provides a `registerRoutes(ServerBuilder, GrpcServiceBuilder)` method. `LocalCloudApplication.start()` iterates over registered emulators and calls their registrar. This moves wiring knowledge from the God object into each emulator's package, where it belongs.

```java
// In emulator package:
public interface ServiceRegistrar {
    void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                        LocalCloudConfig config, PostgresDataSource ds);
}

// In LocalCloudApplication:
for (ServiceRegistrar emulator : emulators) {
    emulator.registerRoutes(sb, grpcBuilder, config, dataSource);
}
```

### Decision 2: AdminApiService split

Split into:
- `EnvService` — `/env`, `/oauth2/token`, `/oauth2/auth`, `/profiles`
- `DiagnosticsService` — `/diagnostics`, `/diagnostics/archive`, `/requests`, `/capabilities`, `/coverage`
- `ProjectsApiService` — `/projects` (CRUD)
- `ServicesConfigService` — `/routing`, `/config/services`, `/config/iam`, `/services/{id}/enable`, `/services/{id}/disable`, `/credentials`

All remain Armeria annotated services registered at root.

### Decision 3: Flyway

Add dependency `org.flywaydb:flyway-core`, add `flyway-core` and `flyway-database-postgresql` to build.gradle. Existing `SchemaManager` becomes a migration V1. Future schema changes are V2, V3, etc. Flyway's `migrate()` runs on startup before any service registration.

### Decision 4: RegexRouteHelper

Static utility: `RegexRouteHelper.registerVerbRoute(sb, method, pathPattern, handler)`. Generates the `Route.builder()...regex:^...$` boilerplate. Used by every emulator with `:verb` custom methods (Bigtable, VertexAI, KMS, Functions, Scheduler, ServiceUsage, CloudBilling).

### Decision 5: Test coverage target

Minimum 2 test files per emulator package. Tests focus on REST handler CRUD (create/get/list/delete), gRPC service method stubs (request validation, response shape), and repository SQL (upsert, find, delete).

### Decision 6: Stub persistence

Create `logging_sinks` and `monitoring_alert_policies` tables in PostgreSQL. Update LoggingEmulator and MonitoringEmulator to read/write from these tables. Existing hardcoded JSON becomes the default response when no persisted data exists (preserving backward compatibility).

## Risk Assessment

| Risk | Mitigation |
|------|-----------|
| Flyway migration fails on existing DB | Test against a copy of production schema before deploying |
| ServiceRegistrar refactoring breaks route matching | Full test suite (930+ tests) run after each story |
| RegexRouteHelper misses edge case regex patterns | Extract from existing working routes — don't invent new patterns |
| Story 5 (tests) exposes hidden bugs in untested emulators | This is a feature, not a bug — fix bugs as they surface |
