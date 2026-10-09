# LocalCloud Model Context Protocol (MCP) Architecture and Guide

The LocalCloud MCP bridge enables AI coding agents (such as Cursor, Claude Code, Claude Desktop, Gemini/Antigravity, Windsurf, and Cline) to discover, inspect, and interact with 25+ local Google Cloud services running inside LocalCloud.

---

## 1. Architecture Overview

```
┌────────────────────────────────────────────────────────┐
│                   AI Coding Agent                      │
│      (Cursor, Claude Code, Claude Desktop, etc.)       │
└───────────────────────────┬────────────────────────────┘
                            │
               JSON-RPC 2.0 │ Stdio Stream
        (stdout pure data / stderr diagnostics)
                            │
┌───────────────────────────▼────────────────────────────┐
│              LocalCloud MCP Bridge (CLI)               │
│          `localcloud mcp` / `McpAdapter`               │
│                                                        │
│  • Auto-starts Docker container on demand if stopped   │
│  • Idempotent data-volume locked startup               │
│  • Guarantees container non-replacement                │
│  • Automatic reconnection on container restart         │
│  • Attributed caller headers (X-LocalCloud-*)          │
└───────────────────────────┬────────────────────────────┘
                            │
                   HTTP/SSE │ Loopback Gateway (port 5380)
                            │ Headers: X-LocalCloud-Project,
                            │          X-LocalCloud-User
┌───────────────────────────▼────────────────────────────┐
│               LocalCloud Docker Runtime                │
│             Volume: `localcloud-data`                  │
│                                                        │
│   GCS • BigQuery • Pub/Sub • Firestore • Spanner       │
│   Cloud SQL • Secret Manager • Cloud Functions • ...   │
└────────────────────────────────────────────────────────┘
```

### Stdio Protocol Purity
MCP communicates via JSON-RPC 2.0 over standard input and output (`stdio`).
- **`stdout`**: Reserved strictly for valid JSON-RPC messages. Any banner text, color codes, or ASCII characters on `stdout` corrupts client JSON parsers.
- **`stderr`**: Used for progress diagnostics, runtime health messages, and startup notices.
- **Errors**: Translated into structured MCP tool results or JSON-RPC protocol error frames so the AI agent can read and self-correct.

---

## 2. Core Capabilities and Design Principles

### On-Demand Auto-Start
When an AI agent launches `localcloud mcp`, the CLI checks if the container runtime is running:
- **If stopped or missing**: Automatically starts the container in the background (`Controller.start(ensure_project=True, allow_replace=False)`).
- **Concurrency protection**: Takes the per-volume file lock (`data_volume_lock`) and uses the full readiness budget (60s), ensuring concurrent agents starting at the same time do not race or time out.
- **Manual override**: Passing `--no-start` disables auto-start, exiting with code `runtime_not_running` if LocalCloud is not already running.

### Non-Replacement Policy
LocalCloud ensures container stability for running agents:
- Attaches to the active container as-is even if host settings or YAML options differ.
- Container replacement (e.g. changing ports or images) remains an explicit, deliberate action using `localcloud start` or `localcloud restart`.

### Single Shared Data Volume & Single Container
- All agents and repositories share the `localcloud-data` Docker volume by default.
- Multiple agents and workspaces run against a single shared container instance, conserving host RAM, CPU, and disk space.
- A custom `--data-volume` is only used when hard container isolation is explicitly demanded.

### Logical Project-Level Scope
- Default project is `local-gcp-project`, ensuring all commands (`lc env`, `lc console`, `lc reset`, and MCP) align on the exact same project and seeded sample data.
- When an agent or developer passes an explicit `--project-id`, the runtime ensures the logical project exists on connection without restarting the container.
- Caller identity defaults to `local-developer` (normalized to `local-developer@localcloud.invalid`), attributing actions per agent or user.

### Auto-Reconnection on Restart
- If the LocalCloud container is restarted (`lc restart`) or port mappings change while an MCP session is open, the bridge detects `java_mcp_unavailable`, automatically re-resolves the target gateway, and retries the request once before failing.

---

## 3. One-Command Setup: `lc mcp install`

LocalCloud provides an automated installer that configures AI coding assistants at the user level by default so all repositories can access LocalCloud:

```sh
# Install for Cursor (user-level in ~/.cursor/mcp.json)
lc mcp install --client cursor

# Install for Claude Code (user scope via `claude mcp add` CLI)
lc mcp install --client claude-code

# Install for Claude Desktop (user-level in claude_desktop_config.json)
lc mcp install --client claude-desktop

# Install for Gemini / Antigravity (user-level in ~/.gemini/antigravity/mcp_config.json)
lc mcp install --client gemini

# Install for Windsurf
lc mcp install --client windsurf

# Install for Cline
lc mcp install --client cline

# Install for all supported clients on your machine
lc mcp install --client all
```

### Installation Options
| Flag | Description |
|---|---|
| `--client <name>` | Target AI client: `cursor` (default), `claude-code`, `claude-desktop`, `gemini`, `windsurf`, `cline`, or `all`. |
| `--global` | Install into user-level configuration (default: true). |
| `--project` | Install into project/workspace configuration instead of user-level configuration. |
| `--project-id <id>` | Pin a specific GCP project ID (defaults to shared `local-gcp-project`). |
| `--data-volume <name>` | Specify a non-default Docker volume. (Omitted by default). |
| `--user <name>` | Specify the caller identity (default: `local-developer`). |
| `--command-path <path>` | Explicit executable command or binary path (e.g., `/opt/homebrew/bin/lc`, `localcloud`). |
| `--bare` | Use bare `localcloud` command instead of resolving an absolute path. |

### Safety and Atomicity
- **No Docker dependency**: Running `lc mcp install` does not require Docker to be running.
- **Config preservation**: Safely parses existing configuration files and preserves third-party MCP servers.
- **Atomic writes**: Uses temporary files with atomic rename (`os.replace`) to prevent file corruption.
- **Binary resolution**: For user-level configurations, prioritizes globally installed system binaries (such as Homebrew `/opt/homebrew/bin/localcloud`, `/usr/local/bin/localcloud`, or system PATH) so agent setups are permanent across all projects and survive virtualenv removals. Falls back to virtualenv or bare command if no system binary exists. Use `--bare` or `--command-path` for explicit overrides.

---

## 4. Manual Configuration Examples

### Cursor (`~/.cursor/mcp.json` or `.cursor/mcp.json`)
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
*(On macOS, an absolute path like `/opt/homebrew/bin/localcloud` is recommended when Cursor is launched from the Dock or Finder. Use bare `"localcloud"` if launching from an interactive terminal with configured PATH).*

### Claude Code
Run using the Claude Code CLI:
```sh
claude mcp add --scope user localcloud -- /opt/homebrew/bin/localcloud mcp
```

### Claude Desktop (`claude_desktop_config.json`)
- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`
- Linux: `~/.config/Claude/claude_desktop_config.json`

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

---

## 5. Authoritative MCP Catalog

The LocalCloud MCP server exposes **27 tools**, **14 resources**, **7 resource templates**, and **6 prompts** directly to coding agents.

### Tools (27)
1. **API Discovery & Invocation**:
   - `localcloud_get_api_catalog`: Returns the complete catalog of all supported Google Cloud REST APIs, methods, and request/response schemas.
   - `localcloud_call_api`: Generic caller to invoke any supported GCP REST management API directly inside LocalCloud.
2. **Service & Project Inspection**:
   - `localcloud_list_services`: List all running GCP services, status, and loopback ports.
   - `localcloud_get_service`: Get detailed endpoints, ports, and configuration for a specific service.
   - `localcloud_list_projects`: List all logical GCP projects currently initialized in the container.
   - `localcloud_get_project`: Get details for a specific project.
3. **Resource Browsing & Data Access**:
   - `localcloud_browse_resources`: Browse buckets, datasets, tables, topics, subscriptions, and queues.
   - `localcloud_read_resource`: Read metadata or content for a browsed resource.
   - `localcloud_query_data`: Query data across emulated databases (BigQuery, Spanner, Cloud SQL).
4. **Environment & SDK Generation**:
   - `localcloud_get_env`: Get SDK environment variables for shell, JSON, or Terraform.
   - `localcloud_generate_sdk_env`: Generate environment export code for client SDKs.
   - `localcloud_generate_gcloud_env`: Generate `gcloud` CLI configuration commands.
   - `localcloud_generate_terraform_env`: Generate provider configuration for Terraform / OpenTofu.
   - `localcloud_validate_agent_config`: Validate that agent SDK settings match LocalCloud endpoints.
5. **Readiness & Compatibility**:
   - `localcloud_check_readiness`: Check health and readiness across all services or a specific service.
   - `localcloud_check_compatibility`: Check Google Cloud API compatibility and supported feature parity.
6. **Diagnostics & Logging**:
   - `localcloud_get_diagnostics`: Retrieve recent diagnostic findings and health events.
   - `localcloud_get_recent_requests`: Inspect recent HTTP requests received by the LocalCloud gateway.
   - `localcloud_get_logs`: Fetch runtime container and emulator logs.
7. **Scenarios, Recipes & State**:
   - `localcloud_list_recipes`: List pre-configured scenario recipes.
   - `localcloud_get_recipe`: Get definition and seed actions for a recipe.
   - `localcloud_list_scenarios`: List test scenarios.
   - `localcloud_get_scenario`: Get definition of a test scenario.
   - `localcloud_get_seed_schema`: Inspect seed data schemas.
   - `localcloud_export_state`: Export runtime project state.
   - `localcloud_list_checkpoints`: List saved project checkpoints.
   - `localcloud_diff_project`: Compare current project state against a checkpoint.

### Resources (14)
- `localcloud://api/catalog`: Complete catalog of supported Google Cloud APIs.
- `localcloud://api/openapi`: OpenAPI specifications for LocalCloud management facades.
- `localcloud://services`: Enabled services and assigned loopback ports.
- `localcloud://env/shell`: Shell environment variable exports (`export STORAGE_EMULATOR_HOST=...`).
- `localcloud://env/json`: Structured JSON representation of all emulator endpoints.
- `localcloud://env/terraform`: Terraform provider endpoint overrides.
- `localcloud://env/databases`: Database connection strings (PostgreSQL, MySQL, Redis, Spanner).
- `localcloud://readiness`: Live readiness report across all services.
- `localcloud://compatibility`: Service feature compatibility matrices.
- `localcloud://diagnostics/latest`: Latest health check diagnostics.
- `localcloud://recipes`: Available data-seeding recipes.
- `localcloud://scenarios`: Available integration test scenarios.
- `localcloud://terraform/readiness`: Terraform provider readiness checks.
- `localcloud://schema/seed`: Seed schemas for emulated services.

### Resource Templates (7)
- `localcloud://schema/seed/{service}`
- `localcloud://readiness/{service}`
- `localcloud://compatibility/{service}`
- `localcloud://recipes/{id}`
- `localcloud://scenarios/{id}`
- `localcloud://browse/{service}/{resourceType}`
- `localcloud://browse/{service}/{resourceType}/{resourceId}`

### Prompts (6)
- `use-localcloud-instead-of-gcp`: Instructs coding agents to direct all Google Cloud client libraries, SDKs, and Terraform configurations to LocalCloud emulators rather than real GCP.
- `debug-localcloud-service`: Guidance for diagnosing service health and connectivity.
- `write-localcloud-integration-test`: Template and best practices for writing integration tests against LocalCloud.
- `seed-localcloud-scenario`: Instructions for seeding test fixtures and test data.
- `terraform-with-localcloud`: Directing Terraform / OpenTofu providers to LocalCloud loopback ports.
- `compatibility-aware-implementation`: Designing code aware of LocalCloud's emulated API surface.

---

## 6. Coding Agent Best Practices

1. **Use the built-in MCP Prompt**:
   Instruct the agent to use the server's prompt:
   > Follow the `use-localcloud-instead-of-gcp` prompt from the `localcloud` MCP server. Always check local endpoints using `localcloud_get_env` or `localcloud://env/shell` before making cloud calls.

2. **Data access via SDKs**:
   Data operations (uploading files to GCS, publishing messages to Pub/Sub, querying Firestore) should be executed using standard Google Cloud client libraries (`google-cloud-storage`, `@google-cloud/pubsub`, etc.) directed to the emulator endpoints exported by `localcloud_get_env` or `eval "$(lc env)"`.

3. **Single container efficiency**:
   Because LocalCloud uses a single shared data volume and container, multiple agents running in different repositories can work concurrently without starting duplicate containers or wasting host RAM.
