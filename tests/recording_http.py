"""Real loopback HTTP servers that record what reaches them, for proxy-bypass tests."""

from __future__ import annotations

import json
import threading
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

Responder = Callable[[str, str, dict[str, str]], tuple[int, Any]]


@contextmanager
def recording_server(respond: Responder | None = None) -> Iterator[tuple[int, list[dict[str, Any]]]]:
    """Serve on 127.0.0.1; yields the port and the list of recorded requests.

    `respond(method, target, headers)` returns a status and a JSON body or text. Without it every
    request is answered 502, as a proxy that cannot reach the target would.
    """
    hits: list[dict[str, Any]] = []

    class Handler(BaseHTTPRequestHandler):
        def _handle(self) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            if length:
                self.rfile.read(length)
            headers = {key.lower(): value for key, value in self.headers.items()}
            hits.append({"method": self.command, "target": self.path, "headers": headers})
            status, body = (respond or (lambda *_: (502, "proxy cannot reach the target")))(
                self.command, self.path, headers
            )
            payload = body if isinstance(body, str) else json.dumps(body)
            data = payload.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "text/plain" if isinstance(body, str) else "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        do_GET = do_POST = do_DELETE = do_CONNECT = _handle  # noqa: N815

        def log_message(self, *args: Any) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server.server_address[1], hits
    finally:
        server.shutdown()
        server.server_close()
