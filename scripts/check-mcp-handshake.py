from __future__ import annotations

import argparse
import json
import os
import selectors
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any


STATUS_TOOL = "localcloud_runtime_status"


class McpHandshakeError(RuntimeError):
    pass


def check_mcp_handshake(command: Path, timeout: float) -> float:
    if timeout <= 0:
        raise ValueError("timeout must be positive")

    executable = command.resolve()
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="localcloud-mcp-check-") as scratch:
        stderr_path = Path(scratch) / "stderr"
        with stderr_path.open("wb") as stderr:
            process = subprocess.Popen(
                [str(executable), "mcp", "--no-start"],
                cwd=scratch,
                env={
                    **os.environ,
                    # No engine listens here: the bridge has to answer without Docker.
                    "DOCKER_HOST": f"unix://{scratch}/docker.sock",
                    "HOME": scratch,
                    "LOCALCLOUD_HOME": scratch,
                    "LOCALCLOUD_TELEMETRY": "false",
                },
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=stderr,
            )
        assert process.stdin is not None and process.stdout is not None
        stdin = process.stdin
        stdout_fd = process.stdout.fileno()
        os.set_blocking(stdout_fd, False)
        selector = selectors.DefaultSelector()
        selector.register(stdout_fd, selectors.EVENT_READ)
        output = bytearray()

        def failure(problem: str) -> McpHandshakeError:
            reported = stderr_path.read_text(encoding="utf-8", errors="replace").strip()
            return McpHandshakeError(
                f"{executable} {problem}" + (f"\n{reported}" if reported else "")
            )

        def send(message: dict[str, Any]) -> None:
            try:
                stdin.write(json.dumps(message).encode("utf-8") + b"\n")
                stdin.flush()
            except BrokenPipeError:
                # The bridge has exited; the next read reports how.
                pass

        def result(request: dict[str, Any]) -> Any:
            method = request["method"]
            send(request)
            while True:
                line, newline, rest = bytes(output).partition(b"\n")
                if newline:
                    output[:] = rest
                    try:
                        message = json.loads(line)
                    except ValueError:
                        message = None
                    if not isinstance(message, dict):
                        raise failure(
                            "wrote a line that is not JSON-RPC to stdout: "
                            f"{line.decode('utf-8', errors='replace')!r}"
                        )
                    if "method" in message or message.get("id") != request["id"]:
                        continue
                    if "result" not in message:
                        raise failure(
                            f"answered {method} with {json.dumps(message.get('error'))}"
                        )
                    return message["result"]
                remaining = timeout - (time.monotonic() - started)
                if remaining <= 0 or not selector.select(remaining):
                    raise failure(f"did not answer {method} within {timeout:.1f} seconds")
                try:
                    chunk = os.read(stdout_fd, 65536)
                except BlockingIOError:
                    continue
                if not chunk:
                    try:
                        code = process.wait(timeout=1)
                    except subprocess.TimeoutExpired:
                        raise failure(f"closed stdout before answering {method}") from None
                    raise failure(f"exited with code {code} before answering {method}")
                output.extend(chunk)

        try:
            result(
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "initialize",
                    "params": {
                        "protocolVersion": "2025-06-18",
                        "capabilities": {},
                        "clientInfo": {"name": "localcloud-release-check", "version": "0"},
                    },
                }
            )
            send({"jsonrpc": "2.0", "method": "notifications/initialized"})
            listed = result({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
            tools = listed.get("tools") if isinstance(listed, dict) else None
            names = [
                tool.get("name")
                for tool in (tools if isinstance(tools, list) else [])
                if isinstance(tool, dict)
            ]
            # Without a runtime the bridge lists only its own status tool.
            if STATUS_TOOL not in names:
                raise failure(f"did not list {STATUS_TOOL} in tools/list: {names}")
            return time.monotonic() - started
        finally:
            selector.close()
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            try:
                stdin.close()
            except BrokenPipeError:
                pass
            process.stdout.close()


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Verify that a native LocalCloud bundle answers the MCP handshake "
            "over stdio without Docker."
        )
    )
    parser.add_argument("command", type=Path)
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args()

    try:
        elapsed = check_mcp_handshake(args.command, args.timeout)
    except (OSError, McpHandshakeError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(f"MCP handshake answered after {elapsed:.3f} seconds")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
