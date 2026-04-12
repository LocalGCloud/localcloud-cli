## ADDED Requirements

### Requirement: Enable service via API
The system SHALL expose `POST /_localcloud/services/{id}/enable` that starts the emulator process for external services or enables request routing for facade services.

#### Scenario: Enable an external service
- **WHEN** a client calls `POST /_localcloud/services/bigquery/enable`
- **THEN** the BigQuery emulator process is started via supervisord and the service status transitions to "healthy" once the health check passes

#### Scenario: Enable a facade service
- **WHEN** a client calls `POST /_localcloud/services/secretmanager/enable`
- **THEN** the Secret Manager facade starts accepting requests on the gateway port

#### Scenario: Enable an already-enabled service
- **WHEN** a client calls enable on a service that is already running
- **THEN** the endpoint returns 200 with `{ "status": "already_enabled" }`

### Requirement: Disable service via API
The system SHALL expose `POST /_localcloud/services/{id}/disable` that stops the emulator process for external services or disables request routing for facade services.

#### Scenario: Disable an external service
- **WHEN** a client calls `POST /_localcloud/services/pubsub/disable`
- **THEN** the Pub/Sub emulator process is stopped via supervisord and the service status transitions to "disabled"

#### Scenario: Disable a facade service
- **WHEN** a client calls `POST /_localcloud/services/logging/disable`
- **THEN** the Cloud Logging facade rejects new requests with HTTP 503

#### Scenario: Disable an already-disabled service
- **WHEN** a client calls disable on a service that is already stopped
- **THEN** the endpoint returns 200 with `{ "status": "already_disabled" }`

### Requirement: Enable/disable toggle in console
The Services page SHALL display a toggle switch per service allowing users to enable or disable it from the UI.

#### Scenario: User disables a service
- **WHEN** the user toggles the switch for Cloud Logging from enabled to disabled
- **THEN** the console calls `POST /_localcloud/services/logging/disable` and the service status changes to "Disabled"

#### Scenario: User enables a service
- **WHEN** the user toggles the switch for Cloud Logging from disabled to enabled
- **THEN** the console calls `POST /_localcloud/services/logging/enable` and the health check resumes

### Requirement: Disabled services show distinct visual state
Disabled services SHALL be visually distinct from enabled services in both the Dashboard and Services page.

#### Scenario: Disabled service on Dashboard
- **WHEN** a service is disabled
- **THEN** its service card appears dimmed with a "Disabled" badge replacing the health status

#### Scenario: Disabled service in sidebar
- **WHEN** a service is disabled
- **THEN** its sidebar sub-item text appears dimmed and the health dot is replaced with a gray dot
