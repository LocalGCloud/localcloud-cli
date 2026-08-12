from __future__ import annotations

import json
import re
from typing import Any

from .config import DEFAULT_INSTANCE, DEFAULT_PROJECT, DEFAULT_USER
from .controller import Controller
from .errors import HostError
from .java_client import JavaMcpClient


ENDPOINT_FIELDS = frozenset(
    {
        "endpoint",
        "env_value",
        "endpoint_env",
        "token_uri",
        "auth_uri",
    }
)
ENDPOINT_RECORD_MARKERS = frozenset(
    {
        "env_var",
        "gcloud_endpoint_env_var",
        "terraform_endpoint_env_var",
    }
)
ENDPOINT_CONFIG_ASSIGNMENT = re.compile(
    r"(?im)^\s*(?:export\s+)?[\"']?"
    r"(?P<key>[A-Z][A-Z0-9_]*)[\"']?\s*[:=]"
)


def _is_endpoint_env_key(key: str) -> bool:
    normalized = key.upper()
    return (
        normalized.endswith(("_HOST", "_ENDPOINT", "_URL", "_URI"))
        or "EMULATOR_HOST" in normalized
        or "CUSTOM_ENDPOINT" in normalized
        or "API_ENDPOINT_OVERRIDES" in normalized
    )


def _is_endpoint_value_key(key: str) -> bool:
    normalized = key.lower()
    return (
        normalized in ENDPOINT_FIELDS
        or normalized in {"emulator", "host", "url", "uri"}
        or normalized.endswith(("_endpoint", "_host", "_url", "_uri"))
    )


def _is_endpoint_record(value: dict[Any, Any]) -> bool:
    keys = {str(key).lower() for key in value}
    if {"token_uri", "auth_uri"} <= keys:
        return True
    if "endpoint_env" in keys and "real_google_cloud_fallback" in keys:
        return True
    return bool(
        keys.intersection({"endpoint", "env_value"})
        and keys.intersection(ENDPOINT_RECORD_MARKERS)
    )


def _rewrite_endpoint_value(
    value: Any,
    endpoint_map: dict[str, Any],
    *,
    port: bool = False,
) -> Any:
    from .endpoints import rewrite_endpoints, validate_local_endpoints

    rewritten = value
    if port and not isinstance(value, bool):
        mapped = endpoint_map.get(str(value))
        if mapped is not None:
            rewritten = str(mapped) if isinstance(value, str) else int(mapped)
    rewritten = rewrite_endpoints(rewritten, endpoint_map)
    validate_local_endpoints(rewritten)
    _validate_no_stale_canonical_endpoints(rewritten, endpoint_map)
    return rewritten


def _rewrite_nested_generated_value(
    value: Any, endpoint_map: dict[str, Any]
) -> Any:
    from .endpoints import rewrite_endpoints

    rewritten = rewrite_endpoints(value, endpoint_map)
    _validate_no_stale_canonical_endpoints(rewritten, endpoint_map)
    return rewritten


def _rewrite_endpoint_config(value: str, endpoint_map: dict[str, Any]) -> str:
    if not any(
        _is_endpoint_env_key(match.group("key"))
        for match in ENDPOINT_CONFIG_ASSIGNMENT.finditer(value)
    ):
        return value
    return _rewrite_endpoint_value(value, endpoint_map)


def _transform_endpoint_payload(
    value: Any,
    endpoint_map: dict[str, Any],
    *,
    endpoint_context: bool = False,
) -> Any:
    if isinstance(value, list):
        return [
            _transform_endpoint_payload(
                item,
                endpoint_map,
                endpoint_context=endpoint_context,
            )
            for item in value
        ]
    if not isinstance(value, dict):
        if isinstance(value, str):
            if endpoint_context:
                return _rewrite_nested_generated_value(value, endpoint_map)
            return _transform_endpoint_text(value, endpoint_map)
        return value

    generated_record = endpoint_context or _is_endpoint_record(value)
    env_mapping = any(_is_endpoint_env_key(str(key)) for key in value)
    transformed: dict[Any, Any] = {}
    for key, child in value.items():
        normalized_key = str(key).lower()
        if generated_record and _is_endpoint_value_key(normalized_key):
            transformed[key] = _rewrite_endpoint_value(child, endpoint_map)
        elif generated_record and normalized_key == "port":
            transformed[key] = _rewrite_endpoint_value(
                child, endpoint_map, port=True
            )
        elif env_mapping and _is_endpoint_env_key(str(key)):
            transformed[key] = _rewrite_endpoint_value(child, endpoint_map)
        elif (
            normalized_key == "text"
            and isinstance(child, str)
            and (value.get("type") == "text" or "mimeType" in value)
        ):
            transformed[key] = _transform_endpoint_text(child, endpoint_map)
        else:
            transformed[key] = _transform_endpoint_payload(
                child,
                endpoint_map,
                endpoint_context=generated_record,
            )
    return transformed


def _transform_endpoint_text(value: str, endpoint_map: dict[str, Any]) -> str:
    try:
        parsed = json.loads(value)
    except (json.JSONDecodeError, TypeError):
        return _rewrite_endpoint_config(value, endpoint_map)
    if not isinstance(parsed, (dict, list)):
        return _rewrite_endpoint_config(value, endpoint_map)
    transformed = _transform_endpoint_payload(parsed, endpoint_map)
    if transformed == parsed:
        return value
    return json.dumps(transformed, ensure_ascii=False, separators=(",", ":"))


def _validate_no_stale_canonical_endpoints(
    value: Any, endpoint_map: dict[str, Any]
) -> None:
    serialized = value if isinstance(value, str) else json.dumps(value)
    for canonical_port, host_port in endpoint_map.items():
        canonical = str(canonical_port)
        if canonical == str(host_port):
            continue
        stale_host = re.search(
            rf"(?:localhost|127\.0\.0\.1|\[::1\]):{re.escape(canonical)}(?!\d)",
            serialized,
            re.IGNORECASE,
        )
        stale_port = re.search(
            rf'(?:\\?")port(?:\\?")\s*:\s*{re.escape(canonical)}(?!\d)',
            serialized,
        )
        if stale_host or stale_port:
            raise HostError(
                "stale_endpoint",
                "Java MCP result contains an unmapped canonical endpoint",
                {
                    "canonical_port": canonical,
                    "expected_host_port": int(host_port),
                },
            )


class McpAdapter:
    def __init__(
        self,
        instance: str = DEFAULT_INSTANCE,
        project: str = DEFAULT_PROJECT,
        user: str = DEFAULT_USER,
        *,
        controller: Controller | None = None,
    ):
        self.instance = instance
        self.project = project
        self.user = user
        self.controller = controller if controller is not None else Controller()
        try:
            target = self.controller.mcp_target(instance, project, user)
        except HostError as error:
            if error.code != "instance_not_running":
                raise
            raise HostError(
                "instance_not_running",
                "Run "
                f"localcloud start --instance {instance} --project-id {project} "
                f"--user {user} before connecting MCP.",
                {"instance": instance, "project": project, "user": user},
            ) from error
        self.endpoint_map = {
            str(canonical): int(host_port)
            for canonical, host_port in target["endpoint_map"].items()
        }
        self.java = JavaMcpClient(
            target["url"], project=target["project"], user=target["user"]
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
                response["result"] = _transform_endpoint_payload(
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

    def close(self) -> None:
        self.controller.release_mcp_target(self.instance, self.project, self.user)


def run(
    instance: str = DEFAULT_INSTANCE,
    project: str = DEFAULT_PROJECT,
    user: str = DEFAULT_USER,
) -> None:
    import anyio

    anyio.run(_run_sdk, instance, project, user)


async def _run_sdk(instance: str, project: str, user: str) -> None:
    from mcp import types
    from mcp.server.stdio import stdio_server
    from mcp.shared.message import SessionMessage

    adapter = McpAdapter(instance=instance, project=project, user=user)
    try:
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
    finally:
        adapter.close()
