# Logging and Monitoring Workspaces Implementation Plan

> Implements the approved design in `docs/superpowers/specs/2026-08-01-logging-monitoring-workspaces-design.md`.

**Goal:** Replace the database-oriented Logging and Monitoring pages with dedicated, Google Cloud-inspired service workspaces backed by canonical LocalCloud observability data.

**Architecture:** Preserve the existing application shell and unrelated service paths. Route Logging and Monitoring through a new service-aware shell. Add project-scoped observability browse and mutation operations using the existing `/browse` and `/mutate` conventions. Keep display normalization in focused frontend utilities and canonical persistence behavior in repositories.

**Stack:** Java 21, Armeria annotated services, PostgreSQL, Jackson, JUnit 5, SolidJS, esbuild, Node test runner, existing LocalCloud CSS tokens.

## Delivery Order

The work is deliberately sequenced as:

1. Lock response and routing contracts with tests.
2. Build backend read models.
3. Build backend mutations.
4. Add frontend API and route foundations.
5. Add the dedicated shell and pages.
6. Remove the obsolete database paths.
7. Verify the full browser workflow and regressions.

This order keeps every frontend task grounded in a working API contract and defers destructive cleanup until the new workspaces function end to end.

---

## Phase 1: Backend Read Contracts

### Task 1: Add project-scoped Logging browse contracts

**Files**

- Modify: `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java`
- Create: `localcloud-server/src/test/java/com/localcloud/admin/ObservabilityBrowseServiceTest.java`
- Reuse: `localcloud-server/src/test/java/com/localcloud/integration/TestDataSource.java`

**Implementation**

1. Add a focused path for `GET /browse/logging/entries` that can inspect `ServiceRequestContext.queryParams()` without changing browse behavior for unrelated services.
2. Parse and validate:
   - `logName`
   - `minSeverity`
   - `resourceType`
   - supported text query
   - `startTime`
   - `endTime`
   - bounded `limit`
   - opaque cursor
3. Query `log_entries` by resolved project ID.
4. Use stable descending `(timestamp, id)` ordering and a cursor built from both values. Do not use offset pagination.
5. Return all fields needed for expanded rows: `id`, `log_name`, `resource_type`, parsed `resource_labels`, `severity`, `text_payload`, parsed `json_payload`, `timestamp`, and `insert_id`.
6. Return result-derived severity, resource-type, and log-name facet counts.
7. Return fixed interval histogram buckets for the selected time range.
8. Add `GET /browse/logging/sinks`, backed by `LoggingSinkRepository.list(projectId)`.
9. Preserve the existing `/browse/logging` response temporarily until the frontend cutover task removes its caller.

**Tests**

- entries from another project never appear
- each supported filter works alone and in combination
- invalid severity, interval, limit, and cursor return clear client errors
- two entries with the same timestamp paginate without duplication or omission
- resource labels and JSON payloads are objects, not encoded JSON strings
- malformed stored JSON degrades to a safe raw value without failing the response
- facets and histogram reflect the filtered scope
- sink listing is project-scoped

**Verification**

```bash
cd localcloud-server
./gradlew test --tests "com.localcloud.admin.ObservabilityBrowseServiceTest"
```

**Acceptance gate:** The browser API can retrieve complete, filterable log entries and sinks without SQL or schema APIs.

### Task 2: Add project-scoped Monitoring browse contracts

**Files**

- Modify: `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/monitoring/MonitoringAlertPolicyRepository.java`
- Modify: `localcloud-server/src/test/java/com/localcloud/admin/ObservabilityBrowseServiceTest.java`
- Modify: `localcloud-server/src/test/java/com/localcloud/emulators/monitoring/MonitoringAlertPolicyRepositoryTest.java`

**Implementation**

1. Add `GET /browse/monitoring/timeseries`.
2. Accept metric type, resource type, label, start-time, and end-time filters.
3. Read `time_series` and `metric_points` together and return:
   - stable series identity
   - metric type
   - parsed metric labels
   - resource type
   - parsed resource labels
   - ordered typed points
   - latest value and timestamp
   - point count
4. Build the metric catalog from distinct stored metric types. Do not fabricate descriptors that have no stored representation.
5. Add `GET /browse/monitoring/alert-policies` through a new `list(projectId)` repository method.
6. Add `GET /browse/monitoring/notification-channels`, including the count and names of policies that reference each channel.
7. Normalize `conditions_json`, `notification_channels_json`, `documentation_json`, and `labels_json` into JSON values in the response.
8. Keep malformed records visible with a record-level parse warning rather than failing the whole page.

**Tests**

- project isolation for series, points, policies, and channels
- metric, resource, label, and interval filters
- DOUBLE and INT64 values remain distinguishable
- points are chronological inside each series
- latest value and point count are correct
- malformed labels and policy JSON are isolated to the affected record
- channel usage includes every referencing policy

**Verification**

```bash
cd localcloud-server
./gradlew test \
  --tests "com.localcloud.admin.ObservabilityBrowseServiceTest" \
  --tests "com.localcloud.emulators.monitoring.MonitoringAlertPolicyRepositoryTest"
```

**Acceptance gate:** Metrics Explorer, Alerting, and Notification Channels can render from normalized, project-scoped browse responses.

---

## Phase 2: Canonical Mutation Paths

### Task 3: Complete Logging sink repository operations

**Files**

- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/logging/LoggingSinkRepository.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java`
- Modify: `localcloud-server/src/test/java/com/localcloud/emulators/logging/LoggingSinkRepositoryTest.java`
- Create: `localcloud-server/src/test/java/com/localcloud/admin/ObservabilityMutateServiceTest.java`

**Implementation**

1. Separate repository `create` and `update` semantics. Updating a missing sink must not silently create one.
2. Validate sink IDs and non-empty destinations.
3. Preserve writer identity as repository-managed, read-only data.
4. Add Logging dispatch to both MutateService route switches.
5. Implement:
   - `POST /mutate/logging/sinks/create`
   - `POST /mutate/logging/sinks/update`
   - `POST /mutate/logging/sinks/delete`
6. Resolve project only through the existing `_projectId` injection.
7. Return structured success records and clear duplicate, not-found, and validation errors.
8. Keep Logging ConfigService and console mutations on the same repository.

**Tests**

- create, update, and delete lifecycle
- duplicate create fails
- update and delete missing sink fail
- project A cannot modify project B's sink
- writer identity is generated and cannot be overwritten
- ConfigService-created sinks appear in console browse results

**Verification**

```bash
cd localcloud-server
./gradlew test \
  --tests "com.localcloud.emulators.logging.LoggingSinkRepositoryTest" \
  --tests "com.localcloud.admin.ObservabilityMutateServiceTest"
```

**Acceptance gate:** Logs Router CRUD changes the canonical sink records used by the emulator.

### Task 4: Complete Monitoring policy and channel repositories

**Files**

- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/monitoring/MonitoringAlertPolicyRepository.java`
- Create: `localcloud-server/src/main/java/com/localcloud/emulators/monitoring/MonitoringNotificationChannelRepository.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java`
- Modify: `localcloud-server/src/test/java/com/localcloud/emulators/monitoring/MonitoringAlertPolicyRepositoryTest.java`
- Create: `localcloud-server/src/test/java/com/localcloud/emulators/monitoring/MonitoringNotificationChannelRepositoryTest.java`
- Modify: `localcloud-server/src/test/java/com/localcloud/admin/ObservabilityMutateServiceTest.java`

**Implementation**

1. Extend alert-policy persistence to round-trip fields already present in `alert_policies`:
   - display name
   - conditions
   - combiner
   - notification channels
   - documentation
   - enabled state
2. Add explicit create, list, update, and delete semantics. Preserve the existing Monitoring REST facade's policy behavior.
3. Add a channel repository over `notification_channels` with create, list, update, and delete.
4. Validate channel names, types, display names, and JSON labels.
5. Mask sensitive label values only in browse responses, not in canonical storage.
6. Add Monitoring dispatch to both MutateService route switches.
7. Implement policy operations:
   - `POST /mutate/monitoring/alert-policies/create`
   - `POST /mutate/monitoring/alert-policies/update`
   - `POST /mutate/monitoring/alert-policies/delete`
8. Implement channel operations:
   - `POST /mutate/monitoring/notification-channels/create`
   - `POST /mutate/monitoring/notification-channels/update`
   - `POST /mutate/monitoring/notification-channels/delete`
9. Reject channel deletion when any policy references it and return the referencing policy names.

**Tests**

- full policy field round-trip
- full channel field round-trip
- project isolation
- duplicate, missing, and malformed records
- enabled-state updates
- policy references to channels
- referenced-channel deletion guard
- policy deletion releases the channel guard
- policy records created through the existing REST facade appear in console browse results

**Verification**

```bash
cd localcloud-server
./gradlew test \
  --tests "com.localcloud.emulators.monitoring.MonitoringAlertPolicyRepositoryTest" \
  --tests "com.localcloud.emulators.monitoring.MonitoringNotificationChannelRepositoryTest" \
  --tests "com.localcloud.admin.ObservabilityMutateServiceTest"
```

**Acceptance gate:** Alerting and channel UI changes use canonical project-scoped storage with referential guards.

---

## Phase 3: Frontend Contracts and Routing

### Task 5: Add focused console API methods and normalization utilities

**Files**

- Modify: `localcloud-console/src/api.js`
- Create: `localcloud-console/src/utils/observability.js`
- Create: `localcloud-console/test/observability.test.js`
- Modify: `localcloud-console/package.json`

**Implementation**

1. Add named API methods instead of scattering `api.browse` and `api.mutate` calls:
   - `loggingEntries(filters, signal)`
   - `loggingSinks(signal)`
   - `createLoggingSink`, `updateLoggingSink`, `deleteLoggingSink`
   - `monitoringTimeSeries(filters, signal)`
   - `monitoringAlertPolicies(signal)` and policy mutations
   - `monitoringNotificationChannels(signal)` and channel mutations
2. Reuse `appendProject` so every request follows the active project.
3. Add `AbortSignal` support to all browse calls used by interactive filters.
4. Add pure helpers for:
   - query parameter serialization
   - Logging query-subset parsing and display
   - severity ordering
   - timestamp bucket formatting
   - JSON normalization and label display
   - metric series identity and color index
   - DOUBLE and INT64 value formatting
   - channel-label masking
5. Add `npm test` using Node's built-in test runner for pure utilities. Do not introduce a second UI framework or a broad test dependency.

**Tests**

- exact query serialization and escaping
- supported and unsupported Logging query expressions
- severity ordering through EMERGENCY
- stable metric series identity across refreshes
- typed-value formatting
- malformed JSON and masked labels

**Verification**

```bash
cd localcloud-console
npm test
npm run build
```

**Acceptance gate:** Page components receive stable, named API and normalization contracts.

### Task 6: Extract service route definitions and add observability routes

**Files**

- Modify: `localcloud-console/src/app.jsx`
- Create: `localcloud-console/src/utils/serviceRoutes.js`
- Create: `localcloud-console/test/serviceRoutes.test.js`

**Implementation**

1. Extract pure path parsing and building from `app.jsx` so it can be tested without Solid rendering.
2. Define service-aware primary routes:
   - Logging: `logs-explorer`, `log-router`
   - Monitoring: `metrics-explorer`, `alerting`, `notification-channels`
3. Preserve the existing service routes for all other services.
4. Default `/logging` to `/logging/logs-explorer` and `/monitoring` to `/monitoring/metrics-explorer` using replace-state navigation.
5. Preserve `?project=` through direct navigation, tab changes, refresh, back, and forward.
6. Treat old `/logging/editor`, `/logging/db-history`, `/logging/db-stats`, and Monitoring equivalents as clean redirects to the new defaults. Do not retain aliases in rendering code.
7. Keep standalone `/logs` unchanged; it is the LocalCloud runtime log page, not Cloud Logging.

**Tests**

- parse and build every new route
- default redirects
- old database path redirects
- encoded subpaths and project query preservation
- unrelated service paths remain byte-for-byte compatible

**Verification**

```bash
cd localcloud-console
npm test
npm run build
```

**Acceptance gate:** Every approved screen has a direct, refresh-safe URL without changing unrelated navigation.

---

## Phase 4: Shared Workspace Foundation

### Task 7: Add the observability service shell and styles

**Files**

- Create: `localcloud-console/src/components/observability/ObservabilityServiceShell.jsx`
- Create: `localcloud-console/src/data/observability.js`
- Create: `localcloud-console/src/styles/observability.css`
- Modify: `localcloud-console/src/pages/ServiceExplorer.jsx`
- Modify: `localcloud-console/dev.js`
- Modify: `localcloud-console/build.js`

**Implementation**

1. Define per-service tab metadata, default routes, labels, and utility actions in one data module.
2. Build semantic `tablist`, `tab`, and `tabpanel` behavior.
3. Move Settings, Remote Sync, and Guide into service-header utilities or a responsive overflow menu.
4. Keep the current LocalCloud service icon, title, description, project context, themes, and tokens.
5. Add a narrow dispatch near the top of `ServiceExplorer`: Logging and Monitoring render `ObservabilityServiceShell`; Workflows and standard services follow existing paths unchanged.
6. Add `observability.css` to both development and production CSS manifests.
7. Implement responsive behavior:
   - utilities collapse first
   - tabs remain horizontally scrollable when needed
   - split panes stack at narrow widths
   - tables use deliberate horizontal overflow
8. Implement common skeleton, empty, inline error, status announcement, and destructive confirmation patterns.

**Verification**

```bash
cd localcloud-console
npm test
npm run build
```

Browser check:

- direct navigation to each tab
- keyboard arrow, Home, and End behavior
- visible focus in light and dark themes
- utilities remain reachable at narrow width

**Acceptance gate:** Dedicated pages can mount in a stable, accessible shell without any database tabs.

---

## Phase 5: Cloud Logging Workspace

### Task 8: Implement Logs Explorer

**Files**

- Create: `localcloud-console/src/components/observability/logging/LogsExplorer.jsx`
- Create: `localcloud-console/src/components/observability/logging/LogQueryToolbar.jsx`
- Create: `localcloud-console/src/components/observability/logging/LogFieldsPanel.jsx`
- Create: `localcloud-console/src/components/observability/logging/LogHistogram.jsx`
- Create: `localcloud-console/src/components/observability/logging/LogResults.jsx`
- Modify: `localcloud-console/src/styles/observability.css`

**Implementation**

1. Build one page-level state owner for query, structured filters, time range, cursor, result, and selected histogram interval.
2. Abort the prior request on query, project, route, or interval replacement.
3. Protect state with a monotonically increasing request token so an ignored abort cannot write stale data.
4. Implement structured filters and the documented LocalCloud query subset. Unsupported syntax produces an inline validation message before request dispatch.
5. Render result facets from the server response.
6. Render a semantic SVG histogram with keyboard-selectable buckets and a textual summary.
7. Render compact rows with inline expansion for full payload and metadata.
8. Preserve query state in URL search parameters without dropping the global project parameter.
9. Implement loading, no-entry, no-match, persistence-disabled, validation-error, and request-error states.
10. Do not add live streaming, saved queries, sharing, full LQL, Log Analytics, or log storage controls.

**Browser verification**

- seeded entries render with text and JSON payloads
- severity, log-name, resource, text, and time filters combine correctly
- histogram narrowing updates results
- row expansion is keyboard operable
- browser refresh preserves the query
- project switch aborts the old request and shows only the new project
- empty and error states preserve filter controls

**Acceptance gate:** Logs Explorer supports a complete local investigation workflow with no database concepts.

### Task 9: Implement Logs Router

**Files**

- Create: `localcloud-console/src/components/observability/logging/LogsRouter.jsx`
- Create: `localcloud-console/src/components/observability/logging/SinkEditor.jsx`
- Modify: `localcloud-console/src/styles/observability.css`

**Implementation**

1. Render sink name, destination, and read-only writer identity.
2. Provide create and edit in a side panel or focused inline region.
3. Validate sink ID and destination before mutation.
4. Confirm deletion with the exact sink name.
5. Refresh only the sink list after successful mutations.
6. Announce mutation completion and keep focus on the originating action or logical successor.
7. Omit inclusion and exclusion filters until routing semantics support them.

**Browser verification**

- empty state teaches supported destinations
- create, edit, and delete round-trip through the canonical repository
- duplicate and missing sink errors remain inline
- keyboard and narrow-width flows remain usable

**Acceptance gate:** Logs Router manages real LocalCloud sinks without implying unsupported routing controls.

---

## Phase 6: Cloud Monitoring Workspace

### Task 10: Implement Metrics Explorer

**Files**

- Create: `localcloud-console/src/components/observability/monitoring/MetricsExplorer.jsx`
- Create: `localcloud-console/src/components/observability/monitoring/MetricSelector.jsx`
- Create: `localcloud-console/src/components/observability/monitoring/MetricChart.jsx`
- Create: `localcloud-console/src/components/observability/monitoring/MetricSeriesTable.jsx`
- Modify: `localcloud-console/src/styles/observability.css`

**Implementation**

1. Build metric type, resource type, label, and interval selectors from the browse contract.
2. Use one page-level request owner with abort and stale-response guards.
3. Render DOUBLE and INT64 points on a responsive SVG line chart.
4. Use stable series identities for color, legend, and table ordering.
5. Pair every color with a marker or line pattern and readable legend label.
6. Provide Chart, Table, and Both modes.
7. Make chart points keyboard inspectable and expose the same values in the table.
8. Do not interpolate across missing intervals.
9. Implement no-metrics, no-points-in-interval, malformed-series, loading, and request-error states.
10. Omit dashboards, aggregation, PromQL, MQL, and alert-from-chart shortcuts.

**Browser verification**

- multiple series render with stable identities
- chart and table agree on values and timestamps
- interval and label filters work
- keyboard point inspection announces series, timestamp, and value
- project switch cannot show stale series
- Chart, Table, and Both survive refresh when encoded in URL state

**Acceptance gate:** Metrics Explorer visualizes real metric points with an equivalent accessible table.

### Task 11: Implement Alerting

**Files**

- Create: `localcloud-console/src/components/observability/monitoring/AlertPolicies.jsx`
- Create: `localcloud-console/src/components/observability/monitoring/AlertPolicyEditor.jsx`
- Modify: `localcloud-console/src/styles/observability.css`

**Implementation**

1. Render display name, enabled state, combiner, condition summary, selected channels, and creation time.
2. Build a focused editor with:
   - display name
   - enabled state
   - combiner
   - notification-channel selection
   - documentation
   - structured common condition fields plus validated JSON fallback
3. Validate JSON before mutation and retain the user's draft on errors.
4. State clearly that LocalCloud stores API-compatible policy configuration but does not evaluate incidents or deliver notifications.
5. Confirm deletion with the exact policy name.
6. Refresh policy and channel-usage data after successful mutations.

**Browser verification**

- create, edit, enable or disable, and delete
- malformed condition JSON remains editable with a precise error
- selected channels round-trip
- list summaries reflect updated policy data
- no copy claims incident evaluation or delivery

**Acceptance gate:** Alerting manages canonical policy configuration with honest emulator capability boundaries.

### Task 12: Implement Notification Channels

**Files**

- Create: `localcloud-console/src/components/observability/monitoring/NotificationChannels.jsx`
- Create: `localcloud-console/src/components/observability/monitoring/NotificationChannelEditor.jsx`
- Modify: `localcloud-console/src/styles/observability.css`

**Implementation**

1. Render display name, type, enabled state, masked labels, and policy usage.
2. Support create and edit for generic fields represented by LocalCloud storage.
3. Keep sensitive label values write-only after initial entry. Never render full secrets back into the form.
4. Confirm deletion and surface referencing policies when deletion is blocked.
5. Refresh policy usage after policy or channel changes.
6. Group or filter by channel type without implying channel delivery support.

**Browser verification**

- create, edit, enable or disable, and delete an unused channel
- stored sensitive values remain masked
- referenced channel deletion is blocked with policy names
- deletion succeeds after the final reference is removed

**Acceptance gate:** Notification Channels provides safe CRUD and accurate dependency feedback.

---

## Phase 7: Clean Cutover

### Task 13: Remove obsolete database integration for Logging and Monitoring

**Files**

- Modify: `localcloud-console/src/data/services.js`
- Modify: `localcloud-console/src/pages/ServiceExplorer.jsx`
- Modify: `localcloud-console/src/pages/DataBrowser.jsx`
- Modify: `localcloud-console/src/utils/databaseContext.js` only if observability-specific branches remain
- Modify: `localcloud-console/src/styles/components.css` only for selectors made dead by this cutover

**Implementation**

1. Remove Logging and Monitoring from `SQL_SERVICES`.
2. Remove their static table schemas from `SERVICE_SCHEMAS` when no remaining consumer requires them.
3. Remove `LoggingView` and `MonitoringView` from `DataBrowser.jsx` and their dispatch cases.
4. Remove any observability-specific SQL placeholders, history, stats, schema-tree, or database-context code.
5. Do not leave compatibility aliases, hidden tabs, commented JSX, or dead exports.
6. Preserve service metadata, icons, settings, guide content, reset behavior, and Remote Sync.
7. Confirm `DataBrowser` and `SQLEditor` still behave identically for their remaining services.

**Verification**

```bash
cd localcloud-console
npm test
npm run build
```

Static checks:

- no Logging or Monitoring membership in `SQL_SERVICES`
- no dedicated Logging or Monitoring view remains in `DataBrowser.jsx`
- no new workspace imports database helpers

**Acceptance gate:** There is one observability UI path per service and no retained database fallback.

---

## Phase 8: Integrated Verification and Cleanup

### Task 14: Run focused backend regression suites

**Files**

- No planned production edits unless a verified regression is found.

**Commands**

```bash
cd localcloud-server
./gradlew test \
  --tests "com.localcloud.admin.ObservabilityBrowseServiceTest" \
  --tests "com.localcloud.admin.ObservabilityMutateServiceTest" \
  --tests "com.localcloud.emulators.logging.LoggingSinkRepositoryTest" \
  --tests "com.localcloud.emulators.monitoring.MonitoringAlertPolicyRepositoryTest" \
  --tests "com.localcloud.emulators.monitoring.MonitoringNotificationChannelRepositoryTest" \
  --tests "com.localcloud.integration.GrpcTranscodingIntegrationTest"
```

Then run the complete server suite once:

```bash
cd localcloud-server
./gradlew test
```

**Acceptance gate:** New contracts and existing gRPC or REST observability surfaces pass together.

### Task 15: Run console build and real-browser smoke scenarios

**Files**

- No planned production edits unless browser verification finds a real defect.

**Commands**

```bash
cd localcloud-console
npm test
npm run build
```

Start LocalCloud with seeded data covering:

- text and JSON log entries across severities, resources, log names, projects, and timestamps
- two logging sinks
- multiple time series with DOUBLE and INT64 points
- enabled and disabled alert policies
- referenced and unreferenced notification channels

Exercise in a real browser:

1. Visit every new URL directly and refresh.
2. Use browser back and forward across service tabs.
3. Switch projects on every page and verify isolation.
4. Complete all Logging filters and sink mutations.
5. Compare Monitoring chart and table values.
6. Complete policy and channel mutations, including the channel reference guard.
7. Verify loading, empty, malformed-record, API-error, and persistence-disabled states.
8. Verify keyboard-only use at desktop and narrow widths.
9. Verify light and dark themes.
10. Visit representative unrelated services: BigQuery SQL Editor, Spanner Data Explorer, Pub/Sub, Workflows, and Settings.

**Acceptance gate:** The approved workflows operate end to end, are visually correct, and do not regress unrelated services.

### Task 16: Final cleanup and documentation sync

Only after Task 15 passes:

1. Remove obsolete selectors, imports, helpers, and comments proven unused by the cutover.
2. Update user-facing service guidance only where it still refers to Data Explorer or SQL for Logging and Monitoring.
3. Update compatibility documentation only if the new console surfaces expose a previously undocumented API capability.
4. Stop development servers and remove temporary visual-companion artifacts from the active session if they are not intended for source control.
5. Perform one final build after cleanup.

**Acceptance gate:** No temporary scaffolding, stale copy, dead observability code, or untracked generated output remains.

---

## Cross-Task Invariants

- Every read and mutation is scoped by the active project.
- The console never accesses observability tables through SQL Editor or schema APIs.
- Repository-backed API and console behavior share the same canonical rows.
- Stale requests cannot overwrite state after project, route, filter, or interval changes.
- Sensitive notification-channel labels are never returned unmasked to the UI.
- Unsupported Google Cloud capabilities are omitted rather than simulated with placeholders.
- Logging and Monitoring routes are direct-linkable and browser-history safe.
- Chart information always has a non-visual equivalent.
- Existing non-observability service behavior is preserved.

## Definition of Done

The implementation is complete when all approved acceptance criteria are demonstrated in a running browser, the focused and full server suites pass, the console tests and production build pass, old database paths are removed, and the new workspaces use canonical project-scoped observability storage end to end.
