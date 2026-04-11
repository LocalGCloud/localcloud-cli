## 1. Request Inspector — See Everything

- [ ] 1.1 Extend `RequestLogger.RequestLogEntry` to capture `requestHeaders`, `requestBody`, `responseHeaders`, `responseBody` (byte arrays, max 1MB each). Add config flag `localcloud.log.capture-bodies`.
- [ ] 1.2 Modify `RequestLogger` middleware to intercept and store request/response bodies using Armeria's content preview or aggregation
- [ ] 1.3 Add `GET /_localcloud/requests/{id}` endpoint returning full request detail (headers, bodies, timing breakdown)
- [ ] 1.4 Add Flask proxy route `GET /api/requests/<id>` proxying to the detail endpoint
- [ ] 1.5 Create `RequestDetailDrawer` component in the Logs page — slide-out panel showing: method, URL, status, timing, tabbed view for Request Headers / Request Body / Response Headers / Response Body. JSON bodies syntax-highlighted with collapsible sections.
- [ ] 1.6 Add "Copy as cURL" button to the detail drawer — generates valid cURL command from method, URL, headers, body
- [ ] 1.7 Add "Replay" button to the detail drawer — re-sends the request via `POST /api/requests/<id>/replay`, shows new response alongside original

## 2. Cross-Service Tracing — Follow the Flow

- [ ] 2.1 Generate UUID `traceId` in the API gateway for each incoming request. Store in `RequestLogEntry`. Pass as `X-Trace-Id` header on proxied requests.
- [ ] 2.2 Propagate `traceId` through `EventBus` events — add traceId field to event payloads so downstream handlers inherit the trace
- [ ] 2.3 Add `GET /_localcloud/traces/{traceId}` endpoint returning all request log entries and events with the given traceId, ordered by timestamp
- [ ] 2.4 Add Flask proxy route and frontend `TraceView` component — waterfall timeline showing operations as horizontal bars, connected by arrows, with service labels and duration
- [ ] 2.5 Add WebSocket endpoint `/_localcloud/ws/logs` that pushes new request entries in real-time. Flask SSE endpoint `GET /api/stream/logs` as alternative.
- [ ] 2.6 Update Logs page to use WebSocket/SSE instead of 3-second polling. Keep polling as fallback.

## 3. Interactive Query Consoles — Talk to Your Data

- [ ] 3.1 Add `POST /_localcloud/query/bigquery` endpoint — accepts `{sql, dataset}`, executes via BigQuery `jobs.query` API, returns `{columns, rows, error, executionTimeMs}`
- [ ] 3.2 Add `POST /_localcloud/query/spanner` endpoint — accepts `{sql, instance, database}`, creates session, executes SQL, returns results, deletes session
- [ ] 3.3 Add `POST /_localcloud/query/memorystore` endpoint — accepts `{command}`, executes via Redis RESP, returns formatted response
- [ ] 3.4 Add Flask proxy routes for all three query endpoints
- [ ] 3.5 Create `QueryConsole` component — code editor textarea with monospace font, Execute button, results table below, error display, query history (localStorage). Tab selector for BigQuery/Spanner/Memorystore.
- [ ] 3.6 Add `QueryConsole` as a new tab or mode in the Data Browser for BigQuery, Spanner, and Memorystore
- [ ] 3.7 Add Pub/Sub message publisher with template suggestions — topic selector, JSON data textarea with validation, attributes key-value editor, Publish button
- [ ] 3.8 Add schema sidebar to Spanner query console — show tables, columns, types extracted from DDL

## 4. Structured Log Explorer — Find the Needle

- [ ] 4.1 Add `GET /_localcloud/logs` endpoint with query params: `severity`, `search`, `logName`, `since`, `until`, `limit`. Returns structured log entries from PostgreSQL.
- [ ] 4.2 Add Flask proxy route `GET /api/logs` with same params
- [ ] 4.3 Create `LogExplorer` component — replaces or extends the current Logs page with: severity filter buttons (DEBUG/INFO/WARNING/ERROR), search input, time-range selector (5m/1h/24h/custom), log entry list with expandable JSON payloads
- [ ] 4.4 Add severity distribution mini-chart above log entries — bar chart showing count per severity in the selected time range
- [ ] 4.5 Add clickable trace ID in each log entry — navigates to the trace view (from Story 2)
- [ ] 4.6 Add log-name grouping/filtering — collapsible groups or dropdown filter by log name

## 5. Event Replay and Chaos Testing — Break Things Safely

- [ ] 5.1 Add `EventRecorder` to EventBus — records all events with type, source, payload, timestamp, traceId into a bounded list (last 500 events)
- [ ] 5.2 Add `GET /_localcloud/events` endpoint returning recorded events. Add `POST /_localcloud/events/{id}/replay` to re-emit an event.
- [ ] 5.3 Create `EventHistory` panel in the console — list of recent events with type badges, source service, timestamp, payload preview. "Replay" button per event.
- [ ] 5.4 Create `FaultInjector` gateway middleware — intercepts requests before routing. Checks fault config per service. Returns configured error code or adds delay.
- [ ] 5.5 Add `POST /_localcloud/faults` (set fault config), `GET /_localcloud/faults` (list active), `DELETE /_localcloud/faults` (clear all) admin endpoints
- [ ] 5.6 Create `FaultInjection` panel in console — per-service toggles for error code (dropdown: 500/503/429/408), latency (slider: 0-5000ms), count (next N or unlimited). Show active faults as a warning banner.
- [ ] 5.7 Add `POST /_localcloud/snapshots` (create named snapshot), `GET /_localcloud/snapshots` (list), `POST /_localcloud/snapshots/{name}/restore` (restore). Snapshots save seed YAML + current PostgreSQL state.
- [ ] 5.8 Create `Snapshots` section in Settings page — list snapshots, create new, restore, delete

## 6. What Changed? — Diff View

- [ ] 6.1 Add `MutationTracker` class that records write operations per service when recording is active. Track: service, operation (create/update/delete), resource identifier, timestamp.
- [ ] 6.2 Add `POST /_localcloud/diff/start`, `POST /_localcloud/diff/stop` (returns changes), `GET /_localcloud/diff/status` admin endpoints
- [ ] 6.3 Create `ChangeDiff` component — toggle button in toolbar "Record Changes". On stop, shows grouped summary: per-service counts ("+3 documents", "-1 row", "5 messages published") with expandable details.
- [ ] 6.4 Add "before snapshot" capture at recording start — count records per service so the diff can show net changes

## 7. Developer Onboarding — SDK Snippets and Getting Started

- [ ] 7.1 Create `sdkSnippets.js` — static JSON mapping `{service, language}` to code snippet templates. Cover Python, Java, Go, Node.js for: GCS, Pub/Sub, Firestore, BigQuery, Spanner, Secret Manager, Memorystore.
- [ ] 7.2 Add "Code" tab to each service in Data Browser — language selector tabs (Python/Java/Go/Node.js), copy button, snippet display with env var instructions
- [ ] 7.3 Create `GettingStartedWizard` component — 4-step overlay: (1) service health check, (2) env var copy, (3) run sample code, (4) verify in logs. Dismissible, remembered in localStorage. Accessible from Settings.
- [ ] 7.4 Add notification bell icon to console header — unread count badge. Panel shows: service health changes, seed loaded, reset performed, faults active, errors.
- [ ] 7.5 Add `GET /_localcloud/notifications` endpoint returning recent system events (service state changes, seed operations, resets)

## 8. Service Topology — See the Architecture

- [ ] 8.1 Add `GET /_localcloud/events/subscriptions` endpoint returning EventBus subscription map: which event types are subscribed by which handlers/services
- [ ] 8.2 Create `Topology` page — SVG-based graph visualization showing services as nodes (circles with icon/name/health), connections as directed arrows with event type labels
- [ ] 8.3 Add click-to-inspect on connections — panel showing recent events on that edge, message counts, last event time
- [ ] 8.4 Add "Copy Architecture" button — generates a formatted text summary of all services, ports, protocols, env vars, and connections
- [ ] 8.5 Add service health timeline — horizontal timeline per service showing healthy/unhealthy transitions over the session, with timestamps on hover
- [ ] 8.6 Add Topology page to the main navigation bar in `app.jsx`
