# Docker UX: Smart Defaults

**Date**: 2026-04-19
**Status**: Approved
**Scope**: Image defaults, startup experience, compose cleanup

## Context

With the CLI removed (Path B decision), Docker is the sole interface. The current `docker run` command works but has unnecessary friction: mandatory volume pre-creation, no startup guidance, and compose gotchas.

Design principle: *anything that can be taken care of programmatically, should be.*

## Changes

### 1. Volume Auto-Management

Remove the mandatory `docker volume create localcloud-data` step.

- **docker-compose.yml**: Remove `external: true` and `name: localcloud-data` from the volumes section. Let Compose auto-create the volume.
- **docker run**: The Dockerfile already has `VOLUME /var/lib/localcloud`. Docker creates an anonymous volume automatically. Users who want a named volume can still pass `-v localcloud-data:/var/lib/localcloud`.
- **README.md**: Remove the `docker volume create` line from quick start.

### 2. Startup Banner

Add a welcome banner to `docker-entrypoint.sh` that prints after the gateway health check passes. Printed by the existing background seed process.

```
═══════════════════════════════════════════════════════
  LocalCloud is ready!

  Console:  http://localhost:8080
  Health:   http://localhost:8080/_localcloud/health

  Configure your SDKs:
    eval "$(curl -s http://localhost:8080/_localcloud/env?format=shell)"

  Enabled services: GCS, Pub/Sub, Firestore, BigQuery,
    Secret Manager, Cloud Tasks, Spanner, Bigtable,
    Logging, Monitoring, Memorystore, Workflows
═══════════════════════════════════════════════════════
```

The enabled services list is built dynamically from the `LOCALCLOUD_ENABLE_*` env vars.

### 3. Compose Cleanup

- Remove `docker.sock` mount (only needed for GKE, which is disabled by default). Add a comment showing how to enable it.
- Remove `external: true` on the volume (covered in section 1).

### 4. Ports (No Change)

Keep all ports fixed and explicit. Predictable ports are more valuable than a shorter command. The copy-paste from docs is a one-time cost.

## Files Changed

| File | Change |
|------|--------|
| `docker-entrypoint.sh` | Add startup banner after gateway health check |
| `docker-compose.yml` | Remove `external: true`, comment out `docker.sock` |
| `README.md` | Remove `docker volume create` from quick start |

## Not Changed

- `Dockerfile` — already has `VOLUME` directive
- Port mapping — stays as-is (all ports fixed)
- `supervisord.conf` — no changes needed
