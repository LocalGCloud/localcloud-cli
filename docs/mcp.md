# LocalCloud MCP: a local cloud environment for AI agents

LocalCloud gives AI coding agents a free local cloud environment for building, testing, and debugging Google Cloud applications. Its MCP server connects agents to service discovery, SDK configuration, resource inspection, data queries, readiness checks, and diagnostics. Application code uses standard Google Cloud SDKs pointed at the local runtime.

Start the environment with one command, create local projects for experiments and tests,
and run local service workflows with zero Google Cloud service charges. Initial startup may
download the image; project capacity depends on your machine. For Claude and Codex plugins,
see the [local client setup and GitHub plugin guide](mcp-marketplace-guide.md).

[Website](https://local.cloud/) · [Website MCP guide](https://local.cloud/docs/mcp/) · [Source](https://github.com/LocalGCloud/localcloud-cli) · [Releases](https://github.com/LocalGCloud/localcloud-cli/releases)

## Quickstart

Requirements: **Docker engine, the latest LocalCloud CLI, and the LocalCloud Docker image**. The CLI runs natively on macOS and Linux, includes the MCP bridge, and obtains the image when needed. No separate MCP server installation or Google Cloud account is required for local workflows.

### Install LocalCloud

On macOS, or Linux with Homebrew:

```sh
brew install LocalGCloud/tap/localcloud
lc --version
lc doctor
```

For an existing Homebrew installation, run `brew update` and `brew upgrade localcloud` to use the latest CLI.

On macOS or Linux without Homebrew:

```sh
curl -fsSL https://local.cloud/install.sh | sh
```

If the installer prints an instruction to source your shell configuration or export `PATH`, run it in the current terminal before continuing:

```sh
localcloud --version
localcloud doctor
```

The [release page](https://github.com/LocalGCloud/localcloud-cli/releases/latest) also provides signed standalone archives for macOS ARM64/x86_64 and Linux ARM64/x86_64. macOS binaries require macOS 13 or newer; Linux binaries require glibc 2.35 or newer. Native Windows binaries are not shipped; Windows users need a suitable Linux/WSL environment and a client launch configuration that can reach it.

### Connect an agent

For Cursor:

```sh
lc mcp install --client cursor
```

Reload the client and enable the `localcloud` MCP server. The bridge starts or reuses the runtime automatically and uses the current repository's project (see [One Project per Repository](#one-project-per-repository)). The first start can take a few minutes while Docker downloads the runtime image; until it is ready the agent sees a `localcloud_runtime_status` tool that waits for it and reports any problem. New runtimes publish Docker ports on all host interfaces by default. To start it deliberately with ports bound to localhost before connecting:

```sh
lc start --local-only
```

For Claude Code, use `lc mcp install --client claude-code`; for Claude Desktop, use `lc mcp install --client claude-desktop`; `lc mcp install --client all` configures every supported client installed on this machine. Codex uses `codex mcp add localcloud -- "$(command -v localcloud)" mcp`. Other client configurations are described below, and the [local setup guide](mcp-marketplace-guide.md) includes client verification and a first task.

### Existing environments and updates

Use the latest LocalCloud CLI and image together. Connecting MCP or updating the CLI reuses an existing container; it does not replace an older runtime. If a strict client rejects a tool schema, deliberately update the selected runtime and reconnect.

Inspect `lc status`. To deliberately upgrade the selected runtime while retaining its named data volume:

```sh
lc restart --image agentcloud/localcloud:latest --pull
```

Include the same `--data-volume` and configuration file you normally use if you target a custom environment. The restart briefly interrupts clients; reconnect afterward.

### Native archives and plugin packages

Get native CLI archives and available plugin packages from the [latest release assets](https://github.com/LocalGCloud/localcloud-cli/releases/latest). Configure Claude Desktop through the installed CLI as shown in the [local setup guide](mcp-marketplace-guide.md#claude-desktop). A plugin ZIP is not an MCP desktop-extension bundle. Only use a `.mcpb` package when that artifact is explicitly present in a release and supported by your client. Claude and Codex GitHub plugins use the separately installed CLI.

### Complete a first task

Give the agent this prompt:

> Use the LocalCloud MCP server. List local services, check readiness, and get the SDK environment for this project. Inspect the compatibility information before writing a small Google Cloud integration test. Use only the returned local endpoints; stop if a required operation is unavailable. Do not request real Google Cloud credentials or fall back to real Google Cloud.

A connected client should discover `localcloud_list_services`, `localcloud_check_readiness`, and `localcloud_get_env`. For an initial tool call, use `localcloud_list_services` with `{}`. Then call `localcloud_get_env` with `{"format":"json"}`. Read the returned endpoint values rather than assuming default ports.

## What agents can do

| Workflow | MCP support | Application workflow |
| --- | --- | --- |
| Build a storage and messaging feature | Discover Cloud Storage/Pub/Sub, obtain SDK endpoints, inspect resources and recent requests | Upload a test object, publish a message, consume it, and assert the result using standard SDKs |
| Inspect and query local data | Browse resources, check database connection profiles, run supported queries | Explore a dataset and run a deterministic BigQuery query |
| Write a repeatable integration test | Read compatibility, SDK/ Terraform configuration, recipes and test prompts | Create only test-owned resources, verify results, and clean them up through the SDK |
| Diagnose an application failure | Check readiness, diagnostics, logs and recent requests | Identify an endpoint, schema, or service-readiness problem and rerun the failing test |

Run the [three reproducible MCP and SDK workflows](mcp-workflows.md), or start with [SDK examples](https://local.cloud/docs/sdk-examples/), [Terraform guidance](https://local.cloud/docs/terraform/), and the [agent entry point](https://local.cloud/ai/agents.md). LocalCloud compatibility is service- and operation-specific; validate release behavior against real Google Cloud separately.

## Permissions and local data

Write and destructive MCP management operations are controlled by the runtime settings `LOCALCLOUD_MCP_WRITE` and `LOCALCLOUD_MCP_DESTRUCTIVE`, both disabled by default. These are **runtime** settings, not permissions enabled by `lc mcp install`. The installed client starts the bridge; it does not grant extra runtime privileges. SDK operations can still change local application data, so use test-owned resources and explicit cleanup.

The default data volume is shared across clients and repositories. A project ID selects a logical project; it is not a hard security boundary between agents. Use a separate data volume when an independent runtime is needed. Review [privacy and outbound behavior](https://local.cloud/docs/privacy/) and the [applicable license](https://local.cloud/docs/licensing/) for the artifact you install. LocalCloud is free to use for the documented local development workflows; it is not described here as open source or as a hardened execution sandbox.

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
│  • Answers the handshake at once; connects in the      │
│    background and reports problems as tool results     │
│  • Starts a stopped runtime; never replaces one        │
│  • Selects the repository's project (git, roots)       │
│  • Concurrent requests; reconnects after restarts      │
│  • Attributed caller headers (X-LocalCloud-*)          │
└───────────────────────────┬────────────────────────────┘
                            │
            HTTP JSON-RPC │ Local Gateway (port 5380)
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

### The handshake never waits for Docker
The bridge answers the client's MCP handshake at once and connects to the runtime in the background, so clients with short startup timeouts (Codex 10s, Claude Code 30s) never drop the server while LocalCloud starts.
- **Runtime ready** (the usual case, about a second): the handshake, tools, resources and prompts come from the runtime unchanged.
- **Runtime starting or unavailable**: the client sees a single `localcloud_runtime_status` tool and empty resource and prompt lists. Other tool calls wait up to 90 seconds for a starting runtime, then return an error result that says what is wrong and what to do (start Docker, fix `localcloud.yaml`, run `lc start` under `--no-start`, ...). `localcloud_runtime_status` retries immediately and reports `ready`, `starting` or `unavailable` with the project, data volume and next step.
- **Runtime becomes ready later**: the bridge sends `tools/list_changed`, `resources/list_changed` and `prompts/list_changed`, and clients that support them reload the full catalog. Otherwise reconnect the server.
- Problems are also written once to stderr, which clients keep in their MCP logs.

### On-Demand Auto-Start
- **If stopped or missing**: the bridge starts the runtime (`Controller.start(ensure_project=True, allow_replace=False)`) under the per-volume lock, with the full 60-second readiness budget, so agents connecting at the same time share one start instead of racing. Startup can still time out if the runtime does not become ready within that budget.
- **Manual override**: `--no-start` never starts a runtime. The bridge still answers the handshake and reports `runtime_not_running` through `localcloud_runtime_status` until the user runs `lc start`.

### Non-Replacement Policy
LocalCloud ensures container stability for running agents:
- Attaches to the active container as-is even if host settings or YAML options differ.
- Container replacement (e.g. changing ports or images) remains an explicit, deliberate action using `localcloud start` or `localcloud restart`.

### Single Shared Data Volume & Single Container
- All agents and repositories share the `localcloud-data` Docker volume by default.
- Multiple agents and workspaces run against a single shared container instance, conserving host RAM, CPU, and disk space.
- A custom `--data-volume` is only used when hard container isolation is explicitly demanded.

### One Project per Repository
Every command, the bridge included, selects the same project for a repository, so `lc env` in a terminal and the agents working in that repository see the same data:
1. `--project-id`;
2. `context.project` in the repository's own `localcloud.yaml` (or an explicit `--config`/`LOCALCLOUD_CONFIG`);
3. the git repository's name, slugified into a project ID (`Payments_API.v2` becomes `payments-api-v2`). Worktrees share their main checkout's project; a repository rooted at the home directory is ignored;
4. otherwise `local-gcp-project` (or the `context.project` of a shared home or remembered config).

The bridge learns the repository from its working directory, or from the client's workspace roots (MCP `roots/list`) when the client provides them, as Cursor and VS Code do. A repository's project is created on first use (`lc start`, `lc env`, `lc console`, or an MCP connection) without restarting the runtime. Caller identity defaults to `local-developer` (normalized to `local-developer@localcloud.invalid`), attributing actions per agent or user.

### Concurrent Requests and Reconnection
- Requests run concurrently: a long query does not hold up pings, cancellations or other calls, and a cancelled request gets no response. Tool calls, resource reads and prompts may run up to 10 minutes.
- When the runtime refuses a connection (stopped, restarted, or moved to other ports), the bridge connects again, starting the runtime unless `--no-start`, and resends the request once. A request the runtime had already received is never resent; it returns an error instead.

---

## 3. One-Command Setup: `lc mcp install`

`lc mcp install` writes the `localcloud` server entry for a client. It needs no Docker. By default it configures the **user level**, so every repository gets LocalCloud and the bridge selects each repository's project itself:

```sh
lc mcp install --client cursor           # ~/.cursor/mcp.json (the default client)
lc mcp install --client claude-code      # runs `claude mcp add-json --scope user`
lc mcp install --client claude-desktop   # claude_desktop_config.json
lc mcp install --client gemini           # Gemini CLI: ~/.gemini/settings.json
lc mcp install --client antigravity      # ~/.gemini/config/mcp_config.json
lc mcp install --client windsurf         # Windsurf / Devin Desktop
lc mcp install --client cline            # ~/.cline/data/settings/cline_mcp_settings.json
lc mcp install --client all              # every supported client installed on this machine
```

`--project` writes the repository's own file instead, for the clients that read one: `.cursor/mcp.json`, `.mcp.json` (Claude Code, through `claude mcp add-json --scope project`), `.gemini/settings.json`, and `.agents/mcp_config.json` (Antigravity). Claude Desktop, Windsurf and Cline have no project-level file; `--project` rejects them, and `--client all --project` skips them.

| Client | User level | Project level |
| --- | --- | --- |
| Cursor | `~/.cursor/mcp.json` | `.cursor/mcp.json` |
| Claude Code | `claude mcp add-json --scope user` (`~/.claude.json`) | `.mcp.json` |
| Claude Desktop | `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS), `%APPDATA%\Claude\…` (Windows), `~/.config/Claude/…` (Linux) | — |
| Gemini CLI | `~/.gemini/settings.json` | `.gemini/settings.json` |
| Antigravity | `~/.gemini/config/mcp_config.json` | `.agents/mcp_config.json` |
| Windsurf / Devin Desktop | an existing `~/.codeium/windsurf/mcp_config.json` or `~/.codeium/mcp_config.json`, else `~/.config/devin/mcp_config.json` (`$XDG_CONFIG_HOME`; `%APPDATA%\devin` on Windows) | — |
| Cline | `~/.cline/data/settings/cline_mcp_settings.json` (`$CLINE_MCP_SETTINGS_PATH`), or the VS Code extension's file until Cline migrates it | — |

Codex uses its own registration command, `codex mcp add`.

### Installation Options
| Flag | Description |
|---|---|
| `--client <name>` | `cursor` (default), `claude-code`, `claude-desktop` (alias `claude`), `gemini`, `antigravity`, `windsurf`, `cline`, or `all`. |
| `--global` | Install into user-level configuration (the default). |
| `--project` | Install into the repository's configuration instead. |
| `--project-id <id>` | Pin one project for every workspace the client opens. Without it the bridge selects each repository's project. |
| `--data-volume <name>` | Pin a non-default Docker volume. |
| `--user <name>` | Pin the caller identity (default: `local-developer`). |
| `--command-path <path>` | Explicit executable command or binary path (e.g., `/opt/homebrew/bin/lc`, `localcloud`). |
| `--bare` | Use bare `localcloud` command instead of resolving an absolute path. |

Only the flags you pass are written. A repository's `localcloud.yaml` is read by the bridge when it runs, not copied into a client's settings.

### Safety and Atomicity
- **No Docker dependency**: Running `lc mcp install` does not require Docker to be running.
- **Config preservation**: Merges the `localcloud` entry and keeps every other setting and server. A file that is not a JSON object, or whose `mcpServers` is not an object, is reported and left untouched.
- **Atomic writes with a backup**: Writes through a temporary file and `os.replace`, keeping the previous file as `<name>.bak`.
- **Idempotent**: Reports each client as installed, updated, or already up to date. Claude Code's own `claude mcp` commands make its change when the `claude` CLI is installed; a failing command is reported rather than worked around.
- **Binary resolution**: For user-level configurations, prioritizes globally installed system binaries (such as Homebrew `/opt/homebrew/bin/localcloud`, `/usr/local/bin/localcloud`, or system PATH) so agent setups are permanent across all projects and survive virtualenv removals. Falls back to virtualenv or bare command if no system binary exists. Project-level files get the bare `localcloud` command, since the repository is shared. Use `--bare` or `--command-path` for explicit overrides.

---

## 4. Manual Configuration Examples

Use `command -v localcloud` to find the installed executable. Substitute that absolute path in desktop-client examples; `/opt/homebrew/bin/localcloud` is an Apple Silicon Homebrew example, not a universal path. Merge the server entry into existing configuration rather than replacing other servers.

### Codex

```sh
codex mcp add localcloud -- "$(command -v localcloud)" mcp
codex mcp list
```

Alternatively, merge into `~/.codex/config.toml`:

```toml
[mcp_servers.localcloud]
command = "/opt/homebrew/bin/localcloud"
args = ["mcp"]
startup_timeout_sec = 120
```

See [Codex MCP configuration](https://developers.openai.com/codex/mcp/) for client settings.

### VS Code / GitHub Copilot

Merge this into the workspace `.vscode/mcp.json`, then use **MCP: List Servers** in the Command Palette to start `localcloud`:

```json
{
  "servers": {
    "localcloud": {
      "type": "stdio",
      "command": "/opt/homebrew/bin/localcloud",
      "args": ["mcp"]
    }
  }
}
```

VS Code uses `servers`, while Cursor and Claude Desktop use `mcpServers`. See [VS Code MCP configuration](https://code.visualstudio.com/docs/agent-customization/mcp-servers).

### Cline

`lc mcp install --client cline` writes Cline's shared settings file. To configure it by hand, open Cline's **MCP Servers** settings and its configuration editor, and merge the `localcloud` entry from the Cursor/Claude Desktop example below into `mcpServers`, using the executable path on your machine. See [Cline MCP documentation](https://docs.cline.bot/mcp/mcp-overview).

### Gemini CLI

`lc mcp install --client gemini` configures Gemini CLI (CLI 0.1.9 configured Antigravity for this name; use `--client antigravity` for Antigravity). Gemini's own command works too:

```sh
gemini mcp add --scope user localcloud "$(command -v localcloud)" mcp
gemini mcp list
```

Gemini CLI stores MCP servers in `~/.gemini/settings.json` for user scope. See [Gemini CLI MCP documentation](https://geminicli.com/docs/tools/mcp-server/).

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
claude mcp add --scope user localcloud -- "$(command -v localcloud)" mcp
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

CLI and runtime releases are independent. Available tools, resources, templates and prompts can vary with the runtime image and enabled permissions. Discover them through `tools/list`, `resources/list`, `resources/templates/list`, and `prompts/list` on the connected runtime; those responses are authoritative. The entries below describe common capabilities, not a fixed catalog size.

While the runtime is starting or unavailable, the bridge lists only its own `localcloud_runtime_status` tool (see [The handshake never waits for Docker](#the-handshake-never-waits-for-docker)).

### Tools
1. **API Discovery & Invocation**:
   - `localcloud_get_api_catalog`: Discovers methods and schemas exposed by LocalCloud's management API catalog.
   - `localcloud_call_api`: Invokes a catalogued local management operation using its operation ID and typed parameters.
2. **Service & Project Inspection**:
   - `localcloud_list_services`: List configured services with endpoints, status, protocol and compatibility metadata.
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
   - `localcloud_get_logs`: Get log-oriented diagnostics from LocalCloud's request log. Use `lc logs` for container stdout/stderr.
7. **Scenarios, Recipes & State**:
   - `localcloud_list_recipes`: List pre-configured scenario recipes.
   - `localcloud_get_recipe`: Get definition and seed actions for a recipe.
   - `localcloud_list_scenarios`: List test scenarios.
   - `localcloud_get_scenario`: Get definition of a test scenario.
   - `localcloud_get_seed_schema`: Inspect seed data schemas.
   - `localcloud_export_state`: Export runtime project state.
   - `localcloud_list_checkpoints`: List saved project checkpoints.
   - `localcloud_diff_project`: Compare current project state against a checkpoint.
8. **Local Identity**:
   - `localcloud_identity_check`: Inspect the local identity issuer or verify LocalCloud-issued tokens without returning credentials.

### Resources
- `localcloud://api/catalog`: LocalCloud management API operations and schemas.
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

### Resource Templates
- `localcloud://schema/seed/{service}`
- `localcloud://readiness/{service}`
- `localcloud://compatibility/{service}`
- `localcloud://recipes/{id}`
- `localcloud://scenarios/{id}`
- `localcloud://browse/{service}/{resourceType}`
- `localcloud://browse/{service}/{resourceType}/{resourceId}`

### Prompts
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

## Troubleshooting

| Symptom | Action |
| --- | --- |
| `mcp install` is not recognized | Update LocalCloud through its installation channel and ensure the client uses that executable |
| Docker cannot be reached | Run `lc doctor`, start Docker, and retry; installing client configuration alone does not require Docker |
| Desktop client cannot find `localcloud` | Set an absolute executable path from `command -v localcloud`; restart the client |
| The agent only sees `localcloud_runtime_status` | LocalCloud is starting or unavailable; call the tool for the reason and next step. After it reports `ready`, reconnect the server if the client did not reload its tool list |
| The first tool call is slow | The first start downloads the runtime image; run `lc start --local-only` once beforehand to avoid the wait |
| Status reports `mcp_connection_timeout` | Check `lc status` and `lc logs --tail 100`; retry with `localcloud mcp --connect-timeout 60` |
| Status reports `runtime_not_running` with `--no-start` | Start it explicitly with `lc start`, or remove `--no-start` to allow automatic startup |
| The agent uses a different project than `lc env` | The client started the bridge outside the repository and does not report workspace roots; install with `--project` in the repository, or pass `--project-id` |
| A write operation is rejected | Inspect the operation's safety and runtime permission settings; client installation does not enable write/destructive permissions |
| Tools are missing or a service is disabled | Inspect the connected runtime catalog, readiness and compatibility; CLI version alone does not determine runtime tools |
| Protocol parser reports invalid JSON | Ensure the client launches `localcloud mcp` directly; wrappers must keep diagnostics off stdout |

To disconnect, disable or remove only the `localcloud` server entry in the client's MCP settings. This does not delete the runtime's persistent volume. Report issues at [LocalCloud CLI issues](https://github.com/LocalGCloud/localcloud-cli/issues) with the CLI version, runtime image/version, client, and sanitized error output.
