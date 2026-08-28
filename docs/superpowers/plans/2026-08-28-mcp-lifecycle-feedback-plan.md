# MCP Lifecycle Feedback Implementation Plan

**Design:** `docs/superpowers/specs/2026-08-28-mcp-lifecycle-feedback-design.md`

**Goal:** Make an interactive `localcloud mcp` invocation report truthful readiness and stop cleanly on Ctrl-C while preserving protocol-only stdout and silent non-interactive launches.

## Task 1: Report MCP bridge readiness

**Files:**
- Modify `src/localcloud_cli/mcp_stdio.py`
- Modify `tests/test_mcp_stdio.py`

**Steps:**
1. Retain the normalized upstream `/mcp` URL on `McpAdapter` when the selected runtime target is resolved.
2. After adapter construction and both stdio stream contexts have entered, check the existing terminal capabilities for stderr.
3. For interactive stderr only, print and flush the resolved connection URL followed by the accepting-requests guidance.
4. Add a focused async transport test with controlled streams proving the message uses the selected runtime URL, appears on interactive stderr, and leaves stdout untouched.
5. Add non-interactive coverage proving an MCP-host-style launch remains silent.
6. Run the focused MCP stdio tests.

**Acceptance:** Readiness is announced only after the bridge is available, the endpoint is accurate, non-interactive launchers remain silent, and stdout contains only protocol traffic.

## Task 2: Handle MCP Ctrl-C as a clean process exit

**Files:**
- Modify `src/localcloud_cli/cli.py`
- Modify `tests/test_cli.py`

**Steps:**
1. Separate `KeyboardInterrupt` from `SystemExit` in the CLI orchestration path.
2. For the `mcp` command only, print and flush `MCP connection closed.` when stderr is interactive and return exit status 130.
3. Preserve the existing re-raise and lifecycle-reporter behavior for interrupts from every other command.
4. Add focused tests proving MCP interruption returns 130 without re-raising, remains silent for non-interactive stderr, and does not change non-MCP interruption behavior.
5. Run the focused CLI interruption tests.

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
