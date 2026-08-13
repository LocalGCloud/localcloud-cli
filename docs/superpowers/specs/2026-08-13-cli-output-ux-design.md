# LocalCloud CLI Output UX Design

## Goal

Make LocalCloud commands self-explanatory while they run and concise when they finish. Interactive terminals should provide a polished, color-coded experience; redirected output must remain stable and automation-safe. Complete command results remain available as JSON through `--verbose`.

## Scope

This change covers:

- root, command, positional, and option descriptions;
- command lifecycle reporting (`Processing`, `Done`, and `Failed`);
- an animated startup panel for `start` and `restart`;
- concise, command-aware summaries for structured results;
- complete JSON output through `--verbose`;
- additive summary fields through `--fields`;
- concise and verbose error rendering;
- color, terminal capability, and redirected-stream behavior.

It does not change controller, Docker runtime, Java MCP, endpoint, or configuration result schemas. It does not turn the CLI into a persistent TUI or add a terminal-rendering dependency.

## Command descriptions

The root description will be:

> Run Google Cloud-compatible services locally in Docker. Manage LocalCloud instances, project context, SDK environments, and the MCP bridge.

Subcommand descriptions will be action-oriented:

| Command | Description |
| --- | --- |
| `guide` | Print guidance for coding agents using LocalCloud. |
| `doctor` | Check Docker access and detect legacy LocalCloud state. |
| `start` | Start an instance and prepare the selected project. |
| `restart` | Restart an instance and reapply volatile seed data. |
| `reset` | Reset the selected project, or recreate all instance data. |
| `stop` | Stop an instance without deleting persistent data. |
| `status` | Show instance health and runtime details. |
| `logs` | Print recent logs from an instance. |
| `console` | Open the web console for the selected project and user. |
| `env` | Print SDK configuration for the selected project. |
| `mcp` | Run the stdio MCP bridge for a running instance. |

Every currently undocumented argument receives concise help. In particular:

- `CONFIG` explains local-file and remembered-config precedence;
- `--project-id` explains project creation/selection context;
- `--user` explains caller identity;
- Docker resource-name options state exactly which resource they override;
- `--tail` documents its default and non-negative constraint;
- `--format` documents its default;
- `--verbose` says it prints the complete JSON result;
- `--fields` says it adds comma-separated JSON paths to the default summary.

## Stream contract

- Human progress and the startup panel are written to `stderr`.
- Command results are written to `stdout`.
- Help, version, and guide text are immediate and do not show progress.
- `mcp` keeps stdout exclusively for JSON-RPC and does not show a lifecycle reporter.
- `env` preserves the exact selected shell, JSON, Terraform, or Docker Compose payload on stdout.
- `logs` prints log text directly by default.
- Redirected stdout never contains ANSI escapes.
- Redirected stderr receives stable, non-animated lifecycle lines rather than cursor-control sequences. When `--verbose` is active and stderr is redirected, lifecycle output is suppressed so a `HostError` remains a standalone parseable JSON document.

## Lifecycle reporting

Each ordinary command starts reporting immediately after argument parsing, before controller construction or configuration lookup. Its initial message is accurate with only parsed context (for example, `Preparing LocalCloud start…`). Once resolved context is available, an interactive reporter replaces that message with a detailed task; redirected stderr emits the detail as the next stable line. For example:

```text
Processing  Starting instance 'default' and preparing project 'local-gcp-project'…
Done        LocalCloud is ready  8.4s
```

Task descriptions name the actual operation and relevant context instead of using a generic waiting message:

- `doctor`: inspect Docker and legacy local state;
- `start`: start the selected instance and prepare the selected project;
- `restart`: restart the selected instance and prepare the selected project;
- `reset`: reset the selected project or all instance data;
- `stop`: stop the selected instance;
- `status`: inspect the selected instance;
- `logs`: read the requested number of recent log lines;
- `console`: locate the selected project and open its console;
- `env`: generate the selected SDK configuration format.

Interactive terminals receive a spinner and elapsed time. Completion replaces the active processing line with `Done`; a `HostError` replaces it with `Failed`. Unexpected exceptions also stop and restore the reporter before normal traceback behavior continues.

Semantic colors are stable:

- processing: cyan;
- done, ready, running, and healthy: green;
- failed, unhealthy, and error: red;
- warning, stopped, reconfigured, and changed values: yellow;
- field labels: bold blue;
- primary values: bright foreground;
- URLs: cyan;
- secondary metadata: dim foreground.

## Startup panel

`start` and `restart` render a branded panel after configuration is resolved and before the controller operation begins. The context is therefore accurate even when values come from `localcloud.yaml` or remembered configuration.

The cloud is a compact, filled, fixed-width `20 x 5` Unicode mark:

```text
       ▁▃▅▇▅▃▁
   ▁▃▆██████████▆▃▁
 ▁▃▆████████████▆▃▁
████████████████████
▀██████████████████▀
```

Each non-space glyph receives its own ANSI foreground color. Color position is computed diagonally from the glyph's `(x, y)` coordinate, following the same core technique as OMP's welcome logo. The cyclic true-color stops are:

1. Google blue `#4285F4`;
2. Google red `#EA4335`;
3. Google yellow `#FBBC04`;
4. Google green `#34A853`;
5. Google blue `#4285F4` to close the cycle without a seam.

ANSI-256 and ANSI-16 approximations are used when true color is unavailable. One of four resting phase offsets is selected per invocation through an injectable selector, making tests deterministic.

On an interactive capable terminal, the logo performs a concurrent 1.5-second animation at 20 FPS:

- the diagonal gradient sweeps into the selected resting phase with cubic ease-out;
- a soft white shine crosses the logo and fades out;
- startup work begins immediately and is never delayed to finish the animation;
- after the logo settles, only the processing spinner continues;
- completion or failure stops the animation, joins its worker, repaints a stable final frame, and restores terminal state in `finally`.

The animation worker exclusively owns stderr while active. It rewrites a fixed number of rows with ANSI cursor movement and clear-line sequences. Width and padding are calculated from unstyled text before ANSI codes are applied, so escape sequences cannot misalign borders or columns.

Responsive behavior:

- at 80 columns or wider, use a split panel with the centered cloud on the left and resolved instance, project, user, services, data mode, and config source on the right;
- from 50 through 79 columns, use a stacked panel;
- below 50 columns, show the compact logo/header and essential instance/project context only;
- terminal width is captured for the short animation frame to keep redraw height and column boundaries stable;
- long context values are ANSI-aware truncated with an ellipsis before padding, so every rendered row has the exact panel width;
- `TERM=dumb` and redirected stderr skip the panel and cursor control;
- `NO_COLOR` retains a static uncolored panel on an interactive terminal but disables gradient animation.

## Concise result summaries

Structured controller results remain unchanged. A CLI-only renderer selects relevant fields from the returned payload through a declarative per-command registry.

Default lifecycle summary fields, in order:

1. `status`;
2. `instance`;
3. `project`, when present;
4. `user`, when present;
5. `container.state`;
6. `container.url`, when present;
7. `services`;
8. `data`;
9. `changed_fields`, when present;
10. `reset_scope`, when present.

Default command mappings:

- `start`, `restart`, and `reset`: lifecycle fields above;
- `stop` and `status`: status, instance, container state, URL when present, services, and data mode;
- `doctor`: status, Docker version, CLI version, default image, non-empty legacy findings, and warning;
- `console`: status, instance, project, user, and opened URL;
- `logs`: raw log text only;
- `guide`, `env`, and `mcp`: native payload semantics, with no summary wrapper.

The default structured format is an aligned label/value list rather than a box:

```text
Status      started
Instance    default
Project     local-gcp-project
State       running
URL         http://127.0.0.1:49080
Services    default
Data        persistent
```

Absent optional default fields are omitted. Lists are rendered as comma-separated values. Nested objects selected explicitly are rendered as compact one-line JSON. Labels, paths, ordering, and value styles are represented by immutable field specifications so maintainers can add or reorder fields without modifying renderer logic.

## `--verbose` and `--fields`

Commands returning structured CLI results accept `--verbose` after the subcommand. This includes `doctor`, `start`, `restart`, `reset`, `stop`, `status`, `logs`, and `console`.

`--verbose` prints the complete existing payload as indented, sorted JSON. On an interactive stdout, JSON receives syntax coloring; when redirected, it remains plain parseable JSON. `logs --verbose` includes both metadata and the complete `logs` string in that JSON object.

Summary commands also accept:

```text
--fields PATH[,PATH...]
```

The option adds known dotted paths after the command's defaults, preserving requested order and removing duplicates. Examples include `container.name`, `network`, `volume`, and `mcp.direct_url`. Known optional fields that are absent from a particular result are omitted. Unsupported paths fail before rendering with a concise message that names the invalid path and the valid paths for that command.

`--fields` is available for `doctor`, `start`, `restart`, `reset`, `stop`, `status`, and `console`. It is intentionally absent from raw content commands (`guide`, `logs`, `env`, and `mcp`). `--verbose` and `--fields` are mutually exclusive.

## Error output

A `HostError` produces, in order:

1. a red `Failed` lifecycle line on stderr;
2. a concise `Error [code] message` line;
3. compact relevant scalar details from the error payload.

When the command supports `--verbose`, the same failure instead prints the existing complete `HostError.to_dict()` object as indented, sorted JSON on stderr. Interactive JSON may be syntax-colored. When stderr is redirected, the reporter is silent and the JSON is plain, standalone, and parseable. Exit status remains `2`.

The reporter never converts unexpected exceptions into user errors. It restores terminal state, optionally marks the active task failed, and lets the exception retain normal traceback behavior.

## Architecture

The implementation stays at the CLI boundary:

- command controllers continue returning their existing dictionaries and strings;
- a focused output module owns ANSI capability detection, visible-width calculation, gradient rendering, field specifications, summary rendering, JSON rendering, and lifecycle reporting;
- CLI dispatch starts a generic reporter immediately after parsing, then refines it when resolved command context becomes available;
- no progress callbacks are threaded through `Controller`, `DockerRuntime`, or `JavaMcpClient`;
- no new runtime dependency is added.

The animation is truthful: it represents the active command and elapsed time. It does not invent internal Docker stages that the CLI cannot observe.

## Verification

Automated tests will cover observable contracts:

- root and subcommand descriptions, including every option;
- output-option availability and `--verbose`/`--fields` exclusivity;
- command-specific processing, done, and failed messages;
- deterministic elapsed-time, spinner, gradient-phase, and animation frames through injected clock/phase values;
- equal visible widths for every panel row with ANSI styling present;
- responsive wide, stacked, and compact layouts;
- TTY true-color, 256-color, plain, `NO_COLOR`, and redirected behavior;
- default summaries for every structured result shape;
- additive nested fields, ordering, deduplication, absent optional fields, and invalid paths;
- complete verbose JSON for success and `HostError` results;
- raw logs and environment payload preservation;
- MCP stdout protocol preservation;
- reporter cleanup on success, `HostError`, unexpected exception, and interruption.

Smoke verification will run real CLI help plus representative successful and failing commands. Docker-backed scenarios will be exercised when the local Docker daemon and LocalCloud image are available; deterministic CLI tests will cover the same stream and rendering contracts without requiring Docker.
