# LocalCloud in Claude and Codex marketplaces

Give your coding agent a local cloud environment to build, test, inspect, and debug Google Cloud applications. Use standard SDKs against local services, verify the results, and iterate without provisioning a live Google Cloud project.

Local service workflows have zero Google Cloud service charges. Start the environment with one command and create local projects for your experiments and test suites, within your machine's capacity. The initial startup may download the image.

## Requirements

- A working Docker engine.
- The LocalCloud CLI, available natively on macOS and Linux.
- The LocalCloud Docker image; the CLI obtains it when needed.

Install and start:

```sh
brew install LocalGCloud/tap/localcloud
lc doctor
lc start --local-only
```

The [main MCP guide](mcp.md) covers the official installer, native archives, SDK configuration, and direct integrations. Use the current CLI and image together. Tested versions are recorded in release evidence.

## Claude Code

In Claude Code:

```text
/plugin marketplace add LocalGCloud/localcloud-cli
/plugin install localcloud@localcloud
```

Enable LocalCloud, reconnect MCP, and check that its tools are listed. A first connection can start or reuse the local runtime. The plugin's launcher needs the installed CLI; it does not install software silently.

For removal:

```text
/plugin uninstall localcloud@localcloud
```

Local MCP requires execution on the user's machine. Ordinary remote Claude chat cannot directly run the CLI or access the user's Docker engine. Cowork support must be qualified separately for its local execution environment; a directory listing alone does not establish it.

## Codex

Register and install the GitHub marketplace:

```sh
codex plugin marketplace add LocalGCloud/localcloud-cli --ref main
codex plugin add localcloud@localcloud
```

In Codex desktop, open the Plugins Directory, select the LocalCloud marketplace, and enable LocalCloud. Check tool discovery in a new conversation. If the client needs a refresh, follow its plugin refresh instructions.

For removal:

```sh
codex plugin remove localcloud@localcloud
```

The GitHub source makes the plugin installable in supported local clients. OpenAI's vendor-controlled public Directory has a separate review route for local MCP software. This guide does not imply that a pending directory submission is approved, or that remote Codex execution can reach a local Docker engine.

## First task

> Build and test a Python workflow that uploads a JSON object to Cloud Storage and reads it back using LocalCloud. Discover readiness, compatibility, and SDK settings first. Create uniquely named test resources, assert the returned JSON matches, and show the command and result. Use local endpoints and clean up only resources this test created.

Expected workflow: MCP discovers the environment; the coding client writes/runs an SDK test; the test verifies content; the agent reports the observed result. SDK writes are distinct from MCP management permissions.

Other starter tasks:

- Test a Pub/Sub publisher and consumer, verifying receipt and acknowledgement.
- Run a BigQuery query and assert the expected result.

## Project and environment selection

Projects are logical data contexts in the selected runtime. Create a local project deliberately, for example:

```sh
lc start --local-only --project-id agent-demo
```

Inspect the current context with `lc status` and obtain SDK settings with `lc env`. Do not assume project names isolate security permissions or resources outside the relevant service's documented behavior.

For a separate Docker environment, select a named data volume and use that same volume for all commands. The default marketplace entry follows the CLI's selected/default context. To pin a particular environment or project, use the CLI's direct MCP installer described in [mcp.md](mcp.md) rather than registering a second copy alongside the plugin.

## Updates and troubleshooting

| Symptom | Action |
| --- | --- |
| CLI not found | Install LocalCloud and restart the client. PATH and standard Homebrew locations are supported. |
| Docker unavailable | Start the Docker engine and run `lc doctor`. |
| First connection is slow | Allow the image download/startup to finish; review CLI diagnostics if it fails. |
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

## Data, permissions, and support

MCP management writes/destructive operations remain disabled by default. SDK tests can create/change application data at the user's request. Remove only test-owned resources; plugin uninstall does not remove Docker volumes.

Read the [CLI privacy reference](mcp-privacy.md), [runtime/website outbound-data reference](https://local.cloud/docs/privacy/), [compatibility guide](https://local.cloud/compatibility/), and [license](../LICENSE). Local operation does not imply zero outbound traffic. The agent client receives requested MCP results under its own policies.

Use [CLI issues](https://github.com/LocalGCloud/localcloud-cli/issues) for product questions and the [security contact](https://local.cloud/security/) for private security reports. Do not put credentials or customer data in public issues.
