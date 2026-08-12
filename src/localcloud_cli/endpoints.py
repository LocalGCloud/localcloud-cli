from __future__ import annotations

import json
import re
from typing import Any
from urllib.parse import urlsplit, urlunsplit

from .errors import HostError
from .java_client import JavaMcpClient


HOST_PORT = re.compile(r"(?P<host>localhost|127\.0\.0\.1|\[::1\])(?P<separator>:)(?P<port>\d{1,5})")
REAL_GOOGLE = re.compile(r"(?:^|[/:.])(?:googleapis\.com|gcr\.io|pkg\.dev)(?:$|[/.:])", re.IGNORECASE)


def environment_config(
    environment: dict[str, Any],
    project: str,
    user: str,
    output_format: str = "shell",
) -> Any:
    java = JavaMcpClient(environment["url"], project=project, user=user)
    result = java.tool(
        "localcloud_get_env",
        {"format": output_format, "project": project},
    )
    rewritten = rewrite_endpoints(result, environment.get("endpoint_map") or {})
    if output_format == "json" and isinstance(rewritten, str):
        try:
            rewritten = json.loads(rewritten)
        except json.JSONDecodeError as error:
            raise HostError(
                "invalid_environment",
                "Java MCP returned invalid JSON environment configuration",
                {"cause": str(error)},
            ) from error
    validate_local_endpoints(rewritten)
    return rewritten


def rewrite_endpoints(value: Any, endpoint_map: dict[str, Any]) -> Any:
    normalized = {str(port): int(host_port) for port, host_port in endpoint_map.items()}
    if isinstance(value, dict):
        return {key: rewrite_endpoints(item, normalized) for key, item in value.items()}
    if isinstance(value, list):
        return [rewrite_endpoints(item, normalized) for item in value]
    if not isinstance(value, str):
        return value

    def replace(match: re.Match[str]) -> str:
        port = match.group("port")
        host_port = normalized.get(port)
        if host_port is None:
            return match.group(0)
        return f"127.0.0.1:{host_port}"

    return HOST_PORT.sub(replace, value)


def validate_local_endpoints(value: Any) -> None:
    serialized = json.dumps(value, sort_keys=True) if not isinstance(value, str) else value
    if REAL_GOOGLE.search(serialized):
        raise HostError(
            "real_google_endpoint",
            "Generated environment references a real Google endpoint",
        )
    for match in re.finditer(r"https?://[^\s\"']+", serialized):
        raw = match.group(0).rstrip("\\,}")
        try:
            parsed = urlsplit(raw)
        except ValueError as error:
            raise HostError("invalid_endpoint", "Generated environment contains an invalid URL", {"url": raw}) from error
        if parsed.hostname and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise HostError(
                "nonlocal_endpoint",
                "Generated environment contains a non-loopback endpoint",
                {"url": raw},
            )
