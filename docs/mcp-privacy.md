# LocalCloud CLI and MCP data reference

This reference describes the public CLI and marketplace plugin, reviewed on 2026-10-09. The runtime and website have separate behavior described in [LocalCloud's privacy and outbound data reference](https://local.cloud/docs/privacy/). The agent client also has its own data policies.

## MCP and local application data

The plugin launcher finds the installed LocalCloud CLI and starts its stdio MCP bridge. It does not add an account, collect a conversation history, or forward the host's unrelated credentials. MCP requests and results pass between the agent client and the selected local runtime. Requested resource contents, queries, and diagnostics can therefore be visible to the agent client; request only data appropriate for that client.

The runtime stores service data in its configured Docker volume. Data remains there until deliberately removed. Removing the plugin does not remove that volume. Plugins, client settings, and local CLI state remain until removed through their respective configuration mechanisms.

## CLI telemetry

The CLI's lifecycle commands can send operational events to LocalCloud's PostHog project, normally at us.i.posthog.com. Startup/restart/reset failures can produce bounded, scrubbed error signatures. Heartbeats contain a generated pseudonymous identifier, timestamps, CLI/release/platform information, enabled services, selected runtime options, and command/error counts. A pseudonymous identifier is not a guarantee of anonymity. The network recipient can also process request metadata such as IP addresses.

Purpose: diagnosing startup problems and understanding product reliability and usage. Recipients: LocalCloud and its analytics processor, PostHog. User-configured event destinations can change the recipient.

The CLI keeps a local telemetry state file, counters, and at most five queued events. Queued events are cleared after successful delivery or a non-retryable rejection; otherwise the queue is bounded by count rather than a time-based retention limit. State remains until removed or an applicable opt-out clears it.

Opt out of CLI telemetry before running commands with `LOCALCLOUD_TELEMETRY=false` or `DO_NOT_TRACK=1`. Configuration-level opt-out also applies. The runtime's telemetry and other outbound paths have their own controls and caveats; a CLI opt-out is not a promise of zero runtime events or network traffic.

## Other network activity

Image downloads and update checks contact the configured image registry. User-selected runtime modes, endpoints, and application workflows may add outbound traffic. Review the runtime reference before selecting those modes. Using generated local SDK endpoints avoids provisioning Google Cloud resources for the documented local workflows.

## Retention and requests

Local application data is retained until the user deletes it. Local telemetry queues and client caches follow the behavior above. The hosted analytics retention schedule and deletion process must be verified against the deployed LocalCloud/PostHog configuration before a marketplace privacy attestation is made; this implementation reference does not establish a hosted retention promise.

For privacy questions or deletion requests, contact orbitor.cloud@gmail.com. Provide the relevant surface and, if appropriate, its pseudonymous telemetry identifier; do not send passwords, tokens, application/customer data, or a full conversation transcript.

For security issues, use [LocalCloud's security reporting contact](https://local.cloud/security/). Read the [Public Preview License](../LICENSE) for permitted use. This reference is not a claim of regulatory certification or production isolation.
