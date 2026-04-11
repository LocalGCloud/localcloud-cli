## ADDED Requirements

### Requirement: Auto-generated service dependency graph
The console SHALL provide a "Topology" page showing a visual graph of all active services and their connections. Connections SHALL be derived from: EventBus subscriptions (e.g., GCS → Pub/Sub), Cloud Tasks target URLs (e.g., Cloud Tasks → Cloud Run), and configured triggers. Services SHALL be shown as nodes with health status indicators, and connections as directed edges with event type labels.

#### Scenario: View service topology
- **WHEN** developer navigates to the Topology page
- **THEN** a graph shows all 13+ services as nodes, with arrows showing data flow (e.g., GCS → Pub/Sub → Cloud Run), and each node shows healthy/unhealthy status

#### Scenario: Click a connection to see events
- **WHEN** developer clicks the arrow between GCS and Pub/Sub
- **THEN** a panel shows recent events on that connection: event types, message counts, last event time

### Requirement: Architecture summary with ports and protocols
The Topology page SHALL include a text-based summary showing all services with their ports, protocols, environment variables, and connection endpoints in a copyable format. This serves as living documentation of the local development environment.

#### Scenario: Copy architecture summary
- **WHEN** developer clicks "Copy Architecture" on the Topology page
- **THEN** a formatted text block is copied showing all services, ports, and env vars — suitable for pasting into a project README or sharing with teammates

### Requirement: Service health timeline
The Topology page SHALL include a timeline showing service health transitions over the session lifetime. Developers can see when a service went unhealthy and when it recovered, helping debug intermittent issues.

#### Scenario: Identify intermittent Spanner failures
- **WHEN** developer notices Spanner is healthy now but was unhealthy earlier
- **THEN** the health timeline shows the unhealthy period (e.g., "Spanner: unhealthy 14:05-14:07") so the developer can correlate with other events
