## Why

The Data Browser currently has a split layout: a 200px left explorer panel listing 14 services inside the page, plus the main sidebar (240px) — consuming ~440px for navigation and leaving limited space for data tables, especially on smaller screens. GCP Console solves this by putting product navigation in the sidebar itself. Moving the service list into the main sidebar when Data Browser is active gives the content panel full width for tables, forms, and drill-down views.

## What Changes

- When "Data Browser" is selected in the sidebar, it expands into a collapsible section showing all 14 services as sub-items (with GCP icons and health dots)
- Clicking a service in the sidebar navigates to `/data/{service}` and loads that service's content in the full-width main area
- The in-page explorer panel (`db-explorer`) is removed — the sidebar IS the explorer
- The content area gains the full width previously split between explorer panel and content
- The service header bar (icon + name + Refresh/Reset) moves to the top of the content area
- Other nav pages (Dashboard, Services, Logs, Usage, Settings) remain unchanged

## Capabilities

### New Capabilities
- `sidebar-service-navigation`: Expandable service sub-navigation in the sidebar when Data Browser is active, with GCP icons, health dots, and active state highlighting

### Modified Capabilities

## Impact

- **Console layout CSS** (`layout.css`): Sidebar needs expandable section styles for sub-items with indentation
- **Console app** (`app.jsx`): Sidebar nav logic changes — Data Browser click expands sub-items, service click sets active service
- **DataBrowser page** (`DataBrowser.jsx`): Remove `db-explorer` grid layout, render only the content panel at full width
- **Components CSS** (`components.css`): Remove `db-explorer-*` classes, add sidebar sub-item styles
- No backend/API changes required
