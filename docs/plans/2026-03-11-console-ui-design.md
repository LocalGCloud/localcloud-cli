# LocalCloud Console UI Design

**Date:** 2026-03-11
**Status:** Approved
**Approach:** Lightweight Monolith (Solid.js + Flask)

---

## Overview

A web-based console for managing LocalCloud services (start/stop/status), viewing logs, and browsing datastore contents. Built with Solid.js for minimal footprint (~8KB), served by a lightweight Flask backend that orchestrates CLI calls for control operations and REST proxies for data reads.

---

## Architecture

### Technology Stack
- **Frontend:** Solid.js (fine-grained reactivity) + vanilla CSS (no build-time dependencies)
- **Backend:** Python Flask microserver on port 9090
- **APIs:** Hybrid
  - **Control ops** (start/stop/reset/logs): CLI subprocess calls via Flask
  - **Data reads** (status/metrics/datastore preview): REST calls to LocalCloud Java backend (port 8080)
- **Deployment:** `localcloud console` CLI command

### Memory Footprint
- Target: < 50MB for console process (Flask + browser SPA)
- Solid.js bundle: ~8KB gzipped
- CSS: ~3KB (hand-written, no framework)
- HTML: ~2KB

---

## Design System (GCP Console-inspired)

### Colors
| Element | Value | Usage |
|---------|-------|-------|
| Background | `#1f2937` | Dark gray, main surface |
| Surface | `#111827` | Darker, cards/panels |
| Primary | `#4285f4` | Blue, actions, highlights |
| Success | `#34a853` | Green, running status |
| Warning | `#fbbc04` | Yellow, warnings |
| Error | `#ea4335` | Red, errors |
| Text | `#e5e7eb` | Light gray (dark mode default) |

### Typography
- **Headings:** Monospace or sans-serif, 14-18px, `#ffffff`
- **Body:** 13px, `#d1d5db`
- **Code/Logs:** Monospace, 12px, `#a1a5b0`

### Components
- **Cards:** 1px solid border (`#374151`), 4px border-radius, padding 16px
- **Buttons:** Inline padding (8px 16px), 4px radius, transition on hover
- **Status badges:** Inline, small font (11px), color-coded
- **Icons:** Material Design icons (CSS-based, no external deps)

---

## Pages & Layout

### Header
```
┌──────────────────────────────────────────────────┐
│ ☰ LocalCloud Console | local-project | 🔄 ⚙️  │
└──────────────────────────────────────────────────┘
```

### Sidebar Navigation
```
□ Dashboard
□ Services
□ Logs
□ Data Browser
□ Settings
```

### Content Area
Main page content (responsive, 1 column on mobile, 2-3 columns on desktop)

---

## Core Pages

### 1. Dashboard
**Purpose:** At-a-glance system health and service overview.

**Components:**
- **System Status Card:**
  - Container uptime, CPU/memory usage, health status
  - Refresh indicator showing last update time
- **Services Grid:** 3-column grid (responsive)
  - Service card: name, status icon (🟢/⚫/🔴), status text
  - Quick actions: `Start`, `Stop`, `Logs` (buttons)
  - Error message (if any, truncated to 1 line)
- **Refresh Interval Selector:** Dropdown (1s, 5s, 10s, manual)

### 2. Services
**Purpose:** Detailed list view of all 13 services.

**Components:**
- **Services Table:**
  - Columns: Name, Status, Port(s), Uptime, Memory, Actions
  - Row actions: Start, Stop, Restart, View Logs (inline buttons)
  - Expandable rows: Config, health check result, recent error
- **Search/Filter:** By name or status
- **Bulk actions:** Start All, Stop All, Restart All (with confirmation)

### 3. Logs
**Purpose:** Centralized log viewer.

**Components:**
- **Service Selector:** Dropdown (or "All Services")
- **Log Viewer:**
  - Scrollable text area with auto-tail (polling every 1-2s)
  - Timestamp, level badge, message
  - Search box (client-side filter)
  - Export as JSON button
  - Clear logs button
- **Level Filter:** Checkboxes (INFO, WARN, ERROR)

### 4. Data Browser
**Purpose:** Read-only preview of datastore contents.

**Components:**
- **Tabs:** One per datastore type
  - **Firestore:** Collection list, doc preview (JSON), doc count
  - **BigQuery:** Dataset/table list, schema preview, sample rows (first 10)
  - **GCS:** Bucket list, object list with size, preview link
  - **Spanner:** Instance/database/table list, schema
  - **Logging:** Recent log entries by resource type
- **Preview panel:** JSON viewer with syntax highlighting (code block)
- **Refresh button:** Lazy load (cache results for 10s)

### 5. Settings
**Purpose:** Console preferences.

**Components:**
- **Display:**
  - Dark/light mode toggle
  - Auto-refresh interval selector
- **Advanced:**
  - Show Java flags (read-only display)
  - Show memory limits
- **Actions:**
  - Export config as JSON
  - Reset all data button (confirm dialog)
  - About LocalCloud (version, docs link)

---

## API Contract

### Flask Backend Endpoints

**Status & Service Info:**
```
GET /api/status
  Response: { uptime: "2h 15m", health: "healthy", memory_mb: 245 }

GET /api/services
  Response: [
    { name: "bigquery", status: "RUNNING", port: 9050, uptime: "2h 15m" },
    { name: "firestore", status: "RUNNING", port: 8086, uptime: "2h 15m" },
    ...
  ]

GET /api/services/{service_name}
  Response: { name, status, port, uptime, logs_lines: N, last_error: "" }
```

**Control Operations (CLI subprocess):**
```
POST /api/services/{service_name}/start
  Response: { success: true, message: "Service started" }

POST /api/services/{service_name}/stop
  Response: { success: true, message: "Service stopped" }

POST /api/services/{service_name}/restart
  Response: { success: true, message: "Service restarted" }

POST /api/reset
  Response: { success: true, message: "Data reset. Please refresh." }
```

**Logs:**
```
GET /api/logs/{service_name}?lines=100
  Response: { service, lines: ["2026-03-11 08:59:54 INFO ...", ...] }

GET /api/logs/all?lines=50
  Response: { lines: [{ service, timestamp, level, message }, ...] }
```

**Data Reads (proxy to LocalCloud backend REST APIs):**
```
GET /api/firestore/collections
  Response: { collections: [{ name: "users", doc_count: 42 }, ...] }

GET /api/firestore/collections/{collection}?limit=10
  Response: { docs: [{ id, data: {...} }, ...] }

GET /api/bigquery/datasets
  Response: { datasets: [{ id, tables: N }, ...] }

GET /api/bigquery/datasets/{dataset}/tables/{table}?rows=10
  Response: { schema: [...], rows: [...] }

GET /api/gcs/buckets
  Response: { buckets: [{ name, objects: N, size_bytes: ... }, ...] }

GET /api/gcs/buckets/{bucket}?prefix=""&limit=50
  Response: { objects: [{ name, size, modified }, ...] }

GET /api/spanner/instances
  Response: { instances: [{ name, databases: [...] }, ...] }
```

---

## State Management (Solid.js)

### Stores
```javascript
// Main service store
const [services, setServices] = createSignal([
  { name: "bigquery", status: "RUNNING", port: 9050, uptime: "2h", error: "" },
  ...
])

// Logs store (circular buffer)
const [logs, setLogs] = createSignal({ service: "all", lines: [] })

// Settings store
const [settings, setSettings] = createSignal({
  darkMode: true,
  refreshInterval: 5000, // ms
  selectedService: "all"
})
```

### Polling Strategy
- **Dashboard/Services:** Poll `/api/services` every 5s (configurable)
- **Logs:** Tail-follow with polling every 1-2s when viewing
- **Data Browser:** Lazy load on tab click, cache for 10s, refresh button to force

---

## Component Hierarchy

```
<App>
  ├─ <Header> (title, dark/light toggle, project ID)
  ├─ <Sidebar> (navigation)
  └─ <Router>
     ├─ <Dashboard> (grid of service cards)
     ├─ <Services> (table view)
     ├─ <Logs> (tail viewer)
     ├─ <DataBrowser> (tabbed datastore preview)
     └─ <Settings> (preferences)
```

---

## Build & Deployment

### Build Process
```bash
# Frontend
npm install solidjs
npm run build  # Output: console/dist/index.html + app.js + styles.css

# Backend (Flask) - no build needed
python -m localcloud.console
```

### Directory Structure
```
localcloud-console/
├─ src/
│  ├─ index.html
│  ├─ app.jsx          (Solid.js entry)
│  ├─ pages/
│  │  ├─ Dashboard.jsx
│  │  ├─ Services.jsx
│  │  ├─ Logs.jsx
│  │  ├─ DataBrowser.jsx
│  │  └─ Settings.jsx
│  ├─ components/
│  │  ├─ Header.jsx
│  │  ├─ Sidebar.jsx
│  │  ├─ ServiceCard.jsx
│  │  └─ StatusBadge.jsx
│  ├─ styles/
│  │  ├─ main.css      (global)
│  │  ├─ layout.css
│  │  └─ components.css
│  └─ api.js           (fetch wrappers)
├─ backend/
│  ├─ app.py           (Flask server)
│  ├─ cli_runner.py    (subprocess wrapper for localcloud CLI)
│  └─ proxy.py         (REST proxy to LocalCloud backend)
├─ dist/               (built frontend, served by Flask)
├─ package.json
└─ vite.config.js      (minimal build config)
```

### CLI Integration
```bash
# New command
localcloud console [--port 9090] [--open]
  --port: Flask server port (default 9090)
  --open: Auto-open browser (default true)
```

---

## Success Criteria

- ✓ All 13 services visible and controllable from console
- ✓ Service start/stop/restart working via CLI integration
- ✓ Logs viewer auto-tailing and searchable
- ✓ Datastore preview (Firestore, BigQuery, GCS, Spanner) working
- ✓ Dark mode enabled by default, light mode option available
- ✓ Memory footprint < 50MB (Flask + browser SPA)
- ✓ Console responsive on desktop and tablet
- ✓ No external CDN dependencies (all CSS/JS local)

---

## Future Enhancements (Out of Scope)

- WebSocket for real-time status updates (instead of polling)
- Write operations (create/delete in Firestore, BigQuery, etc.)
- Custom metrics dashboard
- Service health trends (historical data)
- Dark mode scheduling (automatic at sunset)
- Multi-project support

