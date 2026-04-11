## 1. Sidebar — Expandable Service Sub-Items

- [x] 1.1 Add CSS for sidebar sub-items: `.sidebar-sub-item` with indentation (padding-left: 36px), smaller font (11px), GCP icon, health dot, active state
- [x] 1.2 Add CSS for expand/collapse chevron on Data Browser nav item
- [x] 1.3 Add collapse animation (max-height transition or display toggle)
- [x] 1.4 Responsive: hide sub-items at 900px breakpoint

## 2. App.jsx — Sidebar Navigation Logic

- [x] 2.1 Import TABS array (service list) from DataBrowser or shared constants
- [x] 2.2 Add `dataExpanded` signal — auto-true when `currentPage() === 'data'`, false otherwise
- [x] 2.3 Render service sub-items below Data Browser nav item when expanded
- [x] 2.4 Each sub-item shows GCP icon (`/icons/{id}.svg`), label, health dot from healthData
- [x] 2.5 Clicking a sub-item calls `handleServiceClick(serviceId)` — sets page to 'data' and selectedService
- [x] 2.6 Active sub-item highlighted when `selectedService() === id`

## 3. DataBrowser.jsx — Remove Explorer Panel

- [x] 3.1 Remove the `db-explorer` grid layout (explorer panel + content panel)
- [x] 3.2 Render content directly: service header bar + service view at full width
- [x] 3.3 Keep the service header bar (icon, name, Refresh, Reset) at top of content
- [x] 3.4 Remove the `TABS` rendering loop from DataBrowser (sidebar handles it now)

## 4. CSS Cleanup

- [x] 4.1 Remove `db-explorer`, `db-explorer-panel`, `db-explorer-item`, `db-explorer-icon`, `db-explorer-label`, `db-explorer-dot`, `db-explorer-content`, `db-content-header`, `db-content-title`, `db-content-actions` classes from components.css
- [x] 4.2 Add `db-header` class for the service header bar (flex row, icon + name + action buttons)
- [x] 4.3 Verify responsive layout at 900px — content fills available space

## 5. Verification

- [x] 5.1 Build (`npm run build`) passes
- [x] 5.2 Visual test: sidebar shows expanded services when Data Browser active, collapsed on other pages
- [x] 5.3 Visual test: content area uses full width, no explorer panel
- [x] 5.4 All service views work: GCS, Pub/Sub, Firestore, BigQuery, Secret Manager, Cloud Tasks, Spanner, Bigtable, Logging, Monitoring, GKE, Compute, Cloud Run, Memorystore
- [x] 5.5 URL routing preserved: `#/data/gcs`, `#/data/spanner`, etc.
- [x] 5.6 Dark mode and light mode both look correct
