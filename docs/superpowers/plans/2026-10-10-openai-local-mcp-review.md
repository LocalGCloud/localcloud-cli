# LocalCloud OpenAI Local MCP Review Plan

> Steps use checkbox (`- [ ]`) syntax so an executor can track progress task by task.

**Goal:** Obtain a concrete public-directory review route for LocalCloud's local MCP integration in Codex desktop and a verified publisher identity that preserves the LocalCloud brand.

**Architecture:** Keep the MCP connection local: the installed CLI launches a stdio bridge to the published Docker runtime. Seek product-specific review before uploading. Prepare a finite, individually reviewable tool surface if OpenAI accepts local submissions; do not turn LocalCloud into a hosted service or conceal its MCP dependency in a skills-only upload.

**Tech Stack:** Existing public CLI, MCP/plugin manifests, public native releases, published Docker image, current OpenAI documentation, and the existing MCP/packaging tests.

**Spec:** [Approved marketplace content and owner constraints](../../mcp-marketplace-copy.md), the owner-supplied support reply received on 2026-10-10, and the official sources below. The private case identifier and draft correspondence stay with the maintainer's operational evidence.

## Global Constraints

- Target: public LocalCloud MCP listing in Codex desktop. GitHub installation is available separately; it is not public-directory approval.
- Public name: LocalCloud. Publisher actions: cloud-orbitor. Contact: orbitor.cloud@gmail.com. Do not select a verified personal identity if its name would appear publicly; do not invent a registered business.
- Requirements: Docker engine, LocalCloud CLI, LocalCloud Docker image; native macOS/Linux. Record exact tested versions in review evidence.
- Scope: public CLI/plugin only. No private runtime source, build Dockerfile, image exports, or private credentials in the submission.
- Documentation URLs are HTML guides, not MCP endpoints. Do not supply them, local addresses, tunnels, or dummy servers to the remote submission flow.
- Skills-only submission is not a substitute for the MCP product. The current portal cannot add MCP to an existing skills-only plugin; do not create a misleading interim listing.
- Keep the existing Claude submission and normal GitHub installation intact. Discuss any new client-facing profile or reduced tool surface with the owner before implementing it.
- Support's reply is guidance, not eligibility confirmation, acceptance, a review intake, or approval.

## Current decision gates

| Gate | Evidence | Required answer |
| --- | --- | --- |
| Local review route | Support and the conversion guide direct local-execution integrations to an OpenAI contact/product-specific review. | Is local stdio plus Docker currently eligible for a Codex-desktop public listing? Which team/intake accepts it, and what is the package format? |
| Public publisher | The portal says it displays the selected verified developer identity; remote review guidance associates individual/business verification with the corresponding public name. | Can a legitimately verified individual publish with LocalCloud as the public publisher without displaying the individual's name? If not, what legal business evidence is required? |
| Reviewed operations | Current plugin guidelines require model-callable operations to be individually exposed. The public MCP reference documents `localcloud_call_api` as a catalog/operation-ID executor. | What finite tool set can be accepted for the local review, and which Codex surfaces will be tested? Do not assume generic API discovery becomes compliant through annotations alone. |

## Task 1: Obtain a concrete review referral and identity answer

**Files:** Private maintainer correspondence outside the repository; update `docs/mcp-distribution-log.md` when a new decision or intake exists. No CLI changes.

**Inputs:** Owner-supplied support reply and the saved reply draft. **Outputs:** Written eligibility/intake, package/reviewer requirements, and a public-name decision.

- [x] Read the support reply and check the current official conversion, submission, tool-policy, and remote-review documentation on 2026-10-10.
- [x] Prepare a reply requesting referral to the Codex/Plugins team responsible for local-execution eligibility, the exact intake/package format, and whether desktop-only availability is supported.
- [x] Send the prepared reply in the existing brand-account support conversation on 2026-10-10 and verify that it persists after reload. Requested that the case remain open until the concrete questions are answered.
- [x] Ask exactly which verified identity name appears in the publisher/byline fields. Website or GitHub ownership can supplement the request, but is not documented as an identity-verification replacement.
- [x] Ask whether a finite CLI-enforced tool surface can be evaluated against the published runtime image without supplying private runtime implementation.
- [ ] Record the actual answer. If local submissions are unavailable, request the supported referral or availability-notification route and continue existing GitHub distribution. If only a legal business can publish under the brand, bring that choice to the owner before verification.

**Exit:** A named intake/contact or explicit unavailability decision, plus an exact supported publisher-name policy. Repeated general documentation links do not satisfy this exit.

## Task 2: Prepare the public-only reviewer dossier

**Files:** Inspect `plugins/localcloud/`, `docs/mcp.md`, `docs/mcp-privacy.md`, and `scripts/build-marketplace-plugin.py`. Save sanitized evidence outside the public package. No private runtime reads.

**Inputs:** Current published artifacts and Task 1's accepted review requirements. **Outputs:** Version-bound architecture, package, tool inventory, and reproducible reviewer cases.

- [ ] Record repository commit, CLI release/hash, plugin version, runtime image/digest, client version, and tested platform. Previous qualification is evidence only for its recorded artifact versions.
- [ ] Supply a short architecture description: Codex -> installed CLI stdio -> local Docker runtime. Describe service discovery, SDK settings, resource inspection, queries, readiness, and diagnostics. Explain that local facades emulate Google Cloud APIs; this is not a connector accessing a user's real Google Cloud account.
- [ ] Export the actual initialized tool catalog, input/output schemas, annotations, MCP instructions, resources, and prompts. Review their content; do not infer compliance from a successful initialize or JSON parse.
- [ ] Disclose startup effects (image download/runtime/project setup), management permissions, and SDK application writes separately. Default management-write flags are not a guarantee that every workflow is read-only or already has a client confirmation step.
- [ ] Document local data retention, operational telemetry, processor/deletion disclosures, and what MCP data reaches the user's agent client. Use the scoped privacy policy rather than a guessed retention interval.
- [ ] Package only the public plugin allowlist. List every archive entry and verify that all referenced files are present; provide setup instructions using the published runtime image.

**Exit:** A dossier the approved reviewer can reproduce with public artifacts, with no undocumented endpoint or hidden dependency.

## Task 3: Resolve the reviewed tool surface after route confirmation

**Potential files:** `src/localcloud_cli/mcp_stdio.py`, `src/localcloud_cli/cli.py`, `tests/test_mcp_stdio.py`, `tests/test_marketplace_plugin.py`, `plugins/localcloud/mcp.json`, plugin manifests/skill, and `docs/mcp-marketplace-guide.md`. Touch only files required by the agreed surface and submission format.

**Inputs:** Task 1's written route and the Task 2 inventory. **Outputs:** An owner-approved, enforceable reviewed surface, or a documented rejection of a CLI-only approach.

- [ ] First propose the smallest useful finite surface: service discovery, bounded SDK configuration, readiness/compatibility, resource inspection, and diagnostics. Retain a query or management operation only when its actual bounds and side effects can be reviewed individually.
- [ ] Exclude the generic `localcloud_call_api` executor from the proposed reviewed surface. For a needed operation, expose it individually with an explicit bounded schema; do not permit arbitrary operation-ID, HTTP method, path, or destination selection behind one tool.
- [ ] If a separate CLI profile is needed, agree its user-facing invocation and behavior with the owner before changes. Enforce it in both `tools/list` and `tools/call`; merely hiding a tool is insufficient. Audit resources/prompts/instructions so they do not direct users back to excluded execution paths.
- [ ] Before editing, write tests that fail for direct calls to excluded names/operations, invalid inputs and out-of-scope destinations. Verify blocked calls never reach the runtime. Test the complete paginated catalog and that the normal existing integration retains its expected behavior.
- [ ] Verify exact descriptions, input/output shapes, all three explicit boolean safety annotations, and actual client confirmation for any irreversible operation. An annotation is a client hint, not authorization enforcement.
- [ ] Run the affected tests and plugin packaging checks. If a required fix resides in the private runtime, report the boundary to the owner; do not read or change that source under this CLI-only plan.

**Exit:** No generic execution path bypasses the reviewed surface; no unsupported compliance claim; existing integrations remain functional. Any client-facing changes have owner agreement.

## Task 4: Qualify and submit through the confirmed route

**Files:** Existing MCP/packaging tests, public guides/manifests as required, private qualification evidence, and `docs/mcp-distribution-log.md`.

**Inputs:** Verified route/publisher and exact reviewed package. **Outputs:** Actual submission receipt; public listing only after separate review and publication.

- [ ] Use a clean client profile/project and task-owned Docker volume. Run five positive cases: local discovery/settings, Storage round trip, Pub/Sub delivery, BigQuery result, and diagnosis of a deliberately failed local test.
- [ ] Run negative cases for missing CLI/Docker, unsupported operations, excluded executor calls, and denied destructive actions without deleting unrelated data. Record expected and actual results; model-level behavior is not proved by a launcher unit test.
- [ ] Confirm localhost-only endpoints for the selected test environment and no fallback to real Google Cloud. Keep data cleanup scoped to resources created by the qualification.
- [ ] Verify Apps Management write access and the exact public publisher identity. Owner enters verification documents directly into the official portal.
- [ ] Adapt the package to the confirmed local-review format and upload it through the supplied intake. Resolve actual findings before any final submission attestation.
- [ ] Record draft, submission, approval, and publication separately. After approval, inspect the actual public publisher/name/install behavior and test a fresh installation before marking the entry live.

**Exit:** Verified public listing and working local installation, or a precise external dependency/decision with the GitHub installation still available.

## Official sources checked 2026-10-10

- [Local MCP conversion and product-specific review](https://developers.openai.com/plugins/guides/submit-claude-plugin).
- [Upload flow, displayed developer identity, and skills-only limitations](https://developers.openai.com/plugins/deploy/submission).
- [Tool exposure, annotations, data handling, and metadata policy](https://developers.openai.com/plugins/plugin-guidelines).
- [Remote review scope, verification, and permissions](https://developers.openai.com/plugins/deploy/app-review). Its remote endpoint procedure is not a local-stdio intake.

## Current status

The owner authorized the prepared reply, which was sent in the existing support conversation on 2026-10-10 and verified after reload. LocalCloud remains the requested public publisher; orbitor.cloud@gmail.com is the contact. No identity verification, tool-profile implementation, package upload, or directory approval has been completed by this follow-up. Await a concrete review intake and publisher-name policy before choosing a reviewed profile or submitting a package.
