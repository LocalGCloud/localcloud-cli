# LocalCloud CLI Reference

[`README.md`](../README.md) covers installation and the shortest path to a running environment. This reference documents every command and output mode. `lc` and `localcloud` share the same commands, flags, and behavior.

Run `lc --version` for the installed version, and `lc COMMAND --help` for the flags and valid `--fields` paths of one command.

## Shared options

Most commands accept these options. A command's `--help` lists exactly what it supports.

| Option | Commands | Purpose |
| :--- | :--- | :--- |
| `--data-volume NAME` | all runtime commands | Selects the runtime by its Docker data volume (default: the active runtime, else `localcloud-data`) |
| `--project-id ID` | `start`, `restart`, `reset`, `console`, `env`, `mcp`, `mcp install` | Project within the runtime (default: `context.project`, else `local-gcp-project`) |
| `--user NAME` | same as `--project-id` | Caller identity sent to LocalCloud services (default: `local-developer`) |
| `--container-name NAME`, `--network-name NAME` | `start`, `restart`, `reset` | Override the managed Docker container or network name (also `host.container_name`, `host.network_name`) |
| `--verbose` | most commands | Complete JSON result instead of the concise summary ([details](#complete-json-with---verbose)) |
| `--fields PATH[,PATH...]` | `doctor`, `start`, `restart`, `reset`, `stop`, `status` | Append JSON paths to the concise summary ([details](#additional-summary-fields-with---fields)) |
| `--dry-run` | `start`, `restart`, `reset`, `stop`, `cleanup` | Print planned mutations without changing anything ([details](#lifecycle-dry-runs-and-debug-diagnostics)) |
| `--debug` | all runtime commands | Diagnostics on stderr, including a copyable `docker run` command |

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
- Waits up to 60 seconds for the gateway to report healthy (`/health`), then gives every enabled
  service at least 60 more seconds to answer (`/readiness`); BigQuery, for one, needs a few seconds
  after the gateway is up. A service the runtime reports as failed for good (a failure code such
  as `engine_port_unpublished`) is not waited for: the command succeeds, and `start`, `restart`
  and `status` warn that the service is not available, with the runtime's reason and remedy. An
  engine that merely does not answer yet (`connection_refused`) is waited for. A service still
  warming up at the deadline fails the command with `services_not_ready`, naming it, or saying
  why `/readiness` gave no answer; the runtime keeps running and stays recorded, so `status`,
  `logs` and `stop` find it.
- TLS is disabled by default. `--tls` enables it; `--no-tls` overrides an enabled configuration value.
- `--no-ui` runs headless: the gateway serves no web console, while every service API, the admin
  API, MCP, `/health`, and `/readiness` work as usual. It sets `LOCALCLOUD_CONSOLE=false` on the
  container, which overrides `server.console.enabled` in `localcloud.yaml`. A headless runtime
  stays headless on later `start` and `restart`, whatever `server.console.enabled` says, until
  `--ui` turns the console back on (or `host.environment.LOCALCLOUD_CONSOLE` sets it), and the
  output shows **Console: disabled**. When `server.console.enabled` asks for the other mode,
  `start`, `restart` and `status` warn that the runtime keeps the mode it was started with and
  name the flag that applies the configuration. After starting, the CLI checks `/health`: it warns when the
  image predates headless mode (that image still serves the console) and fails with
  `console_not_disabled` when the gateway reports the console on. A container `lc` did not create
  keeps the console setting it was started with: `--ui` or `--no-ui` that asks for the other one
  fails with `console_mode_unmanaged`. Available on `start` and `restart`.
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
- `--image` overrides `host.image` and `LOCALCLOUD_IMAGE` (default: `agentcloud/localcloud:lite`).
- `--full` runs the selected image's full build: tag `latest` for `lite`, `<version>` for `<version>-lite`.
  `--lite` goes back to the lite build, which is the default. Lite has BigQuery's lite edition (no BigQuery
  ML, JavaScript UDFs, or `EXTERNAL_QUERY` on Cloud SQL) and creates metadata-only GKE clusters; every
  service is still available. A runtime keeps the build it was started with on later `start` and
  `restart`, unless `host.image` or `LOCALCLOUD_IMAGE` names another; without its record in
  `active-runtime.json`, the CLI keeps the image the container runs. An image pinned by digest
  (`name:tag@sha256:…`) keeps its build: `--lite` or `--full` fails when it would have to switch it.
  Available on `start` and `restart`.
- `--services` overrides `services.enabled` with a comma-separated list of service IDs, or `default` to use the built-in set. It also overrides service choices saved on the data volume for that start, without clearing them ([details](configuration.md#which-services-run-and-where-each-choice-comes-from)).
- `--tail [SECONDS]` sets how long `start` and `restart` stream the container log after the runtime is ready. The default is 5 seconds; `--tail 0` skips it, and `--tail` with no value streams until you press Ctrl-C. (`lc logs --tail LINES` is a different option: it counts lines.)
- `--skip-config-validation` (or `LOCALCLOUD_SKIP_CONFIG_VALIDATION=1`) skips the CLI's own checks of `localcloud.yaml` when they disagree with what a newer image accepts. The file is still passed through unchanged; see [Configuration](configuration.md#removed-flat-keys). Available on `start`, `restart`, and `reset`.
- `--pull` is enabled by default: checks for a newer image on Docker Hub and pulls only if an update is available. `--no-pull` uses the locally available image only (pulling only if absent locally).
- A runtime pinned to a version tag (such as `agentcloud/localcloud:0.1.8-lite`) is checked for a
  newer version on its channel, lite or full. In a terminal, `start` and `restart` ask before
  switching; elsewhere the result carries an `Image update` line. Passing `--pull` explicitly switches
  without asking, and `--no-pull` looks only at local images. Switching migrates the data volume
  forward. The check reads Docker Hub at most once every 6 hours, and
  `LOCALCLOUD_IMAGE_UPDATE_CHECK=false` turns it off. A floating tag (`lite`, `latest`) is never
  switched, but `start` and `status` say when the local copy is older than Docker Hub's.
  See [Which image runs](configuration.md#which-image-runs-and-where-the-choice-comes-from).
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
until the relay is serving, and prints the session's SDK profile: `GCE_METADATA_HOST`,
`GCE_METADATA_IP` (`127.0.0.1:<port>`) and `GOOGLE_CLOUD_PROJECT`. LocalCloud generates that
profile (the `profile` of the session response, `IdentitySdkProfile` in its API contract);
the command only moves its endpoint variables to the published port before any application
mints a token, and never rewrites a token. `--format json` prints the variables as a JSON
object and `--format docker-compose` as an `environment:` block; `terraform` prints the same
exports as `shell`. Java is not needed on the host.

- One relay runs per data volume, project and requested account spelling. Running the command
  again with the same `--account` value replaces it, ends the previous session and keeps the
  port when it is free. Reuse that spelling for selective `--stop`; omitting `--account`
  stops every relay in the selected project.
- The session relay's capability is passed only to the relay container's environment; the
  command never prints, logs or stores it.
- `GOOGLE_APPLICATION_CREDENTIALS` and gcloud's `application_default_credentials.json` take
  precedence over the metadata server in Google client libraries. The command reports either
  one as a warning with how to isolate it, and leaves both the variable and the file
  unchanged: unset `GOOGLE_APPLICATION_CREDENTIALS`, and start applications with
  `CLOUDSDK_CONFIG` pointing at an empty directory (for example
  `CLOUDSDK_CONFIG="$(mktemp -d)" python my_app.py`).
- When `HTTP_PROXY`, `HTTPS_PROXY` or `ALL_PROXY` is set, the output also sets `NO_PROXY` and
  `no_proxy` to their current entries plus `127.0.0.1,localhost`, so client libraries reach
  the loopback relay directly. The CLI's own LocalCloud calls never use those proxies.
- In strict and gcp-live IAM modes the `--user` caller needs `iam.serviceAccounts.actAs`
  (for example `roles/iam.serviceAccountUser`) on the account.
- Any process on this machine can use the loopback port while the session lasts, the same
  exposure as an ADC key file. Sessions end after 12 hours, with `--stop` (`--account`
  limits it to that account's relay) and when the project is reset. `lc stop` stops the relay
  with LocalCloud's other child containers; run `lc env --identity` again after the next start.
  `--stop` prints `unset` for the endpoint variables the removed sessions set.
- The LocalCloud image must provide identity sessions with an SDK profile; an older image
  reports `identity_sessions_unsupported`.

### `console`

Opens the LocalCloud browser console for the selected project and user.

```sh
lc console
lc console --project-id my-project --user alice
```

On a runtime started with `--no-ui`, `console` fails with `console_disabled` instead of opening a page
the gateway does not serve; `lc restart --ui` turns the console back on.

### `logs`

Prints recent logs from the active LocalCloud container runtime.

```sh
lc logs
lc logs --tail 500
```

### `restart`

Stops the running container and starts with the image (preserving persistent volume state and reapplying volatile seed data). Unlike `start`, `restart` does not check the remote registry for a newer image under the same tag by default (`--no-pull`), using the currently available local image (e.g. `lite`). A runtime pinned to a version tag still gets the [newer-version offer](configuration.md#which-image-runs-and-where-the-choice-comes-from), which reads Docker Hub's tag list at most once every 6 hours; pass `--no-pull` explicitly to keep `restart` off the network.

`restart` recreates a managed runtime, so it picks up an image tag that has moved since the
runtime was created. If that image cannot start on the existing data (for example a newer engine
refuses a schema migration), `restart` fails and then recreates the runtime on the image it was
running, so the runtime is not left missing. The error says so (`rolled_back_to_image`). Nothing
is restored when the settings changed too, or when the old image is gone; the error then carries
`rollback_error`. The same rollback applies when you accept a newer version of a pinned image and it
fails to start. The previous image may then refuse a data volume the new one already migrated.

`start`, `restart` and `status` warn when the Cloud SQL MySQL companion could not start because
its host port is held by another container or process. The server starts the companion on demand
and only logs a warning when it cannot, so the warning is the CLI's signal that MySQL instances
are unreachable.

```sh
# Restart container using the currently available image (default: --no-pull)
lc restart

# Check for a newer image on the remote registry before restarting. A runtime pinned
# to a version tag (0.1.8-lite) moves to the newest version on its channel, without asking.
lc restart --pull

# Restart with TLS
lc restart --tls

# Restart on the full image (BigQuery ML, JavaScript UDFs, real GKE clusters)
lc restart --full

# Restart without the web console, or bring it back
lc restart --no-ui
lc restart --ui

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

`restart` stops the runtime's companion containers as `stop` does and keeps
them, and LocalCloud brings them back when it starts: each Dataproc cluster that
was `RUNNING` is started again with a new identity relay. The Cloud SQL MySQL
companion is the exception when the container is recreated: it is removed with
the old container and recreated on demand with the new runtime's port and name.
Its data stays on the volume.

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
`docker rm -f -v <container>`, `docker volume rm -f <volume>`, `lc start`) and
exits non-zero so nothing is destroyed by accident. The container is removed
first because Docker refuses to delete a volume that a stopped container still
mounts; an ephemeral runtime has no `docker rm` step, because `lc stop` already
removes it.

### `stop`

Stops the runtime without deleting persistent volume data.

```sh
lc stop
lc stop --data-volume isolated-data
```

`stop` also stops the runtime's companion containers: every container labelled
`localcloud.managed=true` with the runtime's data volume
(`com.localcloud.volume-name`), such as Dataproc clusters and their identity
relays, the Cloud SQL MySQL server and Cloud Run workloads. They are stopped,
not deleted. On the next `start`, LocalCloud starts again each Dataproc cluster
that was `RUNNING`, with a new identity relay; a cluster stopped with
`clusters.stop` stays `STOPPED` until `clusters.start`. A job that was running
on a cluster ends in `ERROR`, since its driver stopped with the cluster. The
MySQL server starts again when an instance needs it. A stop that removes the
runtime (an ephemeral runtime) removes its companion containers too.

### `doctor`

Diagnoses Docker daemon access and permissions, the Docker host setup, and legacy LocalCloud host files.

```sh
lc doctor
lc doctor --fix    # offer to fix what it finds, asking before each change
```

**Docker** shows the engine version and the `docker` command, then the Docker app (Colima, Rancher Desktop or Docker Desktop) with its version, its VM type and, on VZ, whether Rosetta is on. An engine that none of these apps serves shows no app; `--verbose` reports it as `docker_host: unknown`. **Setup** lists findings; each has an `id` in
`--verbose` output (`setup_findings`):

| Finding | When | `--fix` |
| :--- | :--- | :--- |
| `docker_not_installed` | No Docker engine | macOS with Homebrew: `brew install colima docker`, then `colima start --cpu 4 --memory 8` (plus `--vm-type vz --vz-rosetta` on Apple Silicon). Otherwise prints the steps |
| `docker_not_running` | A Docker app is installed but stopped | Starts the app the Docker context names (`colima start`, `rdctl start`, `open -a Docker`). Otherwise prints the steps |
| `docker_unreachable` | `DOCKER_HOST` or the context points at an engine that does not answer | Prints the steps |
| `docker_permission_denied` | The Docker socket refuses access | Prints the steps |
| `qemu_vm` | Apple Silicon and the Docker VM runs on QEMU | Colima: deletes and recreates the VM on VZ with Rosetta (`colima delete` without `--data`, keeping CPU, memory and disk). Rancher Desktop: `rdctl set --virtual-machine.type=vz --virtual-machine.use-rosetta=true`. Docker Desktop: prints the steps (QEMU was removed in 4.44) |
| `rosetta_off` | Apple Silicon, VZ, Rosetta off | Colima: `colima stop`, `colima start --vz-rosetta`. Rancher Desktop: `rdctl set --virtual-machine.use-rosetta=true`. Docker Desktop: prints the steps |

LocalCloud prefers VZ with Rosetta on Apple Silicon: it is faster, and QEMU has known issues with LocalCloud
(the BigQuery emulator can fail under it). Fixes that turn Rosetta on install the Rosetta runtime first when it
is missing (`softwareupdate --install-rosetta --agree-to-license`).

Every fix shows its commands before it runs and stops at the first failing command, reporting the command
and its exit code. Switching a VM restarts it, so running containers stop. A fix that can delete Docker data
(switching the VM type, until each app is verified to keep its data) says so, lists the LocalCloud volumes,
and runs only after you type `delete`. `--fix` needs an interactive terminal and does not run with
`--verbose`. The CLI never runs `sudo` or installer scripts.

`lc doctor` works without Docker: it exits with `docker_unavailable`, whose `details.reason` is
`not_installed`, `not_running`, `unreachable` or `permission_denied`, and prints the matching finding.

When `start`, `restart` or `reset` fails, or starts with service errors in its log, the CLI runs the same
checks, prints any findings under the error (and in `setup_findings` with `--verbose`), and offers the fixes in
a terminal. `lc start` in a terminal also offers to install or start Docker when it is unavailable, waits up to
120 seconds for it, and then continues.

### `cleanup`

Finds and removes malformed Docker resources, stale runtime state, and legacy lock files.
It also removes managed child containers (the Cloud SQL MySQL companion, Dataproc clusters)
whose data volume no longer exists, such as one left behind by a throwaway `--data-volume`
run. Such a container is not claimed by any runtime, so `stop`, `restart` and `reset` never
touch it, and it can hold the companion's host port (`5406` by default).

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
metadata. When selected, `~/.local/share/localcloud/localcloud.yaml` and a user seed
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

Runs the stdio Model Context Protocol bridge for AI tools and coding environments, or configures coding agent clients.

```sh
lc mcp
lc mcp --project-id my-project
lc mcp --connect-timeout 30
lc mcp --no-start
lc mcp install --client cursor
lc mcp install --client claude-code --project
lc mcp install --client all
```

The bridge answers the client's handshake immediately and connects to LocalCloud
in the background, starting a stopped or missing runtime (never replacing a
running one) and creating the selected project. Unlike other commands, it gives
each git repository its own project: without `--project-id` or a `context.project`
in the repository's config, it uses the name of the repository it runs in, or of
the workspace the client reports through MCP roots. Until the runtime is ready, the client sees a single
`localcloud_runtime_status` tool and other calls return what to fix; when it
becomes ready the bridge tells the client its tool, resource and prompt lists
changed. `--no-start` never starts a runtime: the bridge reports
`runtime_not_running` until `lc start` is run.

When the runtime container fails to start, `localcloud_runtime_status`, calls
made meanwhile, and forwarded errors carry `error.diagnostics`: the CLI version,
the image (reference, ID and registry digest), the exit code, whether Docker
killed it for memory, and the newest log lines with credentials redacted. They
never carry container names or IDs, labels, or Docker's own error text.
`lc mcp --debug` also writes them to stderr.

Requests run concurrently, and a request refused because the runtime restarted
or moved is sent again once after reconnecting.

When run directly in an interactive terminal, the command reports the resolved
LocalCloud `/mcp` endpoint and then `Connected to LocalCloud at …` on stderr.
`--connect-timeout SECONDS` (default 10) bounds how long each connection
attempt waits for the endpoint after the runtime starts; a runtime that is not
ready by then is reported as `mcp_connection_timeout`.

Once connected, the stdio bridge remains open without a session timeout while
it accepts requests. Pressing Ctrl-C prints `MCP connection closed.`, exits
with status 130, and does not emit a traceback. Non-interactive MCP launchers
receive no lifecycle text, only one stderr line per connection problem, and
stdout stays reserved for JSON-RPC traffic.

#### `mcp install`

Installs the LocalCloud MCP server entry into an AI client's configuration. It
does not need Docker.

```sh
lc mcp install --client {cursor|claude-code|claude-desktop|claude|gemini|antigravity|windsurf|cline|all} [--global|--project] [--bare] [--command-path CMD] [--project-id ID] [--data-volume NAME] [--user NAME]
```

`--global` (the default) writes the user-level configuration, so every
repository gets LocalCloud and the bridge selects each one's project. `--project`
writes the repository's file for Cursor, Claude Code, Gemini CLI and
Antigravity; the other clients have none. `--client all` configures every
supported client installed on this machine and fails if it finds none. Only
`--project-id`, `--data-volume` and `--user` values you pass are pinned in the
entry. Claude Code is configured through `claude mcp add-json` when its CLI is
installed; if that command fails while replacing an entry, the previous entry is
put back. Existing settings and other servers are kept, the previous file is
saved as `<name>.bak`, and each client is reported as installed, updated, or
already up to date.

User-level entries get the absolute path of a permanent `localcloud` install
(Homebrew or `~/.local/bin` before a virtualenv); repository entries get the
bare `localcloud`. `--bare` and `--command-path` override that.

Use `--client claude-code` for Claude Code; `claude` is an alias for Claude Desktop. Codex uses `codex mcp add` rather than this installer. See the [local client setup guide](mcp-marketplace-guide.md) for commands, connection checks, and GitHub plugin setup.

For complete architectural details, client configuration files, and the full MCP tools catalog, see the [MCP Architecture & Guide](mcp.md).

## Output Modes

### Interactive terminals

Interactive commands display lifecycle spinners and elapsed time. `doctor`, `status`, and `reset` also display the colorful LocalCloud artwork and resolved context. `start`, `restart`, and `stop` keep progress compact while logs stream.

Every command ends with one line on stderr, after its summary: `[OK]` and what happened, or `[FAIL]` and what went wrong, followed by the elapsed time. The summary therefore has no status row; `--fields status` adds one. `lc status` ends with `[FAIL]` for an unhealthy container and still exits 0, because the check itself worked. When a command stops on an error, the `Error [code] …` line above carries the message and the closing line names the command: `[FAIL] LocalCloud status failed`.

The resolved context shows `Data` only when the data is `ephemeral`; a data volume alone means the data is kept. When no config file applies, `Config` reads `built-in defaults (overridable by ./localcloud.yaml)`.

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

### Start failures

When the runtime container exits before it becomes healthy, or `docker start`
fails, the error is `container_start_failed`. It shows the exit code, whether
Docker killed the container for memory, Docker's error text, the image
(reference, ID and registry digest), the CLI version and the newest log lines.
`--verbose` prints them as `details.diagnostics`, with the newest 40 log lines
(at most 4 KB). ANSI codes are stripped; credentials in URLs, tokens, passwords,
private keys and e-mail addresses are redacted.

A LocalCloud image refuses data that a newer image wrote, so a runtime started
on an older image than the one that last ran its data volume exits while
starting. After each successful `start` or `restart`, the CLI records the image
the data volume became ready on in `volume-images.json`, beside
`active-runtime.json`. When a different image then exits while starting, the
error names the recorded image (`details.data_last_ready_on`) and gives the
command that starts the runtime on it again (`details.steps`). The CLI does not
switch images, pull, or touch the data by itself.

A LocalCloud image that refuses its data says so: the container prints
`LOCALCLOUD_START_REFUSED reason=data_newer_than_build` and exits with code 78.
The CLI reads that as `details.diagnostics.start_refused`, states that the image
is older than the data on the volume, and gives `lc restart --image …` for the
recorded image or `lc restart --pull` when none is recorded or the same tag now
names another image. Images that do not print the line are handled as above.

A port that another process took before Docker started a stopped runtime is
reported as `port_no_longer_available`.

## Exit codes

| Code | Meaning |
| :---: | :--- |
| `0` | Success. `lc status` also exits 0 when the container is unhealthy, because the check itself worked |
| `1` | Completed with failures (for example a partial `cleanup`), or an unexpected internal error |
| `2` | LocalCloud error: invalid input, a Docker problem, or a failed operation, shown as `Error [code] message` (with `--verbose`, the same fields as JSON) |
| `130` | Interrupted with Ctrl-C |

See the README's [troubleshooting table](../README.md#troubleshooting) for common error codes and fixes.

## Related References

- [Configuration and runtime identity](configuration.md)
- [SDK, Terraform, and MCP integrations](integrations.md)
