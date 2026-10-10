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
  seed: auto
  data: persistent
  memory: 4g
  environment:
    LOCALCLOUD_LOG_VERBOSITY: debug
services:
  enabled:
    - gcs
    - firestore
    - pubsub
server:
  logging:
    verbosity: debug
```

Configuration selection order is:

1. an explicit positional config path;
2. host `LOCALCLOUD_CONFIG`;
3. `./localcloud.yaml`;
4. the runtime's remembered config;
5. `$HOME/.localcloud/localcloud.yaml` (or `LOCALCLOUD_HOME/localcloud.yaml`);
6. no user file.

A selected explicit, environment, or remembered path that is missing or unreadable fails instead of falling back.

CLI resource flags override corresponding `host` values. `--project-id` and `--user` override request context only; they do not replace the server's YAML-derived `context.project`. The selected file is mounted read-only at `/etc/localcloud/localcloud.yaml`; LocalCloud discovers that canonical path without `LOCALCLOUD_CONFIG`. With no file, the CLI adds neither a config mount nor a config environment variable.

The container recursively merges the file over packaged `localcloud.defaults.yaml`. Omitted values inherit; a mapping member set to `null` is deleted. Existing setting-specific environment variables have higher precedence. Use `host.seed: disabled`, not `null`, to disable host-side seeding.

When no host seed file is selected, LocalCloud keeps its packaged default
auto-seed behavior. When `host.seed` resolves to a user file, the CLI mounts it
read-only at `/etc/localcloud/cli-seed.yaml`; that sentinel suppresses the
packaged default seed worker, and the CLI applies the user YAML through the
LocalCloud seed API after readiness.

### Removed flat keys

| Removed flat key | Replacement |
| :--- | :--- |
| `project`, `user` | `context.project`, `context.user` |
| `services` | `services.enabled` |
| `data_volume`, `seed`, `data`, `image`, `memory` | The same key under `host` |
| `docker_socket`, `transparent_network`, `environment` | The same key under `host` |
| `container_name`, `network_name` | The same key under `host` |

Changing the config path or presence recreates the managed runtime. Editing server or catalog values at the same path is picked up on explicit restart without putting those values or secrets into Docker labels. Protect config files containing credentials with normal host-file permissions.

If the CLI's host or context checks disagree with what a newer LocalCloud image accepts, pass `--skip-config-validation` or set `LOCALCLOUD_SKIP_CONFIG_VALIDATION=1` on `start`, `restart`, or `reset`. This bypasses CLI closed-set field/version checks and removed-flat-schema detection. The file is still passed through unchanged, so LocalCloud remains the final authority. Bypassed checks appear in result `diagnostics`. YAML syntax and Docker-driving value checks such as `host.memory` and `host.data` are never bypassed.

## Available Services

The catalog ships **27 services**, 22 of them enabled by default. Tier gating is
enforced by the container at startup, not by the CLI.

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

A single data volume can host multiple logical projects. Every command selects the project the same
way, so a repository's terminal commands and its MCP agents share one:

1. `--project-id`;
2. `context.project` of the directory's `localcloud.yaml`, or of an explicit `--config` / `LOCALCLOUD_CONFIG`;
3. the git repository's name, slugified into a valid project ID (`Payments_API.v2` becomes
   `payments-api-v2`). Worktrees use their main checkout's name; a repository at the home directory
   is ignored;
4. `context.project` of the home or remembered config, then `local-gcp-project`.

`start`, `restart`, `env`, `console` and an MCP connection create a repository's project on first use;
`start`, `restart` and MCP also create one named by `--project-id`. Switching request context is
immediate:

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
record anonymous usage. Only `start`, `restart`, `reset`, and `stop` send it, to the same PostHog
project and key as the LocalCloud server, the Console (whose summaries the server forwards), and
the website. The other commands only update local counts, so they never wait on the network.

Every CLI event can be told apart from those sources in three ways:

| Marker | CLI | Server and Console |
| :--- | :--- | :--- |
| Event name | `cli_` prefix | `heartbeat`, `server_started`, `service_error`, `console_summary`, … |
| `source` / `$lib` | `cli` / `localcloud-cli` | no `source` / `localcloud-java` |
| `distinct_id` | `lcc_` + 16 hex digits | `lc_` + 16 hex digits |

Properties that mean the same thing as a server property use its name and type: `services_enabled`,
`services_enabled_count`, `error_type`, `error_message`, `version`, and `commit_id`.

- `cli_startup_error`: sent when `start`, `restart`, or `reset` fails, or when the startup log
  shows `[FAILED]`/`ERROR` lines. Errors that only need your input are not sent: an unconfirmed or
  declined port mapping, conflicting dry-run flags, a busy runtime lock, or nothing to restart. It
  carries the command; the outcome (`failed`, `started_with_errors`, or `interrupted`); the error
  code, message (`error_message`), and cause; the phase, state, and timeout when known; the exception type for
  unexpected errors; and up to ten error lines from the current run's log, each cut to 240
  characters.
- `cli_heartbeat`: sent at most once an hour. It carries per-command counts and the number of
  startup errors since the last heartbeat. The running container reports its own service and usage
  metrics separately.

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
| `host.environment.LOCALCLOUD_TELEMETRY: "false"` in `localcloud.yaml` | Disables CLI telemetry and is passed to the container, so it disables server telemetry too. `telemetry.json` keeps only `{"disabled": true}` so runs that cannot reach Docker still honor it |

`LOCALCLOUD_EVENT_API_KEY` and `LOCALCLOUD_POSTHOG_URL`, in the shell or in `host.environment`,
point events at a different PostHog project or proxy. Events go to `/batch/` under the URL's path,
after dropping a capture path such as `/i/v0/e/`: `https://proxy.example/ph/i/v0/e/` sends to
`https://proxy.example/ph/batch/`.

## Related References

- [CLI commands and output modes](cli-reference.md)
- [SDK, Terraform, and MCP integrations](integrations.md)
- [LocalCloud MCP Architecture and Complete Tool Catalog](mcp.md)

