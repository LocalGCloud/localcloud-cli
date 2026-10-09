from __future__ import annotations

import anyio
import copy
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
        auto_start: bool = True,
    ):
        selected_controller = controller if controller is not None else Controller()
        self.config = config
        self.controller = selected_controller
        self.connect_timeout = connect_timeout
        self.on_connecting = on_connecting
        self.auto_start = auto_start
        connecting_url: str | None = None

        def url_resolved(url: str) -> None:
            nonlocal connecting_url
            connecting_url = f"{url.rstrip('/')}/mcp"
            if on_connecting is not None:
                on_connecting(connecting_url)

        if auto_start and hasattr(selected_controller, "start"):
            if terminal_capabilities(sys.stderr).interactive:
                print(
                    f"LocalCloud MCP ensuring runtime for volume '{config.data_volume}'…",
                    file=sys.stderr,
                    flush=True,
                )
            selected_controller.start(
                config,
                ensure_project=True,
                allow_replace=False,
            )

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
                timeout_unit = "second" if connect_timeout == 1 else "seconds"
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
            elif error.code == "runtime_not_running":
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
            else:
                raise

        self._set_target(target)

    def _set_target(self, target: dict[str, Any]) -> None:
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
                {"data_volume": self.config.data_volume, "cause": str(error)},
            ) from error
        self.target = target
        self.mcp_url = f"{url}/mcp"
        self.java = JavaMcpClient(url, project=self.config.project, user=self.config.user)

    def _refresh_target(self) -> bool:
        target = self.controller.target(
            self.config,
            readiness_timeout=self.connect_timeout,
        )
        url = target.get("url", "").rstrip("/")
        if url and f"{url}/mcp" != self.mcp_url:
            self._set_target(target)
            return True
        return False

    def handle(self, message: dict[str, Any]) -> dict[str, Any] | None:
        request_id = message.get("id")
        method = str(message.get("method"))
        is_notification = request_id is None
        try:
            try:
                response = self.java.forward(message)
            except HostError as error:
                if error.code == "java_mcp_unavailable":
                    # In case of container restart or port change, re-resolve and retry once
                    try:
                        if self._refresh_target():
                            response = self.java.forward(message)
                        else:
                            raise error
                    except Exception:
                        raise error
                else:
                    raise

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
            if method == "tools/list" and isinstance(response.get("result"), dict):
                tools = response["result"].get("tools")
                if isinstance(tools, list):
                    # Fill display metadata while preserving the runtime's safety declarations.
                    response = copy.deepcopy(response)
                    for tool in response["result"]["tools"]:
                        if (
                            not isinstance(tool, dict)
                            or not isinstance(tool.get("name"), str)
                            or not tool["name"]
                        ):
                            continue
                        annotations = tool.get("annotations")
                        if annotations is None:
                            annotations = tool["annotations"] = {}
                        if not isinstance(annotations, dict):
                            continue
                        title = tool.get("title") or annotations.get("title")
                        if not title:
                            title = " ".join(
                                word.upper() if word in {"api", "sdk", "sql", "iam"} else word
                                for word in tool["name"].removeprefix("localcloud_").split("_")
                            )
                            title = title[:1].upper() + title[1:]
                        if not tool.get("title"):
                            tool["title"] = title
                        if not annotations.get("title"):
                            annotations["title"] = title
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
    auto_start: bool = True,
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
        run_args: list[Any] = [_run_sdk, config, connect_timeout, report_connecting]
        if not auto_start:
            run_args.append(False)
        anyio.run(*run_args)
    finally:
        signal.signal(signal.SIGINT, previous_sigint)


async def _run_sdk(
    config: LocalCloudConfig,
    connect_timeout: float = 10.0,
    on_connecting: Callable[[str], None] | None = None,
    auto_start: bool = True,
) -> None:
    from mcp import types
    from mcp.server.stdio import stdio_server
    from mcp.shared.message import SessionMessage

    adapter = McpAdapter(
        config,
        connect_timeout=connect_timeout,
        on_connecting=on_connecting,
        auto_start=auto_start,
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
                    response = await anyio.to_thread.run_sync(adapter.handle, message)
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
                        except Exception as fallback_error:
                            sys.stderr.write(
                                f"[localcloud mcp] Failed to validate error fallback message: {fallback_error}\n"
                            )
                            sys.stderr.flush()
                            continue

                    await write_stream.send(SessionMessage(parsed))
