# LocalCloud CLI Reference

[`README.md`](../README.md) covers installation and the shortest path to a running environment. This reference documents every command and output mode. `lc` and `localcloud` share the same commands, flags, and behavior.

## Commands

### `update`

```bash
localcloud update
# Equivalent: lc update
```

Updates the CLI to the latest release through its original installation channel.
Script installations reuse `https://local.cloud/install.sh`, preserving their
installation directory and shell configuration. Homebrew installations run
`brew upgrade localcloud`. Source and manual installations receive instructions
for updating through their original installation method.

This command does not require Docker or change containers, volumes, or runtime
configuration. It streams installer/Homebrew output and returns their exit status.

### `start`

Starts the container runtime on the selected data volume and initializes the project context.

```sh
lc start
lc start --local-only
lc start --project-id my-project
lc start --data-volume isolated-data --user alice
lc start ./custom-config.yaml
lc start --memory 8g --image myrepo/localcloud:dev --services gcs,pubsub,firestore
```

- Reuses an existing running container attached to the same volume.
- Creates project contexts when they do not exist.
- Waits up to 60 seconds for container health and service readiness.
- TLS is disabled by default. `--tls` enables it; `--no-tls` overrides an enabled configuration value.
- Docker ports omit the host IP by default (`-p 5380:5380`), using Docker's default
  bind address, normally all host interfaces. `--local-only` binds every published
  port to `127.0.0.1` (`-p 127.0.0.1:5380:5380`), including TLS ports,
  alternative host ports, and transparent-network aliases. SDK endpoints still use localhost.
  LocalCloud publishes its Cloud SQL MySQL companion on the same interfaces. Older
  images publish it on every interface, and `status` warns when that happens.
- `--local-only` is available on `start`, `restart`, and `reset`; pass it each time
  to keep localhost-only publishing. Changing the option recreates a managed
  container while preserving its persistent data volume. Attached containers keep
  their existing bindings.
- `--memory` overrides `host.memory` (default: `4g`).
- `--image` overrides `host.image` and `LOCALCLOUD_IMAGE` (default: `agentcloud/localcloud:latest`).
- `--services` overrides `services.enabled` with a comma-separated list of service IDs, or `default` to use the built-in set.
- `--pull` is enabled by default: checks for a newer image on Docker Hub and pulls only if an update is available. `--no-pull` uses the locally available image only (pulling only if absent locally).
- New runtimes prefer the canonical host ports. If that complete set is unavailable,
  the CLI proposes one contiguous mapping from `5508-5539`, then `5821-5840`,
  then `5322-5342`, and asks before creating the container. It never scans the
  operating system's general ephemeral range. Off the canonical ports a runtime
  publishes only the ports its enabled services use, so four can run beside the
  canonical one.
- `--port-range START-END` (or `host.port_range`) takes the host ports from that
  range instead, even when the canonical ports are free, without asking. Like
  `--local-only`, pass the flag each time; images that predate `host.port_range`
  reject that setting, so use the flag with them.
- With Cloud SQL MySQL enabled (`cloudsql` with `mysql_enabled` not `false`), the
  set includes the companion's port, `5406` by default. LocalCloud publishes it from
  its own MySQL container, so in an alternative mapping it takes the block's last
  port. Each runtime's companion is named after its data volume
  (`localcloud-mysql-<volume>`). Pin the port with `host.environment.LOCALCLOUD_MYSQL_PORT`
  to keep it fixed when the other ports move.

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

# Export Terraform/OpenTofu provider endpoint variables into the current shell.
# The output is shell, not HCL - eval it, do not redirect it into a .tf file.
eval "$(lc env --format terraform)"

# Generate Docker Compose environment variables
lc env --format docker-compose
```

#### `env --identity`

`lc env --identity [--account EMAIL]` gives processes on this machine a local identity:
Application Default Credentials then obtain signed LocalCloud ID and access tokens for one
service account from a metadata server, in every IAM mode.

```sh
# Default account: default@<project>.iam.gserviceaccount.com
eval "$(lc env --identity)"

# A specific service account of the project (email, account ID or unique ID)
eval "$(lc env --identity --account runner@my-project.iam.gserviceaccount.com)"

# Remove the relay, end the session and unset the variables
eval "$(lc env --identity --stop)"
```

It creates a 12-hour session through LocalCloud's guarded `POST /identity/sessions`, starts
a relay container from the image of the running LocalCloud (its `localcloud-relay` binary)
on the runtime's network, publishes the relay's metadata port on `127.0.0.1` only, waits
until the relay is serving, and prints `GCE_METADATA_HOST`, `GCE_METADATA_IP`
(`127.0.0.1:<port>`) and `GOOGLE_CLOUD_PROJECT`. `--format json` prints those variables as
a JSON object and `--format docker-compose` as an `environment:` block; `terraform` prints the
same exports as `shell`. Java is not needed on the host.

- One relay runs per data volume, project and account. Running the command again replaces
  it, ends the previous session and keeps the port when it is free.
- The session relay's capability is passed only to the relay container's environment; the
  command never prints, logs or stores it.
- `GOOGLE_APPLICATION_CREDENTIALS` and gcloud's `application_default_credentials.json` take
  precedence over the metadata server in Google client libraries. The command reports either
  one as a warning and leaves it unchanged.
- In strict and gcp-live IAM modes the `--user` caller needs `iam.serviceAccounts.actAs`
  (for example `roles/iam.serviceAccountUser`) on the account.
- Any process on this machine can use the loopback port while the session lasts, the same
  exposure as an ADC key file. Sessions end after 12 hours, with `--stop` (`--account`
  limits it to that account's relay) and when the project is reset. `lc stop` stops the relay
  with LocalCloud's other child containers; run `lc env --identity` again after the next start.
- The LocalCloud image must provide identity sessions; an older image reports
  `identity_sessions_unsupported`.

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

Stops the running container and starts with the image (preserving persistent volume state and reapplying volatile seed data). Unlike `start`, `restart` does not check the remote registry by default (`--no-pull`), using the currently available local image (e.g. `latest`).

```sh
# Restart container using the currently available image (default: --no-pull)
lc restart

# Check for a newer image on the remote registry before restarting
lc restart --pull

# Restart with TLS
lc restart --tls

# Restart with ports accessible only from this machine
lc restart --local-only

# Restart with different memory and services
lc restart --memory 8g --services gcs,pubsub
```

A managed runtime already using alternative host ports is recreated on
`restart` so it can return to the canonical mapping when those ports are free.
Container ports do not change. If the canonical set is still unavailable, the
CLI displays every proposed host-to-container mapping and defaults to `No`.
Non-interactive callers must opt in explicitly:

```sh
lc restart --accept-dynamic-ports
```

Declining, or omitting that flag without an interactive terminal, leaves the
existing container unchanged.

`restart` keeps the runtime's companion containers, such as Dataproc clusters,
which LocalCloud resumes. The Cloud SQL MySQL companion is the exception: it is
removed with the old container and recreated on demand with the new runtime's
port and name. Its data stays on the volume.

### `reset`

Clears the selected project's emulator data. Reload sample data with the
Console's Re-seed Data action.

```sh
# Reset only the selected project (default)
lc reset

# Print the manual steps to recreate every project on the volume
lc reset --all-projects
```

`lc reset` (no flag) resets a single project through the LocalCloud reset API,
the one the Console uses, so it does not require `mcp.destructive`. It never
touches the Docker data volume. `lc reset --all-projects` does not mutate
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

Companion containers, such as Dataproc clusters and the Cloud SQL MySQL server,
are stopped rather than deleted, and LocalCloud resumes them on the next `start`.

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
When canonical ports are unavailable, dry-run prints the exact alternative
host-port mapping without prompting or mutating Docker.

The rendered `docker run` command is the minimal operator-equivalent command:
it omits image-inherited environment, image labels, and CLI-internal management
metadata. When selected, `$HOME/.localcloud/localcloud.yaml` and a user seed
file appear as read-only `-v` bind mounts.

Use `--debug` for diagnostics on stderr. For a selected or planned runtime,
debug output includes one shell-quoted `docker run` command that can be copied
and executed. Contiguous one-to-one published ports use Docker range syntax,
for example `-p 127.0.0.1:5380-5405:5380-5405/tcp`, instead of one flag
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
    "url": "http://127.0.0.1:5380"
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
