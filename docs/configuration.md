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
5. no user file.

A selected explicit, environment, or remembered path that is missing or unreadable fails instead of falling back.

CLI resource flags override corresponding `host` values. `--project-id` and `--user` override request context only; they do not replace the server's YAML-derived `context.project`. The selected file is mounted read-only at `/etc/localcloud/localcloud.yaml`. With no file, the CLI adds neither a mount nor container `LOCALCLOUD_CONFIG`.

The container recursively merges the file over packaged `localcloud.defaults.yaml`. Omitted values inherit; a mapping member set to `null` is deleted. Existing setting-specific environment variables have higher precedence. Use `host.seed: disabled`, not `null`, to disable host-side seeding.

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

| Service ID | Google Cloud service | Default status |
| :--- | :--- | :--- |
| `gcs` | Cloud Storage | Enabled |
| `pubsub` | Pub/Sub | Enabled |
| `firestore` | Firestore | Disabled |
| `bigtable` | Bigtable | Enabled |
| `spanner` | Spanner | Enabled |
| `bigquery` | BigQuery | Enabled |
| `sheets` | Google Sheets | Enabled |
| `secretmanager` | Secret Manager | Enabled |
| `cloudtasks` | Cloud Tasks | Enabled |
| `cloudscheduler` | Cloud Scheduler | Enabled |
| `cloudfunctions` | Cloud Functions (2nd Gen) | Enabled |
| `alloydb` | AlloyDB | Enabled |
| `dataproc` | Dataproc | Enabled |
| `cloudiam` | Cloud IAM | Enabled |
| `cloudresourcemanager` | Cloud Resource Manager | Enabled |
| `serviceusage` | Service Usage | Enabled |
| `cloudbilling` | Cloud Billing | Enabled |
| `logging` | Cloud Logging | Enabled |
| `monitoring` | Cloud Monitoring | Enabled |
| `gke` | GKE | Disabled |
| `compute` | Compute Engine | Disabled |
| `cloudrun` | Cloud Run | Disabled |
| `memorystore` | Memorystore (Redis/Valkey) | Enabled |
| `workflows` | Cloud Workflows | Enabled |
| `vertexai` | Vertex AI | Disabled |
| `kms` | Cloud KMS | Disabled |
| `cloudsql` | Cloud SQL | Enabled |

## Runtime Identity and Multi-Project Context

LocalCloud separates durable container storage from logical Google Cloud project contexts.

### Data volumes (`--data-volume`)

A named Docker volume provides durable identity. The default `localcloud-data` volume is mounted at `/var/lib/localcloud`. Multiple isolated environments can run concurrently on dynamic loopback ports:

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
