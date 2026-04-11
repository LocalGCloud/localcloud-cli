## ADDED Requirements

### Requirement: Project dropdown in topbar
The console topbar SHALL replace the static project chip with an interactive dropdown that lists all available projects. The active project SHALL be stored in localStorage and persist across sessions.

#### Scenario: View project list
- **WHEN** the user clicks the project chip in the topbar
- **THEN** a dropdown opens showing all projects with their display names

#### Scenario: Switch project
- **WHEN** the user selects a different project from the dropdown
- **THEN** the active project changes, localStorage is updated, and all page data reloads for the new project

#### Scenario: Persisted project selection
- **WHEN** the user refreshes the page
- **THEN** the previously selected project is restored from localStorage

### Requirement: Create project from console
The console SHALL provide a way to create new projects from the project dropdown.

#### Scenario: Create project via dialog
- **WHEN** the user clicks "New Project" in the project dropdown
- **THEN** a dialog appears with fields for project ID and display name
- **WHEN** the user submits the form
- **THEN** the project is created via `POST /_localcloud/projects` and the dropdown updates

### Requirement: Project-scoped console views
The Dashboard, Data Browser, Services, Logs, and Usage pages SHALL display data scoped to the active project. All API calls from the console SHALL include the active project.

#### Scenario: Dashboard shows project-scoped data
- **WHEN** the user is viewing the Dashboard with project "staging" selected
- **THEN** service health, request counts, and stats reflect the "staging" project

#### Scenario: Data Browser shows project-scoped data
- **WHEN** the user browses GCS buckets with project "dev" selected
- **THEN** only buckets belonging to the "dev" project are shown
