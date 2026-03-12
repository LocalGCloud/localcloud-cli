# Solid.js LocalCloud Dashboard - Phase 3 Implementation

This directory contains the Solid.js implementation of the LocalCloud Dashboard web application.

## Directory Structure

```
src/
├── app.jsx                 # Main app entry point (routing, state management, lifecycle)
├── api.js                  # API wrapper for all /_localcloud endpoints
├── components/             # Reusable shared components
│   ├── Header.jsx          # App header with title, project ID, theme toggle
│   ├── Sidebar.jsx         # Navigation sidebar with page links
│   ├── StatusBadge.jsx     # Status indicator component (running/stopped/error)
│   └── ServiceCard.jsx     # Service card displaying service info and status
└── pages/                  # Page components (one per route)
    ├── Dashboard.jsx       # Main dashboard with 3-column service grid
    ├── Services.jsx        # Services table with filtering and details
    ├── Logs.jsx            # Request log viewer with auto-tail
    ├── DataBrowser.jsx     # Tabbed data browser for all services
    └── Settings.jsx        # User settings and environment export
```

## Components Overview

### API Layer (`api.js`)

Exports functions for all LocalCloud admin API endpoints:
- `fetchHealth()` - Get platform health and service status
- `fetchServices()` - Get list of all services
- `fetchRequestLog(options)` - Get request logs with filtering
- `browsePath(service, path)` - Browse service data
- `loadSeed(yamlContent)` - Load seed data
- `resetServices(options)` - Reset all services
- `fetchEnvVars(format)` - Export environment variables
- `fetchLogs(options)` - Get log entries
- `fetchMetrics(options)` - Get monitoring metrics

### Shared Components

#### Header.jsx
- Displays app title "LocalCloud Dashboard"
- Shows current project ID
- Dark/light theme toggle button

#### Sidebar.jsx
- Navigation menu with links to all pages
- Shows active page indicator
- Displays current refresh interval

#### StatusBadge.jsx
- Colored status indicator
- Maps service status to display text
- Used in cards and tables

#### ServiceCard.jsx
- Displays service information in card layout
- Shows status, protocol, port, endpoint, env vars
- Used in Dashboard grid view

### Pages

#### Dashboard.jsx
- Overview of platform health
- 3-column grid of service cards
- Quick stats (uptime, total requests)
- Quick action buttons (reset, export env)
- Main landing page

#### Services.jsx
- All services in table format
- Filterable by status (all/running/stopped/error)
- Detailed columns: name, status, protocol, port, endpoint, env var, request count
- Copy-to-clipboard for environment variables

#### Logs.jsx
- Request log viewer with auto-tail capability
- Filterable by service
- Configurable limit (25/50/100/200)
- Shows: timestamp, service, method, path, status, duration, request/response size
- Color-coded HTTP status (2xx/3xx/4xx/5xx)

#### DataBrowser.jsx
- Tabbed interface for browsing service data
- Services: GCS, Firestore, BigQuery, Pub/Sub, Spanner, Secret Manager
- Service-specific renderers for each data type:
  - **GCS**: Buckets, objects with metadata
  - **Firestore**: Collections, documents with JSON preview
  - **BigQuery**: Datasets, tables with schema
  - **Pub/Sub**: Topics, subscriptions with config
  - **Spanner**: Instances, databases
  - **Secret Manager**: Secrets (values redacted)
- Breadcrumb navigation for nested data
- Fallback JSON viewer for unknown data shapes

#### Settings.jsx
- Display settings (dark mode, refresh interval, auto-tail logs)
- Environment variable export in multiple formats (shell, docker-compose, JSON)
- Quick reference table of all env vars
- Code examples for Python and Node.js
- Documentation links

### Main App (`app.jsx`)

Central state management and routing:
- **State**: currentPage, darkMode, refreshInterval, health, services, projectId
- **Effects**: Auto-refresh health/services every `refreshInterval` seconds
- **Handlers**: Page navigation, settings changes, data refresh, environment export, service reset
- **Rendering**: Conditional page rendering based on currentPage
- **Theme**: Applies dark-mode class to document root

## Key Features

1. **Reactive State Management**: Uses Solid.js `createSignal` and `createEffect`
2. **Real API Integration**: All endpoints call actual LocalCloud backend
3. **Auto-Refresh**: Services and health status auto-poll every N seconds
4. **Dark Mode**: Theme preference persisted in component state
5. **Error Handling**: Try-catch blocks with user-friendly error messages
6. **Responsive Design**: Works with CSS from Phase 2 (style.css)
7. **Modular Structure**: Each page is independently testable
8. **Type-Safe Props**: Components accept and use props with clear intent

## Build & Run

### Development
```bash
npm install
npm run dev  # Start dev server with hot reload
```

### Production Build
```bash
npm run build  # Creates optimized bundle with esbuild
```

The build output will be ready to serve from `/_localcloud/dashboard/` by the Java backend.

## Solid.js Syntax Notes

- Uses JSX for components (no need for HTML template strings)
- `createSignal` creates reactive state: `const [value, setValue] = createSignal(initial)`
- `createEffect` runs side effects when dependencies change
- `Show` component for conditional rendering (not `if` statements)
- `For` component for list rendering (not `.map()`)
- No hooks like React - just function components
- Event handlers use lowercase: `onclick`, `onchange`

## Integration with Backend

The app expects:
1. HTML file at `/_localcloud/dashboard/index.html` (served by Java backend)
2. Script bundle at `/_localcloud/dashboard/app.js` (built by esbuild)
3. CSS at `/_localcloud/dashboard/style.css` (from Phase 2)
4. API endpoints at `/_localcloud/*` (implemented by Java backend)

## Testing

Each page and component can be tested independently:
1. Mock the API functions in `api.js`
2. Pass test data via props
3. Verify component rendering and interactions

## Future Enhancements

- Real-time WebSocket updates for logs
- Data export/download from data browser
- Seed file uploader UI
- Request filtering and search
- Custom dashboard widgets
- Keyboard shortcuts
