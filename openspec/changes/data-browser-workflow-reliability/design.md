## Context

All 10 database services pass end-to-end tests (114/114). The Data Browser has CRUD for most services. The gaps are around operational polish: per-service health visibility, selective reset, state export, and request body capture.

## Goals / Non-Goals

**Goals:**
- Every Data Browser tab shows real-time health of its service
- Developers can reset individual services without affecting others
- Current state can be exported as shareable seed YAML
- Health endpoint gives per-emulator status

**Non-Goals:**
- Full request body inspection (deferred to developer-experience-console change)
- Query consoles (deferred)
- Fault injection (deferred)

## Decisions

### D1: Per-service health via existing HealthCheckService

The `HealthCheckService` already checks emulators via `ProcessHealthChecker`. Extend the health response to include per-service status. Each service's health check type is defined in `services.yaml` (tcp, http, or none for facades).

### D2: Per-service reset via dedicated endpoint

Add `POST /_localcloud/reset/{service}` that clears only one service's data. For PostgreSQL-backed services (Secret Manager, Cloud Tasks, Memorystore, Bigtable), truncate the relevant tables. For external emulators (GCS, Pub/Sub, BigQuery, Firestore), call their reset/delete APIs if available, or document that full reset requires container restart.

### D3: State export by querying each service's browse endpoint

Export collects data from each service's browse endpoint and assembles it into seed YAML format. This is the inverse of seed loading — read instead of write. For services where export is complex (Spanner DDL + row data), use existing browse endpoints that already return the needed structure.

### D4: Health indicators in Data Browser

Add a small colored dot (green/red/gray) to each tab in the Data Browser. Fetch health status from `/_localcloud/health` (which now includes per-service breakdown) on page load and every 30 seconds.
