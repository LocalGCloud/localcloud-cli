from __future__ import annotations

from typing import Any

from .config import LocalCloudConfig
from .controller import Controller
from .errors import HostError
from .endpoints import transform_endpoint_payload
from .java_client import JavaMcpClient


class McpAdapter:
    def __init__(
        self,
        config: LocalCloudConfig,
        *,
        controller: Controller | None = None,
    ):
        selected_controller = controller if controller is not None else Controller()
        try:
            target = selected_controller.target(config)
        except HostError as error:
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
        self.endpoint_map = {
            str(canonical): int(host_port)
            for canonical, host_port in target["endpoint_map"].items()
        }
        self.java = JavaMcpClient(
            target["url"], project=config.project, user=config.user
        )

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
                    "data": error.to_dict(),
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


def run(config: LocalCloudConfig) -> None:
    import anyio

    anyio.run(_run_sdk, config)


async def _run_sdk(config: LocalCloudConfig) -> None:
    from mcp import types
    from mcp.server.stdio import stdio_server
    from mcp.shared.message import SessionMessage

    adapter = McpAdapter(config)
    async with stdio_server() as (read_stream, write_stream):
        async with read_stream, write_stream:
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
                    parsed = types.jsonrpc_message_adapter.validate_python(response)
                    await write_stream.send(SessionMessage(parsed))
