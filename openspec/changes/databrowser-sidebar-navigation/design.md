## Context

The Data Browser currently uses a two-panel layout: a 200px left explorer listing 14 services + a content area. Combined with the 240px main sidebar, ~440px is consumed by navigation. The content area for tables and forms is cramped, especially for services like BigQuery and Spanner that have multi-level drill-downs.

The proposal moves the service list into the main sidebar as expandable sub-items under "Data Browser", giving the content panel full width.

## Goals / Non-Goals

**Goals:**
- Data Browser content panel uses full available width
- Service navigation lives in the sidebar as collapsible sub-items
- Sidebar collapses to icon-only at 900px breakpoint (sub-items hidden)
- URL routing preserved (`#/data/gcs`, `#/data/spanner`, etc.)
- Health dots and GCP icons retained on service sub-items

**Non-Goals:**
- Nested sub-navigation for service drill-downs (e.g., GCS bucket → objects) — these stay in the content area
- Changing other nav pages (Dashboard, Services, Logs, Usage, Settings)
- Changing the sidebar width

## Decisions

### 1. Expandable section pattern in sidebar

**Decision**: When "Data Browser" is the active page, a sub-item list appears below it in the sidebar, indented with smaller font. Clicking "Data Browser" toggles the section open/closed. Clicking a service sub-item selects it.

**Why**: Matches GCP Console's pattern where clicking a product category expands sub-items. Users see all services without leaving the page context.

**Structure**:
```
  Dashboard
  APIs & Services
  Logs
▾ Data Browser        ← active, expanded
    Cloud Storage     ← sub-item, indented
    Pub/Sub
    Firestore         ← active sub-item highlighted
    BigQuery
    ...
  Usage
  Settings
```

### 2. Auto-expand on navigation

**Decision**: When the user navigates to `#/data` or `#/data/{service}`, the Data Browser section auto-expands. When navigating away to another page, it collapses.

**Why**: The sub-items are only relevant when browsing data. Keeping them collapsed on other pages avoids cluttering the sidebar.

### 3. Responsive behavior

**Decision**: At the 900px breakpoint, the sidebar collapses to 52px (icon-only). Service sub-items are hidden — only the Data Browser icon shows. The user must click the Data Browser icon to navigate, and the content area shows the selected service's data (persisted from the last selection or defaulting to GCS).

**Why**: Sub-items can't fit in a 52px icon-only sidebar. The selected service state is preserved so the user doesn't lose context.

### 4. Content area layout

**Decision**: Remove the `db-explorer` grid layout from DataBrowser.jsx. The component renders only the content: service header bar (icon + name + Refresh/Reset) followed by the service view. No explorer panel.

**Why**: The sidebar IS the explorer now. The content area gets the full width of `main-content`.

## Risks / Trade-offs

- **Sidebar scroll**: With 14 service sub-items expanded, the sidebar content is taller (~600px). On short viewports, some items may require scrolling. **Mitigation**: Sidebar already has `overflow-y: auto`.
- **Two-click navigation**: To switch services, users click the sidebar sub-item (1 click) instead of the in-page explorer (also 1 click). No regression.
- **Loss of health-dot-at-a-glance when collapsed**: When on other pages, users can't see service health dots. **Mitigation**: Dashboard and Services pages already show health status.
