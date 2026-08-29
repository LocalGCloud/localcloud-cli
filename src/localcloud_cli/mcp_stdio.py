from __future__ import annotations

import signal
import sys
from typing import Any, Callable

from .config import LocalCloudConfig
from .controller import Controller
from .errors import HostError
from .endpoints import transform_endpoint_payload
from .java_client import JavaMcpClient
from .output import terminal_capabilities


class McpAdapter:
    def __init__(
        self,
        config: LocalCloudConfig,
        *,
        controller: Controller | None = None,
        connect_timeout: float = 10.0,
        on_connecting: Callable[[str], None] | None = None,
    ):
        selected_controller = controller if controller is not None else Controller()
        connecting_url: str | None = None

        def url_resolved(url: str) -> None:
            nonlocal connecting_url
            connecting_url = f"{url.rstrip('/')}/mcp"
            if on_connecting is not None:
                on_connecting(connecting_url)

        try:
            target = selected_controller.target(
                config,
                readiness_timeout=connect_timeout,
                on_url_resolved=url_resolved,
            )
        except HostError as error:
            if error.code == "runtime_readiness_timeout":
                if connecting_url is None:
                    url = error.details.get("url")
                    if isinstance(url, str) and url:
                        connecting_url = f"{url.rstrip('/')}/mcp"
                target_name = connecting_url or "the LocalCloud MCP endpoint"
                timeout_unit = (
                    "second" if connect_timeout == 1 else "seconds"
                )
                raise HostError(
                    "mcp_connection_timeout",
                    (
                        f"Could not connect to LocalCloud MCP at {target_name} "
                        f"within {connect_timeout:g} {timeout_unit}."
                    ),
                    {
                        "data_volume": config.data_volume,
                        "project": config.project,
                        "url": connecting_url,
                        "timeout_seconds": connect_timeout,
                    },
                ) from error
            if error.code != "runtime_not_running":
                raise
            raise HostError(
                "runtime_not_running",
                "Run "
                f"localcloud start --data-volume {config.data_volume} "
                f"--project-id {config.project} --user {config.user} "
                "before connecting MCP.",
                {
                    "data_volume": config.data_volume,
                    "project": config.project,
                    "user": config.user,
                },
            ) from error
        try:
            self.endpoint_map = {
                str(canonical): int(host_port)
                for canonical, host_port in target["endpoint_map"].items()
            }
            url = target["url"].rstrip("/")
        except (KeyError, TypeError, ValueError, AttributeError) as error:
            raise HostError(
                "runtime_target_invalid",
                "LocalCloud runtime target could not be resolved for MCP",
                {"data_volume": config.data_volume, "cause": str(error)},
            ) from error
        self.mcp_url = f"{url}/mcp"
        self.java = JavaMcpClient(url, project=config.project, user=config.user)

    def handle(self, message: dict[str, Any]) -> dict[str, Any] | None:
        request_id = message.get("id")
        method = str(message.get("method"))
        is_notification = request_id is None
        try:
            response = self.java.forward(message)
            if is_notification:
                return None
            if response is None:
                raise HostError(
                    "java_mcp_invalid_response",
                    "Java LocalCloud MCP returned no response for a request",
                    {"method": method},
                )
            if method in {"tools/call", "resources/read"} and "result" in response:
                response = dict(response)
                response["result"] = transform_endpoint_payload(
                    response["result"], self.endpoint_map
                )
            return response
        except HostError as error:
            if is_notification:
                return None
            return {
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {
                    "code": -32000,
                    "message": error.message,
                    # Only the error code crosses the wire - `details` may
                    # carry upstream RPC bodies, container ids, or other
                    # internals that shouldn't be forwarded to MCP clients
                    # verbatim.
                    "data": {"code": error.code},
                },
            }
        except Exception as error:
            if is_notification:
                return None
            return {
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {
                    "code": -32603,
                    "message": "LocalCloud MCP adapter failed",
                    "data": str(error),
                },
            }


def _raise_keyboard_interrupt(_signum: int, _frame: Any) -> None:
    # asyncio otherwise turns the first SIGINT into cancellation, but the MCP
    # stdin worker can block that cancellation indefinitely.
    raise KeyboardInterrupt


def run(
    config: LocalCloudConfig,
    *,
    connect_timeout: float = 10.0,
) -> None:
    import anyio

    def report_connecting(mcp_url: str) -> None:
        if terminal_capabilities(sys.stderr).interactive:
            print(
                f"Connecting to LocalCloud MCP at {mcp_url} "
                f"(timeout: {connect_timeout:g}s)…",
                file=sys.stderr,
                flush=True,
            )

    previous_sigint = signal.signal(signal.SIGINT, _raise_keyboard_interrupt)
    try:
        anyio.run(_run_sdk, config, connect_timeout, report_connecting)
    finally:
        signal.signal(signal.SIGINT, previous_sigint)


async def _run_sdk(
    config: LocalCloudConfig,
    connect_timeout: float = 10.0,
    on_connecting: Callable[[str], None] | None = None,
) -> None:
    from mcp import types
    from mcp.server.stdio import stdio_server
    from mcp.shared.message import SessionMessage

    adapter = McpAdapter(
        config,
        connect_timeout=connect_timeout,
        on_connecting=on_connecting,
    )
    async with stdio_server() as (read_stream, write_stream):
        async with read_stream, write_stream:
            if terminal_capabilities(sys.stderr).interactive:
                print(
                    f"Connected to LocalCloud at {adapter.mcp_url}\n"
                    "Accepting MCP requests over stdio. Press Ctrl-C to close.",
                    file=sys.stderr,
                    flush=True,
                )
            async for incoming in read_stream:
                if isinstance(incoming, Exception):
                    response = {
                        "jsonrpc": "2.0",
                        "id": None,
                        "error": {
                            "code": -32700,
                            "message": "Parse error",
                            "data": str(incoming),
                        },
                    }
                else:
                    message = incoming.message.model_dump(
                        by_alias=True, exclude_unset=True
                    )
                    response = adapter.handle(message)
                if response is not None:
                    try:
                        parsed = types.jsonrpc_message_adapter.validate_python(
                            response
                        )
                    except Exception as error:
                        # A malformed response (e.g. an upstream Java MCP
                        # payload forwarded verbatim that doesn't match the
                        # local schema) must not kill the whole stdio bridge
                        # for every subsequent request - degrade to a clean
                        # error for this one message instead.
                        fallback = {
                            "jsonrpc": "2.0",
                            "id": response.get("id"),
                            "error": {
                                "code": -32603,
                                "message": (
                                    "LocalCloud MCP adapter produced an "
                                    "invalid response"
                                ),
                                "data": str(error),
                            },
                        }
                        try:
                            parsed = types.jsonrpc_message_adapter.validate_python(
                                fallback
                            )
                        except Exception:
                            continue
                    await write_stream.send(SessionMessage(parsed))
