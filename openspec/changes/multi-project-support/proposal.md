## Why

LocalCloud currently supports a single GCP project (`LOCALCLOUD_PROJECT`). Real GCP environments use multiple projects for isolation (dev/staging/prod, per-team, per-service). Developers building multi-project workflows — cross-project Pub/Sub, shared secrets, project-scoped IAM — cannot test locally without running separate containers. Multi-project support lets a single LocalCloud instance host multiple projects with isolated data, matching how GCP actually works.

## What Changes

- All services namespace data by project ID (storage, secrets, tasks, logs, metrics, etc.)
- Admin API gains project CRUD endpoints (`/_localcloud/projects`)
- Console adds a project switcher dropdown in the topbar (like GCP Console)
- Console Data Browser and Dashboard scope data to the active project
- Seed files support multi-project format with per-project service data
- Env var export scopes to the selected project
- **BREAKING**: `LOCALCLOUD_PROJECT` becomes the *default* project, not the only one

## Capabilities

### New Capabilities
- `project-management`: CRUD for projects (create, list, switch, delete), project-scoped data isolation across all 14 services, Admin API endpoints, CLI commands
- `console-project-switcher`: Topbar project dropdown, project-scoped views in Dashboard/DataBrowser/Usage, project creation dialog

### Modified Capabilities

## Impact

- **Java server**: Gateway, all facade emulators, persistence layer (SchemaManager) — project ID column added to all tables
- **External emulators**: GCS, Pub/Sub, Firestore, Bigtable, Spanner, BigQuery already support project scoping natively via their APIs
- **Admin API**: New `/projects` endpoints, existing endpoints accept `?project=` query param
- **Console**: Topbar, Dashboard, DataBrowser, Usage pages need project-aware data fetching
- **CLI**: `localcloud project create/list/switch/delete` commands
- **Seed format**: New multi-project YAML structure (backward-compatible with single-project)
- **PostgreSQL**: All facade tables (secrets, tasks, logs, metrics, etc.) need `project_id` column
