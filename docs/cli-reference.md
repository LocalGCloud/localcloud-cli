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
- TLS is disabled by default. `--tls` enables it; `--no-tls` overrides an enabled configuration value.
- `--memory` overrides `host.memory` (default: `4g`).
- `--image` overrides `host.image` and `LOCALCLOUD_IMAGE` (default: `jaysen2apache/localcloud:latest`).
- `--services` overrides `services.enabled` with a comma-separated list of service IDs, or `default` to use the built-in set.

Docker socket access uses the tri-state `host.docker_socket` setting and defaults
to `auto`:

```yaml
host:
  docker_socket: auto # true forces; false is a hard opt-out
```

In `auto` mode, the CLI mounts the local Docker socket when any enabled service
requires it: Compute Engine (`compute`), Cloud Run (`cloudrun`), GKE (`gke`), or
Dataproc (`dataproc`). With none of those services enabled, it leaves the socket
unmounted. `true` always requests the mount; `false` never mounts it and the
affected service APIs report that Docker access is disabled. A required but
missing local socket fails preflight before an existing runtime is replaced.

`LOCALCLOUD_DOCKER_ACCESS` is an optional `auto`, `true`, or `false` environment
override and is normally unset. It takes precedence over `host.docker_socket`.
Socket access does not enable the generic workload runtime:
`LOCALCLOUD_RUNTIME_EMBEDDED_DOCKER` remains an independent, explicit setting
that defaults to `false`.

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

# Restart with TLS
lc restart --tls

# Restart with different memory and services
lc restart --memory 8g --services gcs,pubsub
```

### `reset`

Resets emulator data and reapplies initial seed state.

```sh
# Reset only the selected project (default)
lc reset

# Print the manual steps to recreate every project on the volume
lc reset --all-projects
```

`lc reset` (no flag) resets a single project through the LocalCloud API and
never touches the Docker data volume. `lc reset --all-projects` does not mutate
anything: recreating every project means deleting the data volume, and
localcloud never runs `docker volume rm` for you. It prints the steps (`lc stop`,
`docker volume rm -f <volume>`, `lc start`) and exits non-zero so nothing is
destroyed by accident.

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

### Lifecycle dry runs and debug diagnostics

`start`, `restart`, `reset`, and `stop` accept `--dry-run`. The command
validates configuration, inspects local Docker state, and prints every planned
Docker and LocalCloud mutation in execution order without changing Docker,
runtime data, project data, seed state, or active host state.

```sh
lc start --dry-run
lc restart --dry-run
lc reset --dry-run
lc stop --dry-run
```

`lc reset --all-projects --dry-run` renders the manual recreate steps (it never
had a Docker mutation to plan).

Lifecycle dry runs are strictly read-only. They never pull images.
`--dry-run --pull` is rejected because the CLI cannot know the exact
post-pull image configuration without performing the pull. A create or
recreate preview requires the configured image to exist locally.

The rendered `docker run` command is the minimal operator-equivalent command:
it omits image-inherited environment, image labels, and CLI-internal management
metadata. When selected, `$HOME/.localcloud/localcloud.yaml` and a user seed
file appear as read-only `-v` bind mounts.

Use `--debug` for diagnostics on stderr. For a selected or planned runtime,
debug output includes one shell-quoted `docker run` command that can be copied
and executed. Contiguous one-to-one published ports use Docker range syntax,
for example `-p 127.0.0.1:24080-24092:24080-24092/tcp`, instead of one flag
per port. Dry-run plans remain on stdout and can be redirected independently.

Lifecycle commands derive bindings from the LocalCloud configuration rather
than trusting Docker image `EXPOSE` metadata. Metadata drift emits a warning;
pass `--strict-port-validation` to turn that warning into a preflight failure.

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
lc mcp --connect-timeout 30
```

When run directly in an interactive terminal, the command reports the resolved
LocalCloud `/mcp` endpoint on stderr before waiting for it to become ready.
Startup waits at most 10 seconds by default; `--connect-timeout SECONDS`
overrides that positive timeout. If the endpoint is still unavailable, the
command exits with `mcp_connection_timeout`.

Once connected, the stdio bridge remains open without a session timeout while
it accepts requests. Pressing Ctrl-C prints `MCP connection closed.`, exits
with status 130, and does not emit a traceback. Non-interactive MCP launchers
receive no lifecycle text, and stdout stays reserved for JSON-RPC traffic.

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
