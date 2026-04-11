## 1. Database Schema — Projects Table & Column Additions

- [x] 1.1 Add `projects` table to SchemaManager.java (`project_id TEXT PK, display_name TEXT, created_at TIMESTAMP`)
- [x] 1.2 Auto-insert default project from `LOCALCLOUD_PROJECT` on schema init
- [x] 1.3 Add `project_id TEXT NOT NULL DEFAULT 'local-project'` to `time_series` and `metric_points` tables
- [x] 1.4 Add `project_id TEXT NOT NULL DEFAULT 'local-project'` to `redis_data` table
- [x] 1.5 Add `project_id TEXT NOT NULL DEFAULT 'local-project'` to `bigtable_data` table
- [x] 1.6 Ensure `log_entries.project_id` is populated and used in queries

## 2. Project CRUD — Admin API

- [x] 2.1 Create `ProjectService.java` with CRUD methods (list, create, delete, exists)
- [x] 2.2 Add `GET /_localcloud/projects` endpoint to AdminApiService
- [x] 2.3 Add `POST /_localcloud/projects` endpoint (create project)
- [x] 2.4 Add `DELETE /_localcloud/projects/{id}` endpoint (delete project + cascade data)
- [x] 2.5 Block deletion of the default project (return 400)
- [x] 2.6 Add unit tests for ProjectService CRUD operations

## 3. Facade Emulators — Project Scoping Fixes

- [x] 3.1 LoggingEmulator: populate `project_id` on write, filter by project on list
- [x] 3.2 MonitoringEmulator: use new `project_id` column, filter by project on list
- [x] 3.3 MemorystoreEmulator: scope all Redis commands by project_id
- [x] 3.4 BigtableEmulator (if facade): scope bigtable_data queries by project_id
- [x] 3.5 Add unit tests for project-scoped logging and monitoring queries

## 4. Browse API — Project Query Parameter

- [x] 4.1 Extract `?project=` query param in BrowseService, default to config project
- [x] 4.2 Pass project_id to all facade browse queries (secrets, tasks, logging, monitoring, memorystore, bigtable)
- [x] 4.3 Pass project_id in external emulator browse URLs (GCS, Pub/Sub, Firestore, Spanner, BigQuery)
- [x] 4.4 Update `/_localcloud/env` to accept `?project=` and return scoped vars
- [x] 4.5 Update `/_localcloud/reset` to accept `?project=` for single-project reset

## 5. Seed System — Multi-Project Format

- [x] 5.1 Update SeedService to detect `projects:` key in YAML for multi-project format
- [x] 5.2 Implement per-project seeding loop (iterate projects, seed each project's services)
- [x] 5.3 Maintain backward compatibility with single-project `services:` format
- [x] 5.4 Add multi-project seed example to seed.yaml
- [x] 5.5 Add unit tests for multi-project seed parsing

## 6. Console — Project Switcher

- [x] 6.1 Add `GET /api/projects` and `POST /api/projects` to Flask proxy
- [x] 6.2 Replace static project chip in topbar with interactive dropdown (app.jsx)
- [x] 6.3 Store active project in localStorage, restore on page load
- [x] 6.4 Pass active project as `?project=` on all API calls (api.js)
- [x] 6.5 Add "New Project" button in dropdown with create dialog
- [x] 6.6 Update Dashboard, DataBrowser, Services, Logs, Usage to use active project context

## 7. Documentation & Testing

- [x] 7.1 Update DEVELOPER_GUIDE.md with multi-project usage section
- [x] 7.2 Update CLAUDE.md if project structure or commands change
- [x] 7.3 End-to-end test: create 2 projects, seed different data, verify isolation via browse API
