## ADDED Requirements

### Requirement: Toggle switch on each service row
The Services page SHALL display a toggle switch for each service in the table. The toggle SHALL reflect the current enabled/disabled state. Clicking the toggle SHALL call the enable or disable API endpoint.

#### Scenario: User disables a running service
- **WHEN** a user clicks the toggle for a healthy service (e.g., "pubsub")
- **THEN** the toggle SHALL switch to off, the service status SHALL change to "disabled", and the row SHALL be visually dimmed

#### Scenario: User enables a disabled service
- **WHEN** a user clicks the toggle for a disabled service (e.g., "spanner")
- **THEN** the toggle SHALL switch to on and the service status SHALL update to "starting" then "healthy" once the emulator is ready

### Requirement: Disabled services visually distinct
Disabled services SHALL be visually dimmed (reduced opacity) in both the Services table and Dashboard cards. The status badge SHALL show "disabled" instead of "unhealthy".

#### Scenario: Disabled service appearance
- **WHEN** a service is disabled
- **THEN** its row SHALL have reduced opacity (0.5), the status badge SHALL read "disabled" with a neutral color, and non-toggle controls SHALL be inactive

### Requirement: Locked indicator for env-overridden services
When a service's enabled state is controlled by an environment variable, the toggle SHALL be disabled (non-interactive) and show a lock icon or tooltip indicating "Controlled by environment variable".

#### Scenario: Env-locked service
- **WHEN** `LOCALCLOUD_ENABLE_GKE=false` is set via environment variable
- **THEN** the GKE toggle SHALL appear locked/disabled and the user SHALL NOT be able to toggle it from the UI

### Requirement: Toggle state syncs with persisted config
After toggling a service, the console SHALL refetch the service list to confirm the state change. If the API call fails, the toggle SHALL revert to its previous state and show an error message.

#### Scenario: API failure reverts toggle
- **WHEN** a user toggles a service but the API returns an error
- **THEN** the toggle SHALL revert to its previous position and a brief error message SHALL appear
