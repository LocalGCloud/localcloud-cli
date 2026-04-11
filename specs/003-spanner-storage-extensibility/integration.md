# Integrating Persistent Spanner Emulator into LocalCloud

## Overview

The forked Cloud Spanner Emulator at `../local_cloud_dependencies/cloud-spanner-emulator/` adds optional LevelDB-backed persistent storage via the `--data_dir` flag. This document describes how to integrate the forked emulator into LocalCloud so Spanner data survives container restarts.

## Prerequisites

The emulator binary must be built before the LocalCloud Docker image. Run from the emulator fork directory:

```bash
cd ../local_cloud_dependencies/cloud-spanner-emulator
./build-offline.sh
```

This produces:
- Docker image: `spanner-emulator-build:latest` (contains `/build/output/emulator_main`)
- Local binary: `artifacts/spanner-emulator-main` (312MB, ELF ARM64)

## Integration Steps

### 1. Dockerfile Changes

Replace the upstream spanner emulator image with the locally built fork:

```dockerfile
# BEFORE (upstream, no persistence):
FROM gcr.io/cloud-spanner-emulator/emulator:latest AS spanner-emulator
# ...
COPY --from=spanner-emulator /gateway_main /usr/local/bin/spanner-gateway
COPY --from=spanner-emulator /emulator_main /usr/local/bin/spanner-emulator-main

# AFTER (forked, with persistence):
FROM spanner-emulator-build:latest AS spanner-emulator-fork
FROM gcr.io/cloud-spanner-emulator/emulator:latest AS spanner-emulator-upstream
# ...
# Use gateway from upstream (unchanged), emulator from our fork
COPY --from=spanner-emulator-upstream /gateway_main /usr/local/bin/spanner-gateway
COPY --from=spanner-emulator-fork /build/output/emulator_main /usr/local/bin/spanner-emulator-main
```

**Why two images?** The fork currently only builds `emulator_main` (the C++ gRPC backend). The `gateway_main` (Go REST gateway) is unchanged and can be taken from upstream.

### 2. Supervisord Changes

Update `supervisord.conf` to pass `--data_dir` to the emulator:

```ini
# BEFORE:
[program:spanner-emulator]
command=/usr/local/bin/spanner-gateway --hostname 0.0.0.0 --grpc_binary /usr/local/bin/spanner-emulator-main

# AFTER:
[program:spanner-emulator]
command=/usr/local/bin/spanner-gateway --hostname 0.0.0.0 --grpc_binary /usr/local/bin/spanner-emulator-main --emulator_args="--data_dir=/var/lib/localcloud/spanner-data"
```

**Note:** The gateway spawns the emulator binary as a subprocess. The `--emulator_args` flag passes arguments through to `emulator_main`. If the gateway doesn't support `--emulator_args`, use a wrapper script instead (see Alternative below).

#### Alternative: Wrapper Script

If `--emulator_args` is not supported by the gateway, create a wrapper:

```bash
# /usr/local/bin/spanner-emulator-wrapper
#!/bin/bash
exec /usr/local/bin/spanner-emulator-main --data_dir=/var/lib/localcloud/spanner-data "$@"
```

Then in `supervisord.conf`:
```ini
command=/usr/local/bin/spanner-gateway --hostname 0.0.0.0 --grpc_binary /usr/local/bin/spanner-emulator-wrapper
```

### 3. Data Directory

The Spanner data directory must be created and included in the persistent volume:

```dockerfile
# In Dockerfile, add to the mkdir chain:
RUN mkdir -p /var/lib/localcloud/spanner-data \
    && chown -R localcloud:localcloud /var/lib/localcloud/spanner-data
```

The existing `VOLUME /var/lib/localcloud` and `localcloud-data` Docker volume already cover this path, so Spanner data will persist alongside GCS, PostgreSQL, and other service data.

### 4. docker-compose.yml

No changes needed. The existing volume mount handles persistence:

```yaml
volumes:
  - localcloud-data:/var/lib/localcloud
```

### 5. Build Order

The LocalCloud build depends on the emulator build. Update `build.sh` or document:

```bash
# 1. Build the forked spanner emulator (one-time, ~60 min first build)
cd ../local_cloud_dependencies/cloud-spanner-emulator
./build-offline.sh

# 2. Build LocalCloud (uses spanner-emulator-build image)
cd ../localcloud
docker compose build
```

## Behavior

| `--data_dir` | Storage | Data survives restart? |
|---|---|---|
| Not set (empty) | In-memory (original behavior) | No |
| Set to a path | LevelDB at that path | Yes |

When `--data_dir=/var/lib/localcloud/spanner-data` is set:
- Each Spanner database gets a subdirectory: `spanner-data/{database_id}/storage/`
- LevelDB files are stored there (SST files, WAL, MANIFEST)
- Data persists across `docker compose down` / `docker compose up` cycles
- Removing the Docker volume (`docker volume rm localcloud-data`) clears all data

## Verifying the Integration

```bash
# 1. Start LocalCloud with Spanner enabled
LOCALCLOUD_SERVICES=spanner docker compose up -d

# 2. Create an instance and database, insert data
# (use gcloud or client SDK pointed at localhost:9010)

# 3. Restart the container
docker compose restart

# 4. Query the data — it should still be there

# 5. Check LevelDB files exist
docker compose exec localcloud ls /var/lib/localcloud/spanner-data/
```

## File Changes Summary

| File | Change |
|------|--------|
| `Dockerfile` | Replace spanner emulator source image with local fork |
| `supervisord.conf` | Add `--data_dir` to spanner emulator launch command |
| `build.sh` (optional) | Add emulator build step before LocalCloud build |

## Architecture

```
LocalCloud Container
├── /usr/local/bin/spanner-gateway         ← from upstream (unchanged)
├── /usr/local/bin/spanner-emulator-main   ← from fork (with persistence)
└── /var/lib/localcloud/
    └── spanner-data/                      ← LevelDB persistent storage
        ├── {database_id_1}/storage/       ← per-database LevelDB
        └── {database_id_2}/storage/
```

## Known Limitations

- Only `emulator_main` is built from the fork; `gateway_main` uses upstream
- The fork's devcontainer base image is Ubuntu 18.04; GCC 13 is installed during build (adds ~2 min)
- First build takes ~60 minutes; subsequent builds with cached layers take ~5 minutes
- Zscaler corporate proxy requires `java_tools-v12.7.zip` to be manually pre-downloaded into `bazel-distdir/` (see emulator's `build-offline.sh`)
