# LocalCloud MCP submission kit

Use these descriptions for LocalCloud MCP listings. Installation requires LocalCloud CLI 0.1.9 or newer. The MCP server is included in the CLI/runtime; there is no separate server to purchase.

## Identity

- Name: LocalCloud MCP
- Tagline: A local cloud environment for AI coding agents.
- Website: https://local.cloud/
- MCP guide: https://local.cloud/docs/mcp/
- Repository: https://github.com/LocalGCloud/localcloud-cli
- Available repository guide: https://github.com/LocalGCloud/localcloud-cli/blob/main/docs/mcp.md
- Agent installation guide: https://github.com/LocalGCloud/localcloud-cli/blob/main/llms-install.md
- Categories: Cloud Infrastructure, Developer Tools, AI Agents, Testing
- Tags: mcp, localcloud, google-cloud, gcp, coding-agents, local-development, integration-testing, sdk, terraform
- Transport: local stdio through `localcloud mcp`; runtime HTTP on the developer's machine
- Cost: Free
- License: Use the license supplied with the installed CLI/runtime artifact. Do not label LocalCloud as open source or invent a license identifier.

## Registry description (under 100 characters)

A free local cloud environment for AI agents to build, test, and debug Google Cloud applications.

Use `websiteUrl` and publisher metadata to include the website and MCP guide when the registry's description limit cannot fit both links.

## Short directory description

LocalCloud MCP gives AI coding agents a free local cloud environment to build, test, and debug Google Cloud applications. Discover services, configure SDKs, inspect data, and diagnose failures through MCP. Requires CLI 0.1.9+. Website: https://local.cloud/ MCP guide: https://local.cloud/docs/mcp/

## Full listing description

LocalCloud gives AI coding agents a free local cloud environment for building, testing, and debugging Google Cloud applications. Its MCP server lets agents discover services and API schemas, obtain SDK and Terraform configuration, inspect resources, query supported databases, check readiness and compatibility, and investigate logs and recent requests.

The CLI starts or reuses a persistent local runtime when an MCP client connects. Agents can work against standard Google Cloud SDKs and local endpoints without provisioning a live Google Cloud project. Project context, caller attribution, and named data volumes support repeatable development workflows.

Install LocalCloud CLI 0.1.9 or newer:

```sh
brew install LocalGCloud/tap/localcloud
lc --version
lc doctor
lc mcp install --client cursor
```

Replace `cursor` with `claude-code` or `claude-desktop` for those clients. Codex, VS Code, Cline, Gemini CLI, Antigravity, and Windsurf setup is documented in the MCP guide. On macOS/Linux without Homebrew, use the website installer or signed release archives. Docker must be running when the server connects. The MCP bridge is included: `localcloud mcp` is the stdio command.

Example agent task: “Use LocalCloud MCP to discover local services, check readiness, get SDK endpoints, and write an integration test using only local resources. Stop if the required operation is unsupported; do not fall back to real Google Cloud.”

Management writes and destructive operations are controlled by runtime permission settings. Local compatibility is service- and operation-specific; validate application release behavior against Google Cloud separately.

Website: https://local.cloud/
MCP guide: https://local.cloud/docs/mcp/
Repository and immediately available setup guide: https://github.com/LocalGCloud/localcloud-cli/blob/main/docs/mcp.md

## Awesome-list entry

`[LocalGCloud/localcloud-cli](https://github.com/LocalGCloud/localcloud-cli) - Free local cloud environment for AI coding agents to build, test, and debug Google Cloud apps, with MCP service discovery, SDK configuration, data inspection, and diagnostics. [Website](https://local.cloud/) · [MCP guide](https://local.cloud/docs/mcp/) (CLI 0.1.9+).`

Adapt badges and formatting to each repository's contribution rules. Do not submit to remote-only directories or claim that a localhost endpoint is a public hosted server.
