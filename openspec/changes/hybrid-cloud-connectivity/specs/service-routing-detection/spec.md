## ADDED Requirements

### Requirement: Routing status API endpoint
The system SHALL expose `GET /_localcloud/routing` that returns the routing status for every registered service. The response SHALL be a JSON object with service IDs as keys and routing info as values.

#### Scenario: All services running locally
- **WHEN** all emulator processes are running and healthy
- **THEN** the endpoint returns each service with `{ "emulatorRunning": true, "healthy": true, "routing": "local" }`

#### Scenario: Some services not running
- **WHEN** the BigQuery emulator is stopped but Pub/Sub is running
- **THEN** BigQuery returns `{ "emulatorRunning": false, "healthy": false, "routing": "cloud" }` and Pub/Sub returns `{ "emulatorRunning": true, "healthy": true, "routing": "local" }`

#### Scenario: Service is running but unhealthy
- **WHEN** the GCS emulator process is running but health check fails
- **THEN** GCS returns `{ "emulatorRunning": true, "healthy": false, "routing": "unknown" }`

### Requirement: Routing status includes port and env var info
Each service entry in the routing response SHALL include the service's port number and environment variable name so the frontend can display actionable information.

#### Scenario: Full routing response structure
- **WHEN** a client calls `GET /_localcloud/routing`
- **THEN** each service entry includes `port`, `envVar`, `emulatorRunning`, `healthy`, and `routing` fields

### Requirement: Routing endpoint respects refresh interval
The routing endpoint SHALL be polled by the frontend at the same interval as the health check endpoint to keep the UI current.

#### Scenario: Periodic polling
- **WHEN** the console refresh interval is set to 5 seconds
- **THEN** the routing endpoint is called every 5 seconds alongside the health check

### Requirement: Routing response includes mode field
Each service entry SHALL include a `mode` field with value `local` or `remote`, reflecting the persisted routing configuration. The `routing` field reflects auto-detected state; `mode` reflects user-configured intent.

#### Scenario: Service configured as remote
- **WHEN** a service has been set to remote mode via `PUT /_localcloud/routing/{service}`
- **THEN** its routing entry includes `"mode": "remote"` alongside the detected `routing` field

#### Scenario: Default mode is local
- **WHEN** no routing configuration has been set for a service
- **THEN** its routing entry includes `"mode": "local"`

### Requirement: Set service routing mode API
The system SHALL expose `PUT /_localcloud/routing/{service}` that sets the routing mode for a service to `local` or `remote`. When set to `remote`, the request body MAY include `remote_project` and `remote_region`.

#### Scenario: Switch service to remote
- **WHEN** a client calls `PUT /_localcloud/routing/gcs` with `{ "mode": "remote", "remote_project": "my-dev", "remote_region": "us-central1" }`
- **THEN** GCS routing mode is set to `remote` and the config is persisted in PostgreSQL

#### Scenario: Switch service back to local
- **WHEN** a client calls `PUT /_localcloud/routing/gcs` with `{ "mode": "local" }`
- **THEN** GCS routing mode is set to `local` and requests resume going to the local emulator

#### Scenario: Invalid service ID
- **WHEN** a client calls `PUT /_localcloud/routing/nonexistent`
- **THEN** the endpoint returns HTTP 404
