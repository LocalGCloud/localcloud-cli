# LocalCloud marketplace copy — draft for review

Status: approved for implementation on 2026-10-09, with the launch requirements and benefits below. Not yet submitted. This document is the content specification for the [marketplace plan](superpowers/plans/2026-10-09-localcloud-mcp-marketplaces.md).

## Positioning

Lead with the environment an agent can use to build and verify cloud application behavior. Explain MCP as the connection to that environment, then introduce concrete workflows. Use emulation and compatibility limits in the technical details rather than as the opening pitch.

Recommended headline:

> Give your coding agent a cloud environment it can actually work with.

Alternative headlines for review:

- Your agent’s cloud development workspace.
- From cloud code to tests you can run locally.

## Introduction

LocalCloud connects your coding agent to a local environment for building, testing, and debugging Google Cloud applications. Through MCP, the agent can discover services, obtain SDK connection settings, inspect resources, query data, and investigate failures—then use standard Google Cloud SDKs to exercise the application.

Ask it to build a Cloud Storage workflow, test a Pub/Sub consumer, or validate a BigQuery query. Your agent can run the code, check the results, and iterate against local services without first provisioning a live Google Cloud project.

Start the local environment with one command, create projects for experiments and integration tests, and run local service workflows with zero Google Cloud service charges. First startup may download the Docker image; local project capacity depends on your machine.

## Listing fields

| Field | Proposed content |
| --- | --- |
| Display name | LocalCloud |
| Plugin identifier | localcloud |
| Publisher | LocalCloud; use the verified LocalCloud business identity where required |
| OpenAI subtitle | Local cloud for coding agents |
| Common short description | Build, test, and debug Google Cloud applications with a local cloud environment your coding agent can inspect through MCP. |
| Category | Developer Tools, subject to the platform’s available categories |
| Website | https://local.cloud/ |
| Documentation | https://local.cloud/docs/mcp/ |
| Support | https://github.com/LocalGCloud/localcloud-cli/issues |
| Privacy | https://local.cloud/docs/privacy/ — verify that the current policy covers the CLI, runtime, retention, and user controls before submission |
| Terms | https://local.cloud/license/ — user approved aligning the CLI/plugin to this Public Preview License on 2026-10-09; apply the alignment during implementation |
| Icon | Reuse docs/assets/localcloud-mcp-icon.png |

`localcloud` is a proposed plugin identifier, not a replacement for the accepted MCP Registry identifier `cloud.local/localcloud`. Confirm names before creating a marketplace draft with a lasting identity.

OpenAI requires product names without an MCP/Plugin suffix and descriptions without pricing promotion. The OpenAI listing uses LocalCloud and focuses on functionality. The Claude README and launch guide can explain the zero Google Cloud service charges for local workflows; OpenAI metadata uses the factual equivalent, running without provisioning Google Cloud resources, to respect its promotion rule. LocalCloud pricing is unchanged; there is no Pro/paid upgrade message. [OpenAI plugin guidelines](https://developers.openai.com/plugins/plugin-guidelines).
I think from a requirement perspective, this is like a brand new launch. So I don't think we have to keep mentioning like a logo cloud CLI.19 or newer and cloud runtime 0.15 and things like that. So let's just mention requirement is a Docker engine, localcloud CLI, and localcloud image Docker image, right? That's it. So keep it simple. We can mention that it's available in native Mac OS and Linux platform, right? Those are fine. Other thing looks good, but one point we need to add is about in an introduction or benefit somewhere that it provides these services so that there will be zero cloud cost, an instant setup, unlimited projects, and things like that.
## Full marketplace description

Give your coding agent a cloud environment it can actually work with.

LocalCloud provides a local development environment for Google Cloud applications and connects it to your agent through MCP. The agent can discover available services, obtain connection settings for standard SDKs, inspect application resources, query data, and diagnose failures.

Use it to work through concrete development tasks:

- Build a Cloud Storage upload-and-read workflow and verify the stored content.
- Test a Pub/Sub publisher and consumer, including receiving and acknowledging a message.
- Run a BigQuery query against test data and assert the expected result.
- Investigate a failed integration test using local service status, resource inspection, and diagnostics.

MCP supplies context and inspection tools. Your coding agent uses its normal coding and execution tools, together with Google Cloud SDKs, to implement the application and exercise supported operations. LocalCloud runs its service environment in Docker on your machine; the plugin connects to it through the LocalCloud CLI.

Requirements: a working Docker engine, the LocalCloud CLI, and the LocalCloud Docker image. The CLI runs natively on macOS and Linux and obtains the image when needed. Use the current CLI and image together; detailed tested versions belong in release evidence.

The runtime is a development and testing environment. Compatibility varies by service and operation, so validate release behavior against Google Cloud before deploying an application to production. Setup, runtime startup, telemetry, update checks, and other documented outbound behavior are described in the linked guides; local execution is not a promise of zero network traffic. MCP results are also shared with the agent client according to that client’s data practices.

Read the setup guide: https://local.cloud/docs/mcp/

Editorial note: this description is ready for review, not evidence that every example or marketplace surface has already passed qualification. Apply the approved license alignment and include the governing link before publication. Do not put this editorial note in a listing.

## Starter prompts

These fit OpenAI’s 128-character limit and provide three concrete workflows for both listings. Run each through the installed plugin before calling it a working example. [OpenAI review fields](https://developers.openai.com/plugins/deploy/submission), [Anthropic directory policy](https://support.claude.com/en/articles/13145358-anthropic-software-directory-policy).

1. Build and test a Cloud Storage upload-and-read workflow with LocalCloud using the standard Python SDK.
2. Test a Pub/Sub publisher and consumer with LocalCloud, then verify the message is received and acknowledged.
3. Run a BigQuery query against LocalCloud test data and assert the expected result.

## Expanded example: build a feature and prove it works

> Use LocalCloud to build and test a Python workflow that uploads a JSON object to Cloud Storage and reads it back. First discover the available service and SDK connection settings. Create resources owned by this test, run the integration test, and assert that the returned JSON matches the original. Show the test command and result. Use local service endpoints; stop and explain if a required operation is unsupported. Clean up only the resources created for this test.

Proposed agent workflow:

1. Discover service readiness, endpoints, SDK settings, and documented limitations through MCP.
2. Write application code and an integration test using the standard SDK.
3. Run the test against LocalCloud through the coding client’s execution tools.
4. Inspect the relevant local resources or diagnostics if the assertion fails.
5. Make a targeted correction, rerun the test, and report the observed result.

A second, more ambitious demo can connect a Storage upload to a Pub/Sub consumer after that combined workflow passes qualification. Do not advertise an untested combined demo as a proven result.

## Setup guide draft

The final guide will have separate Claude and Codex marketplace installation sections, a dependency check, a first working example, troubleshooting, and removal steps. Until those packages are tested and published, the existing CLI integration is:

```sh
brew install LocalGCloud/tap/localcloud
```

Have Docker running and the LocalCloud CLI installed before connecting. The CLI downloads the LocalCloud image when needed. For an intentionally loopback-bound environment, start LocalCloud with:

```sh
lc start --local-only
```

Claude Code:

```sh
lc mcp install --client claude-code
```

Codex:

```sh
codex mcp add localcloud -- "$(command -v localcloud)" mcp
```

These are direct MCP configuration commands, not marketplace installation commands. Choose one integration route per client to avoid duplicate LocalCloud servers. A running older runtime is not silently upgraded by installing the CLI; follow the MCP guide’s explicit upgrade procedure when needed. Linux/native archive installation remains documented in docs/mcp.md.

## Review boundaries

- Keep public changes and release packaging within this CLI project. No new repository, runtime source, runtime Dockerfile, image export, or runtime internals in submissions.
- Use cloud-orbitor and orbitor.cloud@gmail.com for publisher actions. Do not use personal social accounts or switch to a personal publisher identity silently.
- The user approved alignment with the current Public Preview License on 2026-10-09. Apply that agreement to the CLI/plugin during implementation before distributing the plugin license or stating the permitted audience.
- Do not claim complete Google Cloud parity, production suitability, zero egress, universal client support, open-source licensing, customer adoption, or a service/tool count without matching evidence.
- Keep MCP management writes/destructive actions disabled by default. SDK application writes still occur when the user asks to run a workflow.
- Public copy must distinguish plugin installation, tool availability, submission, approval, and a live public listing.
- The user approved proceeding on 2026-10-09, using Cloud Orbiter accounts and the simplified requirements/benefits above.
