## Why

Users running LocalCloud often have services already running on their host (e.g., a local Redis instance on port 6379) that conflict with built-in emulators. Currently, disabling a service requires setting environment variables (`LOCALCLOUD_ENABLE_*`) and restarting the container. There's no way to toggle services from the web console, and runtime toggles via the admin API are lost on restart because enable/disable state isn't persisted.

## What Changes

- Add toggle switches to the Services page in the web console so users can enable/disable individual services with one click
- Persist service enable/disable state to PostgreSQL so toggles survive container restarts
- Load persisted state on startup, merging it with environment variable defaults (env vars take precedence for explicit overrides, persisted state fills in the rest)
- Set sensible defaults: lightweight facade services default to enabled, heavy external emulators (Spanner, BigQuery) default to disabled unless explicitly enabled
- Add a new admin API endpoint to read/write persisted service configuration

## Capabilities

### New Capabilities
- `service-toggle-persistence`: Persist service enable/disable state to PostgreSQL and restore on startup. Includes a new `service_config` table, a config API endpoint (`GET/PUT /_localcloud/config/services`), and startup merge logic (env var > persisted state > services.yaml defaults).
- `service-toggle-console-ui`: Toggle switches in the console Services page to enable/disable services at runtime. Visual feedback (dimmed cards, status badges), confirmation for disabling active services, and sync with the persisted config.

### Modified Capabilities
- `service-enable-disable`: Extend the existing enable/disable flow to write state to the persistence layer after toggling, so the next restart remembers the user's choices.

## Impact

- **Backend (Java)**: New `ServiceConfigRepository` class for PostgreSQL persistence, schema migration to add `service_config` table, modified `AdminApiService` to persist toggle state, modified startup flow in `LocalCloudConfig` to load persisted config.
- **Frontend (Solid.js)**: Modified `Services.jsx` to add toggle switches per service row, new API calls for config endpoint.
- **Admin API**: New `GET/PUT /_localcloud/config/services` endpoint for bulk config read/write.
- **Database**: New `service_config` table (`service_id TEXT PK, enabled BOOLEAN, updated_at TIMESTAMP`).
- **Defaults**: services.yaml `defaultEnabled` field already exists — heavy services (spanner, bigquery) can be changed to `false` default.
