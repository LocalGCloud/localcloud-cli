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

The [release page](https://github.com/LocalGCloud/localcloud-cli/releases/latest) also provides signed standalone archives for macOS ARM64 (Apple Silicon) and Linux ARM64/x86_64. macOS binaries require macOS 13 or newer; Linux binaries require glibc 2.35 or newer. Native Windows binaries are not shipped; Windows users need a suitable Linux/WSL environment and a client launch configuration that can reach it.

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
lc restart --pull
```

On a runtime pinned to a version tag (such as `agentcloud/localcloud:0.1.8-lite`), `--pull` also moves it to the newest version on the same lite or full channel, and the new image migrates the data volume forward. Include the same `--data-volume` and configuration file you normally use if you target a custom environment. The restart briefly interrupts clients; reconnect afterward.

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

Write and destructive MCP management operations are controlled by the runtime settings `mcp.write` and `mcp.destructive` in `localcloud.yaml` (or the environment variables `LOCALCLOUD_MCP_WRITE` and `LOCALCLOUD_MCP_DESTRUCTIVE`, which take precedence), both disabled by default and applied when the runtime starts. These are **runtime** settings, not permissions enabled by `lc mcp install`. The installed client starts the bridge; it does not grant extra runtime privileges. SDK operations can still change local application data, so use test-owned resources and explicit cleanup.

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
- **Runtime ready** (the usual case, about a second): the handshake, tools, resources and prompts come from the runtime, with missing tool display titles filled in.
- **Runtime starting or unavailable**: the client sees a single `localcloud_runtime_status` tool and empty resource and prompt lists. Other tool calls wait up to 90 seconds for a starting runtime, then return an error result that says what is wrong and what to do (start Docker, fix `localcloud.yaml`, run `lc start` under `--no-start`, ...). `localcloud_runtime_status` retries immediately and reports `ready`, `starting` or `unavailable` with the project, data volume and next step. When the runtime container fails to start, the error also carries `diagnostics`: the CLI version, image, exit code, whether it was killed for memory, the newest log lines with credentials redacted, and the image the data volume last became ready on when that was another image.
- **Runtime slow to answer**: a connected runtime has 3 seconds to answer the handshake or a list. After that the bridge answers as for a starting runtime, so neither takes more than about 7 seconds. A list the runtime answers later is kept: the bridge sends the `list_changed` notifications and the client gets that list by asking again.
- **Runtime becomes ready later**: the bridge sends `tools/list_changed`, `resources/list_changed` and `prompts/list_changed`, and clients that support them reload the full catalog. Otherwise reconnect the server.
- Problems are also written once to stderr, which clients keep in their MCP logs; `localcloud mcp --debug` adds the diagnostics of a failed start.

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
The bridge gives every coding session in a repository the same project, so agents working in different repositories keep their data apart:
1. `--project-id`;
2. `context.project` in the repository's own `localcloud.yaml` (or an explicit `LOCALCLOUD_CONFIG`);
3. the git repository's name, slugified into a project ID (`Payments_API.v2` becomes `payments-api-v2`). Worktrees share their main checkout's project; a repository rooted at the home directory is ignored;
4. otherwise `local-gcp-project` (or the `context.project` of a shared home or remembered config).

The bridge learns the repository from its working directory, or from the client's workspace roots (MCP `roots/list`) when the client provides them, as Cursor and VS Code do, and creates the project on connection without restarting the runtime. Only the bridge uses step 3: terminal commands such as `lc start` and `lc env` use `--project-id`, the configured `context.project`, or `local-gcp-project`. To work in the agents' project from a terminal, pass it (`eval "$(lc env --project-id payments-api-v2)"`), or set `context.project` in the repository's `localcloud.yaml` so both use it. Caller identity defaults to `local-developer` (normalized to `local-developer@localcloud.invalid`), attributing actions per agent or user.

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

Runtime `agentcloud/localcloud:0.1.17` lists 25 tools by default. The runtime settings `mcp.write`
and `mcp.destructive` (environment `LOCALCLOUD_MCP_WRITE` and `LOCALCLOUD_MCP_DESTRUCTIVE`, both
`false` by default) add the write and destructive tools; with both on, `tools/list` holds 37. A tool
called without its setting is refused whether or not it was listed.

**Read tools** (always listed):

| Tool | Required arguments | What it does |
| :--- | :--- | :--- |
| `localcloud_list_services` | | Lists the configured services with their endpoints, enabled state and callable state. |
| `localcloud_get_status` | | Overall gateway health and uptime. |
| `localcloud_list_projects` | | Lists the projects. |
| `localcloud_get_project` | `project` | Project details. |
| `localcloud_get_env` | | SDK environment variables (`format`) or database profiles. |
| `localcloud_generate_terraform_env` | | Terraform shell environment variables. |
| `localcloud_validate_agent_config` | | Checks an agent's environment (`env` or `text`) against LocalCloud's endpoints. |
| `localcloud_check_readiness` | | Readiness of every service, or of one (`service`). |
| `localcloud_check_compatibility` | `service` | Compatibility and isolation caveats of a service. |
| `localcloud_list_recipes`, `localcloud_get_recipe` | `id` (get) | Agent recipes. |
| `localcloud_list_scenarios`, `localcloud_get_scenario` | `id` (get) | Packaged agent scenarios. |
| `localcloud_get_seed_schema` | | Seed YAML JSON schemas and example templates, for all services or one (`service`). |
| `localcloud_list_checkpoints` | `project` | Lists a project's checkpoints. |
| `localcloud_diff_project` | `project`, `name` | Diffs the project's state against a checkpoint. |
| `localcloud_export_state` | `project` | Exports the project's resource state as YAML, optionally for some `services`. |
| `localcloud_get_api_catalog` | | Searches the management REST operations (`text`, `method`, `path`, `safety`, `limit`). |
| `localcloud_call_api` | `operation_id` | Calls a management operation with `path_parameters`, `query_parameters`, `headers`, `body` and `content_type`. Write and destructive operations need the matching setting. |
| `localcloud_query_data` | `service`, `sql` | Runs SQL text as the Console SQL Editor does. See below. |
| `localcloud_browse_resources` | `service`, `resourceType` | Lists resources of a service. Only Cloud Tasks queues (`cloudtasks`) can be listed; anything else answers `NOT_SUPPORTED`. |
| `localcloud_get_diagnostics` | | LocalCloud diagnostics. |
| `localcloud_get_recent_requests` | | Recent HTTP and gRPC requests. |
| `localcloud_get_usage` | | Gets a project's usage meters as `GET /usage` reports them, in Google's billing units without prices: per service, each meter's unit, kind, basis, total and sdk/console origin split, and what Google bills that LocalCloud does not measure. `service` limits the answer to one service. |
| `localcloud_get_logs` | `service` | An emulator's runtime logs. Use `lc logs` for the container's stdout and stderr. |
| `localcloud_identity_check` | | Identity readiness, active keys and sessions, or verifies a `token` (`audience`, `expected_type`). |

Most tools also take `project`, which overrides the project the bridge selected.

`localcloud_query_data` takes `service`, one of `alloydb`, `bigquery`, `bigtable`, `cloudsql`, `gcs`,
`memorystore` and `spanner`, and `sql`, plus:

- `instance` and `database` for `cloudsql` (or `instance.database.table` names in the SQL) and
  `spanner` (a Spanner read without them uses the first instance and database; a change needs both);
- `cluster` (or `instance`) and `database` for `alloydb`, which is read-only;
- `table_definitions` for `gcs`: BigQuery external table definitions (`sourceUris`, `sourceFormat`,
  `autodetect`) keyed by the table name the SQL reads.

`bigquery` runs the text as one query job, `bigtable` runs Bigtable GoogleSQL, and `memorystore` runs
`SELECT * FROM dbN [WHERE key = | LIKE '...'] [LIMIT n]`. Read SQL always runs; write SQL needs
`mcp.write`, and destructive SQL also `mcp.destructive`. Results stop at `server.query_max_rows` and
`server.query_max_bytes` (BigQuery and Cloud Storage at their first 100-row page) and are then marked
truncated.

**Write tools** (`mcp.write`): `localcloud_create_project`, `localcloud_seed_project`,
`localcloud_apply_scenario`, `localcloud_checkpoint_project`, `localcloud_identity_setup`,
`localcloud_identity_mint`.

**Destructive tools** (`mcp.destructive`): `localcloud_delete_project`, `localcloud_reset_project`,
`localcloud_restore_project`, `localcloud_delete_checkpoint`, `localcloud_reset_service`.
`localcloud_import_state` needs both settings, and so do `localcloud_apply_scenario` with `replace`
and `localcloud_checkpoint_project` with `replace_existing`.

A scenario that runs Spark (`olap-application`) can take minutes to apply the first time, while the
Dataproc image is pulled. The bridge waits up to `LOCALCLOUD_SCENARIO_APPLY_TIMEOUT` seconds (default
900) for `localcloud_apply_scenario` instead of the usual 600, and a client that sends the call as an
MCP task gets the task back at once and polls it.

### Resources
- `localcloud://env/json`: Environment variables for every enabled service, as JSON.
- `localcloud://env/databases`: Database connection profiles.
- `localcloud://readiness`: Readiness across all services.
- `localcloud://diagnostics/latest`: Latest diagnostics.
- `localcloud://api/catalog`: Management API operations and their schemas.
- `localcloud://api/openapi`: The management API's OpenAPI contract.
- `localcloud://schema/seed`: Seed schema catalog.
- `localcloud://recipes/<id>`: One resource per recipe: `analytics-core`, `database-postgres`,
  `database-alloydb`, `database-spanner`, `streaming`, `batch-spark`, `cache` and `secrets`.

### Resource Templates
- `localcloud://recipes/{id}`

### Prompts
- `terraform-with-localcloud`: Terraform configuration instructions for LocalCloud.

---

## 6. Coding Agent Best Practices

1. **Point the agent at LocalCloud's endpoints**:
   > Use the `localcloud` MCP server. Always check local endpoints using `localcloud_get_env` or `localcloud://env/json` before making cloud calls, and never fall back to real Google Cloud.

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
| Status reports `container_start_failed` | Read `error.diagnostics`. Killed for memory: give Docker more memory. `start_refused: data_newer_than_build`, or a log line saying the image does not know the data, means a newer image wrote the data: run the step the next step names (`lc restart --image …` or `lc restart --pull`), which keeps the data |
| Status reports `mcp_connection_timeout` | Check `lc status` and `lc logs --tail 100`; retry with `localcloud mcp --connect-timeout 60` |
| Status reports `runtime_not_running` with `--no-start` | Start it explicitly with `lc start`, or remove `--no-start` to allow automatic startup |
| The agent uses a different project than `lc env` | Expected inside a git repository: agents use the repository's project, `lc env` the configured or default one. Run `lc env --project-id <project>` with the project `localcloud_runtime_status` reports, or set `context.project` in the repository's `localcloud.yaml` |
| The agent's project is not the repository's | The client started the bridge outside the repository and does not report workspace roots; install with `--project` in the repository, or pass `--project-id` |
| A write operation is rejected | Inspect the operation's safety and runtime permission settings; client installation does not enable write/destructive permissions |
| Tools are missing or a service is disabled | Inspect the connected runtime catalog, readiness and compatibility; CLI version alone does not determine runtime tools |
| Protocol parser reports invalid JSON | Ensure the client launches `localcloud mcp` directly; wrappers must keep diagnostics off stdout |

To disconnect, disable or remove only the `localcloud` server entry in the client's MCP settings. This does not delete the runtime's persistent volume. Report issues at [LocalCloud CLI issues](https://github.com/LocalGCloud/localcloud-cli/issues) with the CLI version, runtime image/version, client, and sanitized error output.
