# Set up LocalCloud MCP in Claude, Codex, and Cursor

Give your coding agent a local cloud environment to build, test, inspect, and debug Google Cloud applications. Use standard SDKs against local services, verify the results, and iterate without provisioning a live Google Cloud project.

Local service workflows have zero Google Cloud service charges. Start the environment with one command and create local projects for your experiments and test suites, within your machine's capacity. The initial startup may download the image.

This guide connects a local client and takes it through a first task. Direct MCP registration and the GitHub plugins below work independently of official directory publication. See the [distribution log](mcp-distribution-log.md) for submission progress.

## Requirements

- A working Docker engine.
- The LocalCloud CLI, available natively on macOS and Linux.
- The LocalCloud Docker image; the CLI obtains it when needed.

Install and start:

```sh
brew install LocalGCloud/tap/localcloud
localcloud --version
command -v localcloud
lc doctor
lc start --local-only
lc status
```

For an existing Homebrew installation, use `brew update` and `brew upgrade localcloud` before reconnecting clients. Without Homebrew, use the official installer or native archives linked in the main MCP guide. Wait for startup to complete before connecting your client.

If `command -v localcloud` prints nothing, finish CLI installation and reopen your terminal. Desktop clients should use an absolute executable path; user-scoped LocalCloud installations resolve one automatically. Project-scoped installations use a bare command unless you specify `--command-path`.

The [main MCP guide](mcp.md) covers the official installer, native archives, SDK configuration, and direct integrations. Use the current CLI and image together. Tested versions are recorded in release evidence.

## Direct MCP setup

LocalCloud runs the CLI over **stdio**. `https://local.cloud/docs/mcp/` is a documentation page, not an MCP endpoint. Do not put it in a client's server URL field.

Choose direct registration below or the [GitHub plugin setup](#optional-github-plugin-setup). Keep one LocalCloud MCP entry per client so tools are not duplicated.

### Claude Code

Run in your terminal:

```sh
lc mcp install --client claude-code
claude mcp get localcloud
claude mcp list
```

The installer configures user scope by default. Open a new Claude Code session, run `/mcp`, and check that `localcloud` connects. For project scope with an absolute executable path:

```sh
lc mcp install --client claude-code --project --command-path "$(command -v localcloud)"
```

Review the workspace/server approval in Claude Code before expecting a project-scoped connection.

Use the explicit name `claude-code`: the shorter `claude` installer alias selects **Claude Desktop**.

The equivalent manual registration is:

```sh
claude mcp add --scope user localcloud -- "$(command -v localcloud)" mcp
```

See [Claude Code's MCP documentation](https://code.claude.com/docs/en/mcp).

### Claude Desktop

```sh
lc mcp install --client claude-desktop
```

Fully quit and reopen Claude Desktop, then start a new conversation. Check its local MCP server settings for `localcloud` and try the connection-check prompt below. On macOS, the installer updates `~/Library/Application Support/Claude/claude_desktop_config.json`.

For manual configuration, merge this entry into `mcpServers` and replace the example command with the output of `command -v localcloud`:

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

The example path is for Apple Silicon Homebrew; other installations use different paths. Preserve existing server entries when editing the file. Ordinary Claude web/mobile chat cannot launch your local CLI or access your Docker engine.

### Codex CLI and desktop

Use Codex's own registration command:

```sh
codex mcp add localcloud -- "$(command -v localcloud)" mcp
codex mcp get localcloud --json
codex mcp list
```

These commands register and inspect configuration; listing a server alone does not prove a connection. Open a new **local** Codex chat or CLI session, use `/mcp` in the CLI to inspect active servers, and run the connection-check prompt below. Restart the desktop app if it has not picked up the configuration. A remote cloud session cannot automatically reach your machine's Docker runtime.

For manual setup or a longer startup allowance, merge this table into `~/.codex/config.toml` instead of registering a second server:

```toml
[mcp_servers.localcloud]
command = "/opt/homebrew/bin/localcloud"
args = ["mcp"]
startup_timeout_sec = 120
```

Replace `command` with your absolute CLI path. See [Codex MCP configuration](https://developers.openai.com/codex/mcp/) for client settings.

### Cursor

```sh
lc mcp install --client cursor
```

The default target is `~/.cursor/mcp.json`. Restart Cursor, open its MCP settings, enable `localcloud`, and check that tools appear. For `.cursor/mcp.json` in the current workspace with an absolute executable path, use `lc mcp install --client cursor --project --command-path "$(command -v localcloud)"`. Manual configuration uses the same `mcpServers` JSON shown for Claude Desktop.

### Other local clients

| Client | Setup |
| --- | --- |
| Windsurf | `lc mcp install --client windsurf`, then restart and check MCP settings. |
| Antigravity | `lc mcp install --client antigravity`, then restart and check MCP settings. |
| Gemini CLI | Use [Gemini CLI's own registration command](mcp.md#gemini-cli); LocalCloud's `gemini` alias selects Antigravity. |
| VS Code / GitHub Copilot | Follow the [VS Code example](mcp.md#vs-code--github-copilot), which uses a `servers` JSON wrapper. |
| Cline | Use [Cline's MCP configuration editor](mcp.md#cline) rather than assuming an extension settings path. |

`lc mcp install --client all` writes Cursor, Claude Code, Claude Desktop, Antigravity, and Windsurf configurations, even if their applications are absent. Select individual clients when setting up only the tools you use. Codex is configured separately with `codex mcp add`.

## Optional GitHub plugin setup

The plugin adds workflow guidance alongside MCP. Install it instead of a direct MCP registration, or disable/remove the direct entry first. A GitHub source and a vendor's official public directory are separate distribution paths.

### Claude Code plugin

In Claude Code:

```text
/plugin marketplace add LocalGCloud/localcloud-cli
/plugin install localcloud@localcloud
```

Enable LocalCloud, reconnect MCP, and check that its tools are listed. A first connection can start or reuse the local runtime. The plugin's launcher needs the installed CLI; it does not install software silently.

Claude uses a literal launcher for the standard `localcloud mcp` command and requires the installed CLI on the client's PATH. If Claude Code cannot find it, use `lc mcp install --client claude-code` instead of the plugin's MCP entry. The direct installer records the installed CLI's absolute path and supports explicit MCP options.

For removal:

```text
/plugin uninstall localcloud@localcloud
```

Local MCP requires execution on the user's machine. Ordinary remote Claude chat cannot directly run the CLI or access the user's Docker engine. Cowork support must be qualified separately for its local execution environment; a directory listing alone does not establish it.

### Codex plugin

Register and install the GitHub marketplace:

```sh
codex plugin marketplace add LocalGCloud/localcloud-cli --ref main
codex plugin add localcloud@localcloud
codex plugin list
```

In Codex desktop, open the Plugins Directory, select the LocalCloud marketplace, and enable LocalCloud. Check tool discovery in a new conversation. If the client needs a refresh, follow its plugin refresh instructions.

If your Codex version does not expose `codex plugin`, use the direct MCP setup above.

For removal:

```sh
codex plugin remove localcloud@localcloud
```

The GitHub source makes the plugin installable in supported local clients. OpenAI's vendor-controlled public Directory has a separate review route for local MCP software. This guide does not imply that a pending directory submission is approved, or that remote Codex execution can reach a local Docker engine.

## Verify the connection and run a first task

In a new conversation, first ask:

> Use the LocalCloud MCP tools to list services, check readiness, and obtain SDK settings. Show the selected project and the local endpoints returned by the tools. Report unavailable services. Do not use live Google Cloud or guess ports.

A working connection returns actual tool results for discovery/readiness and local SDK settings. Resolve missing tools or connection errors before trying an application workflow. Then ask:

> Build and test a Python workflow that uploads a JSON object to Cloud Storage and reads it back using LocalCloud. Discover readiness, compatibility, and SDK settings first. Create uniquely named test resources, assert the returned JSON matches, and show the command and result. Use local endpoints and clean up only resources this test created.

Expected workflow: MCP discovers the environment; the coding client writes/runs an SDK test; the test verifies content; the agent reports the observed result. SDK writes are distinct from MCP management permissions.

If the client cannot execute code, run its generated test yourself. The [reproducible workflow guide](mcp-workflows.md) includes complete prompts and runnable SDK examples.

Other starter tasks:

- Test a Pub/Sub publisher and consumer, verifying receipt and acknowledgement.
- Run a BigQuery query and assert the expected result.

## Project and environment selection

Projects are logical data contexts in the selected runtime. Create a local project deliberately, for example:

```sh
lc start --local-only --project-id agent-demo
lc mcp install --client claude-code --project-id agent-demo
```

For a direct Codex connection to that project, use `codex mcp add localcloud -- "$(command -v localcloud)" mcp --project-id agent-demo`. Update or replace the existing entry rather than enabling a second copy.

Inspect the current context with `lc status` and obtain SDK settings with `lc env`. Do not assume project names isolate security permissions or resources outside the relevant service's documented behavior.

For a separate Docker environment, select a named data volume and use that same volume for all commands. The default marketplace entry follows the CLI's selected/default context. To pin a particular environment or project, use the CLI's direct MCP installer described in [mcp.md](mcp.md) rather than registering a second copy alongside the plugin.

## Updates and troubleshooting

| Symptom | Action |
| --- | --- |
| CLI not found | Check `command -v localcloud` and restart the client. Direct registration can use an absolute path. Claude's plugin requires the CLI on the client's PATH; Codex's plugin also checks standard installation locations. |
| `lc mcp install --client codex` fails | Use Codex's `codex mcp add` command shown above. |
| Claude Code has no tools after `--client claude` | Run `lc mcp install --client claude-code`; the shorter alias configures Desktop. |
| Docker unavailable | Start the Docker engine and run `lc doctor`. |
| First connection is slow or times out | Start with `lc start --local-only` and wait for completion. Codex can also use the startup allowance shown above. |
| Tools are duplicated | Choose the plugin or a direct MCP configuration and remove the redundant entry. |
| Strict client rejects a tool schema | Update the CLI and deliberately update the selected runtime; reconnect MCP. |
| Operation unavailable | Inspect compatibility and use a supported operation; do not fall back to live Google Cloud silently. |
| Wrong project or environment | Inspect `lc status`; use an explicit project/data-volume setup for that task. |

Update the CLI through its installation channel. Updating it or installing the plugin does not replace an existing runtime. Inspect the selected volume first, then deliberately update that environment:

```sh
lc status
lc restart --image agentcloud/localcloud:latest --pull
```

Carry your normal `--data-volume` and configuration flags when selecting a custom environment. Restart interrupts connected clients briefly. Update only an environment you intend to change.

For direct registration, remove it with `claude mcp remove --scope user localcloud` or `codex mcp remove localcloud`. For Claude Desktop or Cursor, remove only the `localcloud` entry from the client's MCP settings and restart. Removing an integration does not delete Docker data.

## Data, permissions, and support

MCP management writes/destructive operations remain disabled by default. SDK tests can create/change application data at the user's request. Remove only test-owned resources; plugin uninstall does not remove Docker volumes.

Read the [CLI privacy reference](mcp-privacy.md), [runtime/website outbound-data reference](https://local.cloud/docs/privacy/), [compatibility guide](https://local.cloud/compatibility/), and [license](../LICENSE). Local operation does not imply zero outbound traffic. The agent client receives requested MCP results under its own policies.

Use [CLI issues](https://github.com/LocalGCloud/localcloud-cli/issues) for product questions and the [security contact](https://local.cloud/security/) for private security reports. Do not put credentials or customer data in public issues.

For setup help, include the client name, `localcloud --version`, and a redacted error message.
