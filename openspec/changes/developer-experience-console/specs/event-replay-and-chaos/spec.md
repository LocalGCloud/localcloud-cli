## ADDED Requirements

### Requirement: Event recording and replay
The console SHALL record all events passing through the EventBus (e.g., gcs.object.created, pubsub.message.published) with their full payload. Developers SHALL be able to select any recorded event and replay it — re-triggering the same downstream handlers with the same payload.

#### Scenario: Replay a Pub/Sub message after fixing handler code
- **WHEN** a Pub/Sub message triggered a Cloud Run handler that crashed, developer fixes the handler, and clicks "Replay" on the recorded event
- **THEN** the same message is re-published to the topic, triggering the handler again with the same payload

#### Scenario: View event history
- **WHEN** developer opens the Event History panel
- **THEN** all recent events are listed with event type, source service, timestamp, and payload preview

### Requirement: Per-service fault injection
The console SHALL allow developers to inject faults into any emulator service. Configurable fault types: error response (specify HTTP status code), latency injection (specify delay in ms), and request timeout. Faults SHALL be configurable as: all requests, next N requests, or percentage-based.

#### Scenario: Inject 503 errors into GCS
- **WHEN** developer enables fault injection on GCS with "Return 503 for next 5 requests"
- **THEN** the next 5 requests to GCS return 503 Service Unavailable, and the 6th request works normally

#### Scenario: Add latency to Pub/Sub
- **WHEN** developer enables "Add 2000ms latency" on Pub/Sub
- **THEN** all Pub/Sub API responses are delayed by 2 seconds, allowing testing of timeout handling

#### Scenario: Disable fault injection
- **WHEN** developer clicks "Clear All Faults"
- **THEN** all services return to normal behavior immediately

### Requirement: Data snapshots and restore
The console SHALL support saving named snapshots of all emulator state. Developers can create a snapshot, make changes, and restore to the snapshot if the changes didn't work out. Snapshots capture seed data + any mutations made since seeding.

#### Scenario: Create and restore a snapshot
- **WHEN** developer clicks "Save Snapshot" and names it "before-migration-test"
- **THEN** the current state is saved. After making changes, clicking "Restore" on "before-migration-test" returns all services to the saved state
