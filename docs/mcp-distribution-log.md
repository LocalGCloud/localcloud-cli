# LocalCloud MCP distribution record

Maintainer reference for every MCP integration, listing, submission, and launch announcement made during this rollout. Started and reconciled **2026-10-09 (Pacific time)**. GitHub status checks below were made on 2026-10-10 at 01:45 UTC.

**GitHub plugins and direct local MCP installation are available. Official Claude review is pending; an OpenAI public-directory submission has not been made.** Third-party placements have their own statuses below. A submitted form, open PR, or private management page is not an approved public listing.

## Canonical identity and integration

- Product: **LocalCloud**; public source: [LocalGCloud/localcloud-cli](https://github.com/LocalGCloud/localcloud-cli).
- Publisher actions: **cloud-orbitor**; publisher contact: **orbitor.cloud@gmail.com**. Keep private login, billing, and identity-verification details out of this record.
- Transport: **local stdio**, launched by the installed CLI as `localcloud mcp`. The [website MCP guide](https://local.cloud/docs/mcp/) is an HTML documentation page, not a remote MCP endpoint.
- Requirements: Docker engine, LocalCloud CLI, and the LocalCloud Docker image. Native CLI platforms: macOS and Linux.
- [Local setup and connection checks](mcp-marketplace-guide.md) · [MCP reference](mcp.md) · [Approved listing copy](mcp-marketplace-copy.md) · [Privacy](mcp-privacy.md) · [License](https://local.cloud/license/) · [Support](https://github.com/LocalGCloud/localcloud-cli/issues).
- Scope: the public CLI/plugin only. Do not supply private runtime source, its build Dockerfile, or image exports to a directory or validator.

## How to read the status

**Available / published** means the recorded catalog or public placement was verified. **Submitted / in review** means receipt or review is confirmed, but publication is pending. **Deferred** means no completed submission. **Withdrawn / superseded** entries must not be reused.

Dates are Pacific time. **Checked** identifies a fresh API/page check; **recorded** identifies saved submission or installation evidence. Rows based on saved evidence were not all revisited during this reconciliation. Review estimates are the providers' original estimates, not promised publication dates.

## Official marketplaces and registries

| Destination | Status | Direct reference | Evidence and next action |
| --- | --- | --- | --- |
| Claude GitHub marketplace | Available; remote installation qualified | [Catalog](https://github.com/LocalGCloud/localcloud-cli/blob/main/.claude-plugin/marketplace.json) · [Local setup](mcp-marketplace-guide.md#claude-code-plugin) | Recorded 2026-10-09. Install `localcloud@localcloud` from `LocalGCloud/localcloud-cli`; source plugin is 0.1.5. Independent of Claude Directory approval. |
| Codex GitHub marketplace | Available; remote installation and tool use qualified | [Catalog](https://github.com/LocalGCloud/localcloud-cli/blob/main/.agents/plugins/marketplace.json) · [Local setup](mcp-marketplace-guide.md#codex-plugin) | Recorded 2026-10-09. Same public CLI repository and plugin; independent of OpenAI Directory approval. |
| Claude official Directory | Submitted; In review; not live at last observation | [Owner management page](https://claude.ai/directory/manage/plugins/e917c263-e80b-4fa1-9070-45bde6b2f8a7) · [Edit](https://claude.ai/directory/manage/plugins/e917c263-e80b-4fa1-9070-45bde6b2f8a7/edit) | Portal recorded 2026-10-09: plugin 0.1.5, zero blockers/holds, security scan passed for `e9e5f34`; later documentation commit `af839dd` detected and In review. Push webhook connected; a real main push received HTTP 200. Await reviewer decision. These are private management links, not public listing URLs. |
| OpenAI official Directory | Support request escalated; no draft/upload/submission recorded | [Publisher portal](https://platform.openai.com/plugins) · [Support](https://help.openai.com/) | Recorded 2026-10-09. Await the supported local-stdio submission and brand-publisher verification route. No case ID or public LocalCloud listing URL was supplied. Keep the public publisher under the brand identity. |
| Official MCP Registry | Earlier entry withdrawn; brand replacement publication unconfirmed | [Registry](https://registry.modelcontextprotocol.io/) · [Approved name lookup](https://registry.modelcontextprotocol.io/v0.1/servers/cloud.local%2Flocalcloud/versions/latest) | Intended name: `cloud.local/localcloud`. Withdrawal receipt recorded 2026-10-09. The intended-name lookup returned 404 during this reconciliation; search requests timed out. Verify brand publication before claiming it is active or relying on automatic ingestion. |
| GitHub MCP Registry | Nomination emailed; inclusion unconfirmed | [GitHub MCP directory](https://github.com/mcp) | Recorded 2026-10-09: nomination sent to the published `partnerships@github.com` route. No LocalCloud directory URL or case ID confirmed. Reconcile any further correspondence under the brand account. |

## Third-party directories

| Destination | Status | Direct reference | Evidence and next action |
| --- | --- | --- | --- |
| TensorBlock MCP Index | Listing PR merged; installation correction pending | [Public profile](https://www.tensorblock.co/mcp/servers/github-localgcloud-localcloud-cli-46793d77?client=cursor) · [Merged PR #3405](https://github.com/TensorBlock/awesome-mcp-servers/pull/3405) · [Correction PR #3416](https://github.com/TensorBlock/awesome-mcp-servers/pull/3416) | GitHub checked 2026-10-09: #3405 merged; #3416 open. The profile previously used the HTML guide as an MCP URL; correction supplies stdio command/args. The profile returned HTTP 200 during this check, but its client-rendered install panel was not revalidated. Verify the panel after merge and deployment. |
| Cursor Directory | Submitted; security scan pending at last observation | [Submission/profile path](https://cursor.directory/plugins/localcloud-mcp) | Confirmation recorded 2026-10-09; unpublished/hidden during scanning. Fresh request returned HTTP 429, so publication remains unconfirmed. |
| MCPServers.org / wong2 | Submitted; review pending at last observation | [Submission form](https://mcpservers.org/submit) | Recorded 2026-10-09: “Submission Successful!”; original review estimate up to two weeks, around October 23. No approved LocalCloud permalink received. |
| MCP Market | Submitted to free queue; publication unconfirmed | [Submission form](https://mcpmarket.com/submit) | Recorded 2026-10-09: submission confirmed; original estimate 4–6 weeks, around November 6–20. No listing permalink received. |
| MCPServers.com | Submitted; publication unconfirmed | [Submission form](https://mcpservers.com/submit) | Recorded 2026-10-09: implementation submitted for review. No listing permalink or approval date received. |
| MCP.so | Brand submission issue open; directory placement unconfirmed | [Issue #5047](https://github.com/chatmcp/mcpso/issues/5047) | GitHub checked 2026-10-09: open, authored by cloud-orbitor. Replaces closed issue #5034. Free community route; no placement purchased. |

## GitHub awesome lists

| Destination | Status | Direct reference | Evidence and next action |
| --- | --- | --- | --- |
| punkpeye/awesome-mcp-servers | Brand PR open; Glama requirement unresolved | [PR #16056](https://github.com/punkpeye/awesome-mcp-servers/pull/16056) · [Validation clarification](https://github.com/punkpeye/awesome-mcp-servers/pull/16056#issuecomment-6086208723) | GitHub checked 2026-10-09. Maintainer requests Glama validation and a score badge. Brand reply asks how its sandbox supports the host CLI plus published Docker runtime. No Glama badge, validator package, or runtime source/Dockerfile supplied. Await a supported CLI-only route. |
| TensorBlock/awesome-mcp-servers | Brand listing merged; correction open | [PR #3405](https://github.com/TensorBlock/awesome-mcp-servers/pull/3405) · [PR #3416](https://github.com/TensorBlock/awesome-mcp-servers/pull/3416) | GitHub checked 2026-10-09. Same placement as TensorBlock above; count it once. Merge/redeploy the correction before treating its generated setup as qualified. |
| rohitg00/awesome-devops-mcp-servers | Brand PR open | [PR #352](https://github.com/rohitg00/awesome-devops-mcp-servers/pull/352) | GitHub checked 2026-10-09. Required minimum runtime was added in response to review; no new actionable bot findings recorded. Await maintainer merge. |
| appcypher/awesome-mcp-servers | Skipped; upstream archived | [Upstream](https://github.com/appcypher/awesome-mcp-servers) | Recorded 2026-10-09: archived upstream rejected PR creation. Prepared historical fork is not an upstream listing; do not create duplicates. |

## Other destinations reviewed

These are candidates or deferred routes, not created listings.

| Destination | Status / prerequisite | Reference | Last evidence |
| --- | --- | --- | --- |
| Glama | No submission completed; CLI/Docker validation route unresolved | [Servers](https://glama.ai/mcp/servers) · [Maintainer question](https://github.com/punkpeye/awesome-mcp-servers/pull/16056#issuecomment-6086208723) | Recorded 2026-10-09. Initial profile completion also required legal acceptance. Do not publish private runtime packaging to clear the requirement. |
| Smithery | Deferred; API-key authorization not completed | [Publication guide](https://smithery.ai/docs/build/publish) | Recorded 2026-10-09. Browser login alone did not publish a listing; CLI authorization was canceled at its credential-access prompt. |
| PulseMCP | Intake was paused; no manual submission | [Submission page](https://www.pulsemcp.com/submit) | Pause recorded 2026-10-09. Fresh request returned HTTP 403; current intake not verified. Registry ingestion also depends on a confirmed active brand entry. |
| Cline Marketplace | Deferred; required native Cline installation test incomplete | [Marketplace repository](https://github.com/cline/mcp-marketplace) | Recorded 2026-10-09. Setup documentation is available; no submission issue or qualifying Cline install test claimed. |
| Docker MCP Catalog | Deferred; no qualified containerized stdio bridge | [Contribution requirements](https://github.com/docker/mcp-registry/blob/main/CONTRIBUTING.md) | Recorded 2026-10-09. Host-native CLI uses Docker discovery; no supported public container profile qualified. Do not substitute a documentation URL or untested socket-mount configuration. |
| MCPHub | Deferred; login requires legal acceptance | [Registry](https://www.mcphub.app/registry) | Recorded 2026-10-09. No completed account or submission. |
| mcpdirectory.dev | Automatic ingestion candidate; listing unverified | [Directory](https://mcpdirectory.dev/) | Recorded 2026-10-09. No manual route or LocalCloud result found; do not assume ingestion from a withdrawn registry entry. |
| mcpdirectory.app | Deferred; service unavailable at last check | [Directory](https://mcpdirectory.app/) | Recorded 2026-10-09: HTTP 502 / DNS failure. No submission. |

## Product pages and announcements

| Destination | Recorded placement | Direct reference | Evidence / follow-up |
| --- | --- | --- | --- |
| LocalCloud website | MCP guide and launch walkthrough published | [MCP page](https://local.cloud/docs/mcp/) · [Walkthrough](https://local.cloud/blog/localcloud-mcp-agent-cloud/) | Publication recorded 2026-10-09. The owner is handling website alignment with the [CLI setup guide](mcp-marketplace-guide.md); this ledger update does not deploy the site. |
| X, LocalCloud_AI | Updated four-post brand thread with demo and hashtags | [Hook + video](https://x.com/LocalCloud_AI/status/2108599008977703159) · [Reply 2](https://x.com/LocalCloud_AI/status/2108599010445705329) · [Reply 3](https://x.com/LocalCloud_AI/status/2108599011834024234) · [Reply 4](https://x.com/LocalCloud_AI/status/2108599013155197153) | Publication and permalink readback recorded 2026-10-09. This is the revised announcement, superseding the earlier post below. |
| DEV | Article published during the initial rollout | [Article](https://dev.to/jaysen99/give-your-coding-agent-a-local-cloud-environment-with-mcp-2b2l) | Anonymous view recorded 2026-10-09; current visibility not rechecked. Historical account placement; no new personal-account publication in this reconciliation. |
| Hacker News | Regular link submission published | [Item #50021011](https://news.ycombinator.com/item?id=50021011) | Exact submission recorded 2026-10-09; current visibility not rechecked. |
| Reddit r/mcp | Showcase post published during the initial rollout | [Post](https://www.reddit.com/r/mcp/comments/1x1mucq/) | Anonymous view recorded at publication on 2026-10-09; current visibility/moderation state not rechecked. |
| LinkedIn | Historical initial-rollout post only | [Recorded permalink](https://www.linkedin.com/feed/update/urn:li:activity:7514331414318759936/) | Publication recorded 2026-10-09; current visibility not rechecked. Owner subsequently instructed us not to use personal LinkedIn. No further personal LinkedIn posting. |
| GitHub organization | LocalCloud icon set | [LocalGCloud organization](https://github.com/LocalGCloud) | Brand-avatar receipt recorded 2026-10-09. Organization branding, not a separate MCP directory listing. |

## Withdrawn and superseded references

Retain these links to explain old search results and avoid submitting duplicates. They are not current active submissions.

| Previous reference | Disposition | Replacement |
| --- | --- | --- |
| Official Registry `io.github.jhsenjaliya/localcloud`, version 0.1.9 | Withdrawn; saved registry receipt says `deleted` on 2026-10-09. Legacy API lookup now returns 404. | Intended `cloud.local/localcloud`; publication still needs confirmation. |
| [punkpeye PR #16030](https://github.com/punkpeye/awesome-mcp-servers/pull/16030) | Closed without merge; checked 2026-10-09. | [Brand PR #16056](https://github.com/punkpeye/awesome-mcp-servers/pull/16056) |
| [TensorBlock PR #3385](https://github.com/TensorBlock/awesome-mcp-servers/pull/3385) | Closed without merge; checked 2026-10-09. | [Merged brand PR #3405](https://github.com/TensorBlock/awesome-mcp-servers/pull/3405) |
| [DevOps PR #351](https://github.com/rohitg00/awesome-devops-mcp-servers/pull/351) | Closed without merge; checked 2026-10-09. | [Brand PR #352](https://github.com/rohitg00/awesome-devops-mcp-servers/pull/352) |
| [MCP.so issue #5034](https://github.com/chatmcp/mcpso/issues/5034) | Closed; checked 2026-10-09. | [Brand issue #5047](https://github.com/chatmcp/mcpso/issues/5047) |
| [Earlier X post](https://x.com/LocalCloud_AI/status/2108549606422536549) | Superseded by the revised brand thread; old-post visibility not rechecked. | [Current recorded thread](https://x.com/LocalCloud_AI/status/2108599008977703159) |

## Follow-up and maintenance

1. Check Claude's existing review and OpenAI's existing support conversation before taking another submission action. Add public listing URLs only after publication is verified.
2. Verify brand MCP Registry publication. Keep the withdrawn name in the historical record, never as the active feed identifier.
3. Follow TensorBlock correction #3416 through merge, deployment, and an actual client install-panel check; confirm stdio `command` and `args`, with no documentation URL used as an endpoint.
4. Follow the Glama clarification on punkpeye #16056 and the DevOps #352 merge. Preserve the CLI-only privacy boundary.
5. Recheck the submitted third-party queues and record the eventual LocalCloud permalinks, approval/rejection dates, and any requested changes. Retain existing IDs; do not send duplicates because a queue is slow.

For every future change, update the existing row's status, direct submission and live links, evidence date/type, and next action. Add a new row for a new destination and retain a retired-reference row for a replacement. Keep provider messages, IDs, and status distinctions exact; never infer approval from a successful HTTP response. Record a blocked check as blocked rather than refreshing its verification date silently.

Plugin, CLI, and runtime versions are independent. The last qualified native CLI release is [0.1.11](https://github.com/LocalGCloud/localcloud-cli/releases/tag/v0.1.11); its downloadable plugin ZIP is historical plugin 0.1.3, while the current GitHub catalogs and Claude submission use plugin 0.1.5. [Homebrew publication evidence](https://github.com/LocalGCloud/homebrew-tap/actions/runs/38004527000) and [launcher correction PR #14](https://github.com/LocalGCloud/localcloud-cli/pull/14) document that distinction. Link future qualification evidence here when artifacts change.

No automated monitoring or paid placement is configured. This document is a maintained reference, not a live status feed. Private screenshots and receipts stay outside the public repository; credentials, webhook secrets, verification documents, and private runtime implementation must never be added here.

## Historical execution notes

<details>
<summary>Original rollout log and release observations — superseded by the reference tables above</summary>

The following notes preserve the sequence and evidence recorded during execution. Their original status tables, identities, requirements, and pending actions are historical snapshots; use the reconciled tables above for the latest known state.

## Public identity and links

- Product: LocalCloud MCP
- Website: https://local.cloud/
- Website MCP guide: https://local.cloud/docs/mcp/ (live, HTTP 200 verified)
- Source and currently available guide: https://github.com/LocalGCloud/localcloud-cli/blob/main/docs/mcp.md
- Repository: https://github.com/LocalGCloud/localcloud-cli
- Required CLI: 0.1.9 or newer for `lc mcp install` and automatic runtime startup
- Required runtime: 0.1.5 or newer for qualified strict-client schemas; existing older environments need the explicit upgrade in the guide
- Contact: agent@local.cloud; general support: info@local.cloud
- Positioning: free local cloud development for agents; no Pro or paid-version claims; do not describe the proprietary runtime as open source.

## Execution rules

Use existing authenticated brand accounts where available. The user authorized Google sign-in with their supplied account, account setup, public submissions, and posting. Skip optional destinations that cannot be completed without human verification, required legal acceptance, unavailable permissions, or payment. Do not silently accept new binding terms, solve CAPTCHAs, purchase placement, or expose the local runtime publicly. Record the reason and next action. Preserve other work in the repositories.

## Initial release observations

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

- [x] Agent-focused MCP guide and README quickstart
- [x] Client setup matrix, including Codex and VS Code; configuration/SDK checks and native-UI limits distinguished
- [x] Three reproducible example workflows and troubleshooting
- [x] Short, medium, and long submission descriptions with website and guide links
- [x] Existing-brand logo exports, screenshot, architecture visual, and recorded short demo
- [x] Official registry metadata and qualified installable package
- [x] Qualified desktop package; optional Smithery publication deferred with a concrete authorization blocker
- [x] Docker Catalog feasibility and concrete current integration blocker
- [x] Public registry entry and directory/awesome-list submissions with evidence; external approvals distinguished
- [x] Practical announcements through available accounts
- [x] Private reference record saved separately for the maintainer
- [x] Final status reconciliation and completion audit; pending reviews and optional prerequisites recorded

## Submission ledger

| Destination | State | Submission or live URL | Follow-up |
| --- | --- | --- | --- |
| Official MCP Registry | Published; active/latest 0.1.9 | https://registry.modelcontextprotocol.io/v0.1/servers/io.github.jhsenjaliya%2Flocalcloud/versions/0.1.9 | Namespace `io.github.jhsenjaliya/localcloud`; exact API readback verifies metadata, guide and qualified MCPB hash |
| GitHub MCP Registry | Nomination emailed; inclusion pending | https://github.com/mcp | Sent to the official `partnerships@github.com` inclusion route; exact Gmail Sent search verified. Curated GitHub listing is not yet confirmed |
| Smithery | Deferred: CLI authorization exposes the account API key | https://smithery.ai/docs/build/publish | Browser sign-in completed; local MCPB requires CLI/API publication. The CLI authorization explicitly asks for API-key access, so it was canceled under computer-control confirmation policy. No listing published |
| Cursor Directory | Submitted; security scan pending | https://cursor.directory/plugins/localcloud-mcp | Page confirms scanning; plugin remains unpublished and hidden until the security agent finishes. Do not count it as live |
| Glama | Deferred: new account requires legal acceptance | https://glama.ai/complete-profile | Google sign-in completed; profile completion requires Terms of Service acceptance, which was not performed |
| PulseMCP | Skipped for now: submissions paused | https://www.pulsemcp.com/submit | Browser page says new submissions and listing changes are temporarily paused (notice updated September 3, 2026); publish to Official Registry for automatic pickup when service resumes |
| MCPServers.org / wong2 | Submitted; review pending | https://mcpservers.org/submit | Browser confirmed “Submission Successful!” for LocalCloud MCP; free queue, review within 2 weeks, email on approval |
| punkpeye awesome-mcp-servers | Ready PR; maintainer review pending | https://github.com/punkpeye/awesome-mcp-servers/pull/16030 | Open, not draft; submission check passed. Final body links qualified releases, website and guide |
| appcypher awesome-mcp-servers | Skipped: upstream archived | https://github.com/jhsenjaliya/localcloud-awesome-mcp-servers-appcypher/commit/8ff392246f0d42b34c1c75912bc96597341e5285 | API confirmed archived=true after rejecting PR creation with 404; prepared fork branch is retained, not an upstream listing |
| TensorBlock MCP Index / awesome list | Ready PR; maintainer review pending | https://github.com/TensorBlock/awesome-mcp-servers/pull/3385 | Open, not draft; cloud category entry includes install, transport, prerequisites, website and guide |
| Awesome DevOps MCP Servers | Ready PR; maintainer review pending | https://github.com/rohitg00/awesome-devops-mcp-servers/pull/351 | Open, not draft; GCP/local platform entry |
| Cline Marketplace | Deferred: required native Cline install test not completed | https://github.com/cline/mcp-marketplace | Submission rules require watching Cline set up from README/llms-install. Cline is not installed/configured here; strict SDK proof does not establish that prerequisite. Logo/installation docs are prepared; no issue claiming a Cline test was submitted |
| Docker MCP Catalog | Deferred: no qualified containerized stdio bridge | https://github.com/docker/mcp-registry/blob/main/CONTRIBUTING.md | Local entries require a source Dockerfile and Toolkit verification. The published MCP bridge is a host-native CLI using host Docker discovery; its public repository has no such container profile. The loopback runtime endpoint is not an eligible public hosted server. No untested socket-mount profile or fake public endpoint submitted |
| MCP Market | Submitted; review pending | https://mcpmarket.com/submit | Browser confirmed “Submitted! You're in the free queue”; current stated wait 4–6 weeks; email on publication |
| MCPServers.com | Submitted; review pending | https://mcpservers.com/submit | Browser confirms implementation submitted for review; website/guide, logo, architecture visual, versions and prerequisites supplied |
| MCP.so | Free community issue submitted; review pending | https://github.com/chatmcp/mcpso/issues/5034 | Open submission issue; current paid main form was not purchased. No approved directory placement claimed |
| MCPHub | Deferred: login requires legal acceptance | https://www.mcphub.app/registry | Login page explicitly makes signing in agreement to new terms; no account completion or submission |
| mcpdirectory.dev | Automatic ingestion path; listing unverified | https://mcpdirectory.dev/ | Site says data comes from MCP Registry, GitHub and npm, refreshed daily. Official entry is published; no manual submission route observed and current LocalCloud search did not find a listing |
| mcpdirectory.app | Deferred: unavailable | https://mcpdirectory.app/ | Fetch returned 502; browser navigation returned DNS resolution failure. Revisit if service becomes available |
| Other awesome-list categories | Scope screened | — | Remote-only lists and unrelated Web3/OSINT/research lists are not suitable for this local stdio server. Three relevant cloud/DevOps submissions above cover the reviewed scope |
| LocalCloud website/blog | Guide, walkthrough and demo live | https://local.cloud/blog/localcloud-mcp-agent-cloud/ | Website commit `9d7a8f6`; deployment run `37933108475` succeeded. Guide/article/video HTTP 200 verified; full build, installer and 159 performance checks passed |
| DEV | Published; anonymous view verified | https://dev.to/jaysen99/give-your-coding-agent-a-local-cloud-environment-with-mcp-2b2l | Canonical link to original walkthrough; `mcp`/`ai` tags and truthful Fully Autonomous disclosure |
| Hacker News | Published regular link submission | https://news.ycombinator.com/item?id=50021011 | Existing `jaysen_apache` account; practical walkthrough submitted as a normal link, without duplicate Show HN or generated comments |
| Reddit r/mcp | Published; anonymous view verified | https://www.reddit.com/r/mcp/comments/1x1mucq/ | Existing `jhsonline` account; showcase flair, Brand Affiliate label and maintainer/AI-assistance disclosure. No duplicate crossposts |
| LinkedIn | Published; permalink verified | https://www.linkedin.com/feed/update/urn:li:activity:7514331414318759936/ | Existing Jay Sen account; website, guide, CLI/runtime prerequisites and runnable workflows. UI confirmed Post successful |
| X | Published with recorded demo | https://x.com/LocalCloud_AI/status/2108549606422536549 | Existing brand account; actual MP4 attached, website/guide and CLI/runtime requirements included |

## Action history

### 2026-10-09

1. Accepted full rollout scope and requirement to keep a follow-up record.
2. Inspected current Git state: earlier local MCP files are now committed; clean checkout at version 0.1.9.
3. Checked GitHub latest release: still v0.1.8 at this observation.
4. Initialized computer control and located Chrome with the authorized Google account already signed in. No account, permission, or external posting changes made by this observation.
5. Read current MCP guide and created this ledger. Public submissions have not yet been sent.
6. Verified v0.1.9 release publication at https://github.com/LocalGCloud/localcloud-cli/releases/tag/v0.1.9 and successful release job https://github.com/LocalGCloud/localcloud-cli/actions/runs/37905521567. The Homebrew formula now selects 0.1.9 on all four supported platform/architecture combinations.
7. Queried the running runtime without changing application data. MCP initialize reported runtime version 0.1.3; discovery returned 27 tools, 14 resources, 7 templates, and 6 prompts. The newer source catalog is not evidence of the installed runtime's exposed catalog.
8. Submitted LocalCloud MCP to MCPServers.org using the free plan and Cloud Service category. Included CLI 0.1.9+, website and intended website guide links, plus the available GitHub guide as the primary URL. Chrome visibly confirmed successful submission and a review window of up to 2 weeks. No registry name or public remote endpoint was claimed.
9. Published the expanded MCP guide, client-specific examples, `llms-install.md`, and submission copy to CLI main in commit `5c3869f`. JSON documentation examples parsed successfully; 27 focused MCP tests passed.
10. Downloaded the official macOS ARM64 0.1.9 release; SHA-256 matched the release metadata. The binary reports `0.1.9 (commit 60bd923c80ee, released 2026-10-09)`. Its MCP/install help and a temporary Cursor project installation passed; an existing third-party MCP entry was preserved.
11. Submitted the repository to MCP Market's free queue. Chrome visibly confirmed submission and the stated 4–6 week wait. This destination accepts a repository URL, not custom description text; the published README and guide supply installation details.
12. The official Python MCP SDK accepted initialize from the published bridge against the existing runtime 0.1.3, but rejected `tools/list` because multiple singleton `inputSchema.properties` objects are malformed. This is a runtime schema issue, not a successful full-client qualification. Verify the newer image before claiming client compatibility or publishing installable registry packages.
13. Created the website `/docs/mcp/` page, docs navigation entry, and LLM index link. Synced upstream documentation to CLI 0.1.9. First website build used an older host Node; switching to installed Node 24.21.0 reached SEO validation, which required removing a duplicate brand in the page title. Deployment is still pending.
14. Website full build, strict upstream verification, installer suite, and graph refresh passed. Pushed website commit `9851f2e`; deployment runs automatically from main. Live verification remains pending.
15. Started a task-owned isolated runtime on data volume `localcloud-mcp-qualification-20261009`, image SHA-256 `892dfc09e2d126b7700a098bea59d657f5214cea7b991c463a71a33bc5d93378`, only GCS enabled, loopback ports with gateway 5508. It reports runtime 0.1.4 and reproduces malformed singleton-property schemas. The pre-existing shared runtime was preserved. This qualification runtime must be cleaned up after testing.
16. Traced the schema defect to `McpService.schema(Map, List)`. Added an all-tool regression check in the runtime repository; a repair and exact-image qualification are in progress.
17. Created task forks for awesome-list contributions: https://github.com/jhsenjaliya/localcloud-awesome-mcp-servers-punkpeye and https://github.com/jhsenjaliya/localcloud-awesome-mcp-servers-appcypher. No listing PRs submitted yet.
18. Inspected PulseMCP's current submit page in Chrome. New submissions and listing edits are explicitly paused; recorded its Official Registry fallback and moved on.
19. Website deployment initially failed on a fixed 83-page Markdown expectation after adding the guide. Updated it to 84 in `90e07f5`. A local performance check then used pre-commit artifacts; rebuilt from current HEAD and all 159 performance tests passed. Deployment https://github.com/LocalGCloud/LocalGCloud.github.io/actions/runs/37908228130 succeeded. The live https://local.cloud/docs/mcp/ returns HTTP 200 and contains the CLI 0.1.9 minimum and MCP guide title.
20. The new all-tool runtime regression failed against the old schema helper. Normalized singleton property definitions through the existing `props` helper in `McpService.schema`; the full MCP test class passed. Source/test changes and generated API contracts remain in the runtime worktree pending packaging, documentation synchronization and commit.
21. Submitted punkpeye draft PR https://github.com/punkpeye/awesome-mcp-servers/pull/16030 and attached it to this chat. Draft status explicitly discloses the pending exact-image schema qualification. Appcypher's API rejected PR creation with HTTP 404; its prepared fork branch is retained for follow-up.
22. Glama Google sign-in reached a new-account profile completion page requiring acceptance of Terms of Service. Did not complete that legal acceptance. The authorized Google profile/email sign-in occurred; no MCP listing has been submitted there. This optional destination can be revisited with human term acceptance.
23. Verified that appcypher's upstream repository is archived; recorded a concrete skip reason rather than repeating the failed PR request.
24. Created and read back a private reference Page. It contains the full rollout history and operational follow-up details; the public ledger records publication evidence separately.
25. Downloaded official `mcp-publisher` 1.8.1 into `/tmp/localcloud-mcp-publisher.X1EhMb` and began its GitHub device authentication using the existing maintainer identity. Registry package publication has not occurred.
26. Completed official registry publisher authentication through GitHub's Model Context Protocol identity-verification application. Maintainer identity is an active LocalGCloud organization admin. No registry metadata or package has been published yet.
27. Committed schema repair and canonical documentation/contracts as runtime source `b4faedfd`, and retry wording correction as `12ca77c6`, without including unrelated dirty Vertex AI/client-config edits. Created a clean runtime checkout `/tmp/localcloud-mcp-runtime.iVdp2T` on `codex/mcp-distribution-runtime`, based on the existing v0.1.4 source. Prepared VERSION 0.1.5 in `1660d1f6`; incorporated reviewed wording in `77da210f`.
28. Built the full production-shaped free runtime through its canonical Dockerfile, including the documentation-check stage, with no mounted source/JAR/UI overlays. Final candidate image was `sha256:4b9ac982eb4aaf0382239b3cf7bea60580434ea4afaf87e716b5ae4b809e8df4` at source `77da210f`. Candidate remains local and unpromoted.
29. Created task-owned qualified runtime volume `localcloud-mcp-qualified-20261009`, container `localcloud-volume-5f03985018c7` (`ca02c9a296a7f966c050ecbc9bff3da0d168ef8a8eb412033c5995b4c541744f`), gateway `http://127.0.0.1:5511`, with GCS/PubSub/BigQuery enabled and no Docker socket. Strict SDK discovery against this exact image passed (28 tools, 14 resources, 7 templates, 6 prompts) and project lookup passed.
30. The following service-list call exposed an independent discovery defect for remapped service subsets: disabled rows included inactive canonical addresses, correctly rejected by the CLI's endpoint boundary. Added a regression that failed before repair, then changed MCP and compatibility discovery to omit usable endpoints for disabled services while preserving availability/startability/protocol/port metadata. All 33 focused tests passed (26 MCP, 5 compatibility registry, 2 environment); updated canonical docs and are preparing a refreshed candidate.
31. Exported and visually inspected a 400x400 PNG from the existing LocalCloud vector icon. Implemented `scripts/build-mcpb.py`, using official release hashes, safe tar extraction, four signed CLI archives, and pinned official MCPB packer 2.1.2. Six executable launcher tests passed for supported OS/CPU cases, space-containing paths/arguments, and unsupported-platform rejection.
32. Built universal MCPB 0.1.9 with original licenses/notices, existing-brand icon, explicit macOS 13+/Linux glibc 2.35+/Docker/runtime 0.1.5+ prerequisites. Vendor manifest validation passed; archive SHA-256 `6788f9208230cce57d2d92453c15806819531d63fca4f0eff37dc39fd0175987`, size 102366787 bytes. Native extracted-bundle qualification and public upload remain pending.
33. Fresh subagent reviews found no code/packaging blocker; verified exactly 14 schema corrections among 41 generated tools, executable modes, license preservation, and absence of host credentials/user data in the bundle. Corrected identified catalog/retry wording and platform-prerequisite omissions. Reviews do not replace packaged-image/client qualification.
34. Published MCPB builder/tests and icon in CLI commit `4c41c00`. Combined focused CLI/packaging suite passed 33 tests. Made the dispatch fixture explicitly select DEFAULT_DATA_VOLUME so its assertion does not depend on the host's last active runtime. Restored the saved original localcloud-data active record through the existing state API; no original container/data was changed.
35. Committed inactive discovery endpoint repair as runtime `d79e44c9` and cherry-picked into clean release branch `d0c9525e`. Full candidate rebuilt as `sha256:1bc9202b7e6c8874deb44d4bf5d3f6f2ba8d4aa7a52b0ca745b0d2cbcb839189`; still unpromoted.
36. Ran real standard Python SDK workflows against the task candidate using CLI-projected loopback endpoints: Cloud Storage upload/download content assertion, Pub/Sub publish/pull/ack assertion, and BigQuery SELECT 1 assertion all passed. Removed only the uniquely named test bucket/topic/subscription and restored the original active runtime record in cleanup.
37. Published editable workflow SVG, its guide embed, and the 400x400 marketplace PNG with website commit `fa4ad4d`. Full build/installer/performance gates passed; deployment https://github.com/LocalGCloud/LocalGCloud.github.io/actions/runs/37917162367 succeeded. Guide, PNG, and SVG public URLs each returned HTTP 200.
38. Fresh endpoint review identified DiagnosticsService.serviceConfigSnapshot as one remaining source of inactive addresses in global readiness/diagnostics resources. Added diagnostics-backed MCP tool/resource regression, corrected its fixture wiring/data directory, verified it fails against the old endpoint gate, and applied the same enabled/startable condition. Full focused retest is in progress; final image/publication still pending.
39. Began Cursor Directory publisher sign-in through the authorized Google account; no listing submitted there yet. Added long-tail discovery candidates for follow-up: cursor.directory, mcpservers.com, mcphub.app, mcpdirectory.app/dev. Verify current first-party eligibility and submission UI before use.
40. Diagnostics regression passed with all 38 focused tests. Committed only DiagnosticsService and its MCP regression as `ffcadb11`, then cherry-picked to release branch `453e1fe7`; fresh source review found no remaining endpoint blocker.
41. Built full final-endpoint candidate `sha256:95d387ddd1c9eed8bf408293aba56286a0cc5f05d9584f0f718ec33984852898`; recreated only the task-owned qualified container with that image, preserving its synthetic test volume and the user's original runtime.
42. Pushed the isolated release branch to the existing private source repository. Temporarily enabled its disabled release workflow and dispatched a qualification run. Remote CI was unavailable and no jobs started; the private reference record contains the operational cause. Restored the workflow's original disabled setting. No CI qualification or image promotion occurred at this step.
43. Started a local multiarchitecture candidate build using the existing Buildx builder, without changing its selection/configuration. Canceled it before publication when the next SDK check identified an output-schema defect; no known candidate manifest was published from this attempt.
44. Extracted the exact MCPB archive into `/tmp/localcloud-mcpb-qualified.xs49uA`; its native macOS ARM64 binary reports the verified CLI 0.1.9 release and its executable launcher successfully prints MCP help. Full live bundle qualification remains pending.
45. Strict MCP SDK 2.0.0 accepted tool discovery and service-list/readiness/diagnostics calls against the exact endpoint-fixed image. It rejected `localcloud_get_env(format=json)` because the tool advertises a string while returning an object. Publication remains gated on this actual client failure.
46. Added a regression exercising all six advertised environment formats. It failed with “json returns object but advertises string.” Corrected the runtime output schema to allow string/object; 39 focused tests and runtime API contract generation passed. Fresh review traced every default tool's success-result type and found no further mismatch. Canonical documentation regeneration/check and refreshed images are in progress.
47. Prepared root `server.json` for `io.github.LocalGCloud/localcloud`, with the real MCPB SHA-256, website, guide, icon, platform prerequisites, CLI 0.1.9 and runtime 0.1.5 minimums. Validated it against the official 2025-12-11 JSON schema; publication has not occurred.
48. Prepared Cursor Directory's manual LocalCloud MCP plugin form with the MCP configuration, full agent-focused description, website/guide links, keywords and existing-brand icon upload. Left it unpublished pending the qualified runtime delivery.
49. Completed Smithery's routine Google profile/email sign-in. Current browser publishing form accepts an HTTP endpoint, while its official docs support local MCPB through CLI/API; preparing that supported local path, without exposing a loopback endpoint publicly.
50. Smithery CLI 1.2.0 authentication displayed “Access to your API key” before authorization. Canceled that step and stopped the waiting CLI process; this optional destination is deferred under the computer-control confirmation policy. No package/server publication or API-key disclosure occurred.
51. Committed environment-format repair as runtime source `a3cf1d0c`, applied to clean release `491fbca0`, regenerated its three affected API artifacts in `4e44e009`, and copied that contract-only change back as `6d88062a`. Documentation generation/check passed; unrelated Vertex AI/config work remains preserved.
52. Full local image rebuilt from clean source `4e44e009` as `sha256:b7361104e77fde4769d79a2b73d68590b0307af3647199729865ee38085e67c2`. Recreated only the task container (`284709695e56df2668c527276efda125c68794f2b987e97ab7291d9aac03aec7`) and verified its actual image identity. Multiarchitecture candidate build/push remains in progress.
53. Added runnable `examples/mcp_workflows.py` and its guide. Against that exact image, strict SDK calls for service discovery/readiness/compatibility/diagnostics/SDK configuration passed; standard SDK Storage upload/read-back, Pub/Sub publish/pull/ack, and BigQuery SELECT 1 passed. Only uniquely named test resources were removed, and the original active runtime selection was restored.
54. Launched the extracted MCPB's actual native launcher against the exact image. Strict SDK discovery passed (28 tools, 14 resources, 7 templates, 6 prompts); 17 tool invocations, including all six env formats, catalog, browse, query, configuration, and diagnostics, plus three resource reads all passed.
55. Created and attached TensorBlock draft PR https://github.com/TensorBlock/awesome-mcp-servers/pull/3385 and DevOps draft PR https://github.com/rohitg00/awesome-devops-mcp-servers/pull/351. Both disclose the pending public runtime delivery and include live website/guide links; neither is an approved listing.
56. Removed the obsolete task-owned 0.1.4 qualification container `localcloud-volume-7b517668a201`, its same-named network, and volume `localcloud-mcp-qualification-20261009`. Cleared only that task's saved runtime record and restored `localcloud-data` as the active selection. The original user container and volume were preserved.
57. Captured and visually inspected the live MCP guide in the in-app browser; saved the screenshot as `docs/assets/localcloud-mcp-guide.jpg`, without exposing other browser tabs or account UI. Logo and architecture SVG remain live at their previously verified public URLs.
58. Reran the focused CLI MCP/installer/packaging suite after adding the runnable example: all 33 tests passed. The actual example's assertions already passed through the released CLI and the exact full image; local runtime evidence remains separate from unavailable remote CI.
59. Multiarchitecture build failed in the AMD64 GCS test linker with “no space left on device.” The shared Colima Docker data disk is 100 GiB (93% used after failure); the host has over 360 GiB available. Did not prune shared images, caches, or volumes, and did not restart or resize the user's VM.
60. Created isolated task-owned Colima profile `localcloud-mcp-rollout-20261009` with 4 CPUs, 8 GiB memory, 192 GiB data disk, VZ and the already-installed Rosetta. Disabled context activation, SSH config modification, and host filesystem mounts. Verified the original `colima` Docker context remained selected. This profile, its builder/cache, and any test containers must be removed after saving final evidence/artifacts.
61. Retrying the canonical full multiarchitecture build in task-owned builder `localcloud-mcp-rollout-20261009`, targeting the isolated Docker context. The existing `dataproc-multiarch` builder remains untouched.
62. Verified the exact official registry namespace/version API returns HTTP 404 before publication; broad search queries timed out, but direct exact-name lookup and IPv4 headers work. Current official authentication docs require organization ownership/admin role, which the existing maintainer has; no GitHub membership/privacy setting was changed.
63. Updated the public README opening to describe the free agent cloud environment, added website/MCP guide links, replaced the stale fixed version badge with the latest-release badge, and removed Pro/Enterprise service-tier labels and marketing notes as requested. Preserved the actual proprietary artifact license.
64. Signed in to MCPServers.com through the supplied Google account. Its Highlight OAuth flow requested optional contacts/calendar access; left every such permission unchecked and continued with profile/email only. Submission form inspection is pending; no directory listing sent there yet.
65. Prepared a new website walkthrough at `/blog/localcloud-mcp-agent-cloud/` using the existing blog layout, featuring executable setup, three SDK workflows, the architecture visual, real output, and verification boundaries. Added explicit runtime 0.1.5 upgrade guidance and desktop bundle instructions to both guides and `llms-install.md`. The reviewed restart command is supported by the published CLI's help.
66. Website build exposed a legacy rule rejecting every MCPB reference, including the qualified CLI-owned release URL. Kept all retired site-local package patterns and added a narrow exception for the exact CLI 0.1.9 release-bundle link, with checks rejecting site-hosted, other-owner, and lookalike-extension references. The full build then passed; updated Markdown count to 85 for the new article. Website publication is still gated on runtime delivery.
67. Added an assertion for the actual MCP BigQuery result, beyond transport success: columns `mcp_smoke`, exactly one row, value 1. The complete example passed again with this assertion and all three standard SDK workflows; original active runtime selection restored.
68. Saved CLI preparation commits `5f1d180`, `800d3db`, and `e11d128` locally. Saved website walkthrough/guide/guard changes in `163601e`; initial full build, installer, performance checks passed. Rebuilding from the committed revision before final gates/deployment to avoid stale hashed assets. These new commits have not been pushed yet.
69. Isolated full multiarchitecture build succeeded and pushed unpromoted index `sha256:558ea196fef9009b14981b58554547b9f759ed9af3511b535ec44b1935dbe68d` under `agentcloud/localcloud:candidate-4e44e009b0525d22ad134e796d6e28a1ee65a456`. No default or semantic-version image tag was promoted.
70. Rebuilt cached metadata layers with the actual build hash/date, preserving the first candidate. Final unpromoted release candidate is `agentcloud/localcloud:candidate-mcp-4e44e009-20261009`, index `sha256:b2f6e01cb1eb59de3caea5fdfa6299a616e84c28d8dcc4e6ee3e1e882de17934`, source `4e44e009b0525d22ad134e796d6e28a1ee65a456`. Verified exactly two runnable platforms: AMD64 `sha256:5e8ee925b5e7eb97536234ecbfc2b133b9f1ca164a267dadd70888e78475d8c7`, ARM64 `sha256:cf00c62847da79d026ad76da1cfb4b4bd2154b87cbdc45079260c099e8c845ee`; additional unknown-platform entries are attestations.
71. Beginning fresh final-candidate qualification in the isolated Docker context using each exact platform digest. AMD64 test volume `localcloud-mcp-release-amd64-20261009`, loopback range 6260–6279, GCS/PubSub/BigQuery. Retain precise container/network IDs and clean up after preserving evidence. Promotion and official registry publication remain pending.
72. Final AMD64 image `sha256:5e8ee925b5e7eb97536234ecbfc2b133b9f1ca164a267dadd70888e78475d8c7` passed strict MCP discovery/configuration/query assertions and all three standard SDK workflows under emulation in the isolated ARM64 VM. Container `localcloud-volume-e7f363a1d844` (`f2c4b3aa5e05f38059ad09e08bc2be526744d8c89221eb08784de474b0f44019`) reports `0.1.5+4e44e009.20261009`, exact source revision, production build mode, free edition. Stopped it before starting the ARM64 test.
73. Final native ARM64 image `sha256:cf00c62847da79d026ad76da1cfb4b4bd2154b87cbdc45079260c099e8c845ee` passed the same example plus extracted MCPB launcher discovery, all environment formats, resource reads, and actual query-value assertion. Container `localcloud-volume-5f079c235e84` (`9f1d9cd31a2b7d3adc9a1836267add37c65fc7e1ef26e5e0c5f4750ffeb1ff1a`) uses volume `localcloud-mcp-release-arm64-20261009`, loopback range 6290–6319, and reports matching production/free version/source metadata. Original active volume restored after checks.
74. Updated the runnable example to preserve an explicitly selected Docker context; the MCP SDK otherwise inherits only six shell environment variables. Verified the example against both the ordinary context and the isolated context. No global Docker context setting was changed.
75. Recorded the actual native ARM64 verification run, saved its timestamped JSON transcript, rendered an 18-second H.264 MP4 and GIF with the existing brand icon and visible text, and inspected the final frame. Added the demo to the README and prepared the website video/transcript. No generated AI-agent interaction or cloud-provider claim was substituted for the test output.
76. Promoted the qualified index without rebuilding to public tags `agentcloud/localcloud:0.1.5`, `:0.1`, and `:latest`. Read back each tag and verified it resolves to `sha256:b2f6e01cb1eb59de3caea5fdfa6299a616e84c28d8dcc4e6ee3e1e882de17934`.
77. Tagged the exact clean release source as runtime `v0.1.5` in the existing source repository, leaving its original disabled CI setting intact. Brought the VERSION-only bump back to the primary working copy as `c7872eaf`, preserving unrelated dirty files.
78. Uploaded the qualified MCPB and separate `MCPB_SHA256SUMS` to the existing CLI v0.1.9 release, preserving the original native assets/checksums. Downloaded the public bundle and verified 102366787 bytes and SHA-256 `6788f9208230cce57d2d92453c15806819531d63fca4f0eff37dc39fd0175987`.
79. Official registry publication rejected the expired publishing token with HTTP 401; no entry was created. Started the normal GitHub device-flow refresh using the same maintainer/app identity and permissions.
80. Separated private operational follow-up details from the public listing ledger and saved a full private copy for the maintainer. Consolidating only unpublished task-owned CLI preparation commits before public push so private details are not exposed through earlier commit history.
81. Completed the routine publisher login refresh. Organization namespace publication was denied with HTTP 403 despite the maintainer's existing organization role; did not change membership privacy or grant broader credentials. Published the recommended personal namespace `io.github.jhsenjaliya/localcloud`, version 0.1.9. Exact version/latest APIs confirm status active and isLatest=true, with the qualified public bundle and matching SHA-256.
82. Published sanitized CLI documentation, examples, metadata and media as `7e5a3f7`, then corrected the registry namespace in `6496521`. Only unpublished task-owned preparation commits were consolidated; no remote history was rewritten. Updated the public repository's agent-focused description, website and relevant MCP/cloud topics and verified their readback.
83. Published website walkthrough/demo revision `9d7a8f6`. Deployment https://github.com/LocalGCloud/LocalGCloud.github.io/actions/runs/37933108475 completed successfully for that exact revision. Live guide, blog, icon, architecture visual and MP4 return HTTP 200. The article includes a textual transcript and version/qualification boundaries.
84. Rewrote the three awesome-list PR descriptions around the delivered releases and marked each ready for review. All remain open and not draft; punkpeye's submission check passed. No upstream PR was merged or maintainer approval represented as complete.
85. Submitted the MCPServers.com form with corrected product name, agent-focused description, actual icon/banner and website/guide links. Browser confirmed “Your MCP server implementation has been submitted for review.” Optional contacts/calendar access remained unchecked and unexpected LAN access was blocked.
86. Submitted Cursor Directory's LocalCloud MCP component and setup metadata. The resulting page is https://cursor.directory/plugins/localcloud-mcp; it explicitly says the plugin is scanning, unpublished and hidden until the security agent finishes. A later check retained that pending status.
87. Used MCP.so's free community issue route after observing paid placement on its main form. Created https://github.com/chatmcp/mcpso/issues/5034 and verified it is open. No paid placement purchased and no listing approval claimed.
88. Sent the official GitHub MCP Registry inclusion nomination to `partnerships@github.com` from the authorized account. Included the active Official Registry entry, repository, website, MCP guide, walkthrough, qualified bundle and prerequisites. Gmail's exact `in:sent` recipient/subject search confirms the nomination thread; curated inclusion remains pending.
89. Published the brand X announcement at https://x.com/LocalCloud_AI/status/2108549606422536549 with the actual recorded MP4, CLI/runtime versions, install command and website/guide links. Verified the post after a non-binding new-account reach notice; did not manufacture engagement.
90. Published https://dev.to/jaysen99/give-your-coding-agent-a-local-cloud-environment-with-mcp-2b2l through the existing account. Previewed the rendered tutorial, used `mcp` and `ai` tags, saved its original-site canonical URL, and selected the truthful Fully Autonomous authorship disclosure. An anonymous browser view confirms publication and links; saved an unobstructed screenshot.
91. Submitted the practical walkthrough as a regular Hacker News link at https://news.ycombinator.com/item?id=50021011 using the existing `jaysen_apache` account. A focused pre-submission search found no matching MCP post. Verified the exact title, URL and author; no duplicate Show HN, generated comments or artificial engagement.
92. Published the r/mcp showcase at https://www.reddit.com/r/mcp/comments/1x1mucq/ using the existing `jhsonline` account. Read the community's promotion rules, selected showcase flair and Brand Affiliate, disclosed maintainer affiliation and AI assistance, and included concrete runnable tests. Anonymous browser readback confirms the complete post is visible; declined duplicate crosspost suggestions.
93. Published the existing Jay Sen account's LinkedIn announcement at https://www.linkedin.com/feed/update/urn:li:activity:7514331414318759936/. Browser confirmed Post successful; the permalink contains the agent-focused description, website/guide, CLI/runtime requirements and verified workflow boundaries.
94. Screened remaining optional destinations. MCPHub login explicitly requires agreement to new terms; did not complete it. mcpdirectory.dev says it ingests MCP Registry/GitHub/npm daily; the official entry provides that feed path, but no LocalCloud listing or manual submission route was verified. mcpdirectory.app returned both a fetch failure and browser DNS failure. Cline's README requires observing Cline install from README/llms-install; that native prerequisite remains unverified in this environment, so no misleading marketplace issue was filed. Remote-only and unrelated awesome lists were excluded.
95. Removed all four task-owned qualification/release containers, their networks and synthetic volumes, cleared only their runtime records, and restored `localcloud-data` as active. Removed the task Buildx builder, stopped and deleted its separate Colima profile, and verified only the original default profile/context remains. Original `localcloud` container, application volume and existing builders were preserved.
96. Saved final manifest/registry, schema regression, build, website checks and cleanup evidence in the maintainer's private artifact folder. Removed the clean, published task release worktree, two clean/pushed submission clones, extracted bundle, downloaded verification CLI/publisher and temporary video renderer. Retained the public release artifacts, source branches/tags, useful shared images/caches and private follow-up evidence.
97. Completion audit verified the active/latest official entry, deployed website revision, three open ready PRs, open MCP.so issue, five published community/social announcements, submission confirmations, and concrete optional deferrals. Reconciled this public record and the private follow-up document; external approvals remain explicitly pending.

## Released artifacts and verification

- CLI 0.1.9: https://github.com/LocalGCloud/localcloud-cli/releases/tag/v0.1.9
- Desktop bundle: https://github.com/LocalGCloud/localcloud-cli/releases/download/v0.1.9/localcloud-mcp-0.1.9.mcpb — 102366787 bytes, SHA-256 `6788f9208230cce57d2d92453c15806819531d63fca4f0eff37dc39fd0175987`.
- Runtime 0.1.5: `agentcloud/localcloud:0.1.5`, index `sha256:b2f6e01cb1eb59de3caea5fdfa6299a616e84c28d8dcc4e6ee3e1e882de17934`. Native ARM64 and emulated AMD64 strict MCP plus Storage/PubSub/BigQuery assertions passed against their exact public image digests.
- 33 focused CLI/MCP/installer/packaging tests and 39 focused runtime tests passed. Schema regressions failed before their fixes. Full production-shaped multiarchitecture builds passed locally; remote runtime CI did not run.
- Website full build, strict upstream verification, installer checks and 159 performance checks passed. Successful deployment and live HTTP proof are recorded separately above.
- The released MCPB's actual macOS ARM64 launcher passed strict SDK discovery, all environment formats, resource reads and an asserted BigQuery result. Other native client UIs and native AMD64 hardware are not claimed as tested.
- Public media: https://local.cloud/brand/localcloud-mcp-icon.png, https://local.cloud/brand/localcloud-mcp-flow.svg, https://local.cloud/brand/localcloud-mcp-demo.mp4.

## Follow-up queue

- Check Cursor Directory's security scan and the three awesome-list PRs before sending any additional submission; reply to genuine maintainer requests without duplicating entries.
- MCPServers.org's stated two-week review window ends around October 23, 2026. MCP Market's stated 4–6 week window is around November 6–20. MCPServers.com, MCP.so and GitHub inclusion have no verified approval date.
- Revisit PulseMCP when intake resumes and check automatic directory ingestion after a refresh. The active Official Registry record is the canonical feed entry.
- Optional human prerequisites: Glama/MCPHub terms acceptance, Smithery's API-key authorization, and a configured Cline agent completing the required installation test. Docker Catalog additionally needs a genuinely qualified containerized stdio integration.
- No placement was purchased. No automatic follow-up or monitoring schedule was created.

## Resume and completion audit

The authorized rollout has been executed with the specific optional deferrals above. Pending review is not approved placement. For future work, preserve existing IDs and confirmation evidence, update the verified date, and check for duplicates before submitting again. Repeat runtime/client qualification only for changed artifacts or a newly claimed integration; preserve the user's existing environments.

## 2026-10-09: Claude and Codex GitHub marketplaces published

- Business actor: cloud-orbitor; publisher contact: orbitor.cloud@gmail.com.
- [Implementation PR #8](https://github.com/LocalGCloud/localcloud-cli/pull/8) merged as 181dee77e3a37abed308613d348e7d6e439a3393.
- [CLI release 0.1.10](https://github.com/LocalGCloud/localcloud-cli/releases/tag/v0.1.10) is public and includes plugin 0.1.0, native macOS/Linux ARM64/x86_64 archives, checksums, and Sigstore verification bundles.
- [Release workflow](https://github.com/LocalGCloud/localcloud-cli/actions/runs/37981658381) passed all native build and publication jobs. The plugin ZIP, its checksum, native SHA256SUMS, and the macOS ARM64 archive were independently verified with Cosign against the release workflow/tag identity.
- Published plugin SHA256: 55bb1997afd518ddd9ca3055b25a1a992cd1a2c5b3a3460f408b1948c959a93e. ZIP contents are limited to nine public integration/license/artwork files.
- The CLI and plugin now carry the owner-approved Public Preview License. The published native archive and plugin ZIP license bytes match the approved agreement.
- Both clients installed LocalCloud from the remote GitHub catalog. Codex loaded the LocalCloud tools and real service discovery succeeded. A separate environment verified MCP discovery/readiness/compatibility/diagnostics/SDK settings/query plus Storage, Pub/Sub, and BigQuery SDK assertions through the published native CLI and installed plugin launcher.
- [Setup guide](mcp-marketplace-guide.md): public requirements are Docker engine, LocalCloud CLI, and the LocalCloud Docker image, with native macOS/Linux availability. Launch benefits describe local service workflows without Google Cloud service charges, one-command setup, and multiple projects within machine capacity.

Publication dependencies remain distinct:

- Claude vendor Directory: [LocalCloud plugin 0.1.5](https://claude.ai/directory/manage/plugins/e917c263-e80b-4fa1-9070-45bde6b2f8a7) was submitted with the owner's terms confirmation. Validation has no blockers or policy holds; the security scan passed and the version is In review. It is not live yet. Public publisher: LocalGCloud; contact: orbitor.cloud@gmail.com.
- OpenAI public Directory: the local stdio MCP and brand-publisher support request was sent and escalated to a specialist. No vendor submission or approval is recorded; no fake HTTPS endpoint or skills-only substitute was submitted. The GitHub Codex plugin and direct local MCP setup remain available.
- Privacy: the [CLI/MCP policy](mcp-privacy.md) covers operational telemetry, processor retention limits, deletion requests, and local data. PostHog Free's documented one-year event query window is not an automatic erasure deadline. CLI/plugin integration does not record sessions; website/runtime policies are scoped separately.
- Homebrew: cloud-orbitor's repository invitation was accepted and [CLI 0.1.11 was published to the tap](https://github.com/LocalGCloud/homebrew-tap/actions/runs/38004527000). Signed checksums and Homebrew installation/tests passed on macOS and Linux, ARM64 and x86_64.

## 2026-10-09: official Claude submission and local setup

- [CLI release 0.1.11](https://github.com/LocalGCloud/localcloud-cli/releases/tag/v0.1.11) is public. Signed release checksums, the plugin ZIP, and the macOS ARM64 archive were verified. That native binary passed real MCP and Storage/Pub/Sub/BigQuery SDK workflows in a task-owned local environment.
- [PR #14](https://github.com/LocalGCloud/localcloud-cli/pull/14) supplies plugin 0.1.5's inspectable Claude launcher. Claude uses the installed CLI on PATH; direct MCP installation records an absolute path when needed. Codex's launcher keeps its existing discovery behavior. The release 0.1.11 plugin ZIP contains the earlier plugin 0.1.3; GitHub marketplaces and the submitted Directory source follow main's plugin 0.1.5.
- The four Directory-only link-field warnings explicitly say no action is needed. The post-submission local-program warning is informational; the security scan passed and an Anthropic reviewer must approve publication.
- A push-only GitHub webhook was configured using cloud-orbitor after owner confirmation. TLS verification is enabled; GitHub's ping received HTTP 200 and Claude reported Webhook connected. The temporary signing-secret file was removed. No source or runtime image was uploaded as part of webhook setup.
- The [local setup guide](mcp-marketplace-guide.md) covers Claude Code, Claude Desktop, Codex CLI/desktop, Cursor, other client references, GitHub plugin installation, connection checks, first SDK tasks, project selection, updates, and removal. Local installation does not depend on vendor Directory approval.

</details>
