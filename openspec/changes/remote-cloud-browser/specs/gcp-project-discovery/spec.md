## ADDED Requirements

### Requirement: GCP project discovery endpoint
The system SHALL expose `GET /_localcloud/gcp/projects` that lists GCP projects accessible with the mounted credentials by calling the Cloud Resource Manager API.

#### Scenario: List accessible projects
- **WHEN** `GET /_localcloud/gcp/projects` is called and valid credentials are mounted
- **THEN** the response SHALL contain an array of `{ projectId, name, projectNumber }` objects

#### Scenario: Credentials not mounted
- **WHEN** `GET /_localcloud/gcp/projects` is called and no credentials are mounted (source = "none")
- **THEN** the response SHALL return 400 with `{ error: "No GCP credentials configured" }`

#### Scenario: Credentials invalid or expired
- **WHEN** `GET /_localcloud/gcp/projects` is called but the access token is invalid
- **THEN** the system SHALL attempt token refresh, and if that fails, return 401 with `{ error: "Credentials expired" }`

### Requirement: Project list caching
The system SHALL cache the project list for 5 minutes to avoid redundant API calls during UI interactions.

#### Scenario: Cached response served
- **WHEN** `GET /_localcloud/gcp/projects` is called within 5 minutes of a previous successful call
- **THEN** the cached result SHALL be returned without calling the GCP API

#### Scenario: Cache expired
- **WHEN** `GET /_localcloud/gcp/projects` is called after the 5-minute cache expires
- **THEN** the system SHALL call the GCP API again and update the cache

### Requirement: Console project picker
The Settings page SHALL display a dropdown populated from the GCP projects endpoint, allowing users to select a remote GCP project for service routing.

#### Scenario: User selects a remote project
- **WHEN** a user selects "my-dev-project" from the project picker dropdown
- **THEN** the selected project SHALL be used as `remote_project` when toggling services to "remote" mode
