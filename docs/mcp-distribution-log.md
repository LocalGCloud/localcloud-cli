# LocalCloud MCP distribution record

Started 2026-10-09. Status: execution in progress.

This record tracks the public rollout of LocalCloud MCP, a free local cloud environment for AI coding agents. The scope includes accurate documentation, CLI 0.1.9+ setup, registry packaging, directory submissions, awesome-list contributions, practical announcements, and verified follow-up links. A submission is not a published listing.

## Public identity and links

- Product: LocalCloud MCP
- Website: https://local.cloud/
- Intended website MCP guide: https://local.cloud/docs/mcp/ (publication pending; use the GitHub guide until verified)
- Source and currently available guide: https://github.com/LocalGCloud/localcloud-cli/blob/main/docs/mcp.md
- Repository: https://github.com/LocalGCloud/localcloud-cli
- Required CLI: 0.1.9 or newer for `lc mcp install` and automatic runtime startup
- Contact: agent@local.cloud; general support: info@local.cloud
- Positioning: free local cloud development for agents; no Pro or paid-version claims; do not describe the proprietary runtime as open source.

## Execution rules

Use existing authenticated brand accounts where available. The user authorized Google sign-in with their supplied account, account setup, public submissions, and posting. Skip optional destinations that cannot be completed without human verification, required legal acceptance, unavailable permissions, or payment. Do not silently accept new binding terms, solve CAPTCHAs, purchase placement, or expose the local runtime publicly. Record the reason and next action. Preserve other work in the repositories.

## Release and documentation evidence

| Item | Observed state | Next action |
| --- | --- | --- |
| CLI checkout | Clean; HEAD `60bd923`, version bump to 0.1.9; MCP changes committed in `a66b277` | Check remote tag, release job, and downloaded artifact before release claims |
| GitHub latest release | v0.1.8 at start of execution | Qualify 0.1.9 availability; give explicit minimum-version instructions |
| MCP guide | Committed, but lists 27 tools | Reconcile with generated runtime catalog and released tools/list |
| Runtime source catalog | 41 tool definitions found during planning | Verify exact runtime version; omit unstable counts from short descriptions |
| Client installer | `all` currently omits Cline; client paths require qualification | Fix demonstrated gaps and test without overwriting real user configurations |
| PyPI | `localcloud-cli` returned 404 during planning | Prepare existing CLI package and verify package ownership/publishing access |
| License | CLI checkout and live runtime licensing page differ | Verify artifact terms and align copy; do not invent license grants |

## Deliverables

- [ ] Agent-focused MCP guide and README quickstart
- [ ] Verified client setup matrix, including Codex and VS Code
- [ ] Three reproducible example workflows and troubleshooting
- [ ] Short, medium, and long submission descriptions with website and guide links
- [ ] Existing-brand logo exports, screenshots, architecture visual, short demo
- [ ] Official registry metadata and qualified installable package
- [ ] MCPB desktop/Smithery package
- [ ] Docker Catalog feasibility and qualified submission or concrete blocker
- [ ] Public listings and awesome-list submissions with evidence
- [ ] Practical announcements through available accounts
- [ ] Private reference Page and final status reconciliation

## Submission ledger

| Destination | State | Submission or live URL | Follow-up |
| --- | --- | --- | --- |
| Official MCP Registry | Planned | https://registry.modelcontextprotocol.io/ | Check existing entry, package publication, namespace ownership, publish and read back |
| GitHub MCP Registry | Planned | https://github.com/mcp | Verify current inclusion route and actual discoverability |
| Smithery | Planned | https://smithery.ai/ | Package local MCPB; authenticate; publish and verify |
| Glama | Planned | https://glama.ai/mcp/servers | Check eligibility and existing entry; submit/claim; verify maintainer metadata |
| PulseMCP | Planned | https://www.pulsemcp.com/submit | Submit server and demonstrated use case if supported |
| MCPServers.org / wong2 | Planned | https://mcpservers.org/submit | Website submission; repository no longer accepts listing PRs |
| punkpeye awesome-mcp-servers | Planned | https://github.com/punkpeye/awesome-mcp-servers | Check duplicates and contribution rules; submit PR |
| appcypher awesome-mcp-servers | Planned | https://github.com/appcypher/awesome-mcp-servers | Check current category and contribution rules; submit PR |
| Cline Marketplace | Planned | https://github.com/cline/mcp-marketplace | Verify real Cline setup, 400x400 logo, then submit issue |
| Docker MCP Catalog | Planned | https://github.com/docker/mcp-registry | Check license and container requirements before submission |
| MCP Market | Planned | https://mcpmarket.com/submit | Use free queue; record confirmation and listing state |
| MCP.so | Planned | https://mcp.so/submit | Current main form is paid; check existing/free path, otherwise skip |
| Additional MCP directories | Discovery pending | — | Add verified active destinations individually; avoid duplicate submissions |
| Cloud/agent/testing awesome lists | Discovery pending | — | Add relevant repositories and follow their rules |
| LocalCloud website/blog | Planned | https://local.cloud/ | Publish MCP page and practical walkthrough; verify deployment |
| DEV | Planned | https://dev.to/ | Use existing account and workflow tutorial |
| Hacker News | Planned | https://news.ycombinator.com/ | Check existing posts and suitable release-ready Show HN |
| Reddit | Planned | https://www.reddit.com/ | Check promotion rules and existing posts; use permitted showcase channels |
| LinkedIn | Planned | https://www.linkedin.com/ | Use relevant brand/account and release-ready announcement |
| X | Planned | https://x.com/LocalCloud_AI | Check account and post with usable installation/guide links |

## Action history

### 2026-10-09

1. Accepted full rollout scope and requirement to keep a follow-up record.
2. Inspected current Git state: earlier local MCP files are now committed; clean checkout at version 0.1.9.
3. Checked GitHub latest release: still v0.1.8 at this observation.
4. Initialized computer control and located Chrome with the authorized Google account already signed in. No account, permission, or external posting changes made by this observation.
5. Read current MCP guide and created this ledger. Public submissions have not yet been sent.

## Resume and completion audit

For each destination, preserve confirmation text, PR/issue IDs, live URLs, and the last verified date. Keep published, submitted/pending, skipped, and blocked distinct. Before completion, verify every advertised command against published artifacts, check website deployment and links, inspect all created PRs/issues/listings, and reconcile every deliverable above. Optional destinations may be skipped with a specific evidenced reason; essential installation and publication claims must be proved.
