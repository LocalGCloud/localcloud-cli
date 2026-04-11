## ADDED Requirements

### Requirement: Project CRUD via Admin API
The system SHALL expose project management endpoints at `/_localcloud/projects`. Projects are stored in PostgreSQL with a `projects` table. The default project from `LOCALCLOUD_PROJECT` SHALL be auto-created on startup.

#### Scenario: List projects
- **WHEN** a GET request is sent to `/_localcloud/projects`
- **THEN** the system returns a JSON array of all projects with `project_id`, `display_name`, and `created_at`

#### Scenario: Create project
- **WHEN** a POST request is sent to `/_localcloud/projects` with `{"project_id": "staging", "display_name": "Staging"}`
- **THEN** the system creates the project and returns 201 with the project object

#### Scenario: Create duplicate project
- **WHEN** a POST request is sent to `/_localcloud/projects` with a `project_id` that already exists
- **THEN** the system returns 409 Conflict

#### Scenario: Delete project
- **WHEN** a DELETE request is sent to `/_localcloud/projects/staging`
- **THEN** the system deletes the project and all associated data across all services, returning 200

#### Scenario: Delete default project is rejected
- **WHEN** a DELETE request is sent to `/_localcloud/projects/{default_project}` where `{default_project}` is the configured `LOCALCLOUD_PROJECT`
- **THEN** the system returns 400 with an error message indicating the default project cannot be deleted

### Requirement: Project-scoped data isolation
All facade emulator data SHALL be scoped by project ID. Each service's database tables SHALL include a `project_id` column. Queries SHALL filter by the active project.

#### Scenario: Secrets are isolated per project
- **WHEN** a secret "api-key" is created in project "dev" and project "staging"
- **THEN** each project has its own independent "api-key" secret with separate versions

#### Scenario: Logging entries scoped by project
- **WHEN** log entries are written for project "dev" and project "staging"
- **THEN** listing log entries for "dev" returns only entries for that project

#### Scenario: Memorystore data scoped by project
- **WHEN** a Redis key "session:abc" is set in project "dev" and project "staging"
- **THEN** each project has its own independent copy of the key

### Requirement: Project-scoped browse endpoints
All `/_localcloud/browse/{service}` endpoints SHALL accept an optional `?project=` query parameter. When omitted, the default project from `LOCALCLOUD_PROJECT` SHALL be used.

#### Scenario: Browse with explicit project
- **WHEN** a GET request is sent to `/_localcloud/browse/secretmanager?project=staging`
- **THEN** the response contains only secrets belonging to the "staging" project

#### Scenario: Browse without project parameter
- **WHEN** a GET request is sent to `/_localcloud/browse/secretmanager` without a `?project=` parameter
- **THEN** the response contains secrets belonging to the default project

### Requirement: Multi-project seed format
The seed system SHALL support a multi-project YAML format where services are nested under project keys. The system SHALL auto-detect the format.

#### Scenario: Multi-project seed
- **WHEN** a seed file contains `projects: { dev: { gcs: { buckets: [...] } }, staging: { gcs: { buckets: [...] } } }`
- **THEN** data is seeded into each project independently

#### Scenario: Single-project seed backward compatibility
- **WHEN** a seed file uses the existing format `services: { gcs: { buckets: [...] } }` without a `projects:` key
- **THEN** data is seeded into the default project from `LOCALCLOUD_PROJECT`

### Requirement: Project-scoped environment variables
The `/_localcloud/env` endpoint SHALL accept a `?project=` query parameter and return environment variables scoped to that project.

#### Scenario: Env vars for specific project
- **WHEN** a GET request is sent to `/_localcloud/env?project=staging`
- **THEN** the response includes `GOOGLE_CLOUD_PROJECT=staging` and `GCLOUD_PROJECT=staging`

### Requirement: Project-scoped reset
The `/_localcloud/reset` endpoint SHALL accept a `?project=` query parameter to reset only one project's data. Without the parameter, all projects are reset.

#### Scenario: Reset single project
- **WHEN** a POST request is sent to `/_localcloud/reset?project=staging`
- **THEN** only the "staging" project's data is deleted across all services

#### Scenario: Reset all projects
- **WHEN** a POST request is sent to `/_localcloud/reset` without a project parameter
- **THEN** all data across all projects is deleted
