# LocalCloud Console - Quick Start Guide

Get the LocalCloud Console up and running in 2 minutes.

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
# Using Docker Compose (recommended)
docker compose up -d

# Or using the CLI
localcloud start
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
| **Dashboard** | Home tab | View all 14 services, health status, uptime |
| **Services** | Services tab | See ports, status, and request counts |
| **Service Explorer** | Explorer tab | Deep-dive into service data with SQL queries, file browsing, and schema views |
| **Data Browser** | Data tab | Preview data in Firestore, BigQuery, GCS, Spanner, and more |
| **Logs** | Logs tab | View request logs in real-time, filter by service |
| **Usage** | Usage tab | API usage per service, estimated GCP cost savings |
| **Settings** | Settings tab | Auto-refresh interval, environment export, about |

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Console page is blank | Rebuild: `cd localcloud-console && npm run build` |
| Services show error | Check `curl http://localhost:8080/_localcloud/health` |
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
    ├─ Serves console static files from /opt/localcloud/console/dist/
    ├─ Admin API at /_localcloud/*
    ├─ gRPC facade services (Secret Manager, Cloud Tasks, etc.)
    └─ PostgreSQL (internal persistence)
```

---

**Ready?** Open http://localhost:8080 and start exploring LocalCloud!
