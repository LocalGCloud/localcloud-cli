# Default Runtime Attachment and Readiness Design

## Status

Design approved. Implementation has not started.

## Goal

Make `localcloud start` reliably reuse the LocalCloud container selected by data-volume identity, wait until the runtime control plane is operational, and return `already_running` when that same runtime was already running.

The default Docker CLI integration suite must exercise the real `localcloud-data` runtime without changing its Docker resources or service data. The explicitly opted-in full OLAP acceptance test remains isolated because its purpose requires destructive lifecycle and data operations.

## Existing Contract to Preserve

The named Docker volume mounted read-write at `/var/lib/localcloud` is the runtime identity. The default identity is `localcloud-data`.

Runtime selection remains independent of container and network names:

- one compatible container using the selected volume is the selected runtime;
- a running selected container is attached and reused;
- a stopped selected container is started in place;
- no selected container permits managed creation for that volume;
- more than one container using the volume is a collision;
- an incompatible container using the volume blocks creation; and
- selecting a different volume selects a different runtime.

`Controller.start()` already resolves the selected volume before creating anything. When the selected container is running and healthy, its intended result is `already_running`. This design does not add a second identity, name-based fallback, or special case for the default container name.

## Verified Failure Modes

Two independent failures currently prevent the intended behavior from working reliably on macOS:

1. The host-port probe sets `SO_REUSEADDR`. On macOS, that probe can bind `127.0.0.1:24080` even while Docker Desktop publishes `0.0.0.0:24080`. The CLI consequently requests canonical ports for a different-volume container, and Docker rejects the create with `port is already allocated`.
2. Docker health is not sufficient operational readiness. A selected container can report healthy while the Java MCP project catalog or automatic seed work is unavailable. `start` currently performs the project operation once and exposes a generic wrapper error instead of waiting for transient startup.

A running container is therefore not automatically an operational LocalCloud runtime. `already_running` is returned only after the selected runtime passes the control-plane checks below.

## Start State Machine

`localcloud start` keeps one state machine keyed by the selected data volume.

### No selected container

Create a managed container, network, and volume according to the existing ownership rules. Wait for Docker and LocalCloud readiness, initialize the requested project context, record the active runtime, and return `started`.

### Selected container is stopped

Start the same immutable container ID. Never replace an attached container. Wait for readiness, initialize the requested project context, record the active runtime, and return `started`.

### Selected container is running

Reuse the same immutable container ID. Do not create, replace, restart, relabel, or reconnect it merely because `start` was invoked. Wait for readiness, initialize the requested project context when necessary, record the active runtime, and return `already_running`.

### Selected managed container has configuration drift

Retain the existing managed-replacement rules. Replacement is allowed only when current ownership validation permits it. Attached runtime drift remains diagnostic and never causes replacement.

### Selected runtime is not yet ready

Keep the same immutable container ID and wait within the shared readiness deadline without restarting it, regardless of ownership. If readiness does not recover, fail with the existing actionable health diagnostics. An explicit `restart` command retains its separate lifecycle semantics.

## Operational Readiness

All post-container startup work uses one monotonic deadline of 60 seconds. The deadline is an upper bound for the complete readiness phase, not a fresh timeout for every request.

Readiness proceeds in this order:

1. Require a running selected container with a published gateway endpoint.
2. Require the existing `/health` check to succeed.
3. Poll the Java MCP project catalog with short bounded requests until the control plane responds successfully.
4. Ensure the requested project using the existing project semantics.
5. Re-read the project catalog until the requested project is visible.
6. If this invocation must apply a configured seed, invoke the authoritative seed operation once and require its successful result.

The readiness poll retries only transient startup failures, including connection failures, timeouts, HTTP `408`, HTTP `429`, and HTTP `5xx` responses. HTTP `4xx` responses other than `408` and `429`, malformed protocol responses, invalid project requests, invalid seed data, and authoritative tool errors fail immediately.

Project and seed mutations are not blindly retried. Read-only catalog checks may be retried. Project creation remains idempotent through a read-before-create and read-after-create sequence. A configured seed operation is sent at most once after the control plane is ready so an ambiguous transport failure cannot duplicate non-idempotent seed effects.

A deadline failure raises a dedicated readiness error containing:

- data-volume identity;
- immutable container ID;
- selected project;
- timeout duration;
- readiness phase; and
- the last underlying error with HTTP status when available.

No readiness failure updates the active-runtime record. A failure against an attached container never removes or replaces that container.

## Java Client Error Fidelity

The Java client must retain enough transport information for readiness classification. Wrapped MCP and project-API failures include the request URL, method, HTTP status when present, and original cause. Higher-level project and seed errors preserve those structured details rather than reducing them to a generic message.

This distinction is required because a connection refusal during startup is retryable while `403 Forbidden` is an operational or configuration failure that waiting cannot repair.

## Host-Port Allocation

Different data volumes may run concurrently. Canonical host ports remain preferred when the complete exposed TCP port set is genuinely free. If any canonical port is occupied, Docker receives dynamic host-port requests for the complete set so endpoint relationships stay consistent.

The availability probe must not set `SO_REUSEADDR`. It performs a strict bind to `127.0.0.1`; an address-in-use result marks the canonical set unavailable. Docker remains the final allocation authority, and an allocation race is reported through the existing container-create failure path.

Resolved endpoint maps continue to come from Docker inspection, never from requested port values.

## Default Docker CLI Integration Contract

Every normal Docker-marked CLI integration test uses the default values:

- data volume: `localcloud-data`;
- image: `jaysen2apache/localcloud:latest` unless `LOCALCLOUD_IMAGE` explicitly overrides it;
- default project and user; and
- the endpoint map inspected from the selected default container.

The normal Docker CLI suite requires one compatible default-volume container to be running. An absent, stopped, incompatible, or colliding default runtime is a failed prerequisite with a specific diagnostic. Unit tests and the Linux release-artifact lifecycle job retain creation and stopped-container coverage.

Before invoking `start`, the integration prerequisite helper performs only read-only polling until the default CLI project and the built-in `local-project` seed data are visible, then snapshots the selected Docker identities. If that prerequisite does not become ready within 60 seconds, the test fails without invoking a command that could create project or seed data. Production `start` readiness is covered independently by focused controller tests.

The suite waits for up to 60 seconds using read-only checks and then verifies:

1. `localcloud start` returns `already_running` with the pre-existing immutable container ID.
2. `status` reports the same data volume, container ID, image identity, mount, endpoint map, and ownership.
3. `env` and generated MCP configuration target that same runtime and default project context.
4. The Java MCP project catalog is readable and contains the default project.
5. Read-only service inventory calls succeed.
6. The deterministic pre-seeded GCS buckets and BigQuery datasets in `local-project` pass concrete inventory and data-plane HTTP checks.
7. Docker container, network, and volume identities are unchanged before and after the checks.

The normal Docker CLI suite must not invoke:

- project creation or reset;
- seed or scenario application;
- checkpoint or restore;
- container restart or stop;
- managed replacement;
- Docker resource creation, relabeling, or removal; or
- data-plane writes.

Polling repeats only the read-only project, inventory, and HTTP checks. Timeout output identifies the last missing or unavailable project, service, or seeded resource.

## Full OLAP Acceptance Exception

`tests/integration/test_full_olap_acceptance.py` remains explicitly opt-in through `LOCALCLOUD_RUN_FULL_OLAP=1`. It keeps a unique ephemeral data volume because fault injection, scenario application, checkpoint/restore, reset, and cleanup are inherently mutating.

This test is not part of the normal read-only default-runtime contract. Its opt-in gate and cleanup obligations remain unchanged except for fixes required by the strict host-port probe.

## Unit and Contract Coverage

Focused tests must prove:

- a running same-volume runtime returns `already_running` without `create`, `start`, `restart`, replacement, or Docker mutation;
- a stopped same-volume runtime starts the same container ID;
- absence of any same-volume runtime permits managed creation;
- a different volume can request dynamic ports while the default runtime owns canonical ports;
- an occupied TCP port is not reported free on macOS-compatible socket semantics;
- transient MCP startup failures recover within the shared deadline;
- the deadline is deterministic under a fake monotonic clock;
- HTTP `4xx` failures other than `408` and `429` are not retried;
- project creation is not duplicated across readiness retries;
- configured seed application occurs at most once;
- readiness failures retain the last structured transport details; and
- the default live CLI integration path performs only the approved read-only calls.

The integration test should snapshot relevant Docker identities before and after execution to make accidental lifecycle mutation observable.

## Release Gates and Documentation

The hosted GitHub source gate remains `pytest -m "not docker"` and requires no local runtime. The Linux AMD64 release-artifact job remains the blocking managed creation and lifecycle smoke test.

The strict local pre-tag suite now requires a running, qualified, operational, and pre-seeded default LocalCloud runtime. `RELEASING.md` must state that requirement explicitly rather than saying only that Docker and the image must be present.

README runtime-identity documentation must state that `start` reuses the selected same-volume container, waits up to 60 seconds for operational readiness, and returns `already_running` only after the requested project context is usable.

## Non-goals

- Introducing container-name or label identity alongside data-volume identity.
- Returning `already_running` for a container whose control plane is unusable.
- Making permanent authorization or protocol errors retryable.
- Allowing two containers to mount the same data volume.
- Making the normal release CLI tests mutate the operator's default runtime or data.
- Converting the explicitly opted-in full OLAP acceptance path into a read-only test.
- Removing project creation or explicit configured-seed behavior from normal CLI usage.
