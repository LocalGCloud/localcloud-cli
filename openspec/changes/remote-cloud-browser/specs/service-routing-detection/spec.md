## MODIFIED Requirements

### Requirement: Routing UI supports editable remote project configuration
The Settings page routing table SHALL include editable fields for `remote_project` and `remote_region` per service, populated from the GCP project picker. When a service is toggled to "remote" mode, the selected GCP project SHALL be saved as the remote_project.

#### Scenario: Edit remote project for a service
- **WHEN** a user sets GCS routing to "remote" and selects "my-dev-project" from the project picker
- **THEN** `PUT /_localcloud/routing/gcs` SHALL be called with `{ mode: "remote", remote_project: "my-dev-project" }`

#### Scenario: Display current remote config
- **WHEN** the Settings page loads and GCS is routed to "remote" with remote_project="my-dev-project"
- **THEN** the routing table SHALL show "my-dev-project" in the remote project column for GCS

#### Scenario: Switch back to local
- **WHEN** a user toggles GCS routing from "remote" back to "local"
- **THEN** the emulator SHALL resume handling requests and the remote_project value SHALL be preserved (for easy re-toggle)
