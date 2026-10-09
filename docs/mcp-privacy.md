# LocalCloud CLI and MCP privacy policy

Effective and last reviewed: 2026-10-09. This policy describes data handled by the public LocalCloud CLI and marketplace plugin, operated by the LocalCloud project maintainers. The runtime and website have separate behavior described in [LocalCloud's privacy and outbound data reference](https://local.cloud/docs/privacy/). The agent client also has its own data policies.

## MCP and local application data

The plugin launcher finds the installed LocalCloud CLI and starts its stdio MCP bridge. It does not add an account, collect a conversation history, or forward the host's unrelated credentials. MCP requests and results pass between the agent client and the selected local runtime. Requested resource contents, queries, and diagnostics can therefore be visible to the agent client; request only data appropriate for that client.

Persistent runtime data is stored in its configured Docker volume and can remain until deliberately removed. Runtime storage modes and service configuration determine other data lifetimes. Removing the plugin does not remove that volume. Plugins, client settings, and local CLI state remain until removed through their respective configuration mechanisms.

## CLI telemetry

The CLI's lifecycle commands can send operational events to LocalCloud's PostHog project, normally at us.i.posthog.com. Startup/restart/reset failures can produce bounded, scrubbed error signatures. Heartbeats contain a generated pseudonymous identifier, timestamps, CLI/release/platform information, enabled services, selected runtime options, and command/error counts. A pseudonymous identifier is not a guarantee of anonymity. The network recipient can also process request metadata such as IP addresses.

Purpose: diagnosing startup problems and understanding product reliability and usage. Recipients: LocalCloud and its analytics processor, PostHog. User-configured event destinations can change the recipient.

The CLI and MCP plugin send operational health, usage, and error telemetry; they do not integrate session recording or screen capture. PostHog's [privacy policy](https://posthog.com/privacy) explains its processing of hosted information.

The CLI keeps a local telemetry state file, counters, and at most five queued events. Queued events are cleared after successful delivery or a non-retryable rejection; otherwise the queue is bounded by count rather than a time-based retention limit. State remains until removed or an applicable opt-out clears it.

Opt out of CLI telemetry before running commands with `LOCALCLOUD_TELEMETRY=false` or `DO_NOT_TRACK=1`. Configuration-level opt-out also applies. The runtime's telemetry and other outbound paths have their own controls and caveats; a CLI opt-out is not a promise of zero runtime events or network traffic.

## Other network activity

Image downloads and update checks contact the configured image registry. User-selected runtime modes, endpoints, and application workflows may add outbound traffic. Review the runtime reference before selecting those modes. Using generated local SDK endpoints avoids provisioning Google Cloud resources for the documented local workflows.

## Retention and requests

Persistent local application data can remain until the user deletes it; runtime and service storage policies can affect particular records. Local telemetry queues and client caches follow the behavior above.

LocalCloud's hosted operational analytics currently use PostHog Cloud's Free plan. PostHog documents a [one-year event query window](https://posthog.com/docs/data/events-retention) for that plan. This limits which events queries return; it is not an automatic physical-erasure deadline. We do not promise that events are erased after a shorter number of days or when the query window expires. Session-recording retention is separate and does not describe the CLI/MCP telemetry.

For privacy questions or deletion requests, contact orbitor.cloud@gmail.com. Provide the relevant surface and, if appropriate, its pseudonymous telemetry identifier; do not send passwords, tokens, application/customer data, or a full conversation transcript.

LocalCloud reviews deletion requests, identifies the records the request concerns, and uses the processor's [data-deletion controls](https://posthog.com/docs/privacy/data-storage#data-deletion) for applicable hosted records. Local Docker data remains under the user's control and can be removed locally. No production data is deleted merely by installing or removing this plugin.

For security issues, use [LocalCloud's security reporting contact](https://local.cloud/security/). Read the [Public Preview License](../LICENSE) for permitted use.
