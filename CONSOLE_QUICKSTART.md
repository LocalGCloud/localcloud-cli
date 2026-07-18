# LocalCloud Console - Quick Start Guide

> **Last updated:** 2026-05-26

Get the LocalCloud Console up and running in 2 minutes.

The console is a Solid.js single-page application served directly by the Armeria gateway — no separate server process needed. It provides a dashboard, service explorer with SQL editor, data browser, log viewer, usage metrics, and settings.

## Prerequisites

- LocalCloud already installed and working
- Node.js 18+ (only needed for building)

## Quick Start

### 1. Build the Frontend (One-Time Setup)

```bash
cd localcloud-console
npm install
npm run build
```

This creates the minified `dist/` files that the Armeria gateway serves.

### 2. Start LocalCloud

```bash
#refer to the script that starts docker container with all the required parameters
./start.sh
```

Wait for the health check to pass.

### 3. Open the Console

Navigate to **http://localhost:8080** in your browser.

The console is served directly by the Armeria gateway — no separate server process needed.

```bash
# Or use the CLI shortcut
localcloud console
```

## What You Can Do

| Feature | Location | What It Does |
|---------|----------|-------------|
| **Dashboard** | Home tab | View all 23 services, health status, uptime, request counts |
| **APIs & Services** | Services tab | See ports, status, routing mode, and env vars per service |
| **Service Explorer** | Explorer tab | Deep-dive into service data with SQL queries, file browsing, and schema views |
| **Data Browser** | Data tab | Preview and mutate data across all services (BigQuery, GCS, Spanner, Firestore, Pub/Sub, Memorystore, Secret Manager) |
| **Logs** | Logs tab | Real-time request logs with filtering and auto-tail |
| **Usage** | Usage tab | API usage per service, estimated GCP cost savings |
| **Settings** | Settings tab | Env var export (shell/Terraform/Docker Compose), SDK examples, cloud routing, auto-refresh, theme |

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Console page is blank | Rebuild: `cd localcloud-console && npm run build` |
| Services show error | Check `curl http://localhost:8080/health` |
| Empty Data Browser | Create test data first (use seed file or SDK examples) |
| Can't reach console | Verify container is running: `docker ps` |

## Next Steps

- Read the full [Developer Guide](DEVELOPER_GUIDE.md) for complete documentation
- See the [Terraform Compatibility Matrix](terraform/COMPATIBILITY.md) for using Terraform with LocalCloud
- Check `CLAUDE.md` in project root for development guidelines
- See `examples/python-sdk-demo/` for SDK usage with LocalCloud

## Architecture

```
Browser (http://localhost:8080)
    ↓
Armeria Gateway (port 8080)
    ├─ Serves console static files (Solid.js SPA)
    ├─ Admin REST API (health, services, browse, seed, reset)
    ├─ In-process gRPC/REST facades (17 services)
    ├─ External emulator routing (GCS:4443, Pub/Sub:8085, Firestore:8086,
    │  Bigtable:8087, Spanner:9010, BigQuery:9050, Memorystore:6379)
    └─ PostgreSQL 17 (persistence)
```

---

**Ready?** Open http://localhost:8080 and start exploring LocalCloud!
