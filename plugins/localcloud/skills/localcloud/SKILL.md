---
name: localcloud
description: Build, test, or debug Google Cloud applications against LocalCloud local services when the user requests a local development workflow.
---

Use the LocalCloud MCP connection to discover the available services, their readiness, SDK connection settings, and operation-level compatibility before choosing an implementation.

1. Identify the user's intended application behavior and project. Inspect the selected local environment through MCP. If the CLI, Docker engine, image, or a required service is unavailable, explain the setup step or documented limit.
2. Obtain SDK settings from LocalCloud. Use standard Google Cloud SDKs and the coding client's normal code execution tools to implement the requested application and tests. For a local workflow, verify SDK endpoints are loopback addresses before application traffic. Stop rather than silently using live Google Cloud.
3. Create uniquely named resources owned by the test. Do not treat project names or the shared runtime as security isolation. Keep application data out of diagnostics unless necessary for the user's requested inspection.
4. Run the integration test and assert the application's observed output. If it fails, inspect only the relevant resources, service readiness, and diagnostics; make a targeted correction and rerun.
5. Report the command, observed result, and relevant compatibility limits. Cleanup may affect only resources created for this test and must respect the client's authorization controls.

MCP management writes and destructive operations are disabled by default. Do not enable them or weaken client approvals to make a workflow pass. SDK application writes are distinct from these management permissions.

Starting MCP may start LocalCloud or reuse an existing runtime. Do not replace, restart, reset, upgrade, or delete an existing environment without the user's authorization. If they request a separate environment, use the CLI's named data-volume configuration and carry the same volume/project through every command.

Local service workflows avoid provisioning Google Cloud resources. Image downloads, update checks, telemetry, and documented outbound features can still use the network; MCP results are handled by the agent client's policies. Validate application release behavior against Google Cloud before production deployment.
