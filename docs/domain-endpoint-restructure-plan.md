# LocalCloud Domain & Endpoint Restructuring Plan

**Status:** Proposed  
**Date:** 2026-05-21  
**Target:** `cloud.localhost` + root-level admin endpoints

---

## Overview

Two changes that make LocalCloud feel more polished:

| Goal | Problem | Solution |
|------|---------|----------|
| `cloud.localhost` instead of `localhost:8080` | Port number in URL feels unpolished | Add Caddy reverse proxy (port 80 → port 8080) |
| `/health` instead of `/health` | Unnecessary prefix on admin endpoints | Register developer-facing services at root-level paths too |

Both `cloud.localhost` (through Caddy) and `localhost:8080` (direct) must continue working.

---

## Phase 1 — Caddy Reverse Proxy

### Files changed: 6

---

### 1. `Dockerfile` — runtime stage

**Install Caddy** (add after the existing apt-get block):

```dockerfile
RUN apt-get update && apt-get install -y caddy && \
    setcap 'cap_net_bind_service=+ep' /usr/bin/caddy
```

`setcap` lets Caddy bind to port 80 as a non-root user — avoids changing the existing supervisord user model. All other programs continue running as `localcloud`.

**Copy Caddyfile** (add to the COPY section):

```dockerfile
COPY Caddyfile /etc/caddy/Caddyfile
```

**Expose port 80** (update existing EXPOSE line):

```
EXPOSE 80 8080 3306 4443 5432 6379 6443 8085 8086 8087 9010 9020 9050 9060
```

---

### 2. `Caddyfile` — new file at project root

```
cloud.localhost {
    reverse_proxy localhost:8080

    log {
        output file /var/log/localcloud/caddy-access.log
    }
}
```

- Proxies `cloud.localhost:80` → `localhost:8080`
- `localhost:8080` still hits the gateway directly (not through Caddy) — backward compat
- Caddy access logs go to `/var/log/localcloud/caddy-access.log`

---

### 3. `supervisord.conf`

Add new program entry:

```ini
[program:caddy]
command=/usr/bin/caddy run --config /etc/caddy/Caddyfile
autostart=true
autorestart=true
priority=35
stderr_logfile=/var/log/localcloud/caddy.err.log
stdout_logfile=/var/log/localcloud/caddy.out.log
```

Priority 35 ensures Caddy starts after `localcloud-server` (priority 30). During the brief gap before the gateway is ready, Caddy returns 502s — fine because nobody hits `cloud.localhost` during container boot.

---

### 4. `docker-entrypoint.sh`

No changes needed. `setcap` handles port 80 binding without requiring root.

---

### 5. `start.sh`

Add port 80 mapping:

```bash
docker run -d --name localcloud \
  -p 127.0.0.1:80:80 \
  -p 127.0.0.1:8080:8080 \
  ...
```

---

### 6. Dockerfile QUICK START comment (top of file)

Update the example `docker run` to include `-p 80:80`.

---

### Phase 1 — What Works

| URL | What it does |
|-----|-------------|
| `http://cloud.localhost/` | Opens console (no port number) |
| `http://localhost:8080/` | Console (backward compat — direct to gateway) |
| Both routes reach the same gateway | All endpoints work on either URL |

### Phase 1 — Edge Cases

| Case | Behavior |
|------|----------|
| Port 80 already in use on host | Drop `-p 80:80`, use `localhost:8080` as before |
| `.localhost` doesn't resolve on this system | Fallback: `localhost:8080` |
| HTTPS needed | `.localhost` is a secure context per RFC 6761 — browsers treat it as trustworthy without TLS |
| Console uses WebSocket | Caddy proxies WebSocket transparently |

---

## Phase 2 — Root-Level Admin Endpoints

### Problem

The console SPA is served at `/`, but all admin/dev endpoints are nested under `/`. This adds noise to every developer command.

| Current | Desired |
|---------|---------|
| `/health` | `/health` |
| `/health/{service}` | `/health/{service}` |
| `/services` | `/services` |
| `/usage` | `/usage` |
| `/export` | `/export` |

### Approach: Dual Registration

Register `HealthCheckService` and `ExportService` at **both** `` (existing, for backward compat) **and** `/` (new root). Armeria matches most-specific routes first, so root-level admin paths take priority over the console's `serviceUnder("/")`.

### Route inventory

**HealthCheckService** (`src/main/java/com/localcloud/gateway/HealthCheckService.java`):

| Method | Existing path | New path |
|--------|---------------|----------|
| `@Get("/health")` | `/health` | `/health` |
| `@Get("/health/{service}")` | `/health/{service}` | `/health/{service}` |
| `@Get("/services")` | `/services` | `/services` |
| `@Get("/usage")` | `/usage` | `/usage` |

**ExportService** (`src/main/java/com/localcloud/admin/ExportService.java`):

| Method | Existing path | New path |
|--------|---------------|----------|
| `@Get("/export")` | `/export` | `/export` |

No path conflicts with console static files (which live at `/index.html`, `/assets/*`, other SPA routes).

### Files changed: 6

---

### 1. `LocalCloudApplication.java` — gateway route registration

After the existing `` registrations (lines 222-228), add:

```java
// Root-level aliases for developer-facing admin endpoints.
// Armeria matches most-specific routes first, so these take priority
// over the console's serviceUnder("/"). The  originals
// remain for backward compatibility.
sb.annotatedService("/", healthCheckService);
sb.annotatedService("/", exportService);
```

---

### 2. `ServiceGatingDecorator.java`

Currently bypasses `/*` and root `/`. Add root-level admin paths:

```java
if (path.startsWith("") || path.startsWith("/icons") ||
    path.equals("/") || path.equals("/health") || path.equals("/services") ||
    path.equals("/usage") || path.startsWith("/export") || path.startsWith("/health/")) {
    return delegate.serve(ctx, req);
}
```

---

### 3. `IamMiddleware.java`

Same pattern — add root-level paths to the IAM bypass condition so they're accessible without authentication:

```java
if (ctx.path().startsWith("") || ctx.path().equals("/health") ||
    ctx.path().startsWith("/health/") || ctx.path().startsWith("/export") ||
    ctx.path().equals("/services") || ctx.path().equals("/usage") || ...) {
    return delegate.serve(ctx, req);
}
```

---

### 4. `Dockerfile` — update HEALTHCHECK

```dockerfile
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD curl -sf http://localhost:8080/health || exit 1
```

Uses `localhost:8080` (not `cloud.localhost`) to avoid depending on DNS resolution inside the container.

---

### 5. `docker-entrypoint.sh`

Check for any `/health` curl calls during startup and update to `/health`.

---

### 6. Tests — update assertions

- `ServiceGatingDecoratorTest.java` — any assertion referencing `/health` path
- `IamMiddlewareTest.java` — any assertion referencing `/services/gcs/config` path

---

### Phase 2 — What Works

| Before | After |
|--------|-------|
| `curl localhost:8080/health` | `curl localhost:8080/health` |
| `curl cloud.localhost/export?format=shell` | `curl cloud.localhost/export?format=shell` |
| Docker HEALTHCHECK at `/health` | HEALTHCHECK at `/health` |
| Old `/*` paths | Old paths **still work** — no breaking changes |

---

## Phase 3 — Website Documentation Updates

### Files changed: 3

---

### 1. `src/components/HomepageVariationFieldManual.astro`

Update `dockerCmd` to include port 80:

```
docker run -d \\
  -p 80:80 -p 8080:8080 -p 4443:4443 \\
  ...
```

Update the env export command to use the new root path:

```
eval "$(curl -s localhost:8080/export?format=shell)"
```

Or if the user prefers the domain version:

```
eval "$(curl -s cloud.localhost/export?format=shell)"
```

### 2. `src/pages/docs/index.mdx`

Show `http://cloud.localhost/` as the primary console URL in the Getting Started guide.

### 3. `src/pages/docs/console.mdx`

Update console access instructions to mention `cloud.localhost`.

---

## Summary of All Changes

### localcloud project (12 files)

| # | File | Phase | Change |
|---|------|-------|--------|
| 1 | `Dockerfile` | 1+2 | Install Caddy, `setcap`, copy Caddyfile, EXPOSE 80, update HEALTHCHECK, update QUICK START comment |
| 2 | `Caddyfile` | 1 | **New** — reverse proxy `cloud.localhost` → `localhost:8080` |
| 3 | `supervisord.conf` | 1 | Add `[program:caddy]` at priority 35 |
| 4 | `start.sh` | 1 | Add `-p 127.0.0.1:80:80` |
| 5 | `docker-entrypoint.sh` | 2 | Check for `/health` references, update to `/health` |
| 6 | `LocalCloudApplication.java` | 2 | Dual-register HealthCheckService and ExportService at `/` |
| 7 | `ServiceGatingDecorator.java` | 2 | Add root-level admin paths to bypass list |
| 8 | `IamMiddleware.java` | 2 | Add root-level admin paths to bypass list |
| 9 | `ServiceGatingDecoratorTest.java` | 2 | Update test assertions |
| 10 | `IamMiddlewareTest.java` | 2 | Update test assertions |

### localcloud-site project (3 files)

| # | File | Change |
|---|------|--------|
| 1 | `HomepageVariationFieldManual.astro` | Add `-p 80:80`, update env export URL |
| 2 | `src/pages/docs/index.mdx` | Show `cloud.localhost` as primary URL |
| 3 | `src/pages/docs/console.mdx` | Update console access instructions |
