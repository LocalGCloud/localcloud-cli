# LocalCloud Configuration and Runtime Identity

[`README.md`](../README.md) covers installation and Quick Start. This reference documents `localcloud.yaml`, service selection, data volumes, projects, and caller identity.

## `localcloud.yaml`

The host CLI and container read the same versioned partial overlay. Image defaults remain authoritative for server and catalog wiring; the CLI resolves `host` values before creating the container.

```yaml
version: 1
context:
  project: local-gcp-project
  user: local-developer
host:
  data_volume: localcloud-data
  data: persistent
  memory: 4g
  environment:
    LOCALCLOUD_MCP_WRITE: "true"
services:
  enabled:
    - gcs
    - firestore
    - pubsub
server:
  console:
    enabled: true   # false runs headless, like lc start --no-ui
```

Configuration selection order is:

1. an explicit positional config path;
2. host `LOCALCLOUD_CONFIG`;
3. `./localcloud.yaml`;
4. the runtime's remembered config;
5. `~/.local/share/localcloud/localcloud.yaml` (or `$LOCALCLOUD_HOME/localcloud.yaml` when `LOCALCLOUD_HOME` is set);
6. no user file.

A selected explicit, environment, or remembered path that is missing or unreadable fails instead of falling back.

CLI resource flags override corresponding `host` values. `--project-id` and `--user` override request context only; they do not replace the server's YAML-derived `context.project`. The selected file is mounted read-only at `/etc/localcloud/localcloud.yaml`; LocalCloud discovers that canonical path without `LOCALCLOUD_CONFIG`. With no file, the CLI adds neither a config mount nor a config environment variable.

The container recursively merges the file over packaged `localcloud.defaults.yaml`. Omitted values inherit; a mapping member set to `null` is deleted. Existing setting-specific environment variables have higher precedence.

Sample data is owned by container startup (`server.auto_seed`) and the Console's Re-seed Data action.
The CLI does not discover, mount, or apply seed files: `host.seed` is retired, and an existing
value is accepted but ignored. Set `server.auto_seed: false` to turn off the container's sample data.
A managed container created with the old CLI seed mount (`/etc/localcloud/cli-seed.yaml`) is
recreated on the next `start` or `restart`, keeping its data volume.

### Which services run, and where each choice comes from

A service is enabled by the highest of these layers that speaks about it. Later layers
override earlier ones:

| Rank | Layer | Set by |
| :--- | :--- | :--- |
| 1 (lowest) | The image's default services | The release |
| 2 | `services.enabled` in `localcloud.yaml` | You, in the config file |
| 3 | A choice saved on the data volume | The Console or the API, when you enable or disable a service |
| 4 | `LOCALCLOUD_ENABLE_<ID>` variables (one service each) | You, in `host.environment` |
| 5 (highest) | `--services` (sent to the runtime as `LOCALCLOUD_SERVICES`) | The command line |

So `lc start --services gcs` runs exactly `gcs`, whatever was saved earlier. Saved choices are
**left in place, not cleared**: a later start without `--services` restores them, on top of the
config file and the defaults. A change made in the Console while the runtime runs is saved and
replaces the launch-time choice for that session.

Saved choices live on the **data volume**, not in `localcloud.yaml`. The config file is your input;
the saved state is something the runtime writes when you toggle a service, and it follows the
volume (so it survives recreating the container). A new `--data-volume`, or `host.data: ephemeral`,
starts without any.

`start`, `restart` and `status` print the result:

```text
Services         gcs
Service sources  --services: gcs
Saved settings   not applied, bigquery (enabled) overridden by --services; a start without that choice restores them
```

`Service sources` names the layer behind each enabled service: `--services`, a
`LOCALCLOUD_ENABLE_*` variable, `saved setting (data volume …)`, `services.enabled (file)` or
`image default`. `Saved settings` appears only when a launch-time choice is overriding something
saved. If a saved choice enables a service that the runtime did not publish ports for (it was
started with a smaller `services.enabled` list), the command warns instead of failing and the
service's SDK environment is not exported; pass `--services` to pick the set.

### Which image runs, and where the choice comes from

The first of these that names an image wins:

| Rank | Source | Set by |
| :--- | :--- | :--- |
| 1 (highest) | `--image`, `--lite`, `--full` | The command line, for this run |
| 2 | `host.image` in `localcloud.yaml` | You, in the config file |
| 3 | `LOCALCLOUD_IMAGE` | You, in the host environment |
| 4 | The image this data volume last ran (`active-runtime.json`) | The CLI, after each `start` or `restart` |
| 5 (lowest) | `agentcloud/localcloud:lite` | The release |

A runtime therefore stays on the image it was started with. That includes a version tag such as
`agentcloud/localcloud:0.1.8-lite`, which never moves by itself. Floating tags (`lite`, `latest`)
are refreshed by `--pull` when the registry has a newer image under the same tag.

The published tags of `agentcloud/localcloud` are:

| Tag | Build | Moves |
| :--- | :--- | :--- |
| `lite` (the CLI's default) | Lite | To each new release |
| `latest`, `0.1` | Full | To each new release (`0.1` within the 0.1 series) |
| `X.Y.Z`, `X.Y.Z-lite` (for example `0.1.17`, `0.1.17-lite`) | Full, lite | Never; one release each |
| `ci` | Full | Only after a release has passed the CI qualification of the setup-localcloud GitHub Action, which uses this tag by default; it can lag `latest` |

The lite build leaves out BigQuery's full edition (BigQuery ML, JavaScript UDFs, `EXTERNAL_QUERY` on
Cloud SQL) and runs GKE as metadata-only clusters.

**Newer versions of a pinned image.** Before `lc start` and `lc restart`, the CLI looks for a
newer `X.Y.Z` version on the runtime's channel: `-lite` tags move only to newer `-lite` tags,
full only to full. It asks Docker Hub first, which takes at most 3 seconds, and keeps the
answer for 6 hours. If Docker Hub cannot be reached, it searches the images already on the
Docker engine and stops asking Docker Hub for 10 minutes. Only images published for the
engine's architecture are offered. What happens next depends on where the image came from:

| Image source | What happens |
| :--- | :--- |
| The data volume's last run (rank 4), interactive terminal | `lc` asks whether to switch. Yes recreates the runtime on the newer image (pulled only if it is not already local) and records it, so later runs stay on it. No keeps the current image, and the same version is not offered again for that data volume. |
| The data volume's last run, with an explicit `--pull` | `lc` switches to the newest version without asking. |
| The data volume's last run, with no terminal (scripts, `--verbose`, `--accept-dynamic-ports`) | Nothing changes. An `Image update` line names the newer tag and the command that switches to it. |
| `host.image` or `LOCALCLOUD_IMAGE` (ranks 2 and 3) | Nothing changes. The `Image update` line names the setting to edit, because the CLI cannot change it. |
| A floating tag (`lite`, `latest`) | Nothing changes. When the local copy's version is older than Docker Hub's newest on that channel, `start` and `status` print an `Image update` line; `--pull` refreshes it. With `--no-pull` only an answer cached in the last 6 hours is used. |
| `--image`, `--lite`, `--full` with a version tag, a digest pin or another repository | Nothing is checked. |

Switching recreates the runtime, and the new image migrates the data volume forward. Going back to
the older image may then be impossible. If the new image fails to start, `start` or `restart` recreates the
runtime on the previous image ([rollback](cli-reference.md#restart)). `--no-pull` skips Docker Hub
and only considers local images. `--dry-run` never checks. Set `LOCALCLOUD_IMAGE_UPDATE_CHECK=false`,
in the host environment or in `host.environment`, to turn the check off.

### Removed flat keys

| Removed flat key | Replacement |
| :--- | :--- |
| `project`, `user` | `context.project`, `context.user` |
| `services` | `services.enabled` |
| `data_volume`, `data`, `image`, `memory` | The same key under `host` |
| `seed` | Retired: sample data comes from `server.auto_seed`; `seed: null` becomes `server.auto_seed: false` |
| `docker_socket`, `transparent_network`, `environment` | The same key under `host` |
| `container_name`, `network_name` | The same key under `host` |

Changing the config path or presence recreates the managed runtime. Editing server or catalog values at the same path is picked up on explicit restart without putting those values or secrets into Docker labels. Protect config files containing credentials with normal host-file permissions.

If the CLI's host or context checks disagree with what a newer LocalCloud image accepts, pass `--skip-config-validation` or set `LOCALCLOUD_SKIP_CONFIG_VALIDATION=1` on `start`, `restart`, or `reset`. This bypasses CLI closed-set field/version checks and removed-flat-schema detection. The file is still passed through unchanged, so LocalCloud remains the final authority. Bypassed checks appear in result `diagnostics`. YAML syntax and Docker-driving value checks such as `host.memory` and `host.data` are never bypassed.

## Gateway Settings

The CLI checks the `version`, `context`, `host`, `tls`, `mcp`, and `services` keys itself. It passes
`server`, `updater`, `infrastructure`, `observability`, and the settings of `services.catalog` entries
through unchanged, and LocalCloud validates them when it starts. LocalCloud refuses a key it does not know and names it, so a
misspelled key fails `start` instead of being silently ignored. `--skip-config-validation` does not
change that.

The CLI packages the defaults of the LocalCloud image it was released with as
[`src/localcloud_cli/defaults/localcloud.v1.yaml`](../src/localcloud_cli/defaults/localcloud.v1.yaml).
The comments there describe each setting; the image you run reads its own copy, which wins if they
differ.

| Setting | Default | What it does |
| :--- | :--- | :--- |
| `server.control_token` | off | Token that protects the control plane (admin API, MCP, admin SQL, identity minting). Off by default on a local gateway. `enabled: true` (or `LOCALCLOUD_CONTROL_TOKEN_ENABLED=true`) turns it on with the token the gateway keeps in its data directory; `value` (or `LOCALCLOUD_CONTROL_TOKEN`) turns it on with your own token of at least 32 printable characters. Remote access (`mcp.allow_remote` or `server.allowed_hosts`) turns it on unless `enabled: false` is set. When it is on, clients send it in the `X-LocalCloud-Control-Token` header. |
| `server.allowed_origins`, `server.allowed_hosts` | `[]` | Browser origins and host names the gateway serves beyond loopback, IP literals, and its own names, for example a LAN device's dev app at `http://192.168.1.20:3000`. Requests without browser headers (SDKs, gcloud, Terraform, the CLI) are not affected. Also `LOCALCLOUD_ALLOWED_ORIGINS` and `LOCALCLOUD_ALLOWED_HOSTS`, comma-separated. |
| `server.auto_seed` | `true` | Seeds sample data when the container starts. |
| `server.unknown_project` | `provision` | What a request that names a project with no record gets. `provision` records the project the first time an authorized request names it; `allow` serves the request without recording it; `reject` answers as Google does for a project that does not exist or that the caller cannot see (403 `PERMISSION_DENIED` with the `ErrorInfo` reason `CONSUMER_INVALID`). Projects created through Resource Manager, the Console, the admin API, MCP or a seed work in every mode. `server.auto_provision_projects: false` is the older spelling of `allow`. |
| `server.query_max_rows`, `server.query_max_bytes` | `10000`, `8388608` | Largest admin SQL result; a larger one is cut and marked truncated. |
| `server.query_history_keep_rows`, `server.migration_run_keep_rows` | `0` | Admin query history and migration runs kept; `0` keeps all of them. |
| `server.operation_retention_hours` | `0` | Hours a finished long-running operation (KMS, Workflows, Cloud Run, Dataproc) stays readable; `0` keeps it. |
| `server.identity` | enabled | Local workload identity: issuer, token lifetimes, clock skew, gateway metadata, and the relay image. |

### Retention

LocalCloud keeps data until you delete it unless you set a limit. Each service's limits are under
`services.catalog.<id>.config`, and `0` means keep:

| Service | Settings (default) |
| :--- | :--- |
| `alloydb`, `cloudsql` | `operation_retention_hours` (`0`) |
| `cloudscheduler` | `executions_keep_per_job` (`0`) |
| `cloudtasks` | `activity_retention_days` (`0`), `task_name_reuse_window_seconds` (`0`; Google uses up to `86400`), `queue_name_reuse_window_seconds` (`0`; Google documents up to `259200`, valid up to `777600`) |
| `gcs` | `lifecycle_interval_seconds` (`0`: bucket lifecycle rules never run on a timer; `POST /mutate/gcs/lifecycle/run` runs them once. Cloud Storage applies them about once a day) |
| `logging` | `application_retention_days`, `runtime_retention_days`, `raw_retention_hours` (all `0`: entries stay until the `*_max_size_mb` budgets trim them) |
| `pubsub` | `enforce_subscription_retention` (`false`: no message expires), `subscription_retention_days` (`7`, used when enforcement is on) |
| `vertexai` | `request_log_keep_per_project` (`0`) |
| `workflows` | `executions_keep_per_workflow` (`0`), `operations_keep` (`0`) |

To test Pub/Sub message expiry as Google applies it:

```yaml
services:
  catalog:
    pubsub:
      config:
        enforce_subscription_retention: true
```

### Retired keys

LocalCloud refuses these keys, and its error says the key is retired. Remove them from
`localcloud.yaml`:

- `server.api_key`, `server.license_server_url`
- `server.feedback_project_url`, `server.feedback_github_repository`, `server.feedback_github_token`
- `server.gcp_credentials.adc_path`, `server.gcp_credentials.sa_key_path`
- `server.identity.default_service_account`, `server.identity.lifetime_extension_accounts`
- `updater.prune_old_image`, `updater.drain_timeout_seconds`
- `gcloudPort`, `healthCheck`, and `usage` in a `services.catalog` entry

`server.logging` is retired too, but LocalCloud only logs a warning and ignores it.

## Available Services

The catalog ships **27 services**, 22 of them enabled by default. The Tier column is catalog
metadata (`minTier`): no license key is required and no service is gated by tier.

| Service ID | Google Cloud service | Default | Tier |
| :--- | :--- | :--- | :--- |
| `gcs` | Cloud Storage | Enabled | Community |
| `pubsub` | Pub/Sub | Enabled | Community |
| `firestore` | Firestore | Disabled | Community |
| `bigtable` | Bigtable | Enabled | Pro |
| `spanner` | Spanner | Enabled | Pro |
| `bigquery` | BigQuery | Enabled | Community |
| `sheets` | Google Sheets | Enabled | Community |
| `secretmanager` | Secret Manager | Enabled | Community |
| `cloudtasks` | Cloud Tasks | Enabled | Community |
| `cloudscheduler` | Cloud Scheduler | Enabled | Community |
| `cloudfunctions` | Cloud Functions (2nd Gen) | Enabled | Community |
| `alloydb` | AlloyDB | Enabled | Community |
| `dataproc` | Dataproc | Enabled | Community |
| `cloudiam` | Cloud IAM | Enabled | Community |
| `cloudresourcemanager` | Cloud Resource Manager | Enabled | Community |
| `serviceusage` | Service Usage | Enabled | Community |
| `cloudbilling` | Cloud Billing | Enabled | Community |
| `logging` | Cloud Logging | Enabled | Community |
| `monitoring` | Cloud Monitoring | Enabled | Community |
| `gke` | GKE | Disabled | Pro |
| `compute` | Compute Engine | Disabled | Pro |
| `cloudrun` | Cloud Run | Disabled | Pro |
| `memorystore` | Memorystore (Redis/Valkey) | Enabled | Community |
| `workflows` | Cloud Workflows | Enabled | Community |
| `vertexai` | Vertex AI | Disabled | Pro |
| `kms` | Cloud KMS | Enabled | Pro |
| `cloudsql` | Cloud SQL | Enabled | Community |

## Runtime Identity and Multi-Project Context

LocalCloud separates durable container storage from logical Google Cloud project contexts.

### Data volumes (`--data-volume`)

A named Docker volume provides durable identity. The default `localcloud-data` volume is mounted at `/var/lib/localcloud`. Multiple isolated environments can run concurrently. Only one runtime can hold the
canonical `5380-5405` range (and `5406` with Cloud SQL MySQL). Additional runtimes publish only
the ports their enabled services use (14 with the default services, including MySQL) as one
contiguous block from `5508-5539`, then `5821-5840`, then `5322-5342`, which fits four of them.
Always read the actual ports from `lc env` or `lc status` rather than assuming them:

```sh
lc start --data-volume test-e2e
lc status --data-volume test-e2e
```

To choose the host ports yourself, set a range. The runtime takes one block from it, even when
the canonical ports are free; changing the range recreates the container on the next `start`:

```yaml
host:
  port_range: 6000-6099
```

`--port-range START-END` on `start`, `restart`, and `reset` overrides `host.port_range`. Like
`--local-only`, the flag applies to one command: pass it each time, or a later `restart` returns the
runtime to the canonical ports when they are free. LocalCloud images that predate the setting reject
`host.port_range` at startup (`Invalid host.port_range: unknown key`); with those, use the flag.

### Project context (`--project-id`)

A single data volume can host multiple logical projects. Commands use `--project-id`, then the
config's `context.project`, then `local-gcp-project`; `start` and `restart` create a project named by
`--project-id`.

The MCP bridge (`lc mcp`) also gives each git repository its own project: when neither `--project-id`
nor the repository's own (or an explicit) config names one, it uses the repository's name, slugified
into a valid project ID (`Payments_API.v2` becomes `payments-api-v2`; worktrees use their main
checkout's name), and creates it on connection. See [One Project per Repository](mcp.md#one-project-per-repository).

Switching request context is immediate:

```sh
lc start --project-id project-alpha
eval "$(lc env --project-id project-alpha)"

lc start --project-id project-beta
eval "$(lc env --project-id project-beta)"
```

### Caller identity (`--user`)

`--user` sets the attributed caller identity sent to LocalCloud services. The default is `local-developer`. Where an email principal is required, LocalCloud normalizes it to `local-developer@localcloud.invalid`.

## Telemetry

Runtime commands (`start`, `restart`, `reset`, `stop`, `status`, `logs`, `console`, `env`, `mcp`)
record anonymous usage. Only `start`, `restart`, `reset`, and `stop` send it, to LocalCloud's
PostHog project. The other commands only update local counts, so they never wait on the network.
The LocalCloud runtime image itself collects and sends no usage telemetry, and its Console sends no
analytics; this section is about the CLI only.

Every CLI event is marked three ways: its name has the `cli_` prefix, `source` / `$lib` are `cli` /
`localcloud-cli`, and `distinct_id` is `lcc_` followed by 16 hex digits.

- `cli_startup_error`: sent when `start`, `restart`, or `reset` fails, or when the startup log
  shows `[FAILED]`/`ERROR` lines. Errors that only need your input are not sent: an unconfirmed or
  declined port mapping, conflicting dry-run flags, a busy runtime lock, or nothing to restart. It
  carries the command; the outcome (`failed`, `started_with_errors`, or `interrupted`); the error
  code, message (`error_message`), and cause; the phase, state, and timeout when known; the exception type for
  unexpected errors; and up to ten error lines from the current run's log, each cut to 240
  characters.
- `cli_heartbeat`: sent at most once an hour. It carries per-command counts and the number of
  startup errors since the last heartbeat.

Every event also carries the enabled services and their count, memory, TLS, Docker socket mode,
image tag (`custom` for any other registry), CLI version and commit, OS and architecture, Python
version, whether the CLI is the standalone binary, and whether `CI` is set.

Text fields are scrubbed before sending. Credentials in URLs, secret-like `key=value` pairs, bearer
tokens, e-mail addresses, long tokens, home and LocalCloud paths, the working directory, the config
path, and any non-default project, user, data volume, container, network, image, registry, or
Docker host name are replaced with placeholders. Raw logs are never sent.

Each sending run makes one request with a 3-second deadline. If delivery fails, up to five events are
kept in `LOCALCLOUD_HOME/telemetry.json` and retried by a later sending command, no sooner than five
minutes after the failed attempt (immediately if a new startup error happens). Telemetry never
changes a command's output or exit code. The same file holds the anonymous ID, a random `lcc_…`
value, and the path of the last config used, so an opt-out in that config is honored even when
Docker is not running.

Turn it off with any of these. Turning it off also deletes buffered events and the ID:

| Setting | Effect |
| :--- | :--- |
| `LOCALCLOUD_TELEMETRY=false` in the shell | Disables CLI telemetry |
| `DO_NOT_TRACK=1` in the shell | Disables CLI telemetry |
| `host.environment.LOCALCLOUD_TELEMETRY: "false"` in `localcloud.yaml` | Disables CLI telemetry. `telemetry.json` keeps only `{"disabled": true}` so runs that cannot reach Docker still honor it. The container receives the variable too, but the image ignores it, since it sends no telemetry |

`LOCALCLOUD_EVENT_API_KEY` and `LOCALCLOUD_POSTHOG_URL`, in the shell or in `host.environment`,
point events at a different PostHog project or proxy. Events go to `/batch/` under the URL's path,
after dropping a capture path such as `/i/v0/e/`: `https://proxy.example/ph/i/v0/e/` sends to
`https://proxy.example/ph/batch/`.

## Related References

- [CLI commands and output modes](cli-reference.md)
- [SDK, Terraform, and MCP integrations](integrations.md)
- [LocalCloud MCP Architecture and Complete Tool Catalog](mcp.md)

