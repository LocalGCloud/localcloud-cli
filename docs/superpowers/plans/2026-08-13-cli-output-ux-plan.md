# LocalCloud CLI Output UX Implementation Plan

> **Historical geometry:** This plan implemented the original 20×5 mark. The active 16×5 contract is defined by `../specs/2026-08-27-terminal-cloud-compaction-design.md`.

## Objective

Implement the approved CLI output design in `docs/superpowers/specs/2026-08-13-cli-output-ux-design.md` without changing controller result schemas or adding a runtime dependency.

## Constraints and compatibility

- Preserve `_execute()` as the dispatch/domain boundary because unit tests and internal callers use it directly.
- Keep `mcp` stdout protocol-only and preserve guide/env payload semantics.
- Existing Docker integration helpers that consume JSON must invoke commands with `--verbose` after the cutover.
- Preserve the user's existing root-description edit only by replacing it with the approved corrected wording.
- Do not modify unrelated user changes in `RELEASING.md`, `docker_runtime.py`, or its tests.

## Files

- Add `src/localcloud_cli/output.py` for rendering, terminal capabilities, summaries, and reporting.
- Update `src/localcloud_cli/cli.py` for parser descriptions/options and output orchestration.
- Expand `tests/test_cli.py` for parser, concise/verbose output, failures, and reporter integration.
- Add `tests/test_output.py` for focused renderer, color, width, fields, panel, and lifecycle contracts.
- Update `tests/integration/test_agent_workflow.py` helpers to request `--verbose` and tolerate/suppress redirected lifecycle output according to the stream contract.
- Update `README.md` only where the user-visible default/verbose output contract needs a concise usage example.

## Step 1: Build the pure rendering core

Create `output.py` with these boring, testable units:

1. ANSI helpers and terminal capability model:
   - color modes: none, ANSI-16, ANSI-256, true color;
   - capability detection from `isatty()`, `TERM`, `COLORTERM`, `NO_COLOR`, and `FORCE_COLOR`;
   - `strip_ansi`, `visible_width`, ANSI-aware truncation, and exact-width padding.
2. Styling helpers for semantic roles (`processing`, `success`, `error`, `warning`, `label`, `url`, `muted`, `primary`).
3. Immutable `FieldSpec` records and a per-command registry containing default and allowed additional dotted paths.
4. Dotted-path resolution with an explicit missing sentinel, stable deduplication, invalid-path validation, and compact value formatting.
5. `render_summary(command, payload, requested_fields, color)` returning lines without writing streams.
6. `render_json(payload, color)` that uses standard indented, sorted JSON as the source of truth and optionally syntax-colors tokens without changing visible text.
7. Concise `HostError` rendering with stable scalar detail selection.

Tests first assert visible text, field order, optional omission, nested selection, deduplication, invalid paths, scalar/list/object formatting, semantic ANSI presence, plain redirected output, and JSON round-tripping after ANSI stripping.

## Step 2: Build the cloud and responsive panel

In `output.py`:

1. Define the fixed `20 x 5` cloud art exactly as approved and assert equal source widths.
2. Implement cyclic five-stop RGB interpolation, ANSI-256/16 fallbacks, diagonal `(x, y)` positioning, phase offsets, shine compositing, and reset placement only around non-space glyphs.
3. Implement deterministic `render_cloud(phase, progress, color_mode)` with no I/O or clock reads.
4. Build wide (>=80), stacked (50-79), and compact (<50) unstyled panel layouts from resolved config fields.
5. Apply style only after calculating/truncating/padding visible widths.
6. Assert every styled row has exactly the target visible width for long ASCII and Unicode context values.

Renderer tests cover resting phases, changing animation frames, true-color and fallback escapes, no-color output, all responsive breakpoints, and border/column alignment.

## Step 3: Build the lifecycle reporter

Add a `LifecycleReporter` in `output.py` with injected stream, clock, sleep/event primitives, terminal width, and phase selector:

1. `start(message)` emits a generic immediate lifecycle state.
2. `update(message, panel_context=None)` refines the active task and starts the panel animation for start/restart.
3. An interactive worker redraws the fixed panel/status frame at 20 FPS for at most 1.5 seconds, then redraws only the spinner line.
4. `succeed(message)` and `fail(message)` signal, join, clear/repaint, and emit stable elapsed completion.
5. Context-manager/finally cleanup is idempotent and restores the cursor on success, host failure, unexpected exception, keyboard interruption, and partially started workers.
6. Non-TTY mode emits stable lines and never uses cursor-control codes. Verbose redirected mode suppresses reporter lines to preserve standalone error JSON.
7. Only the reporter worker writes stderr while active; CLI code communicates through synchronized reporter methods.

Tests use fake streams and clocks plus deterministic phase values. No real sleeps or flaky timing assertions.

## Step 4: Rework parser help and options

In `cli.py`:

1. Replace the root description and all subcommand help with the approved wording.
2. Use subparser `description=` values so `<command> --help` explains behavior, not just the root listing.
3. Add concise help to `CONFIG`, context arguments, resource names, `--tail`, `--format`, and reset scope.
4. Add reusable parser helpers:
   - `--verbose` on doctor/start/restart/reset/stop/status/logs/console;
   - mutually exclusive `--fields` on doctor/start/restart/reset/stop/status/console;
   - an argument parser that splits repeated/comma-separated paths, trims whitespace, and rejects empty values.
5. Keep output flags subcommand-local to avoid ambiguous global/subcommand precedence.

Parser tests assert exact option availability, descriptions, defaults, parsed fields, mutual exclusion, and absence from guide/env/mcp.

## Step 5: Orchestrate execution and rendering

Refactor `main()` without changing `_execute()` return values:

1. Parse arguments and classify native-payload versus structured commands.
2. Start a generic reporter immediately for ordinary commands except guide/mcp.
3. Resolve detailed task/panel context for start/restart without duplicating config or controller side effects. Accomplish this with a small execution context passed through a CLI-only orchestration helper while `_execute()` remains directly callable and behavior-compatible.
4. Execute once, then:
   - guide/env/native string: print unchanged;
   - logs default: print only `result["logs"]`;
   - structured default: render the registry summary;
   - verbose: render the complete existing payload JSON.
5. Mark reporter done before writing the result.
6. On `HostError`, mark failed and render concise or verbose error based on parsed options, returning `2`.
7. Let unexpected exceptions propagate after reporter cleanup.

Main-level tests cover stdout/stderr separation, immediate start, detailed update, done/failed ordering, concise default, exact verbose payload, logs/raw env, and traceback-preserving unexpected errors.

## Step 6: Cut over callers and documentation

1. Change Docker integration `_invoke` and `_invoke_error` helpers to append `--verbose`, then parse plain redirected JSON. Account for the reporter's redirected success lines without weakening assertions on stdout.
2. Add a short README usage section showing concise default output, `--verbose`, and additive `--fields`.
3. Remove obsolete assertions that JSON is the default and replace them with user-visible contracts.
4. Do not add aliases, deprecated switches, or compatibility shims.

## Step 7: Verify behavior

Run focused checks in this order:

1. `uv run --extra test python -m pytest -q tests/test_output.py tests/test_cli.py`
2. `uv run localcloud --help`
3. `uv run localcloud start --help`
4. redirected concise success using the fake-controller test harness;
5. redirected verbose success parsed with `python -m json.tool`;
6. redirected concise and verbose `HostError` flows;
7. PTY smoke run for the animated panel/status through a deterministic fake operation;
8. `uv run --extra test python -m pytest -q` once after focused checks pass;
9. Docker integration scenario only if Docker and the configured LocalCloud image are available.

Then invoke the code-review workflow, address material findings, and rerun the affected focused checks plus the full suite once.
