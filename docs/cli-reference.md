# LocalCloud CLI Reference

[`README.md`](../README.md) covers installation and the shortest path to a running environment. This reference documents every command and output mode. `lc` and `localcloud` share the same commands, flags, and behavior.

## Commands

### `start`

Starts the container runtime on the selected data volume and initializes the project context.

```sh
lc start
lc start --project-id my-project
lc start --data-volume isolated-data --user alice
lc start ./custom-config.yaml
lc start --memory 8g --image myrepo/localcloud:dev --services gcs,pubsub,firestore
```

- Reuses an existing running container attached to the same volume.
- Creates project contexts when they do not exist.
- Waits up to 60 seconds for container health and service readiness.
- Enables TLS by default. `--no-tls` disables it; `--tls` re-enables it explicitly. The flag overrides `host.environment.LOCALCLOUD_TLS_ENABLED` in `localcloud.yaml`.
- `--memory` overrides `host.memory` (default: `4g`).
- `--image` overrides `host.image` and `LOCALCLOUD_IMAGE` (default: `jaysen2apache/localcloud:latest`).
- `--services` overrides `services.enabled` with a comma-separated list of service IDs, or `default` to use the built-in set.

### `status`

Inspects runtime health, Docker container state, endpoints, and ownership.

```sh
lc status
lc status --verbose
lc status --data-volume isolated-data
```

### `env`

Generates Google Cloud SDK configuration for the active project context.

```sh
# Export directly into the current shell
eval "$(lc env)"

# Output a JSON payload
lc env --format json

# Generate Terraform/OpenTofu provider endpoint configuration
lc env --format terraform

# Generate Docker Compose environment variables
lc env --format docker-compose
```

### `console`

Opens the LocalCloud browser console for the selected project and user.

```sh
lc console
lc console --project-id my-project --user alice
```

### `logs`

Prints recent logs from the active LocalCloud container runtime.

```sh
lc logs
lc logs --tail 500
```

### `restart`

Restarts the LocalCloud runtime and reapplies volatile seed data without deleting persistent volume state.

```sh
# Fast in-place restart (default: --no-pull)
lc restart

# Pull the latest image before restarting
lc restart --pull

# Restart without TLS
lc restart --no-tls

# Restart with different memory and services
lc restart --memory 8g --services gcs,pubsub
```

### `reset`

Resets emulator data and reapplies initial seed state.

```sh
# Reset only the selected project (default)
lc reset

# Reset all projects and recreate the managed data volume
lc reset --all-projects
```

### `stop`

Stops the runtime without deleting persistent volume data.

```sh
lc stop
lc stop --data-volume isolated-data
```

### `doctor`

Diagnoses Docker daemon access and permissions and inspects legacy LocalCloud host files.

```sh
lc doctor
```

### `cleanup`

Finds and removes malformed Docker resources, stale runtime state, and legacy lock files.

```sh
# Remove cleanup candidates (default)
lc cleanup

# Inspect candidates without removing them
lc cleanup --dry-run
```

### `guide`

Prints authoritative workflow guidance for AI coding agents and automated developer scripts.

```sh
lc guide
```

### `mcp`

Runs the stdio Model Context Protocol bridge for AI tools and coding environments.

```sh
lc mcp
lc mcp --project-id my-project
```

## Output Modes

### Interactive terminals

Interactive commands display lifecycle spinners and elapsed time. `doctor`, `status`, and `reset` also display the colorful LocalCloud artwork and resolved context. `start`, `restart`, and `stop` keep progress compact while logs stream.

Service selection uses both color and symbols: `●` means selected and `○` means off. The state remains legible when color is disabled.

### Complete JSON with `--verbose`

`doctor`, `cleanup`, `start`, `restart`, `reset`, `stop`, `status`, `logs`, and `console` accept `--verbose` and return their complete structured result as JSON.

```sh
lc status --verbose | jq '{
  status,
  data_volume,
  container: {
    name: .container.name,
    state: .container.state,
    url: .container.url
  },
  services
}'
```

Example result:

```json
{
  "status": "running",
  "data_volume": "localcloud-data",
  "container": {
    "name": "localcloud",
    "state": "running",
    "url": "http://127.0.0.1:49080"
  },
  "services": ["gcs", "pubsub"]
}
```

`status` describes Docker runtime state and does not include request-scoped project, user, or MCP configuration. Use `lc start --verbose` for the complete project and MCP connection result. `env` uses `--format json`; `mcp` reserves stdout for the stdio protocol.

### Additional summary fields with `--fields`

`doctor`, `start`, `restart`, `reset`, `stop`, and `status` accept `--fields` to append supported JSON paths to their concise summary:

```sh
lc start --fields container.name,mcp.direct_url
lc doctor --fields active_runtime,volume_collisions
```

Each command's `--help` output lists its valid field paths. Invalid input reports the valid choices directly.

### Color control

- `NO_COLOR=1` or a dumb terminal disables ANSI color escapes.
- TrueColor, ANSI-256, and ANSI-16 palettes degrade according to terminal capabilities.
- Symbols and text continue to carry status when color is unavailable.

## Related References

- [Configuration and runtime identity](configuration.md)
- [SDK, Terraform, and MCP integrations](integrations.md)
