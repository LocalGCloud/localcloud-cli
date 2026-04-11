## ADDED Requirements

### Requirement: Correlation ID propagation across services
The API gateway SHALL generate a unique trace ID for each incoming request and propagate it through all downstream operations triggered by that request (EventBus events, Cloud Tasks dispatch, Pub/Sub notifications). All log entries, events, and sub-requests generated as a result SHALL carry the same trace ID.

#### Scenario: GCS upload triggers Pub/Sub which triggers Cloud Run
- **WHEN** a client uploads an object to GCS, which triggers a Pub/Sub notification, which triggers a Cloud Run handler
- **THEN** all three operations (GCS upload, Pub/Sub publish, Cloud Run invocation) share the same trace ID

#### Scenario: Trace ID visible in request log
- **WHEN** developer views a request in the Logs page
- **THEN** the trace ID is shown and clicking it navigates to the trace view showing all correlated operations

### Requirement: Visual trace timeline
The console SHALL provide a trace view showing all operations in a trace as a waterfall/timeline. Each operation shows the service name, operation type, duration, status, and any child operations it triggered. Operations are connected by arrows showing the causal chain.

#### Scenario: View a multi-service trace
- **WHEN** developer clicks a trace ID in the Logs page
- **THEN** a timeline view shows all correlated operations: parent request at the top, child events below, with timing bars showing overlap and sequence

#### Scenario: Identify slow step in a chain
- **WHEN** a trace has 4 operations and one took 3 seconds while others took 50ms
- **THEN** the slow operation is visually prominent (wider bar, color-coded) in the timeline

### Requirement: Real-time event stream
The console SHALL support WebSocket or Server-Sent Events for live request streaming in the Logs page, replacing the current 3-second polling. New requests SHALL appear instantly as they arrive.

#### Scenario: See requests in real-time
- **WHEN** developer has the Logs page open and sends an API request
- **THEN** the request appears in the log within 100ms without waiting for a poll cycle
