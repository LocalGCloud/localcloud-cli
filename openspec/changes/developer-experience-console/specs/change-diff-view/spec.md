## ADDED Requirements

### Requirement: Cross-service change diff after operations
The console SHALL provide a "What Changed?" view that shows a unified diff of all data changes across all services since a chosen point in time (or since the last check). Changes SHALL be grouped by service and show: new records created, records modified (with before/after values), and records deleted.

#### Scenario: See changes after running a test
- **WHEN** developer clicks "Start Recording", runs their integration test, then clicks "Show Changes"
- **THEN** the diff view shows: "Firestore: +2 documents in /users, BigQuery: +5 rows in page_views, Pub/Sub: 3 messages published to user-events, Logging: 12 new entries"

#### Scenario: Inspect a specific change
- **WHEN** developer clicks on "+2 documents in /users" in the diff view
- **THEN** the two new documents are shown with their full field values

### Requirement: Change recording toggle
The console SHALL provide a "Record Changes" toggle in the header/toolbar. When enabled, the system tracks all mutations across all services. When the developer stops recording, the accumulated changes are displayed in the diff view.

#### Scenario: Toggle recording on and off
- **WHEN** developer clicks "Record Changes" (starts recording), performs operations, then clicks "Stop Recording"
- **THEN** a change summary appears showing all mutations grouped by service, with counts and expandable details
