# LocalCloud SDK, Terraform, and MCP Integrations

After running `eval "$(lc env)"`, official Google Cloud client libraries detect LocalCloud's loopback emulator endpoints.

## Python

```python
from google.cloud import storage, firestore, pubsub_v1

# Storage connects through STORAGE_EMULATOR_HOST.
storage_client = storage.Client(project="local-gcp-project")
bucket = storage_client.create_bucket("my-bucket")
blob = bucket.blob("test.txt")
blob.upload_from_string("Hello from LocalCloud!")

# Firestore connects through FIRESTORE_EMULATOR_HOST.
db = firestore.Client(project="local-gcp-project")
doc_ref = db.collection("users").document("alice")
doc_ref.set({"name": "Alice", "role": "developer"})

# Pub/Sub connects through PUBSUB_EMULATOR_HOST.
publisher = pubsub_v1.PublisherClient()
topic_path = publisher.topic_path("local-gcp-project", "my-topic")
publisher.create_topic(request={"name": topic_path})
```

## Node.js and TypeScript

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

## Go

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

## Terraform and OpenTofu

`--format terraform` emits shell `export` statements for the `GOOGLE_*_CUSTOM_ENDPOINT`
variables that the Google provider reads. Evaluate them into the shell that runs
`terraform` or `tofu`; do not redirect them into a `.tf` file, because the output is
shell, not HCL.

```sh
eval "$(lc env --format terraform)"
terraform apply
```

The exported variables point Google Cloud resources at LocalCloud loopback ports:

```sh
export GOOGLE_STORAGE_CUSTOM_ENDPOINT="http://127.0.0.1:5382/storage/v1/"
export GOOGLE_BIGTABLE_CUSTOM_ENDPOINT="http://127.0.0.1:5385/"
export GOOGLE_SPANNER_CUSTOM_ENDPOINT="http://127.0.0.1:5387/v1/"
export GOOGLE_BIGQUERY_CUSTOM_ENDPOINT="http://127.0.0.1:5388/"
export GOOGLE_SECRET_MANAGER_CUSTOM_ENDPOINT="http://127.0.0.1:5380/v1/"
export GOOGLE_IAM_CUSTOM_ENDPOINT="http://127.0.0.1:5380/v1/"
export GOOGLE_KMS_CUSTOM_ENDPOINT="http://127.0.0.1:5380/v1/"
export GOOGLE_PROJECT="local-gcp-project"
export GOOGLE_OAUTH_ACCESS_TOKEN="localcloud-user.…"
export GOOGLE_OAUTH_CUSTOM_ENDPOINT="http://127.0.0.1:5380/oauth2/"
export GOOGLE_APPLICATION_CREDENTIALS="/dev/null"
# …one entry per enabled service
```

Ports are the ones the selected runtime actually publishes. A runtime that had to
fall back to an alternative host-port mapping emits those ports instead, so always
regenerate rather than hard-coding values.

The Google provider reads all of these from the environment — project, credentials,
and per-service endpoints — so the provider block needs no LocalCloud-specific
arguments of its own.

## AI Coding Agents and MCP

LocalCloud supports the [Model Context Protocol](https://modelcontextprotocol.io). AI coding agents can inspect, seed, test, and manage 25+ local Google Cloud services running inside LocalCloud.

### One-Command Setup: `lc mcp install`

Configure your AI coding assistant with a single command:

```sh
# Install for Cursor (user-level in ~/.cursor/mcp.json)
lc mcp install --client cursor

# Install for Claude Code (user scope via `claude mcp add` CLI)
lc mcp install --client claude-code

# Install for Claude Desktop (claude_desktop_config.json)
lc mcp install --client claude-desktop

# Install for Gemini / Antigravity (~/.gemini/antigravity/mcp_config.json)
lc mcp install --client gemini

# Install for Windsurf (~/.codeium/windsurf/mcp_config.json)
lc mcp install --client windsurf

# Install for Cline (~/.cline/mcp_settings.json)
lc mcp install --client cline

# Install for all supported clients on your machine
lc mcp install --client all
```

#### Key Capabilities
- **Automatic on-demand startup**: The bridge automatically checks if the LocalCloud container is running and starts it if stopped. To require manual control and disable auto-start, pass `--no-start`.
- **Single shared container**: All agents and workspaces share the default `localcloud-data` Docker volume and container instance, preserving host RAM and compute.
- **System binary resolution**: User-level installations prioritize permanent system installations (e.g. `/opt/homebrew/bin/localcloud` or `/usr/local/bin/localcloud`) so GUI applications launched from macOS Dock/Finder find the binary without shell PATH issues, and agent setups survive repository virtualenv deletion.
- **Custom command overrides**: Pass `--command-path <CMD>` to supply a custom command (e.g. `/opt/homebrew/bin/lc` or `localcloud`), or `--bare` to force a bare command name. Pass `--project` to configure only the current repository workspace instead of user-level settings.

### Manual Configuration Examples

If you prefer to configure your client manually:

#### Cursor (`~/.cursor/mcp.json` or `.cursor/mcp.json`)
```json
{
  "mcpServers": {
    "localcloud": {
      "command": "/opt/homebrew/bin/localcloud",
      "args": ["mcp"]
    }
  }
}
```
*(Use bare `"localcloud"` if launching Cursor from a terminal shell with PATH configured).*

#### Claude Code
```sh
claude mcp add --scope user localcloud -- /opt/homebrew/bin/localcloud mcp
```

#### Claude Desktop (`claude_desktop_config.json`)
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
- **Linux**: `~/.config/Claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "localcloud": {
      "command": "/opt/homebrew/bin/localcloud",
      "args": ["mcp"]
    }
  }
}
```

### Agent Guidance

Instruct your coding agent to use LocalCloud emulators:

> You have access to the `localcloud` MCP server. Follow its `use-localcloud-instead-of-gcp` prompt. Use `localcloud_get_env` or `eval "$(lc env)"` to direct Google Cloud SDKs and Terraform to local emulators.

You can also run:
```sh
lc guide
```
to print authoritative workflow guidance directly into the terminal or context window.

## Related References

- [Quick Start](../README.md#quick-start)
- [LocalCloud MCP Architecture and Complete Tool Catalog](mcp.md)
- [CLI commands and output modes](cli-reference.md)
- [Configuration and runtime identity](configuration.md)


