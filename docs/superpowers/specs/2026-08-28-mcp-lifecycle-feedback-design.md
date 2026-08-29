# MCP Lifecycle Feedback Design

## Goal

Make `localcloud mcp` visibly confirm that its stdio bridge is ready when run in an interactive terminal, and make Ctrl-C stop it cleanly without a Python or PyInstaller traceback.

## Scope

This change is limited to MCP lifecycle feedback, bounded upstream startup, and interruption handling. It does not change MCP request forwarding, JSON-RPC payloads, runtime selection, Docker mutation behavior, or non-MCP command interruption semantics.

## Output Contract

After resolving the selected runtime's target URL, but before waiting for its health and project catalog, an interactive invocation writes:

```text
Connecting to LocalCloud MCP at http://127.0.0.1:49080/mcp (timeout: 10s)…
```

The default upstream startup timeout is 10 seconds. `--connect-timeout SECONDS` accepts any positive finite duration and changes only this startup deadline.

After the LocalCloud runtime is operational and the stdio bridge is open, it writes:

```text
Connected to LocalCloud at http://127.0.0.1:49080/mcp
Accepting MCP requests over stdio. Press Ctrl-C to close.
```

If upstream startup reaches its deadline, the command exits with:

```text
Error [mcp_connection_timeout] Could not connect to LocalCloud MCP at http://127.0.0.1:49080/mcp within 10 seconds.
```

The URL is derived from the selected runtime rather than hard-coded. Lifecycle messages are emitted only when stderr is an interactive terminal. MCP hosts and other non-interactive launchers receive no lifecycle text. Stdout remains reserved exclusively for JSON-RPC stdio traffic in every mode.

When Ctrl-C interrupts `localcloud mcp`, an interactive invocation writes:

```text
MCP connection closed.
```

The command exits with status 130 and does not emit a traceback. Once connected, the MCP stdio session has no duration timeout: it remains open until stdin closes or Ctrl-C interrupts it. If runtime resolution fails before an endpoint exists, existing `HostError` behavior remains unchanged.

## Architecture

`Controller.target()` owns bounded upstream readiness. It resolves the runtime URL once, reports that URL to the MCP adapter, and then polls health plus the project catalog within one monotonic deadline. Individual HTTP calls receive only the remaining time, transient project transport failures may retry, and non-retryable or unknown-project failures remain immediate. `McpAdapter` translates deadline expiry to `mcp_connection_timeout`. `_run_sdk()` emits the connected and accepting-request statuses only after adapter construction succeeds and `stdio_server()` has entered.

`cli.py` owns process-level interruption. Its existing `KeyboardInterrupt` path gains an MCP-specific branch that emits the close message on interactive stderr and terminates with status 130 instead of re-raising. The MCP SDK's stdio transport may leave its stdin worker blocked after event-loop cancellation; a normal interpreter return can therefore hang in thread shutdown or expose another `KeyboardInterrupt` traceback. After flushing the close message, the MCP branch uses a direct process exit to avoid that SDK shutdown failure. Other commands keep their current interruption behavior. `SystemExit` remains distinct and continues to propagate.

`mcp_stdio.run()` temporarily installs a SIGINT handler that raises
`KeyboardInterrupt` on the first Ctrl-C and restores the previous handler on
normal exit. Without that override, asyncio converts the first SIGINT into
cancellation that the blocked MCP stdin worker may never complete.

All lifecycle writes use the existing terminal-capability check and flush stderr immediately. The progress reporter remains disabled for MCP because it would compete with a long-lived protocol process and is unnecessary for these fixed lifecycle messages.

## Error and Shutdown Behavior

- Ctrl-C at any point while the MCP command is active terminates without a traceback.
- The close message is a process lifecycle acknowledgement; it does not imply that an in-flight JSON-RPC request completed.
- Normal stdin EOF continues to end the stdio loop normally and does not change the process exit code.
- Unexpected non-interrupt exceptions retain the existing clean `HostError` rendering unless `--debug` requests a traceback.
- No lifecycle message is written to stdout.

## Verification

Focused tests will prove the observable contract:

1. Interactive startup emits the resolved `/mcp` URL and selected timeout before waiting.
2. The default timeout is 10 seconds and `--connect-timeout` accepts only positive finite durations.
3. Deadline expiry names the endpoint and returns `mcp_connection_timeout`.
4. A ready interactive stdio bridge emits the connected and accepting-requests lines on stderr.
5. Non-interactive startup remains silent and stdout remains protocol-only.
6. A connected bridge stays open beyond the startup timeout.
7. Ctrl-C exits 130 cleanly while non-MCP interrupt behavior remains unchanged.

The CLI reference will document that MCP reserves stdout for JSON-RPC and emits lifecycle diagnostics only on interactive stderr. A packaged executable smoke run will exercise `localcloud mcp`, observe readiness, send Ctrl-C, and confirm the clean shutdown text without a traceback.
