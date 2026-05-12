# Console Redesign — GCP-Style

**Date**: 2026-03-31
**Status**: Approved

## Problem

The current console has three critical issues:
1. Dashboard shows red for all services — status mapping doesn't work correctly
2. Start/stop buttons call `localcloud` CLI via subprocess, which doesn't exist in Docker-only mode
3. Data Browser returns empty — all three browse endpoints are stubs

Additionally, the UI uses a custom dark theme that doesn't match the Google Cloud Console experience the user expects.

## Architecture

### Before (Broken)
```
Browser → Armeria → cli_runner.py (subprocess: localcloud CLI) → Docker container
                → proxy.py → /_localcloud/health (only endpoint used)
```

### After
```
Browser → Armeria (8080) → LocalCloud Admin API (8080/_localcloud/*)
                        → Browse API (8080/_localcloud/browse/*)
```

Armeria becomes a thin JSON proxy. No CLI dependency. No subprocess calls.

## Backend API Design

### Armeria Routes → Admin API Mapping

| Armeria Route | Method | Proxies To | Purpose |
|---|---|---|---|
| `/api/health` | GET | `/_localcloud/health` | Dashboard status + service health |
| `/api/services` | GET | `/_localcloud/health` → extract services | Service list with status |
| `/api/requests` | GET | `/_localcloud/requests` | Request log ring buffer |
| `/api/reset` | POST | `/_localcloud/reset` | Reset all service data |
| `/api/seed` | POST | `/_localcloud/seed` | Load seed YAML |
| `/api/env` | GET | `/_localcloud/env?format=json` | Environment variables |
| `/api/browse/<service>` | GET | `/_localcloud/browse/<service>` | Data browsing |
| `/api/browse/<service>/<path>` | GET | `/_localcloud/browse/<service>/<path>` | Nested data browsing |

### Files to Change
- **Delete**: `backend/cli_runner.py` — no longer needed
- **Rewrite**: `backend/proxy.py` — becomes sole backend, all routes proxy to Admin API
- **Rewrite**: `backend/app.py` — simplified routes, no CLI imports

## Frontend Pages

### 1. Dashboard (Home)
- Project ID, uptime, overall health status at top
- 13 service cards in a responsive grid
- Each card: service name, status dot (green/red), port number
- Quick actions: Reset All, Copy Env Vars
- Auto-refresh every 5 seconds

### 2. APIs & Services
- Table: Service, Status, Port, Endpoint URL, Protocol (REST/gRPC)
- Status: green dot + "Healthy" or red dot + "Unavailable"
- Read-only — no start/stop (services managed by supervisord)
- Click row to expand detail: env var name, supported operations summary

### 3. Logs (Request Log)
- Source: `/_localcloud/requests` ring buffer
- Table: Timestamp, Method, Path, Status Code, Latency
- Filters: service dropdown, status code range, method type
- Auto-refresh toggle with interval control

### 4. Data Browser
- Left panel: service selector (GCS, Pub/Sub, Firestore, BigQuery, Secret Manager, Cloud Tasks)
- Right panel: hierarchical data display
- GCS: buckets → objects
- Pub/Sub: topics → subscriptions
- Firestore: collections → documents (key-value display)
- BigQuery: datasets → tables → schema
- Secret Manager: secrets → versions
- Cloud Tasks: queues → tasks
- All via `/_localcloud/browse/<service>` endpoint

### 5. Settings
- Environment variable export (shell, docker-compose, JSON formats)
- Dark/light mode toggle
- Auto-refresh interval (1-60 seconds)
- About section with version and links

## Visual Design — GCP Console Style

### Color Palette
**Light mode (default):**
- Background: `#ffffff`
- Surface: `#f8f9fa`
- Sidebar: `#ffffff` with `#e8eaed` border
- Primary: `#1a73e8` (Google Blue)
- Text: `#202124` (primary), `#5f6368` (secondary)
- Success: `#34a853` (Google Green)
- Error: `#ea4335` (Google Red)
- Warning: `#fbbc04` (Google Yellow)

**Dark mode:**
- Background: `#202124`
- Surface: `#292a2d`
- Sidebar: `#292a2d`
- Primary: `#8ab4f8`
- Text: `#e8eaed` (primary), `#9aa0a6` (secondary)

### Layout
- **Top bar**: 64px height, project name left, dark mode toggle right
- **Sidebar**: 256px width, collapsible to 56px icons-only
- **Content**: remaining width, 24px padding
- **Typography**: system font stack (Segoe UI, Roboto, sans-serif), 14px body, 20px page titles

### Components
- Cards with 1px `#e8eaed` border, 8px radius, subtle shadow on hover
- Tables with `#f8f9fa` header, hover highlight
- Status dots: 8px circles, green/red/yellow
- Buttons: filled primary (blue), outlined secondary
- Breadcrumb-style page headers

## Files Changed

### Delete
- `backend/cli_runner.py`

### Rewrite
- `backend/app.py` — simplified Flask app, proxy-only routes
- `backend/proxy.py` — all Admin API proxy methods
- `src/api.js` — updated endpoints matching new backend
- `src/app.jsx` — GCP-style layout with sidebar
- `src/pages/Dashboard.jsx` — service health grid
- `src/pages/Services.jsx` — read-only service table
- `src/pages/Logs.jsx` — request log viewer
- `src/pages/Settings.jsx` — env export, theme, about

### New
- `src/pages/DataBrowser.jsx` — functional data browser with service-specific views
- `src/styles/main.css` — GCP color palette, light/dark mode
- `src/styles/layout.css` — sidebar + top bar layout
- `src/styles/components.css` — cards, tables, badges, buttons

## Non-Goals
- Individual service start/stop (managed by supervisord, not controllable from console)
- Data editing/mutation through the console (read-only browsing only)
- Real-time WebSocket streaming (polling is sufficient for dev tool)
