# Console Redesign Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Rebuild the LocalCloud web console with a Google Cloud Console-inspired UI, replace CLI-based backend with direct Admin API proxy, and implement functional data browsing.

**Architecture:** Armeria gateway becomes a thin proxy to LocalCloud Admin API (port 8080) and emulator-specific APIs (GCS on 4443, etc). Frontend is a Solid.js SPA with GCP-style layout (sidebar nav, top bar, service health grid, request logs table, data browser with service-specific views).

**Tech Stack:** Solid.js 1.8, esbuild, Armeria Java gateway

---

### Task 1: Rewrite Armeria backend — remove CLI, proxy-only

**Files:**
- Delete: `localcloud-console/backend/cli_runner.py`
- Rewrite: `localcloud-console/backend/proxy.py`
- Rewrite: `localcloud-console/backend/app.py`

**Step 1: Rewrite proxy.py**

Replace the current proxy with a comprehensive one that calls all Admin API endpoints plus emulator-specific APIs for data browsing.

```python
"""Proxy requests to LocalCloud Admin API and emulator endpoints."""

import logging
import requests
from typing import Dict, Any, Optional

logger = logging.getLogger(__name__)


class BackendProxy:
    """Proxy to LocalCloud Admin API and emulator-specific endpoints."""

    def __init__(self, gateway_url: str = "http://localhost:8080",
                 gcs_url: str = "https://localhost:4443") -> None:
        self.gateway_url = gateway_url
        self.gcs_url = gcs_url
        self.timeout = 5.0

    def _get(self, url: str, params: Optional[Dict] = None, verify_ssl: bool = True) -> Dict[str, Any]:
        try:
            resp = requests.get(url, params=params, timeout=self.timeout, verify=verify_ssl)
            resp.raise_for_status()
            return resp.json()
        except requests.Timeout:
            return {"error": "Request timed out"}
        except requests.ConnectionError:
            return {"error": "Failed to connect to backend"}
        except requests.RequestException as e:
            return {"error": str(e)}

    def _post(self, url: str, data: Any = None, headers: Optional[Dict] = None) -> Dict[str, Any]:
        try:
            resp = requests.post(url, data=data, headers=headers, timeout=self.timeout)
            resp.raise_for_status()
            return resp.json() if resp.content else {"success": True}
        except requests.Timeout:
            return {"error": "Request timed out"}
        except requests.ConnectionError:
            return {"error": "Failed to connect to backend"}
        except requests.RequestException as e:
            return {"error": str(e)}

    # --- Admin API ---

    def get_health(self) -> Dict[str, Any]:
        return self._get(f"{self.gateway_url}/health")

    def get_requests(self) -> Dict[str, Any]:
        return self._get(f"{self.gateway_url}/requests")

    def get_env(self, fmt: str = "json") -> Dict[str, Any]:
        return self._get(f"{self.gateway_url}/env", params={"format": fmt})

    def reset(self) -> Dict[str, Any]:
        return self._post(f"{self.gateway_url}/reset")

    def seed(self, yaml_data: str) -> Dict[str, Any]:
        return self._post(f"{self.gateway_url}/seed",
                          data=yaml_data, headers={"Content-Type": "application/yaml"})

    # --- Data Browse (direct emulator APIs) ---

    def browse_gcs(self, project: str = "local-project") -> Dict[str, Any]:
        data = self._get(f"{self.gcs_url}/storage/v1/b",
                         params={"project": project}, verify_ssl=False)
        if "error" in data:
            return data
        items = data.get("items", [])
        return {"buckets": [{"name": b["name"], "timeCreated": b.get("timeCreated", ""),
                             "location": b.get("location", "")} for b in items]}

    def browse_gcs_objects(self, bucket: str) -> Dict[str, Any]:
        data = self._get(f"{self.gcs_url}/storage/v1/b/{bucket}/o", verify_ssl=False)
        if "error" in data:
            return data
        items = data.get("items", [])
        return {"objects": [{"name": o["name"], "size": o.get("size", "0"),
                             "contentType": o.get("contentType", ""),
                             "updated": o.get("updated", "")} for o in items]}

    def browse_pubsub_topics(self, project: str = "local-project") -> Dict[str, Any]:
        data = self._get(f"{self.gateway_url}/v1/projects/{project}/topics")
        if "error" in data:
            return data
        topics = data.get("topics", [])
        return {"topics": [{"name": t.get("name", "")} for t in topics]}

    def browse_pubsub_subscriptions(self, project: str = "local-project") -> Dict[str, Any]:
        data = self._get(f"{self.gateway_url}/v1/projects/{project}/subscriptions")
        if "error" in data:
            return data
        subs = data.get("subscriptions", [])
        return {"subscriptions": [{"name": s.get("name", ""),
                                   "topic": s.get("topic", "")} for s in subs]}

    def browse_secrets(self, project: str = "local-project") -> Dict[str, Any]:
        """List secrets via Secret Manager gRPC-REST endpoint."""
        data = self._get(f"{self.gateway_url}/v1/projects/{project}/secrets")
        if "error" in data:
            return data
        secrets = data.get("secrets", [])
        return {"secrets": [{"name": s.get("name", ""),
                             "createTime": s.get("createTime", "")} for s in secrets]}

    def browse_cloudtasks_queues(self, project: str = "local-project") -> Dict[str, Any]:
        data = self._get(
            f"{self.gateway_url}/v2/projects/{project}/locations/us-central1/queues")
        if "error" in data:
            return data
        queues = data.get("queues", [])
        return {"queues": [{"name": q.get("name", ""),
                            "state": q.get("state", "")} for q in queues]}
```

**Step 2: Rewrite app.py**

```python
"""LocalCloud Console Flask backend — thin proxy to Admin API."""

import os
import logging
from pathlib import Path

from flask import Flask, jsonify, request, send_from_directory
from flask_cors import CORS

from proxy import BackendProxy

logging.basicConfig(level=logging.INFO,
                    format='%(asctime)s %(name)s %(levelname)s %(message)s')
logger = logging.getLogger(__name__)

app = Flask(__name__, static_folder='../dist', static_url_path='/')
app.config['JSON_SORT_KEYS'] = False
CORS(app)

GATEWAY_URL = os.environ.get('LOCALCLOUD_GATEWAY', 'http://localhost:8080')
GCS_URL = os.environ.get('GCS_URL', 'https://localhost:4443')
proxy = BackendProxy(gateway_url=GATEWAY_URL, gcs_url=GCS_URL)


# --- Health & Status ---

@app.route('/api/health')
def health():
    data = proxy.get_health()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/requests')
def api_requests():
    data = proxy.get_requests()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/env')
def env():
    data = proxy.get_env()
    return jsonify(data), 502 if "error" in data else 200


# --- Control ---

@app.route('/api/reset', methods=['POST'])
def reset():
    data = proxy.reset()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/seed', methods=['POST'])
def seed():
    yaml_data = request.get_data(as_text=True)
    data = proxy.seed(yaml_data)
    return jsonify(data), 502 if "error" in data else 200


# --- Data Browse ---

@app.route('/api/browse/gcs')
def browse_gcs():
    data = proxy.browse_gcs()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/browse/gcs/<bucket>')
def browse_gcs_objects(bucket):
    data = proxy.browse_gcs_objects(bucket)
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/browse/pubsub')
def browse_pubsub():
    data = proxy.browse_pubsub_topics()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/browse/pubsub/subscriptions')
def browse_pubsub_subs():
    data = proxy.browse_pubsub_subscriptions()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/browse/secretmanager')
def browse_secrets():
    data = proxy.browse_secrets()
    return jsonify(data), 502 if "error" in data else 200

@app.route('/api/browse/cloudtasks')
def browse_cloudtasks():
    data = proxy.browse_cloudtasks_queues()
    return jsonify(data), 502 if "error" in data else 200


# --- Serve frontend ---

@app.route('/')
def index():
    return send_from_directory(app.static_folder, 'index.html')

@app.route('/<path:path>')
def serve_static(path):
    if path.startswith('api/'):
        return jsonify({"error": f"Not found: /{path}"}), 404
    try:
        return send_from_directory(app.static_folder, path)
    except Exception:
        return send_from_directory(app.static_folder, 'index.html')


if __name__ == '__main__':
    port = int(os.environ.get('CONSOLE_PORT', 9090))
    logger.info(f"LocalCloud Console on http://localhost:{port}")
    app.run(port=port, use_reloader=False)
```

**Step 3: Delete cli_runner.py**

Remove the file entirely.

**Step 4: Test backend manually**

```bash
cd localcloud-console/backend
python app.py &
curl -s http://localhost:9090/api/health | python3 -m json.tool
curl -s http://localhost:9090/api/requests | python3 -m json.tool
curl -s http://localhost:9090/api/env | python3 -m json.tool
curl -s http://localhost:9090/api/browse/gcs | python3 -m json.tool
kill %1
```

Expected: JSON responses from each endpoint, no errors.

**Step 5: Commit**

```bash
git add localcloud-console/backend/
git commit -m "refactor: replace CLI-based console backend with Admin API proxy"
```

---

### Task 2: Rewrite frontend API layer

**Files:**
- Rewrite: `localcloud-console/src/api.js`

**Step 1: Replace api.js**

```javascript
const BASE = '';

async function get(path) {
    const r = await fetch(`${BASE}${path}`);
    if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
    return r.json();
}

async function post(path, body) {
    const opts = { method: 'POST' };
    if (body) {
        opts.headers = { 'Content-Type': 'application/json' };
        opts.body = JSON.stringify(body);
    }
    const r = await fetch(`${BASE}${path}`, opts);
    if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
    return r.json();
}

export const api = {
    health: () => get('/api/health'),
    requests: () => get('/api/requests'),
    env: () => get('/api/env'),
    reset: () => post('/api/reset'),
    browse: (service, sub) => get(`/api/browse/${service}${sub ? '/' + sub : ''}`),
};
```

**Step 2: Commit**

```bash
git add localcloud-console/src/api.js
git commit -m "refactor: simplify frontend API layer for proxy-only backend"
```

---

### Task 3: Build GCP-style frontend — CSS foundation

**Files:**
- Rewrite: `localcloud-console/src/styles/main.css`
- Rewrite: `localcloud-console/src/styles/layout.css`
- Rewrite: `localcloud-console/src/styles/components.css`

Use the `frontend-design:frontend-design` skill for this task. The CSS should implement:

**Color system:** Light mode default with dark mode toggle.
- Light: `#fff` bg, `#f8f9fa` surface, `#1a73e8` primary, `#202124`/`#5f6368` text, `#34a853` success, `#ea4335` error
- Dark: `#202124` bg, `#292a2d` surface, `#8ab4f8` primary, `#e8eaed`/`#9aa0a6` text

**Layout:** 64px top bar, 256px collapsible sidebar, content area with 24px padding.

**Components:** Service cards with status dots, data tables with hover, status pills, buttons (filled primary, outlined secondary), breadcrumbs.

**Step 1: Write all three CSS files with GCP color palette and layout**
**Step 2: Commit**

```bash
git add localcloud-console/src/styles/
git commit -m "feat: GCP-style CSS foundation with light/dark mode"
```

---

### Task 4: Build GCP-style frontend — app shell and pages

**Files:**
- Rewrite: `localcloud-console/src/app.jsx`
- Rewrite: `localcloud-console/src/pages/Dashboard.jsx`
- Rewrite: `localcloud-console/src/pages/Services.jsx`
- Rewrite: `localcloud-console/src/pages/Logs.jsx`
- Rewrite: `localcloud-console/src/pages/DataBrowser.jsx`
- Rewrite: `localcloud-console/src/pages/Settings.jsx`

Use the `frontend-design:frontend-design` skill for this task. Key requirements:

**App shell (app.jsx):**
- GCP-style sidebar with navigation icons + labels
- Top bar with "LocalCloud" branding + project ID + dark mode toggle
- Sidebar items: Dashboard, APIs & Services, Logs, Data Browser, Settings
- Auto-refresh polling for health data (5s interval)
- Dark mode state management

**Dashboard page:**
- Overall health banner (healthy/degraded)
- Service grid: 13 cards, each with status dot (green/red), name, port, protocol
- Uptime and project ID display
- Quick actions: Reset All, Copy Env Vars buttons

**Services page:**
- Table: Service Name, Status (dot + text), Port, Environment Variable, Protocol
- Read-only (no start/stop)
- Click row to see detail panel with endpoint URL

**Logs page (Request Log):**
- Fetch from `/api/requests`
- Table: Time, Method, Path, Status, Latency
- Filters: service dropdown, method selector
- Auto-refresh toggle
- Empty state when no requests logged

**Data Browser page:**
- Left panel: service tabs (GCS, Pub/Sub, Secret Manager, Cloud Tasks)
- Right panel: service-specific data tables
- GCS: bucket list → click bucket → object list
- Pub/Sub: topics list, subscriptions list
- Secret Manager: secrets list
- Cloud Tasks: queues list
- Loading states, empty states, error states

**Settings page:**
- Environment variables display with copy button (shell format)
- Dark mode toggle
- Auto-refresh interval slider
- About section with version

**Step 1: Write all JSX files**
**Step 2: Build and test**

```bash
cd localcloud-console && node build.js
```

**Step 3: Commit**

```bash
git add localcloud-console/src/
git commit -m "feat: GCP-style console UI with dashboard, services, logs, data browser"
```

---

### Task 5: Build, integrate, and verify

**Files:**
- Modify: `localcloud-console/build.js` (if needed)
- Modify: `localcloud-console/src/index.html` (if needed)

**Step 1: Build the frontend**

```bash
cd localcloud-console && node build.js
```

**Step 2: Start the backend and verify in browser**

```bash
cd localcloud-console/backend && python app.py
```

Open http://localhost:9090 and verify:
- Dashboard shows all services with correct green/red status
- Services page shows table with ports and env vars
- Logs page shows request log (may be empty initially)
- Data Browser shows GCS buckets, Pub/Sub topics, secrets, queues
- Settings shows env vars and dark mode toggle works
- Dark mode toggles correctly

**Step 3: Commit**

```bash
git add localcloud-console/
git commit -m "feat: complete console redesign with GCP-style UI and functional data browsing"
```
