# LocalCloud Console Gemini

An Aura/Gemini redesign of the LocalCloud Console with the same admin API functionality and a higher-fidelity platform UI.

## Overview

LocalCloud Console Gemini is a production-ready web UI for the LocalCloud GCP emulator. It provides:

- **Aura Shell**: Icon-first navigation, command palette, project status center, health rail, and intelligence widget
- **Service Management**: Monitor all emulated GCP services
- **Service Explorer**: Deep-dive into service data with SQL queries, file browsing, and schema views
- **Log Viewer**: Real-time request log viewing with filtering and auto-tail
- **Data Browser**: Read-only preview of data across all services
- **Usage Tracking**: API usage per service with estimated GCP cost savings
- **System Monitoring**: Health status, uptime, request counts, and service ports
- **User Preferences**: Dark/light mode, configurable auto-refresh interval

### Architecture

```
Browser (http://localhost:8080)
    ↓
    Solid.js SPA
    ↓
Armeria Gateway (port 8080)
    ├─ Static file serving from /opt/localcloud/console/dist/
    ├─ Admin API (/_localcloud/*)
    └─ gRPC facade services
         ↓
    PostgreSQL (internal persistence)
```

The console is served directly by the Armeria gateway — no separate backend process needed. In the Docker container, the built frontend files are at `/opt/localcloud/console/dist/` and served at the root path (`/`).

### Tech Stack

- **Frontend**: Solid.js (fine-grained reactivity), vanilla CSS
- **Server**: Armeria (Java) — serves static files + API
- **Build**: esbuild (bundler), npm
- **Design**: Aura/Gemini console theme with layered modules and local/remote visual modes

## Installation

### Prerequisites

- Node.js 18+ (for building frontend)
- LocalCloud running (Docker container)

### Setup

1. **Install Node dependencies** (frontend build):
```bash
cd localcloud-console-gemini
npm install
```

2. **Build the frontend**:
```bash
npm run build
```

This creates:
- `dist/app.js` — minified Solid.js app
- `dist/styles.css` — Aura/Gemini theme
- `dist/index.html` — SPA entry point

## Usage

For local development, run `npm run dev` and open **http://localhost:3001**. The dev server proxies `/_localcloud/*` to **http://localhost:8080**.

```bash
# Start LocalCloud
docker compose up -d

# Open console in browser
open http://localhost:8080

# Or use the CLI shortcut
localcloud console
```

## Features

### 1. Dashboard
- System health status and uptime
- Grid of all 14 services with status indicators
- Quick action buttons (Refresh, Reset All)
- Environment variable export

### 2. Services
- Table view of all services
- Status indicators, port numbers, protocols
- Request count tracking per service

### 3. Service Explorer
- Deep-dive view for each service's data
- SQL query editor for BigQuery datasets
- File browser for GCS buckets
- Schema detection and display
- Spanner instance and database browsing

### 4. Data Browser
- Read-only preview across all services
- Firestore: collections and document preview
- BigQuery: datasets and table schemas
- GCS: buckets and object listing
- Spanner: instances and database details
- Secret Manager: secret names (values redacted)
- Pub/Sub: topics and subscriptions
- Memorystore: key browser

### 5. Logs
- Real-time request log viewer
- Auto-tail with configurable refresh rate
- Service filtering and limit control
- Color-coded HTTP status codes

### 6. Usage
- API request counts per service
- Estimated GCP cost savings
- Pricing reference table

### 7. Settings
- Dark/Light mode toggle (dark is default)
- Auto-refresh interval control (1-60 seconds)
- Environment variable export (shell, Docker Compose, JSON)
- SDK setup examples for Python and Node.js

## Development

### Frontend Development

1. **Install dependencies**:
```bash
npm install
```

2. **Edit source files** in `src/`:
```
src/
├── app.jsx              # Main Solid.js app
├── api.js               # API wrapper functions
├── index.html           # SPA entry point
├── pages/
│   ├── Dashboard.jsx    # Service grid and health
│   ├── Services.jsx     # Service table
│   ├── ServiceExplorer.jsx  # Deep-dive service data
│   ├── DataBrowser.jsx  # Data preview
│   ├── Logs.jsx         # Log viewer
│   ├── Usage.jsx        # API usage and cost tracking
│   ├── Settings.jsx     # User preferences
│   └── settings-data.js # Settings configuration data
└── styles/
    ├── main.css         # Colors, typography, buttons
    ├── layout.css       # Flexbox layout, responsive
    └── components.css   # Cards, tables, alerts
```

3. **Rebuild frontend**:
```bash
npm run build
```

This concatenates CSS and bundles JSX with esbuild.

## Performance

- **Build time**: <1 second
- **Auto-refresh**: Configurable 1-60 seconds

## Security

- **Input validation**: Service names validated against whitelist
- **Error sanitization**: Internal errors logged but not exposed to UI
- **No secrets in UI**: Secret Manager values redacted in data browser
- **Read-only data browser**: All datastore operations are read-only

## File Structure

```
localcloud-console/
├── src/
│   ├── app.jsx              # Main app
│   ├── api.js               # API wrapper
│   ├── index.html           # HTML entry point
│   ├── pages/
│   │   ├── Dashboard.jsx
│   │   ├── Services.jsx
│   │   ├── ServiceExplorer.jsx
│   │   ├── DataBrowser.jsx
│   │   ├── Logs.jsx
│   │   ├── Usage.jsx
│   │   ├── Settings.jsx
│   │   └── settings-data.js
│   └── styles/
│       ├── main.css
│       ├── layout.css
│       └── components.css
├── dist/                    # Built output (generated)
│   ├── app.js
│   ├── styles.css
│   └── index.html
├── package.json
├── package-lock.json
└── README.md
```

## License

See ../localcloud-site/LICENSE - Proprietary. Free for individual developers for personal use, learning, and evaluation. No production or commercial use permitted.
