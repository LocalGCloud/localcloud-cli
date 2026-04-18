## Context

LocalCloud currently supports enabling/disabling services via:
- `LOCALCLOUD_ENABLE_*` environment variables (set before container start)
- `LOCALCLOUD_SERVICES` comma-separated list (overrides all enable flags)
- `POST /_localcloud/services/{id}/enable|disable` admin API (runtime, not persisted)

The console frontend (`Services.jsx`) displays service status but has no toggle controls. The API client already has `api.enableService()` and `api.disableService()` wired up but unused in UI. Runtime toggles are lost on container restart because `LocalCloudConfig.enabledServicesMap` is in-memory only.

**Existing infrastructure to build on:**
- `AdminApiService` (lines 415-481): enable/disable endpoints with supervisord integration
- `LocalCloudConfig.setServiceEnabled()`: runtime toggle via `ConcurrentHashMap`
- `ServiceRoutingRepository`: existing pattern for persisting per-service config to PostgreSQL
- `SchemaManager`: existing migration system for adding tables
- `api.js`: frontend API client with enable/disable methods already defined

## Goals / Non-Goals

**Goals:**
- One-click service toggle from the console UI
- Persist toggle state to PostgreSQL so it survives restarts
- Sensible defaults: lightweight services on, heavy emulators (Spanner, BigQuery) off by default
- Clear precedence: env var override > persisted state > services.yaml defaults

**Non-Goals:**
- Per-project service configuration (all services are global for now)
- Service dependency management (e.g., auto-disabling dependent services)
- Hot-reload of services.yaml at runtime
- Changing the `LOCALCLOUD_SERVICES` env var behavior

## Decisions

### D1: Persistence layer — new `service_config` table in PostgreSQL

**Choice:** Add a `service_config` table with columns `(service_id TEXT PRIMARY KEY, enabled BOOLEAN NOT NULL, updated_at TIMESTAMP DEFAULT NOW())`.

**Why not a config file?** PostgreSQL is already the persistence layer for routing, usage metrics, and secrets. Adding another table follows the established pattern and avoids filesystem permission issues in containers.

**Why not extend `service_routing`?** The routing table tracks local/remote routing mode per project. Service enable/disable is a separate concern (global, not per-project). Mixing them would complicate queries and semantics.

### D2: Startup precedence — env var > persisted > default

**Choice:** On startup, the config merge follows this order:
1. If `LOCALCLOUD_SERVICES` env var is set → use it exclusively (current behavior, unchanged)
2. Else for each service, check `LOCALCLOUD_ENABLE_<SERVICE>` env var → if explicitly set, use it
3. Else check `service_config` table → if row exists, use persisted value
4. Else fall back to `defaultEnabled` from services.yaml

**Why?** Env vars must win because Docker Compose users expect `environment:` block to be authoritative. Persisted state is the "user's last choice from UI". Defaults are the fallback.

### D3: Console UI — inline toggle switch on Services table row

**Choice:** Add a toggle switch (CSS-only, no library) in each service row of the Services page table. Clicking calls `POST /_localcloud/services/{id}/enable` or `disable`, which now also persists the state.

**Why not a separate config page?** The toggle belongs next to the service it controls. Adding a separate page adds navigation overhead for a simple on/off action.

### D4: Persist on toggle — update DB in the enable/disable endpoint

**Choice:** Modify the existing `POST /_localcloud/services/{id}/enable|disable` endpoints to also write to `service_config` after toggling. This keeps the API surface unchanged.

**Why not a separate config endpoint?** A `PUT /_localcloud/config/services` endpoint will also be added for bulk reads, but individual toggles should use the existing enable/disable endpoints since they already handle supervisord start/stop.

### D5: Default changes — heavy services default to disabled

**Choice:** Change `defaultEnabled` in services.yaml for Spanner and BigQuery to `false`. These are resource-heavy (Spanner: 326 MB binary + memory; BigQuery: Python venv + DuckDB) and many users don't need both.

**Why these two?** They're the heaviest emulators by resource usage. GCS, Pub/Sub, Firestore, and Bigtable are lightweight. Facade services (Secret Manager, Cloud Tasks, etc.) run in-process with negligible cost.

## Risks / Trade-offs

- **[Risk] Changing defaults breaks existing users** → Mitigated: env var override still works. Users who set `LOCALCLOUD_SERVICES=...spanner,bigquery...` are unaffected. Document the change in release notes.
- **[Risk] Toggle state diverges from env var** → Mitigated: env var always wins. UI shows a "locked" indicator when env var overrides persisted state.
- **[Risk] Toggling external service fails (supervisord error)** → Already handled: existing enable/disable endpoints return error responses. UI will show the error.
