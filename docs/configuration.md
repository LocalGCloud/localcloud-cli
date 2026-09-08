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
canonical `5365-5375` range, so additional runtimes are offered a contiguous
alternative from `5508-5539`, then `5821-5840`, then `5322-5342`. Always read the
actual ports from `lc env` or `lc status` rather than assuming them:

```sh
lc start --data-volume test-e2e
lc status --data-volume test-e2e
```

### Project context (`--project-id`)

A single data volume can host multiple logical projects. Switching request context is immediate:

```sh
lc start --project-id project-alpha
eval "$(lc env --project-id project-alpha)"

lc start --project-id project-beta
eval "$(lc env --project-id project-beta)"
```

### Caller identity (`--user`)

`--user` sets the attributed caller identity sent to LocalCloud services. The default is `local-developer`. Where an email principal is required, LocalCloud normalizes it to `local-developer@localcloud.invalid`.

## Related References

- [CLI commands and output modes](cli-reference.md)
- [SDK, Terraform, and MCP integrations](integrations.md)
