## MODIFIED Requirements

### Requirement: Enable/disable endpoints persist state
The existing `POST /_localcloud/services/{id}/enable` and `POST /_localcloud/services/{id}/disable` endpoints SHALL write the new enabled/disabled state to the `service_config` PostgreSQL table after successfully toggling the service. If the database write fails, the service toggle SHALL still succeed (best-effort persistence).

#### Scenario: Enable writes to database
- **WHEN** `POST /_localcloud/services/firestore/enable` is called and the service starts successfully
- **THEN** the `service_config` table SHALL contain `service_id=firestore, enabled=true`

#### Scenario: Disable writes to database
- **WHEN** `POST /_localcloud/services/bigquery/disable` is called and the service stops
- **THEN** the `service_config` table SHALL contain `service_id=bigquery, enabled=false`

#### Scenario: Database write failure does not block toggle
- **WHEN** a service is toggled but the PostgreSQL write fails
- **THEN** the service SHALL still be toggled in-memory and the response SHALL include a warning that persistence failed

### Requirement: Services endpoint includes config source
The `GET /_localcloud/services` response SHALL include an `enabledSource` field for each service indicating whether the enabled state comes from `"env"`, `"persisted"`, or `"default"`.

#### Scenario: Services response with source
- **WHEN** `GET /_localcloud/services` is called
- **THEN** each service object SHALL include `"enabledSource": "env"|"persisted"|"default"`
