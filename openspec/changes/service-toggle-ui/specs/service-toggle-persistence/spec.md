## ADDED Requirements

### Requirement: Service config table exists in PostgreSQL
The system SHALL create a `service_config` table with columns `service_id TEXT PRIMARY KEY`, `enabled BOOLEAN NOT NULL`, and `updated_at TIMESTAMP DEFAULT NOW()` during schema migration.

#### Scenario: Table created on first startup
- **WHEN** the LocalCloud server starts and the `service_config` table does not exist
- **THEN** the SchemaManager SHALL create the table as part of the schema migration

### Requirement: Persisted state loaded on startup
The system SHALL load all rows from `service_config` on startup and merge them into the service enabled/disabled state using the precedence: env var > persisted state > services.yaml defaultEnabled.

#### Scenario: Persisted state restored after restart
- **WHEN** a user disables "pubsub" via the UI, then the container restarts
- **THEN** the pubsub service SHALL remain disabled (loaded from `service_config`)

#### Scenario: Env var overrides persisted state
- **WHEN** `service_config` has pubsub=false but `LOCALCLOUD_ENABLE_PUBSUB=true` is set
- **THEN** pubsub SHALL be enabled (env var wins)

#### Scenario: LOCALCLOUD_SERVICES overrides everything
- **WHEN** `LOCALCLOUD_SERVICES=gcs,pubsub` is set and `service_config` has firestore=true
- **THEN** only gcs and pubsub SHALL be enabled; firestore SHALL be disabled

### Requirement: Config API endpoint for bulk read/write
The system SHALL expose `GET /_localcloud/config/services` returning a JSON object mapping service IDs to their enabled state and source (env, persisted, default). The system SHALL expose `PUT /_localcloud/config/services` accepting a JSON object of `{ serviceId: boolean }` pairs to update persisted config.

#### Scenario: Read current config
- **WHEN** `GET /_localcloud/config/services` is called
- **THEN** the response SHALL include each service with `{ "enabled": boolean, "source": "env"|"persisted"|"default", "locked": boolean }`

#### Scenario: Write config persists and takes effect
- **WHEN** `PUT /_localcloud/config/services` is called with `{ "spanner": true }`
- **THEN** the `service_config` table SHALL be updated and the spanner service SHALL be enabled

### Requirement: Toggle writes to persistence
When a service is enabled or disabled via `POST /_localcloud/services/{id}/enable` or `disable`, the system SHALL also write the new state to the `service_config` table.

#### Scenario: Enable persists
- **WHEN** `POST /_localcloud/services/bigquery/enable` is called
- **THEN** `service_config` SHALL contain a row with `service_id=bigquery, enabled=true`

#### Scenario: Disable persists
- **WHEN** `POST /_localcloud/services/pubsub/disable` is called
- **THEN** `service_config` SHALL contain a row with `service_id=pubsub, enabled=false`
