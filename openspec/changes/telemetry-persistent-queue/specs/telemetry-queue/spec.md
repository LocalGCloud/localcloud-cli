## ADDED Requirements

### Requirement: Failed events persisted to PostgreSQL
When a telemetry event fails to send (HTTP error or network unreachable), the system SHALL insert the event JSON into the `telemetry_queue` table.

#### Scenario: Heartbeat fails to send
- **WHEN** the hourly heartbeat POST to PostHog returns a non-200 status or throws a connection error
- **THEN** the event payload SHALL be inserted into `telemetry_queue`

#### Scenario: Error event fails to send
- **WHEN** a `service_error` event fails to send
- **THEN** the event payload SHALL be inserted into `telemetry_queue`

### Requirement: Queued events retried on next cycle
On each heartbeat cycle, the system SHALL attempt to send all queued events (oldest first) before sending the new heartbeat.

#### Scenario: Queued events sent successfully
- **WHEN** the heartbeat cycle runs and 3 events are in the queue and PostHog is reachable
- **THEN** all 3 queued events SHALL be sent and deleted from the queue, then the new heartbeat SHALL be sent

#### Scenario: Queue drain stops on first failure
- **WHEN** the heartbeat cycle runs and PostHog is unreachable
- **THEN** the system SHALL stop attempting to send queued events after the first failure and queue the new heartbeat

### Requirement: Queue capped at 168 events
The `telemetry_queue` table SHALL hold at most 168 events. When a new event is inserted and the count exceeds 168, the oldest events SHALL be deleted.

#### Scenario: Queue overflow
- **WHEN** the queue contains 168 events and a new event is inserted
- **THEN** the oldest event(s) SHALL be deleted to keep the count at 168

### Requirement: telemetry_queue table schema
The system SHALL create a `telemetry_queue` table with columns `id SERIAL PRIMARY KEY`, `event_json TEXT NOT NULL`, `created_at TIMESTAMP DEFAULT NOW()` during schema migration.

#### Scenario: Table created on startup
- **WHEN** the server starts and the table does not exist
- **THEN** the SchemaManager SHALL create the `telemetry_queue` table
