## ADDED Requirements

### Requirement: Pub/Sub topics and subscriptions persist across Docker restarts
The system SHALL persist all Pub/Sub topic and subscription configurations across Docker container restarts. Topics, subscription bindings, ack deadlines, and retry policies MUST survive restarts. Published messages are ephemeral by design and do NOT need to persist.

#### Scenario: Topics survive restart
- **WHEN** a developer creates Pub/Sub topics, restarts the container
- **THEN** all topics are present in `ListTopics` without re-creation

#### Scenario: Subscriptions survive restart with configuration
- **WHEN** a developer creates subscriptions with custom ack deadlines and retry policies, restarts the container
- **THEN** all subscriptions are present with their original configuration

#### Scenario: Messages are ephemeral
- **WHEN** messages are published to a topic and the container is restarted
- **THEN** unacknowledged messages are lost (this is expected and acceptable for local development)

### Requirement: Pub/Sub configuration sync to PostgreSQL
The system SHALL synchronize topic and subscription configurations from the gcloud Pub/Sub emulator to PostgreSQL. The sync SHALL run periodically and on admin operations (create/delete topic/subscription).

#### Scenario: New topic synced to PostgreSQL
- **WHEN** a developer creates a topic via the Pub/Sub gRPC API
- **THEN** the topic name and configuration are recorded in the `pubsub_topics` PostgreSQL table within 30 seconds

#### Scenario: Startup restore from PostgreSQL
- **WHEN** the container starts and PostgreSQL contains previously synced topics and subscriptions
- **THEN** the system re-creates all topics and subscriptions in the gcloud Pub/Sub emulator before accepting client requests
