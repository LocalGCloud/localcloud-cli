# LocalCloud CLI

[![LocalCloud](https://img.shields.io/badge/LocalCloud-local.cloud-4285F4?style=flat-square)](https://local.cloud)
[![CLI](https://img.shields.io/badge/CLI-localcloud%20%7C%20lc-34A853?style=flat-square)](https://local.cloud)
[![Docker](https://img.shields.io/badge/Runtime-Docker-2496ED?style=flat-square&logo=docker&logoColor=white)](https://www.docker.com)
[![MCP](https://img.shields.io/badge/Protocol-MCP-FBBC04?style=flat-square)](https://modelcontextprotocol.io)

The official host CLI for [LocalCloud](https://local.cloud) — a local, Docker-backed Google Cloud emulator platform for rapid development, testing, learning, and AI coding agent workflows.

LocalCloud CLI manages the entire emulator lifecycle, multi-project context, SDK environment variables, Docker volume persistence, and Model Context Protocol (MCP) bridge.

---

## Table of Contents

- [Overview](#overview)
- [Install](#install)
- [Quick Start](#quick-start)
- [CLI Command Reference](#cli-command-reference)
- [Connecting Your Apps & SDKs](#connecting-your-apps--sdks)
- [Configuration Reference (`localcloud.yaml`)](#configuration-reference-localcloudyaml)
- [Runtime Identity & Multi-Project Context](#runtime-identity--multi-project-context)
- [AI Coding Agents & MCP Integration](#ai-coding-agents--mcp-integration)
- [Output Formatting & Automation](#output-formatting--automation)
- [Development & Testing](#development--testing)
- [License & Support](#license--support)

---

## Overview

LocalCloud runs Google Cloud-compatible services locally inside Docker containers without hitting real cloud endpoints or incurring billing:

- **25+ Local Google Cloud Services**: Cloud Storage, Firestore, Pub/Sub, BigQuery, Secret Manager, Cloud SQL, Spanner, Bigtable, Cloud Tasks, Cloud Logging, Dataproc, and more.
- **Zero Cloud Bills & Offline Speed**: Develop and run full test suites completely offline with zero latency and instant teardown.
- **Fast CLI & Dual Alias**: Use either `localcloud` or the fast alias `lc` interchangeably.
- **Multi-Project Isolation**: Run multiple isolated GCP project contexts (`--project-id`) on a single durable runtime.
- **Automatic SDK Configuration**: Generate environment variables for Google Cloud client libraries across Shell, JSON, Terraform, and Docker Compose formats.
- **AI-Native MCP Bridge**: Built-in Model Context Protocol (MCP) server enabling AI coding assistants (Claude Desktop, Cursor, Windsurf) to inspect and manage local cloud resources.
- **Interactive Terminal UI**: OMP-inspired startup banner with 4-color Google branding and clean machine-readable fallbacks for CI/CD.

---

## Install

### Prerequisites

Docker Desktop, Colima, or Docker Engine must be installed and running on your system.

### One-line installer (macOS & Linux)

```sh
curl -fsSL https://local.cloud/install.sh | sh
```

The installer installs `localcloud` and creates the `lc` alias when available.

### Homebrew (macOS & Linux)

```sh
brew install LocalGCloud/tap/localcloud
```

To upgrade or uninstall via Homebrew:

```sh
brew upgrade localcloud
brew uninstall localcloud
```

### Standalone Release Binaries

Signed release archives with Sigstore verification bundles are published for:
- **macOS** 13+ (Apple Silicon & Intel)
- **Linux** (x86_64 & aarch64, glibc 2.35+ / Ubuntu 22.04+)

*Windows users: Follow the Docker setup instructions at <https://local.cloud/docs/getting-started/>.*

---

## Quick Start

Get a fully functional local Google Cloud environment running in 3 steps:

### 1. Check prerequisites

```sh
lc doctor
```

Confirms Docker engine connectivity and verifies environment readiness.

### 2. Start LocalCloud

```sh
lc start
```

Starts the local emulator container on the default `localcloud-data` volume and prepares the `local-gcp-project` project context.

### 3. Configure your shell & connect

```sh
eval "$(lc env)"
```

Exports local Google Cloud SDK emulator environment variables (`STORAGE_EMULATOR_HOST`, `PUBSUB_EMULATOR_HOST`, `FIRESTORE_EMULATOR_HOST`, `BIGQUERY_EMULATOR_HOST`, etc.) directly into your current terminal.

Open the web management console in your default browser:

```sh
lc console
```

---

## CLI Command Reference

`lc` and `localcloud` share the exact same commands, flags, and behaviors.

### `start`

Starts the container runtime on the selected data volume and initializes the project context.

```sh
lc start
lc start --project-id my-project
lc start --data-volume isolated-data --user alice
lc start ./custom-config.yaml
```

- Reuses existing running containers attached to the same volume.
- Automatically creates project contexts if they do not exist yet.
- Waits up to 60 seconds for container health and service readiness before returning.

### `status`

Inspects runtime health, Docker container state, endpoints, and active context.

```sh
lc status
lc status --verbose
lc status --data-volume isolated-data
```

### `env`

Generates Google Cloud SDK configuration for the active project context.

```sh
# Export directly into current shell
eval "$(lc env)"

# Output as JSON payload
lc env --format json

# Generate Terraform/OpenTofu provider endpoint configuration
lc env --format terraform

# Generate Docker Compose environment variables
lc env --format docker-compose
```

### `console`

Opens the LocalCloud browser-based web console for the selected project and user.

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

Gracefully restarts the LocalCloud container runtime and reapplies volatile seed data without deleting persistent volume state.

```sh
# Fast in-place restart of existing container (default: --no-pull)
lc restart

# Pull the latest container image from registry and restart with updated image
lc restart --pull
```
### `reset`

Resets emulator data and reapplies initial seed state.

```sh
# Reset only the selected project's data (default)
lc reset

# Reset all projects and recreate the managed data volume
lc reset --all-projects
```

### `stop`

Stops the container runtime without deleting persistent volume data.

```sh
lc stop
lc stop --data-volume isolated-data
```

### `doctor`

Diagnoses Docker daemon access, permissions, and inspects legacy LocalCloud host files.

```sh
lc doctor
```

### `cleanup`

Finds and removes malformed Docker resources, stale runtime state, and legacy lock files.

```sh
# Remove malformed resources, stale runtime state, and legacy host files (default)
lc cleanup

# Inspect what would be removed without deleting resources
lc cleanup --dry-run
```

### `guide`

Prints authoritative guidance and workflow instructions for AI coding agents and automated developer scripts.

```sh
lc guide
```

### `mcp`

Runs the stdio Model Context Protocol (MCP) bridge for AI tools and coding environments.

```sh
lc mcp
lc mcp --project-id my-project
```

---

## Connecting Your Apps & SDKs

After running `eval "$(lc env)"`, official Google Cloud client libraries automatically detect loopback emulator endpoints.

### Python

```python
from google.cloud import storage, firestore, pubsub_v1

# Storage (connects automatically to STORAGE_EMULATOR_HOST)
storage_client = storage.Client(project="local-gcp-project")
bucket = storage_client.create_bucket("my-bucket")
blob = bucket.blob("test.txt")
blob.upload_from_string("Hello from LocalCloud!")

# Firestore (connects automatically to FIRESTORE_EMULATOR_HOST)
db = firestore.Client(project="local-gcp-project")
doc_ref = db.collection("users").document("alice")
doc_ref.set({"name": "Alice", "role": "developer"})

# Pub/Sub (connects automatically to PUBSUB_EMULATOR_HOST)
publisher = pubsub_v1.PublisherClient()
topic_path = publisher.topic_path("local-gcp-project", "my-topic")
publisher.create_topic(request={"name": topic_path})
```

### Node.js / TypeScript

```typescript
import { Storage } from "@google-cloud/storage";
import { Firestore } from "@google-cloud/firestore";
import { PubSub } from "@google-cloud/pubsub";

const storage = new Storage({ projectId: "local-gcp-project" });
await storage.createBucket("my-bucket");

const firestore = new Firestore({ projectId: "local-gcp-project" });
await firestore.collection("users").doc("alice").set({ name: "Alice" });

const pubsub = new PubSub({ projectId: "local-gcp-project" });
await pubsub.createTopic("my-topic");
```

### Go

```go
package main

import (
	"context"
	"cloud.google.com/go/storage"
)

func main() {
	ctx := context.Background()
	client, err := storage.NewClient(ctx)
	if err != nil {
		panic(err)
	}
	defer client.Close()

	bucket := client.Bucket("my-bucket")
	_ = bucket.Create(ctx, "local-gcp-project", nil)
}
```

### Terraform / OpenTofu

Generate LocalCloud provider endpoint bindings:

```sh
lc env --format terraform > localcloud.tf
```

Produces provider block configurations pointing Google Cloud resources to LocalCloud loopback ports:

```hcl
provider "google" {
  project      = "local-gcp-project"
  access_token = "localcloud-emulator-token"

  storage_custom_endpoint   = "http://127.0.0.1:49080/storage/v1/"
  pubsub_custom_endpoint    = "http://127.0.0.1:49085/"
  firestore_custom_endpoint = "http://127.0.0.1:49084/"
}
```

---

## Configuration Reference (`localcloud.yaml`)

The host CLI and container read the same versioned partial overlay. Image
defaults remain authoritative for server/catalog wiring; `host` values are
resolved by the CLI before it creates the container.

```yaml
# localcloud.yaml
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

Selection order is an explicit positional config, host
`LOCALCLOUD_CONFIG`, `./localcloud.yaml`, the runtime's remembered config,
then no user file. A selected explicit/environment/remembered path that is
missing or unreadable fails instead of falling back.

CLI resource flags override the corresponding `host` values. `--project-id`
and `--user` override request context only; they do not replace the server's
YAML-derived `context.project`. The selected file is mounted read-only at
`/etc/localcloud/localcloud.yaml`; with no file the CLI adds neither a mount
nor container `LOCALCLOUD_CONFIG`.

The container recursively merges the file over packaged
`localcloud.defaults.yaml`. Omitted values inherit and a mapping member set to
`null` is deleted. Existing setting-specific environment variables remain
higher precedence. Use `host.seed: disabled`, not `null`, to turn host-side
seeding off.

| Removed flat key | Replacement |
| :--- | :--- |
| `project`, `user` | `context.project`, `context.user` |
| `services` | `services.enabled` |
| `data_volume`, `seed`, `data`, `image`, `memory` | the same key under `host` |
| `docker_socket`, `transparent_network`, `environment` | the same key under `host` |
| `container_name`, `network_name` | the same key under `host` |

Changing the config path/presence recreates the managed runtime. Editing
server/catalog values at the same path is picked up on explicit restart without
putting those values or secrets into Docker labels. Protect any config file
that contains credentials with normal host-file permissions.

If the CLI's host/context checks ever disagree with what a newer LocalCloud
image actually accepts, pass `--skip-config-validation` (or set
`LOCALCLOUD_SKIP_CONFIG_VALIDATION=1`) on `start`/`restart`/`reset` to bypass
the CLI's closed-set field/version checks and removed-flat-schema detection.
The file is still passed through unchanged, so LocalCloud remains the final
authority; each bypassed check is recorded in `diagnostics` on the resulting
status output. This never bypasses YAML syntax checks or the value checks the
CLI needs to drive Docker itself (e.g. `host.memory`, `host.data`).

### Available Services Inventory

| Service ID | Google Cloud Service | Default Status |
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

---

## Runtime Identity & Multi-Project Context

LocalCloud cleanly separates durable container storage from logical GCP project contexts:

### 1. Data Volumes (`--data-volume`)
- Durable identity is backed by a named Docker volume (default: `localcloud-data`) mounted at `/var/lib/localcloud`.
- Multiple isolated environments (e.g. `team-data`, `ci-volume`, `test-e2e`) can run concurrently on dynamic loopback ports without collision:
  ```sh
  lc start --data-volume test-e2e
  lc status --data-volume test-e2e
  ```

### 2. Multi-Project Context (`--project-id`)
- A single data volume can host multiple logical GCP projects simultaneously.
- Switching project context is instantaneous:
  ```sh
  lc start --project-id project-alpha
  eval "$(lc env --project-id project-alpha)"

  lc start --project-id project-beta
  eval "$(lc env --project-id project-beta)"
  ```

### 3. Caller Identity (`--user`)
- Attributed caller identity sent to LocalCloud services (default: `local-developer`).
- Normalizes to `local-developer@localcloud.invalid` where an email principal is required.

---

## AI Coding Agents & MCP Integration

LocalCloud features first-class support for the [Model Context Protocol (MCP)](https://modelcontextprotocol.io). AI agents can programmatically inspect, seed, test, and manage local cloud resources.

### Claude Desktop / Cursor / Windsurf Configuration

Add LocalCloud as an MCP server in your `claude_desktop_config.json` or `cursor.json`:

```json
{
  "mcpServers": {
    "localcloud": {
      "command": "localcloud",
      "args": ["mcp", "--data-volume", "localcloud-data", "--project-id", "local-gcp-project"]
    }
  }
}
```

### Agent Instruction Prompt

When using coding agents (such as Claude Code, Cursor, Copilot Workspace, Codex), run:

```sh
lc guide
```

or instruct your agent:
> *"Before interacting with local cloud services, run `localcloud guide` to inspect available MCP tools and emulator endpoints."*

---

## Output Formatting & Automation

### Interactive Terminals

On interactive TTYs, LocalCloud displays structured 2-column OMP banners with 4-color Google Cloud artwork, active context details, and lifecycle spinners.

### Machine-Readable JSON (`--verbose`)

For CI/CD pipelines and scripts, pass `--verbose` to obtain complete structured JSON outputs:

```sh
lc status --verbose
```

```json
{
  "status": "running",
  "data_volume": "localcloud-data",
  "project": "local-gcp-project",
  "user": "local-developer",
  "container": {
    "id": "7a8b9c0d1e2f",
    "name": "localcloud",
    "state": "running",
    "url": "http://127.0.0.1:49080"
  },
  "services": "default",
  "mcp": {
    "command": "localcloud",
    "args": ["mcp", "--data-volume", "localcloud-data"],
    "direct_url": "http://127.0.0.1:49080/mcp"
  }
}
```

### Selective Fields (`--fields`)

Extract specific fields directly into standard tabular summary format:

```sh
lc start --fields container.name,mcp.direct_url
```

### Color Mode Control

- `NO_COLOR=1` or dumb terminals disable all ANSI color escapes.
- TrueColor (24-bit) and ANSI-256 color palettes degrade gracefully according to terminal capabilities.

---

## Development & Testing

LocalCloud CLI is developed in Python 3.11+ using [uv](https://docs.astral.sh/uv/).

```sh
# Install dependencies
uv sync --extra test

# Run full test suite
uv run --extra test python -m pytest -q

# Run non-docker unit tests
uv run --extra test python -m pytest -q -m "not docker"

# Test CLI locally
uv run lc --help
```

---

## License & Support

LocalCloud is proprietary software. Individual developers receive the rights outlined in [LICENSE](LICENSE).

- **Website**: [local.cloud](https://local.cloud)
- **Documentation**: [local.cloud/docs](https://local.cloud/docs)
- **Support & Issues**: Open an issue on GitHub or contact support@local.cloud.
