# LocalCloud Container Lifecycle Test Runbook

This runbook documents the required end-to-end CLI lifecycle testing procedure for LocalCloud. Follow this procedure whenever verifying new Docker runtime images (e.g. `agentcloud/localcloud:latest`), testing CLI lifecycle modifications, or qualifying pre-release builds.

## Principles

1. **Isolation First:** Never run exploratory lifecycle tests against the everyday `localcloud-data` volume. Always specify an isolated test volume (e.g. `--data-volume test-lifecycle`) so persistent developer data and container configurations remain unaffected.
2. **Local Image Authority:** Use `--no-pull` to verify the local candidate image directly and avoid redundant network round-trips to Docker Hub during local verification.
3. **Clean Teardown:** Always stop, inspect, and remove ephemeral test containers, networks, and volumes upon test completion, restoring the host state.

---

## Prerequisites

- Docker engine (Docker Desktop, Colima, or `dockerd`) running with at least **4 GiB** of memory.
- Candidate runtime image available locally:
  ```sh
  docker pull agentcloud/localcloud:latest
  ```
- Canonical host ports `5380-5405` free on loopback interfaces.

---

## Lifecycle Test Sequence (11 Operations)

### 1. Doctor & Pre-flight Check
Verify Docker connectivity, image discovery, and host-port availability:

```sh
localcloud doctor
```

**Expected Result:**
- Status `OK`.
- Docker daemon detected with path.
- Image points to `agentcloud/localcloud:latest` with local SHA-256 digest.
- Ports `5380-5405` reported as `(available)`.

---

### 2. Pre-creation Status
Verify status resolution before any container or volume is created:

```sh
localcloud status --data-volume test-lifecycle
```

**Expected Result:**
- Status: `Not created`.
- Data volume: `test-lifecycle`.
- Configured image: `agentcloud/localcloud:latest`.
- Services: `unavailable`.

---

### 3. Dry-Run Mutation Planning
Verify that the CLI computes the correct Docker resource creation plan without applying changes:

```sh
localcloud start --data-volume test-lifecycle --dry-run
```

**Expected Result:**
- `# action: create`
- `# reason: no container uses the selected data volume`
- Exact planned commands printed:
  - `docker volume create ... test-lifecycle`
  - `docker network create ... localcloud-volume-<hash>`
  - `docker run -d ... agentcloud/localcloud:latest`

---

### 4. Start Runtime
Launch the runtime container on the isolated data volume:

```sh
localcloud start --data-volume test-lifecycle --no-pull --tail 2
```

**Expected Result:**
- Creates managed volume and bridge network.
- Boots container and initializes all enabled emulators (Spanner, BigQuery, Pub/Sub, Storage, etc.).
- Auto-seed runs and reaches backend readiness.
- Final output reports `Status Running`, Gateway URL `http://127.0.0.1:5380`, and 22 enabled services.

---

### 5. Running Status Inspection
Inspect active runtime state:

```sh
localcloud status --data-volume test-lifecycle
```

**Expected Result:**
- Status: `Running`.
- Origin: `managed`.
- Ports: `5380-5405`.
- URL: `http://127.0.0.1:5380`.
- All enabled services listed.

---

### 6. SDK Environment Generation
Verify emulator client environment export generation:

```sh
localcloud env --data-volume test-lifecycle
```

**Expected Result:**
- Valid shell exports for all emulators (e.g. `STORAGE_EMULATOR_HOST`, `PUBSUB_EMULATOR_HOST`, `BIGQUERY_EMULATOR_HOST`, `CLOUDSDK_API_ENDPOINT_OVERRIDES_*`).
- Loopback-only endpoints (no real `googleapis.com` endpoints).

---

### 7. Container Log Verification
Confirm log streaming from the running container:

```sh
localcloud logs --data-volume test-lifecycle --tail 20
```

**Expected Result:**
- Recent container log lines displayed.
- Shows successful service start events and auto-seeding completion.

---

### 8. Runtime Restart
Verify that restarting cleanly stops and boots the container while retaining volume identity:

```sh
localcloud restart --data-volume test-lifecycle --no-pull --tail 2
```

**Expected Result:**
- Finds and stops running container.
- Starts container again.
- Flyway reports schema up to date (no migration error).
- Final status returns `Running`.

---

### 9. MCP Stdio Bridge Verification
Verify that the standard I/O Model Context Protocol bridge communicates with the running container:

```sh
echo '{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "test", "version": "1.0"}}}' | localcloud mcp --data-volume test-lifecycle
```

**Expected Result:**
- Connects to `http://127.0.0.1:5380/mcp`.
- Emits JSON-RPC 2.0 response containing server info (`name: localcloud`), capabilities, and instructions.

---

### 10. Stop Runtime
Gracefully stop the running container:

```sh
localcloud stop --data-volume test-lifecycle
```

**Expected Result:**
- Status: `Stopped`.
- Container ID and name displayed.
- Data volume remains intact.

Verify stopped state with `localcloud status --data-volume test-lifecycle` (reports `Stopped`).

---

### 11. Resume Stopped Container
Confirm that starting an existing stopped runtime reattaches without recreating or wiping data:

```sh
localcloud start --data-volume test-lifecycle --no-pull --tail 2
```

**Expected Result:**
- Reattaches to existing stopped container ID.
- Faster startup time (< 20s).
- Status: `Running`.

---

## Teardown & Post-Test Cleanup

Once all operations pass, clean up the ephemeral test resources:

```sh
# 1. Stop the container
localcloud stop --data-volume test-lifecycle

# 2. Inspect container ID and derived network name
CONTAINER_ID=$(docker ps -a --filter "volume=test-lifecycle" --format "{{.ID}}")
NETWORK_NAME=$(docker inspect "$CONTAINER_ID" --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}')

# 3. Remove Docker resources
docker rm -f "$CONTAINER_ID"
docker network rm "$NETWORK_NAME"
docker volume rm test-lifecycle

# 4. Clean ephemeral record from active-runtime.json (if present)
python3 -c "from localcloud_cli.state import clear_active_runtime; from localcloud_cli.config import HostPaths; clear_active_runtime(HostPaths.from_environment(), 'test-lifecycle')"

# 5. Confirm clean host state
localcloud doctor
```
