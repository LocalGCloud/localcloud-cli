# Docker Command Planning and Debug Design

## Context

The lifecycle debug path currently reconstructs and prints a synthetic `docker run` command before starting or restarting an existing container. That command is not executed, repeats configuration already encoded in environment variables and labels, and can describe a managed container configuration that an attached container does not have. It is therefore both noisy and misleading.

Port reporting has a separate accuracy problem. Runtime endpoint discovery reads only `NetworkSettings.Ports`, drops host IP and protocol, and reconstructs every effective binding as TCP. Stopped containers may expose only configured bindings through `HostConfig.PortBindings`, while transparent networking also includes UDP. This causes debug output to omit or misstate published ports.

The Docker SDK has no dry-run mode. A safe preview must be implemented above the SDK by planning operations before any mutation and rendering the same plan that normal execution consumes.

The review also found an independent lifecycle bug: `restart` creates a missing runtime but does not initialize its returned status.

## Goals

- Make Docker diagnostics report the operation actually selected and executed, once.
- Show requested and resolved port bindings with host IP and protocol.
- Add strict read-only `--dry-run` support to every mutating lifecycle command: `start`, `restart`, `reset`, and `stop`; retain cleanup's existing dry-run behavior.
- Render exact, copyable Docker commands from the same values used by SDK execution.
- Include user-configured environment values in exact dry-run output, as explicitly selected.
- Add useful `--debug` diagnostics for configuration selection, lifecycle decisions, Docker resources, ports, execution, and readiness without repeating large environment and label payloads.
- Fix restart when no runtime exists.

## Non-goals

- Do not execute Docker CLI subprocesses; normal execution continues through Docker SDK for Python.
- Do not change runtime configuration labels, environment contracts, ownership rules, endpoint-map output shape, or data-volume safety rules.
- Do not make dry-run reserve ports, pull images, create temporary resources, call LocalCloud mutation APIs, seed data, or write active runtime state.
- Do not make debug output a second exact command preview. Exact output belongs to `--dry-run`.
- Do not add dry-run behavior to read-only commands such as `status`, `logs`, or `env`.

## Operation Planning Model

`docker_runtime.py` will define immutable plan values for Docker mutations. A run plan owns the complete values required by `containers.run`: image, container name, network, memory limit, mounts, published ports, environment, and labels. Its SDK execution and shell rendering consume those same fields.

Other Docker mutations use explicit operation values for start, restart, stop, container removal, network removal, volume removal, network creation, and volume creation where applicable. A lifecycle plan is an ordered sequence of Docker and LocalCloud operations plus an action name and reason.

Controller methods select the lifecycle branch before mutation:

- `start`: create, replace, start stopped container, wait for an already-running unready container, or no-op.
- `restart`: create, replace, or restart.
- `stop`: stop running container or no-op.
- project `reset`: LocalCloud project reset operation.
- all-projects `reset`: the existing managed runtime purge/recreate sequence.

Normal execution consumes the selected plan. Dry-run renders it and returns before the first mutation. This keeps branch selection, command rendering, and SDK arguments aligned without duplicating controller decision logic.

Planning remains fail-closed. It reuses current validation, immutable container identity, ownership classification, collision detection, config drift, and persistent-volume preservation rules. Planning may inspect Docker resources and the local image cache but cannot mutate them.

## Dry-run CLI Contract

`--dry-run` is added to `start`, `restart`, `reset`, and `stop`. `cleanup --dry-run` retains its existing contract.

A lifecycle dry-run:

1. Loads and validates effective configuration.
2. Resolves current Docker resources through read-only inspection.
3. Builds the complete ordered operation plan.
4. Prints the plan in execution order.
5. Returns without acquiring file locks, executing an operation, or writing state.

Skipping the normal data-volume file lock is intentional: creating or changing a
lock file would violate the strict read-only contract. A preview can become
stale if another process changes Docker concurrently; a later normal command
always plans again from fresh state and revalidates identity before mutation.

Rendered `docker run` commands are minimal operator equivalents: they use shell-safe quoting and omit image-inherited environment, image labels, and CLI-internal management metadata. Selected config and user seed files appear as read-only bind mounts. Non-Docker mutations are explicit descriptive lines, so the output never implies that a Docker command covers LocalCloud API work.

No-op plans print the selected action and reason rather than returning an empty result.

`--dry-run --pull` fails with a specific usage error. Pulling would violate the strict read-only contract, while planning from the old local image would not be an exact preview of the requested post-pull run. If a required image is absent locally, dry-run fails with an actionable error instead of pulling it or emitting an incomplete command.

Dry-run output is native text on stdout. Progress and optional debug diagnostics remain on stderr. Normal concise/verbose lifecycle payloads remain unchanged.

## Debug Contract

`--debug` reports configuration, resource, execution, and readiness events on
stderr. For each lifecycle plan it emits one minimal shell-quoted `docker run`
command instead of a lifecycle-action line or published-port list.

For create/recreate, the command is rendered from the same immutable resource
plan used by SDK execution, excluding internal management metadata. For an
existing runtime, image, name, network, memory, mounts, and published bindings
come from Docker inspection; inherited environment and labels are omitted.

Contiguous one-to-one host/container bindings are collapsed into Docker port
range syntax. Default read-write mounts omit the redundant `:rw` suffix.

The actual `docker start`, `docker restart`, `docker stop`, removal, pull, and
readiness events may still follow as the lifecycle operation executes. Debug
remains additive: normal output and exit codes do not change. Unexpected
exceptions continue to re-raise under `--debug` after the reporter is closed.


## Port Model

Published-port inspection will preserve the Docker key (`container-port/protocol`) and every binding's host IP and host port. Live resolved bindings from `NetworkSettings.Ports` take precedence. Configured `HostConfig.PortBindings` provides a fallback when live bindings are absent, including stopped containers.

The public `RuntimeRecord.endpoint_map` remains `dict[str, int]` for SDK endpoint rewriting. Conversion from published bindings keeps the current canonical container-port keys and prefers TCP when Docker exposes the same container port under multiple protocols; a sole UDP binding remains representable. This avoids a breaking payload change while preventing protocol iteration order from selecting the wrong mapping.

Requested run ports retain fixed and dynamic host-port intent. Exact previews render dynamic assignment using Docker's empty host-port syntax, such as `127.0.0.1::24081/tcp`. Debug and dry-run commands collapse contiguous one-to-one bindings into Docker's valid host/container range syntax.

## Error Handling

New errors are specific and actionable:

- dry-run combined with pull;
- local image unavailable during strict dry-run;
- exact plan unavailable from read-only inspection;
- a planned resource changes identity before execution.

The existing HostError rendering and verbose JSON details remain the public error path. Execution still revalidates immutable container identity immediately before mutation. Plan execution does not turn a preview into an authorization token for later state: a normal command always plans against fresh state.

Restarting with no existing runtime returns the normal created/restarted lifecycle payload with initialized status instead of raising `UnboundLocalError`.

## Testing and Verification

Behavioral tests will prove:

- parser acceptance for lifecycle `--dry-run` and rejection on read-only commands;
- strict rejection of `--dry-run --pull`;
- each lifecycle dry-run emits an ordered plan and performs zero Docker, LocalCloud API, seed, or active-state mutations;
- exact run previews contain image, name, network, memory, mounts, environment values, labels, TCP ports, UDP ports, and dynamic host-port syntax;
- normal run execution uses the same run-plan values rendered by dry-run;
- start/restart debug output contains only the selected actual mutation, not a synthetic `docker run`;
- debug output reports requested and resolved published ports;
- stopped-container inspection falls back to configured port bindings;
- same-number TCP/UDP bindings produce a stable backward-compatible endpoint map;
- restart without an existing runtime returns a valid status;
- no-op start/stop plans explain why no mutation is required.

Verification will run the focused CLI, controller, Docker runtime, and output tests. The actual CLI surface will then be smoked through command help and a strict read-only dry-run scenario. No live mutation is required to verify dry-run behavior.