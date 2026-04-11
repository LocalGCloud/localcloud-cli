## ADDED Requirements

### Requirement: Browse Pub/Sub messages in Data Browser
The system SHALL display recent messages from Pub/Sub subscriptions in the Data Browser. Messages MUST be retrieved by pulling from subscriptions and displayed with their data, attributes, publish time, and message ID. The system SHALL show the last 100 messages per subscription.

#### Scenario: View messages for a topic
- **WHEN** user selects Pub/Sub in the Data Browser and clicks on a topic's subscription
- **THEN** the system pulls up to 100 messages from the subscription (without acknowledging them) and displays message ID, publish time, data (decoded from base64), and attributes

#### Scenario: Empty topic shows informative message
- **WHEN** user views a topic subscription with no messages
- **THEN** the system displays "No messages found" with instructions to publish via SDK

### Requirement: Publish seed messages to Pub/Sub topics
The system SHALL publish sample messages to Pub/Sub topics during seed data loading. Each topic SHALL receive messages with content appropriate to the topic name.

#### Scenario: Seed messages published to user-events topic
- **WHEN** seed data is loaded and the `pubsub` section includes messages for `user-events`
- **THEN** messages like user-login, user-signup, user-profile-update events are published to the topic

#### Scenario: Seed messages published to order-events topic
- **WHEN** seed data is loaded and the `pubsub` section includes messages for `order-events`
- **THEN** messages like order-created, order-shipped, order-completed events are published to the topic

### Requirement: Create and delete Pub/Sub topics from Data Browser
The system SHALL allow users to create new topics and delete existing topics from the Data Browser UI.

#### Scenario: Create a new topic
- **WHEN** user clicks "Create Topic", enters a topic name, and submits
- **THEN** the system creates the topic via the Pub/Sub emulator API and it appears in the topic list

#### Scenario: Delete a topic
- **WHEN** user clicks "Delete" on a topic and confirms
- **THEN** the system deletes the topic via the Pub/Sub emulator API and it is removed from the list

### Requirement: Publish messages from Data Browser
The system SHALL allow users to publish messages to Pub/Sub topics directly from the Data Browser UI.

#### Scenario: Publish a message to a topic
- **WHEN** user clicks "Publish Message" on a topic, enters message data and optional attributes, and submits
- **THEN** the system publishes the message via the Pub/Sub emulator API

### Requirement: Firestore seed data must populate documents
The system SHALL ensure Firestore seed data results in visible documents in the Data Browser. The `seedFirestore()` method MUST successfully create documents via the Firestore emulator REST API.

#### Scenario: Firestore collections visible after seeding
- **WHEN** seed data is loaded with a `firestore` section containing collections and documents
- **THEN** the Data Browser shows all seeded collections with their documents and field values

### Requirement: BigQuery CRUD buttons in Data Browser
The system SHALL display Add Row and Delete Row buttons in the BigQuery table data view. These buttons MUST call the existing MutateService BigQuery handlers.

#### Scenario: Add a row to BigQuery table
- **WHEN** user is viewing BigQuery table data and clicks "Add Row", fills in field values, and submits
- **THEN** the system inserts the row via the BigQuery insertAll API and the new row appears in the view

#### Scenario: Delete a BigQuery row
- **WHEN** user clicks "Delete" on a BigQuery row and confirms
- **THEN** the system executes a DELETE DML via the BigQuery jobs API

### Requirement: Cloud Tasks queue creation must work
The system SHALL implement Cloud Tasks mutation handlers for creating and deleting queues. Seed data SHALL include sample queues.

#### Scenario: Create a Cloud Tasks queue
- **WHEN** user clicks "Create Queue" in the Data Browser, enters a queue name, and submits
- **THEN** the system inserts the queue into PostgreSQL and it appears in the queue list

#### Scenario: Cloud Tasks seed data loaded
- **WHEN** seed data is loaded with a `cloudtasks` section
- **THEN** the Data Browser shows seeded queues with their state and configuration

### Requirement: Spanner CRUD buttons in Data Browser
The system SHALL display Add Row, Edit Row, and Delete Row buttons in the Spanner table data view. These buttons MUST call the existing MutateService Spanner handlers.

#### Scenario: Add a row to Spanner table
- **WHEN** user is viewing Spanner table data and clicks "Add Row", fills in column values, and submits
- **THEN** the system inserts the row via the Spanner commit API and the new row appears

#### Scenario: Delete a Spanner row
- **WHEN** user clicks "Delete" on a Spanner row and confirms
- **THEN** the system commits a delete mutation via the Spanner API

### Requirement: Bigtable row mutations must work
The system SHALL implement Bigtable row mutations via PostgreSQL (matching the browse approach) instead of returning a TODO response.

#### Scenario: Add a Bigtable row
- **WHEN** user clicks "Add Row" on a Bigtable table, enters row key, column family, column, and value
- **THEN** the system inserts the row into the `bigtable_data` PostgreSQL table and it appears in the view

#### Scenario: Delete a Bigtable row
- **WHEN** user clicks "Delete" on a Bigtable row and confirms
- **THEN** the system deletes the row from the `bigtable_data` PostgreSQL table
