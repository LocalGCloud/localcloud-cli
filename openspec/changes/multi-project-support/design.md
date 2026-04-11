## Context

LocalCloud runs 14 GCP services in a single container with one project ID (`LOCALCLOUD_PROJECT`). Several facade emulators (Secret Manager, Cloud Tasks, Compute, Cloud Run, GKE) already store `project_id` in their database schema and scope queries by project. External emulators (GCS, Pub/Sub, Firestore, Spanner, BigQuery) natively support project namespacing in their APIs. The gaps are: Logging (has column, unused), Monitoring (no column), Memorystore (no column), Bigtable (no column), and the admin/browse API layer which hardcodes a single project.

## Goals / Non-Goals

**Goals:**
- Multiple GCP projects in a single LocalCloud instance with isolated data
- Project CRUD via Admin API (`/_localcloud/projects`)
- Console project switcher in the topbar
- Backward-compatible — single-project users see no behavior change
- Seed files support multi-project format

**Non-Goals:**
- Cross-project IAM policies (future)
- Cross-project resource sharing (Pub/Sub cross-project subscriptions)
- Project quotas or billing separation
- Organizational hierarchy (folders, orgs)

## Decisions

### 1. Project registry in PostgreSQL

**Decision**: Add a `projects` table to PostgreSQL. The default project from `LOCALCLOUD_PROJECT` is auto-created on startup.

**Why**: Projects need persistence, metadata (display name, created_at), and CRUD. PostgreSQL is already the persistence layer for facade emulators.

**Table**: `projects (project_id TEXT PRIMARY KEY, display_name TEXT, created_at TIMESTAMP DEFAULT NOW())`

**Alternative considered**: Config-file-based project list — rejected because it doesn't support runtime creation.

### 2. Facade emulators — add project_id where missing

**Decision**: Add `project_id TEXT NOT NULL DEFAULT 'local-project'` to tables that lack it:
- `log_entries` — already has column, make it part of query filters
- `time_series`, `metric_points` — add `project_id` column
- `redis_data` — add `project_id` column
- `bigtable_data` — add `project_id` column

**Why**: Consistent project scoping across all services. The DEFAULT clause ensures backward compatibility — existing data maps to the default project.

**Alternative considered**: Separate databases per project — rejected due to connection pool complexity and PostgreSQL resource overhead.

### 3. External emulators — project scoping via API paths (no config change)

**Decision**: External emulators (GCS, Pub/Sub, Firestore, Spanner, BigQuery) already handle project scoping natively via their API paths (`/v1/projects/{project}/...`). No emulator configuration changes needed. The `--project=` flag in supervisord only sets the default for gcloud CLI commands, not the emulator's API.

**Why**: These emulators are designed for multi-project use. Client SDKs pass the project ID in every request.

### 4. Admin API — project-aware browse endpoints

**Decision**: All `/_localcloud/browse/{service}` endpoints accept an optional `?project=` query parameter. Default: the configured `LOCALCLOUD_PROJECT`. New endpoints: `GET/POST/DELETE /_localcloud/projects`.

**Why**: Minimal API change — existing clients without `?project=` get the same behavior.

### 5. Console — project dropdown in topbar

**Decision**: Add a project selector dropdown in the topbar (where the project chip currently is). Selecting a project sets it as the active project for all console views. Store the active project in localStorage.

**Why**: Matches GCP Console UX. Minimal UI change — the project chip already exists, just make it interactive.

## Risks / Trade-offs

- **Data migration**: Adding `project_id` columns with `DEFAULT 'local-project'` means existing data is assigned to the default project. If users change `LOCALCLOUD_PROJECT`, old data stays under the old project name. **Mitigation**: Document this behavior; provide a migration endpoint if needed.
- **External emulator data isolation**: GCS uses filesystem storage (`/var/lib/localcloud/gcs-data`). Multiple projects share the same filesystem root. **Mitigation**: GCS emulator already namespaces by project internally.
- **Seed format breaking change**: Multi-project seed format wraps services under a project key. **Mitigation**: Detect format automatically — if `projects:` key exists, use multi-project; otherwise wrap in default project.
- **BigQuery emulator**: Started with `--project=X` flag which may limit it to one project. **Mitigation**: Test if the emulator accepts requests for other projects despite the flag. If not, this is a known limitation documented in the guide.
