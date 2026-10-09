# LocalCloud MCP Marketplaces Implementation Plan

> Steps use checkbox (`- [ ]`) syntax so an executor can track progress task by task.

**Goal:** Make LocalCloud’s existing CLI MCP integration discoverable and installable through Claude and Codex marketplace paths, with useful agent workflows, accurate documentation, and evidence for every supported listing claim.

**Architecture:** Add one small plugin folder and vendor catalog entries to the existing public LocalGCloud/localcloud-cli repository. Reuse the released LocalCloud CLI and its `localcloud mcp` stdio bridge. Users obtain the companion runtime through the existing Docker distribution. The plugin carries public metadata, guidance, artwork, and an inspectable CLI launcher; it does not carry the runtime implementation.

**Tech Stack:** Existing Python CLI, its native release artifacts, Docker, Claude plugin manifests, Agent Plugins manifests for Codex, JSON catalogs, Markdown, and the existing pytest suite. Use standard-library packaging rather than adding a framework or a second server.

**Spec:** [Draft marketplace content and user constraints](../../mcp-marketplace-copy.md).

## Global Constraints

- Approved for execution by the user on 2026-10-09, with simple requirements and benefits added. Use Cloud Orbiter accounts for all publisher actions; do not use a personal identity.
- Work only in the public CLI project. Do not create a new repository or read/copy private runtime source, its build Dockerfile, runtime layers, or image exports into this work.
- Publisher actions use cloud-orbitor and orbitor.cloud@gmail.com. Verified on 2026-10-09: cloud-orbitor has admin and push access to LocalGCloud/localcloud-cli. Preserve the globally active GitHub account; use per-process credentials and verify the actor before each write.
- Proposed public display name: LocalCloud. Proposed plugin slug and catalog name: localcloud. Preserve the separate MCP Registry identifier cloud.local/localcloud. Confirm lasting names and publisher identity before creating marketplace drafts.
- LocalCloud has no Pro/paid upgrade messaging. Follow each vendor’s copy rules, including OpenAI’s prohibition on pricing promotion in descriptions.
- Public launch requirements: Docker engine, LocalCloud CLI, and the LocalCloud Docker image. Mention native macOS and Linux support. Keep tested CLI/runtime/client versions in qualification and release records rather than repeated marketing requirements.
- Keep MCP management writes and destructive operations disabled by default. SDKs can change application data during user-requested tests. Use task-owned resources and a separate qualification volume; never replace or delete a user’s running environment automatically.
- Preserve existing unrelated changes and the two local main commits already ahead of origin/main. Do not push those commits incidentally as part of this rollout.
- No new remote-hosted MCP service, public tunnel, account upgrade, recurring automation, personal social post, or new dependency without a concrete need and the applicable user choice.
- Preparation, installation, submission, approval, and live listing are separate states. Completion means verified installation/tool use and verified public placement, with any vendor-dependent outcome explicitly recorded.

---

## Distribution paths and current policy constraints

| Path | Intended result | Required treatment |
| --- | --- | --- |
| Claude GitHub marketplace | Users add the CLI repository’s catalog and install the plugin | Publish and test its catalog and plugin folder |
| Claude public Directory | LocalCloud is discoverable in Claude’s vendor directory | Submit the public repository/folder, pass validation and review, then verify the published listing |
| Codex GitHub marketplace | Users add the CLI repository as a source and install LocalCloud | Publish the Codex catalog and test installation in the desktop client |
| OpenAI public Directory shared with Codex | Vendor-controlled public discovery | Obtain OpenAI’s local-MCP submission route; normal public submission expects a reachable HTTPS MCP endpoint |

OpenAI distinguishes Git/repo marketplaces from public directory publication. A local MCP submission currently needs an OpenAI contact if it cannot be deployed publicly. Request that route using the business identity; do not change LocalCloud into a hosted service to satisfy the standard form, and do not submit skills-only packaging as though it exposes the MCP tools. [Packaging guide](https://developers.openai.com/plugins/build/plugins), [local MCP submission guidance](https://developers.openai.com/plugins/guides/submit-claude-plugin).

For Claude, validate the public repo/folder, include an adequate README and license, and meet archive/path/file checks. A launcher or executable can trigger manual review; distinguish a policy hold from a blocking error. Keep secrets, LFS pointers, symlinks, system files, and MCPB download references out of the plugin. Recheck exact current thresholds before packaging. [Pre-submission checklist](https://claude.com/docs/plugins/pre-submission-checklist), [submission procedure](https://claude.com/docs/plugins/submit).

Both vendors require accurate behavior, narrow instructions, meaningful examples, privacy disclosures, reliable error handling, and appropriate tool annotations. Review every relevant section before attesting compliance. Local SDK workflows are not Google Cloud production guarantees. [Anthropic policy](https://support.claude.com/en/articles/13145358-anthropic-software-directory-policy), [Anthropic terms](https://support.claude.com/en/articles/13145338-anthropic-software-directory-terms), [OpenAI guidelines](https://developers.openai.com/plugins/plugin-guidelines).

## File map

All paths below are relative to the public CLI repository root.

| File | Responsibility |
| --- | --- |
| docs/mcp-marketplace-copy.md | Canonical reviewed introduction, listing text, prompts, and naming choices |
| docs/mcp-marketplace-guide.md | Marketplace setup, requirements, first use, troubleshooting, upgrade and removal |
| docs/mcp.md; README.md | Link the new guide and preserve direct CLI integration instructions |
| plugins/localcloud/.claude-plugin/plugin.json | Claude metadata and component declarations |
| plugins/localcloud/.mcp.json | Claude stdio MCP entry |
| plugins/localcloud/plugin.json; plugins/localcloud/mcp.json | Portable Codex identity, OpenAI listing extension, and explicit stdio transport |
| plugins/localcloud/scripts/launch-localcloud.sh | Minimal launcher for the already-installed public CLI; useful failures and correct stdio behavior |
| plugins/localcloud/skills/localcloud/SKILL.md | One provider-neutral build/test/inspect/debug workflow |
| plugins/localcloud/README.md; plugins/localcloud/LICENSE | User-facing plugin description, setup, examples, limitations, and approved terms |
| plugins/localcloud/assets/icon.png | Copy of approved docs/assets/localcloud-mcp-icon.png |
| .claude-plugin/marketplace.json | Claude catalog pointing to ./plugins/localcloud |
| .agents/plugins/marketplace.json | Codex catalog pointing to ./plugins/localcloud |
| scripts/build-marketplace-plugin.py | Allowlisted plugin export/ZIP and checksums; excludes the rest of the repository |
| tests/test_marketplace_plugin.py | Meaningful launcher, schema, scope, archive, and metadata checks |
| .github/workflows/cli-release.yml | Add the plugin asset to the existing release only after package validation passes |
| docs/mcp-distribution-log.md | Public submission URLs, tested versions, review findings, and publication status |

The launcher is new only because a marketplace install must locate the native CLI from a GUI process reliably and report missing prerequisites. Do not build another MCP adapter. Reuse docs/assets artwork, examples/mcp_workflows.py, existing release tooling, and existing MCP tests. Keep all plugin-referenced files inside the plugin folder; no references to a parent’s source or assets.

## Task 1: Approve content and resolve publication identities

**Files:** docs/mcp-marketplace-copy.md; LICENSE only if the user authorizes a license alignment.

- [x] Content and implementation approved on 2026-10-09. Requirements are simplified; benefits cover local workflows without Google Cloud service charges, one-command setup, and multiple local projects. Use factual capacity/startup qualifications and OpenAI-compatible wording.
- [x] License decision approved by the user on 2026-10-09: align the CLI/plugin with the current Public Preview License.
- [x] Applied the approved agreement to the CLI and plugin; release artifacts are next: replace the CLI’s older individual-only LICENSE with the exact owner-approved Public Preview License and include the same agreement in the plugin. Confirm release artifacts carry it and update the intended audience consistently; the website alone does not change an artifact’s terms. [Approved agreement](https://local.cloud/license/), [licensing explanation](https://local.cloud/docs/licensing/).
- [ ] Inspect the logged-in Claude and OpenAI publisher accounts through their official UI. Verify business ownership, submission roles, and any required identity verification. Do not fall back to a personal publisher. Prepare everything independently of credentials/verification the user must supply.
- [ ] Record the final intended audience, publisher display name, product/slug, support contact, terms URL, and privacy URL. Confirm that the selected contacts are monitored; do not invent a support promise.

**Exit:** Approved content and resolved license/audience, with publisher access confirmed or an explicit remaining account prerequisite.

## Task 2: Freeze an implementation baseline and reviewer requirements

**Files:** Current CLI, docs/mcp.md, examples/mcp_workflows.py, existing tests and release workflow; no runtime repository.

- [ ] Record `git status --short --branch`, `git log -2 --oneline`, the selected CLI release, platform, and runtime version/image reference. Use a codex/ branch and a suitable isolated worktree if needed; preserve unrelated commits and user config.
- [ ] Re-read the vendor documents linked above at implementation time. Capture the applicable rules, URLs, date, manifest schemas, publishing attestations, and any scanner findings in the release evidence.
- [ ] Verify the CLI resolves the runtime correctly and preserves an existing instance. Check the actual MCP catalog, strict-client schemas, annotations, errors, tool names, and response size against vendor policy. Do not promise a current service/tool count from an old guide.
- [ ] Review public CLI telemetry and current runtime privacy disclosures. The submission policy must cover collected categories, purpose, recipients, retention, and controls; verify CLI coverage explicitly. Link the provider’s handling of MCP results. Do not equate loopback binding or telemetry opt-out with zero egress.
- [ ] If a required public privacy disclosure is missing, prepare a CLI-scope disclosure and identify the exact site-policy change required. Website editing is outside this plan’s authorization; resolve that dependency before submission rather than attesting to an incomplete policy.

**Exit:** Frozen public-source baseline, requirements/evidence checklist, and known compatibility or policy gaps.

## Task 3: Package the existing MCP bridge

**Files:** plugins/localcloud/; both root catalogs; tests/test_marketplace_plugin.py.

- [ ] Create the plugin folder, approved icon, README, approved LICENSE, and one skill. The skill applies when the user asks to build/test/debug a supported Google Cloud application locally. It discovers local settings, checks compatibility, uses SDKs for application operations, verifies assertions, and limits cleanup to task-owned resources. It must not coerce tool selection, fetch hidden instructions, request secrets, or bypass client safeguards.
- [ ] Add a small POSIX launcher that finds an executable localcloud in PATH or standard Homebrew locations, preserves arguments, and replaces itself with `localcloud mcp`. Use stderr for missing-CLI guidance; no installs/downloads, eval, secret reads, runtime replacement, extra server, or broader permissions. Test exit and signal behavior. Native Windows remains unadvertised.
- [ ] Add Claude’s .claude-plugin/plugin.json and .mcp.json. Resolve the launcher path from the documented plugin root, pass plain arguments, and avoid user-machine paths in committed configuration. A launcher hold requires honest manual review, not an attempt to conceal execution.
- [ ] Add the portable root plugin.json and mcp.json for Codex. Declare the Agent Plugins schemas and stdio type. Use the current client-supported local command/path convention and prove resolution from the installed plugin folder; do not assume Claude-only user_config substitution works. Add OpenAI presentation fields, approved URLs, icons, and three starter prompts.
- [ ] Add each catalog using its own supported schema. Point both entries to ./plugins/localcloud, use the approved catalog identity, and keep installation/authentication policy explicit without introducing a LocalCloud login requirement.
- [ ] Add one focused test module that checks both JSON formats, matching version/identity, required assets and license, description/prompt limits, internal paths, and launcher invocation/error behavior using a stub CLI. Assert that stdout is not contaminated.

Validation commands from the CLI repository:

```sh
python3 -m pytest tests/test_marketplace_plugin.py tests/test_mcp_stdio.py tests/test_mcp_autostart_and_install.py -q
claude plugin validate ./plugins/localcloud
```

Use the official schemas for manifest validation; `claude plugin validate` does not replace the hosted directory scanner. If launcher tests expose an actual shared CLI bug, localize callers and impact first, fix only public CLI code, and run the affected existing tests.

**Exit:** A locally validated plugin with actual stdio tool wiring, not a skill pack that only describes MCP.

## Task 4: Produce a bounded release package

**Files:** scripts/build-marketplace-plugin.py; tests/test_marketplace_plugin.py; .github/workflows/cli-release.yml; src/localcloud_cli/__init__.py.

Prepare CLI patch release 0.1.10 so native archives carry the approved Public Preview License; plugin metadata starts at 0.1.0.

- [ ] Export only an explicit list of files under plugins/localcloud. Package root must be a valid plugin root; preserve launcher executable permissions. Refuse escaping paths, symlinks, hidden credential files, system files, and unapproved payloads. Include licenses/notices applicable to what is actually distributed.
- [ ] Test an intentionally forbidden/escaping file and assert that packaging refuses it. Check exact ZIP entries, extracted permissions, sizes, and checksums. Do not scan or export private runtime contents to prove the exclusion.
- [ ] Measure the full public repository archive and plugin contents against Claude’s current thresholds. Resolve blocking findings within the CLI scope; do not create a new repo or discard unrelated files without discussing a concrete need.
- [ ] Extend the existing release workflow to attach the validated plugin ZIP and checksum. Keep native CLI and MCPB releases working. Determine plugin version independently from the CLI/runtime and document the mapping; start the new plugin at 0.1.0 unless the user chooses otherwise before publication.
- [ ] Run the packaging check and appropriate existing release tests:

```sh
python3 scripts/build-marketplace-plugin.py
python3 -m pytest tests/test_marketplace_plugin.py tests/test_release_script.py tests/test_release_packaging.py tests/test_mcpb_packaging.py -q
```

**Exit:** Reproducible CLI-only plugin package, manifest versions, exact contents, checksums, and successful release checks.

## Task 5: Qualify clean installation and working examples

**Files:** Existing examples/mcp_workflows.py; docs/mcp-marketplace-guide.md; release evidence outside the public package.

- [ ] Use a fresh client profile/project and a named qualification volume. Do not disturb the user’s existing LocalCloud instance, MCP config, plugin sources, or data. Record exact CLI/runtime/client/platform versions.
- [ ] Test macOS Apple Silicon and Intel, then Linux x86_64 and ARM64 where suitable execution environments are available. Build output alone is not a working-client test. Restrict advertised support if a matrix row remains untested.
- [ ] Install through Claude’s catalog and Codex’s Git/repo marketplace. Test GUI launch with a minimal PATH, not only an interactive terminal. Confirm initialize, tools/list, resources/prompts where supported, and one real tool call.
- [ ] Run at least three working example prompts: Storage write/read assertion, Pub/Sub publish/receive/ack assertion, and BigQuery query/result assertion. Run through the installed plugin, using supported SDK operations and sample data.
- [ ] Prepare five positive review cases: service/SDK discovery; Storage round trip; Pub/Sub delivery; BigQuery result; diagnosis of a deliberately failed local integration test. Record the prompt, actual tools used, expected result, and observed evidence.
- [ ] Prepare three negative cases: missing CLI/Docker produces actionable setup guidance; an unsupported operation produces a clear limitation without silently using live Google Cloud; requested deletion of unrelated data does not proceed without explicit authorization or enabled management permissions.
- [ ] Also check cold start, a reused runtime, an older runtime, errors, stderr/stdout separation, duplicate direct-MCP installation, removal/reinstall, and update behavior. Upgrade only a qualification-owned environment.
- [ ] Record a short captioned video of real successful workflows. Exclude credentials, personal paths/account details, private code, and runtime internals. The OpenAI review route currently requests five positive cases, three negative cases, and a walkthrough; prepare these before portal submission. [Submission review fields](https://developers.openai.com/plugins/deploy/submission).

**Exit:** Working tools and assertions in installed clients, accurate support matrix, reviewer cases, and a public-safe demonstration.

## Task 6: Finish the setup and reviewer documentation

**Files:** docs/mcp-marketplace-guide.md; docs/mcp.md; README.md; plugins/localcloud/README.md.

- [ ] Put the approved introduction first, then requirements and installation. Explain that native CLI installation and Docker are prerequisites for the plugin, and the runtime may be downloaded at first startup. Do not claim dependency-free one-click installation.
- [ ] Provide separate tested marketplace instructions for Claude and Codex. Codex source registration can use the existing supported command below after the catalog reaches the selected ref:

```sh
codex plugin marketplace add LocalGCloud/localcloud-cli --ref main
```

- [ ] Cover a first prompt and expected result, SDK setup, local endpoint selection, explicit local-only startup, per-project/volume context, runtime upgrades, permission defaults, troubleshooting, removal, and duplicate-server prevention. Preserve the existing direct MCP instructions as a separate option.
- [ ] State the difference between Claude Code, local Cowork, ordinary Claude chat, Codex desktop, and cloud execution. Only claim each surface after its documented capability and actual qualification agree; a plugin listing does not give a remote client access to a user’s local Docker engine.
- [ ] Include applicable license, privacy, support/security reporting, compatibility limits, and reviewer sample-data instructions. With no product login, explain how the reviewer starts the local environment; ask the vendor how its testing-account requirement applies instead of inventing a hosted account.
- [ ] Recheck links and JSON/string limits, then run `git diff --check` and read the guide as a new user. The final user-flow copy should explain useful decisions rather than internal implementation details.

**Exit:** A complete installation-to-first-result guide that matches the actual package and client support.

## Task 7: Publish and submit through the correct business accounts

**Files:** docs/mcp-distribution-log.md; public release assets and catalogs.

- [ ] Refresh vendor policies and attestations at submission time. Confirm the selected repository, folder, branch/commit, publisher, display name, slug, contact, privacy/terms URLs, and verified account identity. These are publication choices, not inferred personal-account defaults.
- [ ] Commit the scoped changes as the business actor, open a PR in the CLI repository, attach it to this chat, and complete required checks. Publish the approved version and release assets through the project’s release process; avoid pushing unrelated local main commits.
- [ ] Submit plugins/localcloud from the public CLI repository in Claude’s publishing UI. Verify the exact reviewed commit, inspect every validation result, fix blockers, provide review evidence, and explain launcher/dependency holds. Agree to terms/attestations only with the applicable authorization. A request for private runtime source or its Dockerfile must stop that disclosure and be discussed with the user.
- [ ] Verify the published GitHub-backed catalogs install from a fresh remote checkout. Record separate install URLs/commands for each client.
- [ ] Contact OpenAI through an official business-account channel or an existing verified OpenAI contact about local MCP public-directory support. Provide the package architecture, requirements, privacy/license links, working examples, and video. Ask for the appropriate local review path. Do not invent a public endpoint or claim an unsupported standard portal submission has succeeded.
- [ ] If OpenAI supplies a local submission route, complete that route with the tested MCP package. Standard public review also requires verified publishing identity and suitable organization/project permissions. If no supported local route is available, record that external dependency and the working GitHub marketplace distribution distinctly.
- [ ] Handle credential entry, CAPTCHA, identity verification, or missing authority at the actual account step after preparation. If a materially new legal attestation needs the owner, show its exact text and source; do not ask routine implementation approvals repeatedly.

**Exit:** Working remote catalogs, Claude submission receipt, and OpenAI local-MCP review status/receipt. This does not yet equal vendor approval.

## Task 8: Resolve reviews and verify public availability

**Files:** docs/mcp-distribution-log.md; only the files implicated by actual findings.

- [ ] Address reviewer feedback within the public CLI scope, retest the affected behavior, and resubmit the exact qualified version. Do not upload runtime implementation to clear a hold.
- [ ] After approval, complete any separate publish action and open each vendor’s public listing. Verify the product name, publisher, description, icon, requirements, links, prompts, and actual installation path.
- [ ] Reinstall the published version in a fresh client and repeat discovery plus one representative workflow. Confirm the installed artifact/version matches the reviewed package.
- [ ] Record live URLs, submission IDs, approval/publish dates, tested versions, and remaining limitations. Keep private reviewer credentials and identity documents outside public files.
- [ ] Give the user the live links and tested install commands. Where public-directory access remains vendor-dependent, state exactly what is pending and what is already installable. Do not call an unapproved submission a listing.

**Exit:** Verified public listings and working installations, or an explicit vendor decision and documented installable alternative for the local-MCP path.

## Plan review checklist

- [ ] User-approved copy and identity choices precede implementation/publication.
- [ ] Every deliverable has an owner file/path and an observable completion check.
- [ ] Public requirements stay simple; tested CLI/runtime/client versions are recorded in release evidence and platform support is accurate.
- [ ] Runtime implementation and Dockerfile remain outside all package inputs and submission artifacts.
- [ ] Applicable license, privacy, annotations, claims, reviewer cases, and account roles are verified before attestations.
- [ ] Each vendor’s catalog, directory submission, approval, and publication are tracked separately.
- [ ] Launcher/package tests are meaningful; docs-only edits do not add redundant tests.
- [ ] No speculative server rewrite, new repository, personal publishing identity, or permission expansion is hidden in the rollout.
