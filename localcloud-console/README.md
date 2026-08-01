# LocalCloud Console

> **Last updated:** 2026-05-26

A Solid.js web console for the LocalCloud GCP emulator.

## Overview

LocalCloud Console is a production-ready web UI served directly by the Armeria gateway. It provides:

- **Dashboard**: Service health, project info, uptime, request counts
- **APIs & Services**: Monitor all 23 emulated GCP services with status, ports, routing, and env vars
- **Service Explorer**: Deep-dive into service data with SQL queries, file browsing, and schema views
- **Data Browser**: Browse and mutate data across all services (BigQuery, GCS, Spanner, Firestore, Pub/Sub, Memorystore, Secret Manager)
- **Logs**: Real-time request log viewer with filtering and auto-tail
- **Usage**: API usage per service with estimated GCP cost savings
- **Settings**: Environment variable export (shell/Terraform/Docker Compose), SDK examples, cloud routing, auto-refresh, theme

### Architecture

```
Browser (http://localhost:24080)
    ↓
    Solid.js SPA
    ↓
Armeria Gateway (port 24080)
    ├─ Static file serving (Solid.js SPA)
    ├─ Admin REST API
    ├─ In-process gRPC/REST facades (17 services)
    ├─ External emulator routing (GCS:24081, Pub/Sub:24082, Firestore:24083,
    │  Bigtable:24084, Spanner:24085, BigQuery:24087, Memorystore:24089)
    └─ PostgreSQL 17 (persistence)
```

The console is served directly by the Armeria gateway — no separate backend process needed.

### Tech Stack

- **Frontend**: Solid.js (fine-grained reactivity), vanilla CSS
- **Server**: Armeria (Java) — serves static files + API
- **Build**: esbuild (bundler), npm

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

For local development, run `npm run dev` and open **http://localhost:3001**. The dev server proxies `/*` to **http://localhost:24080**.

```bash
# Start LocalCloud
docker compose up -d

# Open console in browser
open http://localhost:24080

# Or use the CLI shortcut
localcloud console
```

## Features

### 1. Dashboard
- System health status and uptime
- Grid of all 23 services with status indicators, ports, and request counts
- Quick action buttons (Refresh, Reset All)
- Environment variable export

### 2. APIs & Services
- Table view of all 23 services with status, ports, protocols, routing mode
- Request count tracking per service
- Enable/disable toggle per service

### 3. Service Explorer
- Deep-dive view for each service's data
- SQL query editor for BigQuery and Spanner
- File browser for GCS buckets
- Schema detection and display
- Workflows definitions and executions browser

### 4. Data Browser
- Browse and mutate data across all services
- BigQuery: datasets, table schemas, row add/edit/delete
- GCS: buckets and object listing
- Spanner: instances, databases, table data with add/edit/delete
- Firestore: collections and document preview
- Secret Manager: secrets and versions
- Pub/Sub: topics, subscriptions, and message browsing
- Memorystore: key browser with Redis data types
- Cloud Tasks: queue listing and management
- AlloyDB: cluster and database browsing
- Bigtable: table and row browsing

### 5. Logs
- Real-time request log viewer with configurable refresh
- Service filtering and limit control
- Color-coded HTTP status codes

### 6. Usage
- API request counts per service
- Estimated GCP cost savings
- Pricing reference table

### 7. Settings
- Dark/Light mode toggle
- Auto-refresh interval control (per-page, configurable 1-60 seconds)
- Environment variable export (shell, Terraform, Docker Compose, JSON)
- SDK setup examples for Python, Java, Node.js, Go
- Cloud routing configuration (local vs remote per service)

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

Proprietary. Free for individual developers for personal use, learning, and evaluation. No production or commercial use permitted.
