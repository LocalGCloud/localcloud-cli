# Install LocalCloud MCP for a coding agent

LocalCloud is a free local cloud development environment for AI coding agents. Use its existing MCP bridge to discover local Google Cloud-compatible services, configure SDK endpoints, inspect resources, and diagnose integration tests.

Website: https://local.cloud/
MCP guide: https://local.cloud/docs/mcp/
Repository guide: https://github.com/LocalGCloud/localcloud-cli/blob/main/docs/mcp.md

1. Install LocalCloud CLI 0.1.9 or newer. On macOS/Linux with Homebrew run `brew install LocalGCloud/tap/localcloud`; otherwise follow https://local.cloud/install.sh and the repository README. For an existing Homebrew installation run `brew update` and `brew upgrade localcloud`.
2. Run `localcloud --version` and `localcloud doctor`. Docker must be running to connect to the runtime. Stop and report a setup problem rather than requesting real Google Cloud credentials.
3. Use `command -v localcloud` to resolve the executable. For Cursor run `lc mcp install --client cursor`; for Claude Code/Desktop use `claude-code`/`claude-desktop`. Follow the MCP guide for other clients. In Cline, merge an entry into its own MCP settings editor rather than assuming a settings-file path:

```json
{
  "mcpServers": {
    "localcloud": {
      "command": "/absolute/path/to/localcloud",
      "args": ["mcp"]
    }
  }
}
```

4. Preserve other MCP entries. Start or enable `localcloud` in the client. The bridge starts/reuses the runtime automatically; first image download can take longer. You can run `localcloud start --local-only` first to explicitly bind newly started runtime ports to localhost.
5. Use runtime 0.1.5 or newer for validated strict-client input/output schemas. Inspect `localcloud status`; if an existing runtime is older, report that an explicit runtime upgrade is needed. Do not replace a running shared container implicitly. Follow the MCP guide's upgrade instructions using the user's same data volume/configuration.
6. Discover tools, call `localcloud_list_services` with `{}`, check readiness, and obtain `localcloud_get_env` with `{"format":"json"}`. Use the returned local endpoints, not guessed default ports.
7. Read compatibility before running one narrow SDK integration check using only test-owned resources. Do not use live GCP credentials, fall back to real Google Cloud, reset a shared project, or enable destructive MCP permissions implicitly.

The bridge requires no separate MCP package. CLI versions and runtime catalogs are independent. Native Windows binaries are not provided. License, privacy, and outbound behavior are described on the website; this guide does not claim open-source licensing, complete Google Cloud parity, or security isolation between logical projects.
