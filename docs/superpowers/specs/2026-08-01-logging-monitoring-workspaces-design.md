# Logging and Monitoring Service Workspaces

**Status:** Approved visual direction
**Date:** 2026-08-01
**Scope:** Cloud Logging and Cloud Monitoring service pages in the LocalCloud console

## Problem

Cloud Logging and Cloud Monitoring currently pass through the shared `ServiceExplorer` database workspace because both services are listed in `SQL_SERVICES`. This gives observability services database-oriented navigation and concepts:

- SQL Editor
- Data Explorer
- query History
- database Stats
- schema and table affordances

The dedicated `LoggingView` and `MonitoringView` inside `DataBrowser.jsx` only render shallow tables. Logging receives up to 100 entries with timestamp, severity, log name, and text payload. Monitoring receives time-series metadata without metric points. The UI therefore exposes the persistence implementation instead of the service users expect.

## Users and Context

The primary users are backend developers and platform or DevOps engineers inspecting a local GCP-compatible environment during development, CI troubleshooting, and demos. They need a familiar Google Cloud task model without unsupported controls or production-scale complexity.

The UI is a product surface. It should remain restrained, dense, direct, and consistent with the existing LocalCloud shell and token system. The design target is production-ready, desktop-first, responsive, and WCAG AA.

## Goals

1. Replace all database terminology and database-only tabs for Logging and Monitoring.
2. Give each service a dedicated, Google Cloud-inspired workspace.
3. Surface observability capabilities already represented by LocalCloud storage, repositories, or emulator APIs.
4. Add focused admin API gaps needed by the console without expanding into unsupported product areas.
5. Preserve LocalCloud project isolation, service reset, Remote Sync, settings, and user guidance.
6. Use explicit loading, empty, error, disabled, and destructive-action states.

## Non-goals

This change does not add:

- Observability Analytics or Log Analytics
- log bucket or log view management
- Monitoring dashboards
- uptime checks
- incident evaluation or notification delivery
- PromQL, MQL, advanced alignment, or aggregation
- production-scale retention, pagination guarantees, or IAM simulation
- Logging exclusion management, because current exclusions are project-wide and do not yet match Google Cloud sink-level routing semantics

Unsupported features must be omitted, not represented by disabled controls or placeholders.

## Chosen Approach

Use **service-specific workspaces** inside the existing LocalCloud application shell.

This approach preserves the current global service navigation and project selector, while replacing the shared database tab strip with service-aware primary navigation. It provides strong Google Cloud familiarity without introducing a new cross-service Observability hub or destabilizing navigation for unrelated services.

### Utility actions

Settings, Remote Sync, and Guide remain available, but move out of the primary task tabs into service-header utility actions or an overflow menu. They are LocalCloud utilities, not core Logging or Monitoring destinations.

## Information Architecture

### Cloud Logging

Primary routes:

- `/logging/logs-explorer`
- `/logging/log-router`

Utility destinations:

- Settings
- Remote Sync
- Guide

Default route: `/logging/logs-explorer`

### Cloud Monitoring

Primary routes:

- `/monitoring/metrics-explorer`
- `/monitoring/alerting`
- `/monitoring/notification-channels`

Utility destinations:

- Settings
- Remote Sync
- Guide

Default route: `/monitoring/metrics-explorer`

### Routing behavior

`app.jsx` should recognize the new service route keys and preserve `?project=`. Direct navigation, refresh, browser back, and browser forward must restore the selected service screen. Logging and Monitoring must never resolve to `editor`, `db-history`, or `db-stats`. Existing service routes remain unchanged for other services.

## Screen Design

## Logging: Logs Explorer

The page follows the functional hierarchy of Google Cloud Logs Explorer while fitting the LocalCloud shell.

### Primary toolbar

- project scope inherited from the global selector
- time-range selector
- refresh action
- structured filters for log name, minimum severity, and resource type
- query input for the subset LocalCloud supports
- Run query action

The supported query subset must be stated through autocomplete or helper text. The UI must not imply full Logging Query Language compatibility.

### Workspace

- collapsible Fields pane with result-derived facets for severity, resource type, and log name
- timestamp histogram for the selected interval
- result count
- expandable log rows
- row summary: severity, timestamp, log name, and payload preview
- expanded detail: text payload, JSON payload, resource type and labels, insert ID, and full log name

### States

- loading: skeleton query results and histogram
- empty: explain that SDK-written entries appear here and preserve the active query
- query error: inline error adjacent to the query controls
- persistence disabled: explicit service-level explanation
- stale request: prior responses must not overwrite newer queries

### Interaction rules

- changing a structured filter updates the query state but does not fetch until Run query, except time range and refresh may rerun the last query
- histogram selection narrows the active time interval
- facets are derived from the current result scope
- row expansion is inline, not modal
- query state is encoded in the URL when practical so refresh and sharing preserve the investigation

## Logging: Logs Router

The page exposes the sink functionality already implemented by `LoggingSinkRepository` and the Logging ConfigService.

### Content

- sink list with name, destination, and writer identity
- create sink action
- edit sink action
- delete sink action with confirmation
- empty state that explains supported destination syntax

### Constraints

Only fields supported by LocalCloud are editable. Inclusion and exclusion filters are not shown until their backend semantics match Google Cloud routing. The writer identity is read-only.

## Monitoring: Metrics Explorer

The page follows Google Cloud Metrics Explorer's task order: select data, inspect chart, inspect table.

### Query controls

- metric type selector populated from stored time series and metric descriptors
- resource type selector
- metric-label filters
- time-range selector
- chart display toggle: Chart, Table, Both
- refresh action

Advanced aggregation, PromQL, and MQL controls are omitted.

### Visualization

- responsive line chart for double and INT64 points
- one series per unique metric and label set
- legend with stable colors and accessible non-color identifiers
- hover or keyboard point inspection
- empty interval treatment that does not interpolate misleading values
- table with metric type, resource type, labels, latest value, latest timestamp, and point count

### States

- loading skeleton for selectors and chart
- no metrics in project
- selected metric has no points in interval
- malformed stored labels or values
- request error with retry

## Monitoring: Alerting

### Policy list

- Create policy primary action
- display name
- enabled state
- combiner
- concise condition summary
- associated notification channels
- created timestamp
- edit and delete actions

### Policy editor

Use a focused page or side panel, not a multi-layer modal. The editor exposes only fields persisted by LocalCloud:

- display name
- enabled state
- condition JSON with structured common fields where possible and a validated JSON fallback
- combiner
- notification-channel selection
- documentation text when supported by the stored policy record

The UI must not claim that LocalCloud evaluates incidents or sends notifications. Copy should describe these records as API-compatible configuration used for local development and tests.

## Monitoring: Notification Channels

### Channel list

- group or filter by channel type
- display name
- type
- enabled state
- labels summary with sensitive values masked
- policy usage count
- edit and delete actions

### Channel editor

Expose only generic fields represented in LocalCloud storage:

- display name
- channel type
- labels
- enabled state

Deletion is blocked when a policy references the channel. The error must identify the referencing policies.

## Backend and Admin API Design

Use existing `/browse/{service}` and `/mutate/{service}/{operation}` conventions. Do not create a second console-only REST style.

### Logging browse operations

`GET /browse/logging/entries`

Query parameters:

- `logName`
- `minSeverity`
- `resourceType`
- `query`
- `startTime`
- `endTime`
- `limit`
- opaque cursor

Response:

- complete display-safe entry records
- histogram buckets
- facet counts
- result count for the returned scope
- next cursor

Pagination should use the stable `(timestamp, id)` ordering instead of offset pagination.

`GET /browse/logging/sinks`

Response:

- sink name
- destination
- writer identity

### Logging mutation operations

- `POST /mutate/logging/sinks/create`
- `POST /mutate/logging/sinks/update`
- `POST /mutate/logging/sinks/delete`

These operations delegate to the existing sink repository so gRPC ConfigService and console changes share storage and behavior.

### Monitoring browse operations

`GET /browse/monitoring/timeseries`

Query parameters:

- `metricType`
- `resourceType`
- label filters
- `startTime`
- `endTime`

Response:

- descriptor catalog
- normalized label objects
- time-series identity
- ordered points with typed values
- latest value and point count

`GET /browse/monitoring/alert-policies`

Returns all project policies using the canonical `alert_policies` table.

`GET /browse/monitoring/notification-channels`

Returns channels plus policy usage information.

### Monitoring mutation operations

- `POST /mutate/monitoring/alert-policies/create`
- `POST /mutate/monitoring/alert-policies/update`
- `POST /mutate/monitoring/alert-policies/delete`
- `POST /mutate/monitoring/notification-channels/create`
- `POST /mutate/monitoring/notification-channels/update`
- `POST /mutate/monitoring/notification-channels/delete`

Alert-policy operations delegate to `MonitoringAlertPolicyRepository`. Notification-channel operations should use one repository shared by console mutations and future API surfaces. All operations resolve project context through the existing admin request mechanism.

## Frontend Component Boundaries

Create dedicated components rather than adding more service branches to the existing 193 KB `ServiceExplorer.jsx` or 152 KB `DataBrowser.jsx`.

Recommended boundaries:

- `ObservabilityServiceShell`: service header, primary tabs, utilities, route synchronization
- `LogsExplorer`: query state and page composition
- `LogQueryToolbar`: query and structured filter controls
- `LogFieldsPanel`: facets
- `LogHistogram`: interval distribution and selection
- `LogResults`: expandable entries
- `LogsRouter`: sink list and CRUD flow
- `MetricsExplorer`: query state and page composition
- `MetricSelector`: metric, resource, label, and interval controls
- `MetricChart`: visualization and accessible point inspection
- `MetricSeriesTable`: tabular alternative
- `AlertPolicies`: policy list and editor flow
- `NotificationChannels`: channel list and editor flow

Pure normalization and formatting logic belongs in focused utility modules so it can be tested without rendering the entire workspace.

## Data Flow

1. `app.jsx` parses the service route and selected project.
2. `ServiceExplorer` delegates Logging and Monitoring to `ObservabilityServiceShell`; other services continue through existing behavior.
3. The shell selects the dedicated page component from the service route definition.
4. Page components call focused methods in `api.js`.
5. BrowseService resolves project context and returns normalized JSON contracts.
6. MutateService validates and delegates writes to the canonical repositories or tables.
7. Successful mutations refresh only the affected list and announce completion through an accessible status region.
8. Request tokens or abort controllers prevent stale fetches after project, route, filter, or interval changes.

## Visual Direction

- Preserve the existing LocalCloud light and dark themes and current typography.
- Use the restrained color strategy already present in the console.
- Use accent color for active navigation, primary actions, selection, and chart series, not decoration.
- Prefer panes, toolbars, tables, and split workspaces over card grids.
- Keep information density appropriate for developers investigating logs and metrics.
- Use 150 to 250 ms state transitions only where they clarify expansion, collapse, or selection.
- Never encode severity, enabled state, or chart identity by color alone.

The physical scene is a developer or platform engineer inspecting a failing local integration on a desktop monitor during a focused debugging session. The existing theme selection remains authoritative rather than forcing light or dark mode.

## Accessibility and Responsive Behavior

- Full keyboard navigation for tabs, query controls, facets, table rows, expanded details, and chart points.
- Visible focus states using existing tokens.
- Semantic tabs and tab panels with correctly linked ARIA attributes.
- Tables retain headers and meaningful row actions.
- Chart data always has a table representation.
- At narrower widths, Fields collapses above results, toolbars wrap, and tables gain deliberate horizontal scrolling.
- Service utilities collapse into an overflow menu before primary task tabs do.
- Loading and mutation completion use live regions without stealing focus.

## Verification Strategy

### Backend contract tests

- project isolation for every browse and mutation operation
- logging severity, log-name, resource, text, and time-bound filters
- stable logging cursor behavior at equal timestamps
- histogram and facet correctness
- sink CRUD visible through both repository and console endpoints
- time-series point ordering and DOUBLE versus INT64 normalization
- malformed labels handled without failing the whole response
- alert-policy CRUD through canonical storage
- notification-channel reference guard
- reset behavior reflected in all new endpoints

### Frontend behavior tests

- route parsing and direct navigation for every new service page
- Logging and Monitoring never render SQL Editor, database History, or database Stats
- query controls produce the expected request contract
- stale responses cannot overwrite current project or query state
- chart/table/both display modes
- policy and channel CRUD success and error states
- keyboard behavior for tabs, expandable rows, dialogs or side panels, and chart inspection

### Smoke verification

Run LocalCloud with seeded logs, metric points, sinks, policies, and channels. In a real browser:

1. Navigate directly to every new URL and refresh it.
2. Switch projects and verify data isolation.
3. Filter logs and inspect expanded text and JSON payloads.
4. Create, edit, and delete a sink.
5. Select a metric and interval, inspect chart points, and compare the table.
6. Create, edit, disable, and delete an alert policy.
7. Create and edit a notification channel; verify referenced channels cannot be deleted.
8. Verify keyboard operation, light and dark themes, loading, empty, and error states.
9. Confirm unrelated service workspaces retain their current routes and behavior.

## Acceptance Criteria

- Logging and Monitoring expose no database-oriented tabs, labels, schema trees, or SQL controls.
- Each service opens its dedicated default route and supports direct links, refresh, and browser history.
- Logs Explorer supports the LocalCloud query subset, facets, histogram, expandable entries, and interval selection.
- Logs Router supports sink CRUD through canonical storage.
- Metrics Explorer charts real stored metric points and provides an equivalent table.
- Alerting supports policy CRUD without claiming incident evaluation.
- Notification Channels supports CRUD and prevents deletion while referenced.
- Project isolation, stale-request protection, accessibility, responsive behavior, and all defined states are verified.
- Existing non-observability service pages remain unchanged.

## Research References

- [Google Cloud Logs Explorer interface](https://docs.cloud.google.com/logging/docs/view/logs-explorer-interface)
- [Google Cloud log routing and sinks](https://docs.cloud.google.com/logging/docs/export/configure_export_v2)
- [Google Cloud Metrics Explorer](https://docs.cloud.google.com/monitoring/charts/metrics-explorer)
- [Google Cloud metric-threshold alerting UI](https://docs.cloud.google.com/monitoring/alerts/using-alerting-ui)
- [Google Cloud notification channels](https://docs.cloud.google.com/monitoring/support/notification-options)
