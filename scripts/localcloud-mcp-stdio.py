#!/usr/bin/env python3
"""stdio bridge for LocalCloud's HTTP MCP endpoint.

Reads one JSON-RPC message per stdin line and forwards it to /mcp. This keeps
stdio-only clients usable without embedding a second MCP implementation.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request


def forward(endpoint: str, line: str) -> str | None:
    data = line.encode("utf-8")
    request = urllib.request.Request(
        endpoint,
        data=data,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "MCP-Protocol-Version": "2025-11-25",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            body = response.read().decode("utf-8")
            return body if body else None
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        return body or '{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":"LocalCloud MCP HTTP error"}}'


def main() -> int:
    parser = argparse.ArgumentParser(description="Bridge stdio MCP clients to LocalCloud /mcp")
    parser.add_argument(
        "--endpoint",
        default="http://localhost:8080/mcp",
        help="LocalCloud MCP HTTP endpoint (default: http://localhost:8080/mcp)",
    )
    args = parser.parse_args()

    for raw in sys.stdin:
        line = raw.strip()
        if not line:
            continue
        try:
            response = forward(args.endpoint, line)
            if response:
                sys.stdout.write(response)
                if not response.endswith("\n"):
                    sys.stdout.write("\n")
                sys.stdout.flush()
        except Exception as exc:  # Keep bridge alive for future requests.
            sys.stdout.write(json.dumps({
                "jsonrpc": "2.0",
                "id": None,
                "error": {
                    "code": -32000,
                    "message": f"LocalCloud MCP bridge failed: {exc}",
                },
            }) + "\n")
            sys.stdout.flush()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
