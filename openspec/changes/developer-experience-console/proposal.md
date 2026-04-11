## Why

The LocalCloud console today is a read-only monitoring dashboard — developers can see service health, request counts, and browse data, but cannot deeply inspect requests, trace cross-service flows, run queries, replay events, or inject failures. These are exactly the capabilities developers need during the build-test-debug cycle with cloud services.

The unique advantage of a local emulator is that there are no security, cost, or sampling constraints. Every request can be fully inspected (headers + body), every event can be replayed, and failures can be injected without consequences. The console should exploit this to provide developer experiences that are **impossible in production** — making LocalCloud not just a cheaper alternative to cloud, but a fundamentally better debugging and learning environment.

## What Changes

### Story 1: Request Inspector — See Everything
Full request/response inspection with headers, bodies, timing, and cURL export. Zero-sampling visibility — every single request is captured with full payloads. This is the foundation for all debugging.

### Story 2: Cross-Service Tracing — Follow the Flow
Visual distributed tracing across services. When a GCS upload triggers a Pub/Sub message that enqueues a Cloud Task, show the entire chain in a connected timeline. This solves the #1 pain point in event-driven architecture debugging.

### Story 3: Interactive Query Consoles — Talk to Your Data
SQL editors for BigQuery and Spanner, Redis CLI for Memorystore, and a Pub/Sub message publisher. Developers should be able to interact with their data directly from the console without switching to external tools.

### Story 4: Structured Log Explorer — Find the Needle
A proper Logs Explorer with severity filtering, JSON payload expansion, full-text search, time-range selection, and correlation with requests. Modeled after GCP's Logs Explorer — the most-used feature in the GCP Console.

### Story 5: Event Replay and Chaos Testing — Break Things Safely
Record events and replay them after code fixes. Inject failures (503s, latency, timeouts) into any service to test resilience. These capabilities are impossible in production but invaluable locally.

### Story 6: What Changed? — Diff View After Operations
After running a test or API call, show a unified diff of what changed across all services: new documents, new messages, new rows, new log entries. Answers the fundamental question: "Did my code do what I expected?"

### Story 7: Developer Onboarding — SDK Snippets and Getting Started
Show per-service code snippets in Python, Java, Go, and Node.js. Add a first-run wizard that guides developers from zero to their first successful API call. Reduce time-to-first-success to under 5 minutes.

### Story 8: Service Topology — See the Architecture
Auto-generated dependency graph showing how services are connected via EventBus subscriptions and trigger configurations. Living documentation of the local development environment.

## Capabilities

### New Capabilities

- `request-inspector`: Full request/response detail view with headers, bodies, timing breakdown, and cURL export
- `cross-service-tracing`: Distributed tracing with visual timeline showing request flow across multiple services via correlation IDs
- `interactive-query-consoles`: SQL editors for BigQuery/Spanner, Redis CLI for Memorystore, Pub/Sub message publisher
- `structured-log-explorer`: Logs Explorer with severity filtering, JSON expansion, full-text search, and request correlation
- `event-replay-and-chaos`: Event recording/replay and per-service fault injection (error codes, latency, timeouts)
- `change-diff-view`: Unified cross-service diff showing what data changed after an operation
- `developer-onboarding`: SDK code snippets per service, first-run getting-started wizard
- `service-topology`: Auto-generated service dependency graph and architecture diagram

### Modified Capabilities

_None_

## Impact

- **Java server**: New endpoints for request detail, tracing, event recording, fault injection, change diffing
- **Console frontend**: 8 new major UI features across existing and new pages
- **Console backend**: New Flask proxy routes for all new endpoints
- **EventBus**: Add correlation ID propagation
- **RequestLogger**: Capture full request/response bodies (configurable)
