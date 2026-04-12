## Context

LocalCloud emulates 14 GCP services in a single Docker container. Developers often use a hybrid setup: some services run locally (env vars point to localhost) while others connect to real Google Cloud (env vars unset or pointing to cloud endpoints). Today, the console shows health status but not routing — developers must manually track which env vars are set to know where traffic goes.

The console also lacks the ability to enable/disable individual services. All 14 services start by default, consuming memory and CPU even when unused.

## Goals / Non-Goals

**Goals:**
- Show a clear Local/Cloud indicator per service in Dashboard, Services page, and sidebar
- Let users override routing indicators when automatic detection isn't possible
- Enable/disable individual emulator services from the console
- Persist user preferences (routing overrides, enable/disable state) across page refreshes

**Non-Goals:**
- Detecting routing from the developer's terminal session (the console runs in the browser, not the shell — it can only check what the backend knows)
- Automatically intercepting or redirecting SDK traffic
- Supporting partial service states (a service is either fully enabled or fully disabled)
- Modifying the actual environment variables in the developer's shell

## Decisions

### 1. Routing detection: Backend-assisted + user override

**Decision:** The backend exposes `GET /_localcloud/routing` which reports, per service, whether its emulator is running and reachable. The frontend combines this with user overrides stored in localStorage.

**Rationale:** The backend can check if each emulator process is running (via supervisord) and if the emulator port is open. However, it cannot know what environment variables are set in the developer's shell session — that's a client-side concern. So:
- Backend provides: `{ serviceId: { emulatorRunning: true/false, port: N } }`
- Frontend logic: If emulator is running AND healthy → default to "Local". If emulator is not running → default to "Cloud". User can override either.
- User overrides stored in localStorage key `localcloud-routing-overrides`.

**Alternatives considered:**
- Pure client-side: Would require the frontend to probe each port, which is blocked by CORS and browser security.
- Env var reporting endpoint: The `/_localcloud/env` endpoint already lists env vars, but these are the container's vars, not the developer's shell vars. Not useful for detection.

### 2. Enable/disable: Supervisord control via admin API

**Decision:** Add `POST /_localcloud/services/{id}/enable` and `POST /_localcloud/services/{id}/disable` endpoints. For external services (GCS, Pub/Sub, etc.), these call supervisord's XML-RPC API to start/stop processes. For facade services (Secret Manager, Cloud Tasks, etc.), these toggle an in-memory flag that gates request routing.

**Rationale:** Supervisord already manages external emulator processes and supports start/stop via its API. Facade services run in-process on the Armeria gateway, so they need a soft toggle (reject requests with 503 when disabled).

**Alternatives considered:**
- Docker exec: Would require Docker socket access from inside the container — security concern.
- Restart container with different env vars: Too heavy for toggling individual services.

### 3. UI: Badge + toggle inline on service cards

**Decision:** Add a small badge ("Local" / "Cloud") next to each service's status dot. Add an enable/disable toggle switch on the Services page detail view. Dashboard cards show the routing badge but not the toggle (to keep them compact).

**Rationale:** Badges are lightweight and don't break existing layouts. The toggle belongs on the Services page where users go for service management.

### 4. Routing state values

Three states per service:
- **Local** (green badge): Emulator is running and healthy, traffic goes to LocalCloud
- **Cloud** (blue badge): Emulator is not running or user explicitly marked as Cloud
- **Unknown** (gray badge): Cannot determine (e.g., service is enabled but unhealthy)

## Risks / Trade-offs

- **[Risk] Routing indicator may be misleading** → Mitigated by allowing user overrides and showing a tooltip explaining that routing depends on the developer's shell env vars, not just the emulator status.
- **[Risk] Disabling a service while requests are in-flight** → Mitigated by returning 503 (Service Unavailable) for new requests rather than killing active connections.
- **[Risk] Supervisord XML-RPC overhead** → Low risk; supervisord's API is local Unix socket, sub-millisecond latency.
- **[Trade-off] localStorage for overrides** → Per-browser, not per-user. Acceptable for a local dev tool.
