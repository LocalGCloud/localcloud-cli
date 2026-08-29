# MCP Lifecycle Feedback Implementation Plan

**Design:** `docs/superpowers/specs/2026-08-28-mcp-lifecycle-feedback-design.md`

**Goal:** Make an interactive `localcloud mcp` invocation report truthful readiness and stop cleanly on Ctrl-C while preserving protocol-only stdout and silent non-interactive launches.

## Task 1: Report MCP bridge readiness

**Files:**
- Modify `src/localcloud_cli/cli.py`
- Modify `src/localcloud_cli/controller.py`
- Modify `src/localcloud_cli/docker_runtime.py`
- Modify `src/localcloud_cli/mcp_stdio.py`
- Modify `tests/test_cli.py`
- Modify `tests/test_controller.py`
- Modify `tests/test_mcp_stdio.py`

**Steps:**
1. Add `mcp --connect-timeout SECONDS`, defaulting to 10 and rejecting non-positive or non-finite values.
2. Extend target resolution to report the selected runtime URL before readiness checks.
3. Bound health and project-catalog requests by one monotonic startup deadline; retry only transient failures.
4. Translate deadline expiry to `mcp_connection_timeout` with the resolved `/mcp` URL and selected duration.
5. Emit the endpoint-aware connecting status only on interactive stderr.
6. Keep the connected stdio read loop unbounded after startup succeeds.
7. Add focused parser, deadline, endpoint, error, non-interactive, and stdout-isolation tests.

**Acceptance:** Startup reports the actual endpoint, defaults to a 10-second configurable deadline, fails with a precise timeout error, and does not impose a duration limit on the connected MCP session.

## Task 2: Handle MCP Ctrl-C as a clean process exit

**Files:**
- Modify `src/localcloud_cli/mcp_stdio.py`
- Modify `src/localcloud_cli/cli.py`
- Modify `tests/test_mcp_stdio.py`
- Modify `tests/test_cli.py`

**Steps:**
1. Temporarily install an MCP-runner SIGINT handler that raises `KeyboardInterrupt` on the first Ctrl-C, then restore the previous handler on normal exit.
2. Separate `KeyboardInterrupt` from `SystemExit` in the CLI orchestration path.
3. For the `mcp` command only, print and flush `MCP connection closed.` when stderr is interactive, then terminate directly with status 130 so the MCP SDK's blocked stdin worker cannot hang interpreter thread shutdown.
4. Preserve the existing re-raise and lifecycle-reporter behavior for interrupts from every other command.
5. Add focused tests proving first-signal handling, direct status-130 exit, non-interactive silence, and unchanged non-MCP interruption behavior.
6. Run the focused MCP and CLI interruption tests.

**Acceptance:** Ctrl-C never reaches PyInstaller as an unhandled MCP exception, interactive users receive a clean close acknowledgement, and other command semantics do not change.

## Task 3: Document and smoke the lifecycle contract

**Files:**
- Modify `docs/cli-reference.md` without overwriting the user's existing edits
- Modify only additional files required by verified defects

**Steps:**
1. Extend the MCP command reference to state that stdout is reserved for JSON-RPC and lifecycle diagnostics appear only on interactive stderr.
2. Run the complete focused MCP and CLI test files.
3. Launch the actual source CLI in a PTY against the available runtime, observe readiness, send Ctrl-C, and verify exit status 130, the close message, and no traceback.
4. Build or use the current packaged executable only if the repository's existing release path makes that artifact available without disturbing unrelated work; otherwise report source-path smoke evidence precisely.
5. Review the final diff for stale behavior, compatibility shims, accidental stdout writes, and interference with pre-existing working-tree changes.
6. Request final code review and address verified findings.

**Acceptance:** Public documentation matches the stream contract, focused tests pass, the actual CLI demonstrates clean interactive shutdown, and all unrelated working-tree changes remain intact.
