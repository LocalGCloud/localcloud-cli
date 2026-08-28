# MCP Lifecycle Feedback Design

## Goal

Make `localcloud mcp` visibly confirm that its stdio bridge is ready when run in an interactive terminal, and make Ctrl-C stop it cleanly without a Python or PyInstaller traceback.

## Scope

This change is limited to MCP lifecycle feedback and interruption handling. It does not change MCP request forwarding, JSON-RPC payloads, runtime selection, Docker behavior, or non-MCP command interruption semantics.

## Output Contract

After the selected LocalCloud runtime has resolved successfully and the stdio bridge is open, an interactive invocation writes these lines to stderr:

```text
Connected to LocalCloud at http://127.0.0.1:49080/mcp
Accepting MCP requests over stdio. Press Ctrl-C to close.
```

The URL is derived from the selected runtime rather than hard-coded. The displayed host and port therefore match the bridge's actual upstream LocalCloud MCP endpoint.

Lifecycle messages are emitted only when stderr is an interactive terminal. MCP hosts and other non-interactive launchers receive no lifecycle text. Stdout remains reserved exclusively for JSON-RPC stdio traffic in every mode.

When Ctrl-C interrupts `localcloud mcp`, an interactive invocation writes:

```text
MCP connection closed.
```

The command exits with status 130 and does not emit a traceback. If startup or runtime resolution fails normally, existing `HostError` behavior remains unchanged.

## Architecture

`mcp_stdio.py` owns transport readiness. `McpAdapter` retains the normalized upstream MCP URL obtained from `Controller.target()`. `_run_sdk()` emits readiness only after adapter construction succeeds and `stdio_server()` has entered, so the message does not claim readiness before the runtime and bridge are available.

`cli.py` owns process-level interruption. Its existing `KeyboardInterrupt` path gains an MCP-specific branch that emits the close message on interactive stderr and returns 130 instead of re-raising. Other commands keep their current interruption behavior. `SystemExit` remains distinct and continues to propagate.

Both call sites use the existing terminal-capability check before writing and flush stderr immediately. The progress reporter remains disabled for MCP because it would compete with a long-lived protocol process and is unnecessary for these fixed lifecycle messages.

## Error and Shutdown Behavior

- Ctrl-C at any point while the MCP command is active terminates without a traceback.
- The close message is a process lifecycle acknowledgement; it does not imply that an in-flight JSON-RPC request completed.
- Normal stdin EOF continues to end the stdio loop normally and does not change the process exit code.
- Unexpected non-interrupt exceptions retain the existing clean `HostError` rendering unless `--debug` requests a traceback.
- No lifecycle message is written to stdout.

## Verification

Focused tests will prove the observable contract:

1. A ready interactive stdio bridge emits the resolved `/mcp` URL and the accepting-requests line on stderr.
2. A non-interactive bridge emits no lifecycle diagnostics.
3. Readiness feedback never writes to stdout.
4. Ctrl-C from `mcp` returns 130 and emits the close message without re-raising.
5. Non-MCP interrupt behavior remains unchanged.

The CLI reference will document that MCP reserves stdout for JSON-RPC and emits lifecycle diagnostics only on interactive stderr. A packaged executable smoke run will exercise `localcloud mcp`, observe readiness, send Ctrl-C, and confirm the clean shutdown text without a traceback.
