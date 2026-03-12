# LocalCloud Console

A lightweight web-based console for managing LocalCloud services, viewing logs, and browsing datastore contents.

## Overview

The LocalCloud Console is a production-ready web UI for the LocalCloud GCP emulator. It provides:

- **Service Management**: Start, stop, and monitor all 13 emulated GCP services
- **Log Viewer**: Real-time request log viewing with filtering and auto-tail
- **Data Browser**: Read-only preview of data in Firestore, BigQuery, GCS, and Spanner
- **System Monitoring**: Health status, uptime, request counts, and service ports
- **User Preferences**: Dark/light mode, configurable auto-refresh interval

### Architecture

```
User Browser (localhost:9090)
    ↓
    Solid.js SPA (26KB minified)
    ↓
Flask Backend (port 9090)
    ├─ Static file serving (HTML/CSS/JS)
    ├─ API endpoints (/api/*)
    ├─ CLI command wrapper (start/stop/reset)
    └─ REST proxy to LocalCloud (port 8080)
         ↓
    LocalCloud Backend (port 8080)
```

### Tech Stack

- **Frontend**: Solid.js (fine-grained reactivity), vanilla CSS
- **Backend**: Flask (Python), requests library
- **Build**: esbuild (bundler), npm
- **CLI**: Python Click framework
- **Design**: GCP Console-inspired dark theme

## Installation

### Prerequisites

- Python 3.11+
- Node.js 16+ (for building frontend)
- LocalCloud running (docker container)

### Setup

1. **Install Python dependencies** (Flask backend):
```bash
cd localcloud-console
pip install -r requirements.txt
```

2. **Install Node dependencies** (frontend build):
```bash
npm install
```

3. **Build the frontend** (one-time):
```bash
npm run build
```

This creates:
- `dist/app.js` (26 KB minified Solid.js app)
- `dist/styles.css` (6 KB GCP theme)
- `dist/index.html` (SPA entry point)

## Usage

### Option 1: Using the CLI Command (Recommended)

```bash
# Start LocalCloud first
localcloud start

# In another terminal, open the console
localcloud console
```

This will:
1. Start the Flask server on port 9090
2. Automatically open your browser to `http://localhost:9090`
3. Connect to the LocalCloud backend on port 8080

Optional flags:
```bash
--port 8888           # Use custom port (default: 9090)
--no-open             # Don't auto-open browser
```

### Option 2: Manual Backend Startup

```bash
cd localcloud-console
python backend/app.py
```

Then open `http://localhost:9090` in your browser.

## Features

### 1. Dashboard
- System health status and uptime
- 3-column grid of all 13 services
- Quick action buttons (Refresh, Reset All)
- Environment variable export

### 2. Services
- Table view of all services
- Start/Stop buttons for each service
- Status indicators (🟢 running, ⚫ stopped, 🔴 error)
- Port numbers and health status

### 3. Logs
- Real-time request log viewer
- Auto-tail with configurable refresh rate
- Service filtering and limit control
- Color-coded HTTP status codes
  - 2xx (green) - Success
  - 3xx (blue) - Redirect
  - 4xx (orange) - Client error
  - 5xx (red) - Server error

### 4. Data Browser
- **Firestore**: Collections and document preview
- **BigQuery**: Datasets and table schemas
- **GCS**: Buckets and object listing
- **Spanner**: Instances and database details
- **Secret Manager**: Secret names (values redacted for security)
- JSON preview for custom data

### 5. Settings
- Dark/Light mode toggle (dark is default)
- Auto-refresh interval control (1-60 seconds)
- Environment variable export in multiple formats:
  - Shell script
  - Docker Compose
  - JSON
- SDK setup examples for Python and Node.js
- Quick reference table of all env vars

## API Endpoints

The console backend exposes these REST endpoints (all proxied from LocalCloud):

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/status` | GET | System health and service status |
| `/api/services` | GET | List all services with details |
| `/api/logs/all` | GET | Request log with filtering |
| `/api/firestore/collections` | GET | Firestore collections |
| `/api/bigquery/datasets` | GET | BigQuery datasets |
| `/api/gcs/buckets` | GET | GCS buckets |

Query parameters:
- `lines=100` - Number of log lines to fetch (default: 100, max: 10000)
- `service=firestore` - Filter by service name

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
├── pages/               # Page components
│   ├── Dashboard.jsx
│   ├── Services.jsx
│   ├── Logs.jsx
│   ├── DataBrowser.jsx
│   └── Settings.jsx
└── styles/              # CSS files
    ├── main.css         # Colors, typography, buttons
    ├── layout.css       # Flexbox layout, responsive
    └── components.css   # Cards, tables, alerts
```

3. **Rebuild frontend**:
```bash
npm run build
```

This concatenates CSS and bundles JSX with esbuild.

### Backend Development

The Flask backend is in `backend/`:
- `app.py` - Main Flask server with API endpoints
- `cli_runner.py` - Wrapper for LocalCloud CLI commands
- `proxy.py` - REST proxy to LocalCloud backend
- `requirements.txt` - Python dependencies

To modify and test:
```bash
# Install Python dependencies
pip install -r requirements.txt

# Run the server (reload on file change)
python backend/app.py
```

## Troubleshooting

### Console won't start
- Check that LocalCloud is running: `localcloud status`
- Verify port 9090 is available: `lsof -i :9090`
- Check Flask error output for details

### Services show "error" status
- Verify LocalCloud backend is healthy: `curl http://localhost:8080/_localcloud/health`
- Check LocalCloud logs: `localcloud logs`

### Data Browser shows empty data
- Verify data exists in the service (create test data if needed)
- Check that the backend proxy is working: `curl http://localhost:9090/api/firestore/collections`

### Browser won't connect to console
- Verify Flask server is running: `curl http://localhost:9090/`
- Check that port 9090 is not blocked by firewall
- Try accessing from `http://127.0.0.1:9090` instead of localhost

### CORS errors in browser console
- Flask-CORS is configured but there may be issues with cross-origin requests
- Check that the LocalCloud backend is responding to proxy requests

## Performance

- **Bundle size**: 31 KB (app.js + CSS)
- **Build time**: <1 second
- **Memory footprint**: <50 MB (Flask + browser)
- **Auto-refresh**: Configurable 1-60 seconds
- **Max log lines**: 10,000

## Security

- **Input validation**: Service names validated against whitelist
- **Error sanitization**: Internal errors logged but not exposed to UI
- **CORS enabled**: Requests from browser are properly handled
- **No secrets in UI**: Secret Manager values redacted in data browser
- **Read-only data browser**: All datastore operations are read-only

## Limitations

Known issues for future improvement:
1. Logs endpoint requires local `localcloud` command in PATH
2. Some accessibility improvements possible (focus states)
3. No real-time WebSocket updates (uses polling)
4. Limited to one LocalCloud instance per console server

## Contributing

To enhance the console:

1. **Frontend changes**: Edit files in `src/`, run `npm run build`
2. **Backend changes**: Edit files in `backend/`, restart Flask
3. **Style changes**: Edit CSS files in `src/styles/`, run `npm run build`
4. **Test changes**: Run `localcloud console`, manually test in browser

## Architecture Notes

### Solid.js Reactivity

The console uses Solid.js primitives:
- `createSignal`: State management (services, logs, settings)
- `createEffect`: Side effects (polling, dark mode)
- `Show/For`: Conditional and list rendering (no unnecessary re-renders)

### Auto-Refresh

Services are polled every N seconds (default: 5s, configurable in Settings):
- Dashboard and Services page update automatically
- Logs page has independent tail-follow mode
- Data Browser loads on-demand, caches for 10s

### Error Recovery

All API calls have error handling:
- Network errors show user-friendly messages
- Failed requests don't crash the UI
- Retry via refresh button or auto-refresh interval

## File Structure

```
localcloud-console/
├── src/
│   ├── app.jsx              # Main app (350 lines)
│   ├── api.js               # API wrapper (170 lines)
│   ├── index.html           # HTML entry point
│   ├── pages/
│   │   ├── Dashboard.jsx    # Service grid
│   │   ├── Services.jsx     # Service table
│   │   ├── Logs.jsx         # Log viewer
│   │   ├── DataBrowser.jsx  # Data preview
│   │   └── Settings.jsx     # User prefs
│   └── styles/
│       ├── main.css         # Colors, typography
│       ├── layout.css       # Layout, responsive
│       └── components.css   # UI components
├── backend/
│   ├── app.py               # Flask server
│   ├── cli_runner.py        # CLI wrapper
│   ├── proxy.py             # REST proxy
│   └── requirements.txt      # Python deps
├── dist/                    # Built output (generated)
│   ├── app.js               # Minified Solid.js bundle
│   ├── styles.css           # Combined CSS
│   └── index.html           # SPA entry point
├── package.json             # Node.js project
├── package-lock.json        # Dependency lock
└── README.md                # This file
```

## License

Same as LocalCloud (see parent project).
