## ADDED Requirements

### Requirement: Remote mode proxies requests to real GCP
When a service is configured in `remote` mode, the LocalCloud gateway SHALL forward incoming requests to the corresponding real GCP API endpoint with injected credentials, and return the GCP response to the client.

#### Scenario: GCS in remote mode
- **WHEN** GCS is set to `remote` with `remote_project: "my-dev"` and a client calls `GET /storage/v1/b` (list buckets)
- **THEN** the gateway forwards the request to `https://storage.googleapis.com/storage/v1/b?project=my-dev` with the configured credential's OAuth token
- **AND** returns the real GCP response to the client

#### Scenario: Remote mode without credentials
- **WHEN** a service is set to `remote` but `LOCALCLOUD_GCP_CREDENTIAL_SOURCE=none`
- **THEN** the gateway returns HTTP 503 with error `"Remote mode requires GCP credentials. Set LOCALCLOUD_GCP_CREDENTIAL_SOURCE and mount credential files."`

#### Scenario: Real GCP returns error
- **WHEN** a proxied request to real GCP returns a 403 Forbidden
- **THEN** the gateway returns the same 403 to the client with the GCP error body intact

### Requirement: Remote mode supports REST services
The remote proxy SHALL support HTTP/REST forwarding for services with REST protocol (GCS, BigQuery, Compute Engine).

#### Scenario: BigQuery query in remote mode
- **WHEN** BigQuery is set to `remote` and a client sends a query job
- **THEN** the request is forwarded to `https://bigquery.googleapis.com/` with OAuth token

### Requirement: Remote config persisted per service per project
The routing mode and remote configuration (project, region) SHALL be persisted in the `service_routing` PostgreSQL table and survive container restarts.

#### Scenario: Routing survives restart
- **WHEN** GCS is set to remote mode and the container restarts
- **THEN** GCS is still in remote mode after restart

#### Scenario: Per-project routing
- **WHEN** project "staging" has GCS set to remote and project "dev" has GCS set to local
- **THEN** requests scoped to "staging" proxy to real GCP and requests scoped to "dev" go to the local emulator

### Requirement: Console routing panel in Settings
The Settings page SHALL include a "Service Routing" panel showing all services with their current mode (Local/Remote) and the ability to switch modes.

#### Scenario: View routing panel
- **WHEN** the user opens Settings and has valid credentials
- **THEN** the routing panel shows each service with a Local/Remote dropdown, credential status indicator, and remote config fields (project, region) for services in remote mode

#### Scenario: Switch to remote from console
- **WHEN** the user selects "Remote" for Cloud Storage in the routing panel and fills in project "my-dev"
- **THEN** the console calls `PUT /_localcloud/routing/gcs` with `{ "mode": "remote", "remote_project": "my-dev" }`
- **AND** the routing badge updates to "Cloud" across Dashboard, Services, and sidebar

#### Scenario: Remote not available without credentials
- **WHEN** credential source is `none` and the user tries to set a service to remote
- **THEN** the dropdown is disabled with a tooltip: "Configure GCP credentials to enable remote mode"
